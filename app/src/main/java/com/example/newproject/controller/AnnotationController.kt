package com.example.newproject.controller

import com.example.newproject.data.NoteRepository
import com.example.newproject.data.sanitizeAnnotationFileTitle
import com.example.newproject.domain.AnnotationComposer
import com.example.newproject.domain.buildNoteExcerpt
import com.example.newproject.domain.toObsidianNoteTitle
import com.example.newproject.model.NoteExcerptLimits
import com.example.newproject.model.state.AnnotationListState
import com.example.newproject.model.state.AnnotationState
import com.example.newproject.model.AnnotationStateWriter
import android.content.ContentResolver
import android.net.Uri
import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.example.newproject.ai.PromptBuilder
import com.example.newproject.model.RelatedNote
import com.google.mlkit.genai.common.DownloadStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * AI補記メモの作成（モデルDL待ち込み）と、補記ファイルの一覧・削除を担当する。
 * annotationState / annotationListState の更新のみを行う。
 * Markdown整形・生成結果の検証は AnnotationComposer（純粋ロジック）に委ねる。
 */
class AnnotationController(
    private val scope: CoroutineScope,
    private val repository: NoteRepository,
    private val aiClient: AiClient,
    private val state: AnnotationStateWriter,
    private val vaultUri: () -> Uri?,
    // Vault切替の世代。NoteViewModel が saveVault() で採番する。
    // 補記の作成は「ノート単位」で activeRequestId が見るが、一覧と削除は
    // 「Vault単位」で寿命が違う（補記管理画面はノート切替と無関係）。
    private val vaultGeneration: () -> Long,
    private val excerptDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    // モデルDL完了後に作成を再開するために保持
    private var pending: PendingAnnotation? = null
    private var createJob: Job? = null
    private var downloadJob: Job? = null
    private var activeRequestId = 0L

    // 一覧・削除は同じ annotationListState を奪い合うのでJobは1本で共有する
    // （削除→再読込の途中で別の削除が走ると、消したはずの項目が戻って見える）。
    // 生成用の createJob とは分ける。ノート切替で一覧を巻き込まないため。
    private var listJob: Job? = null

    fun create(
        contentResolver: ContentResolver,
        title: String,
        content: String,
        summary: String?,
        relatedNotes: List<RelatedNote>,
        aiNotes: List<RelatedNote>,
        wikilinkTitles: Set<String>
    ) {
        // 生成中の連続タップによる重複ファイル作成を防ぐ。
        if (state.current.annotationState is AnnotationState.Loading) return

        val vault = vaultUri()
        if (vault == null) {
            state.update { current ->
                current.copy(
                    annotationState = AnnotationState.Error(
                        message = "Vault が選択されていません。",
                        sourceTitle = title
                    )
                )
            }
            return
        }

        val requestId = ++activeRequestId
        val annotation = PendingAnnotation(
            requestId = requestId,
            title = title,
            content = content,
            summary = summary,
            relatedNotes = relatedNotes,
            aiNotes = aiNotes,
            wikilinkTitles = wikilinkTitles
        )

        state.update { current ->
            current.copy(annotationState = AnnotationState.Loading(title.toObsidianNoteTitle()))
        }
        createJob = scope.launch {
            try {
                when (aiClient.checkAvailability()) {
                    AiAvailability.Unavailable -> updateError(
                        requestId = requestId,
                        sourceTitle = title,
                        message = "補記メモはこの端末では利用できません。"
                    )
                    AiAvailability.NeedsDownload -> {
                        pending = annotation
                        startModelDownload(contentResolver)
                    }
                    AiAvailability.Available -> {
                        createWithAvailableModel(
                            contentResolver = contentResolver,
                            vault = vault,
                            annotation = annotation
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateError(requestId, title, e.message ?: "Unknown error")
            }
        }
    }

    /** 完了・エラー通知を確認済みにする。結果自体は同じノート内で保持する。 */
    fun markViewed() {
        state.update { current ->
            val next = when (val annotation = current.annotationState) {
                is AnnotationState.Success -> annotation.copy(isViewed = true)
                is AnnotationState.Error -> annotation.copy(isViewed = true)
                else -> return@update current
            }
            current.copy(annotationState = next)
        }
    }

    /** ノート・Vault切替時に生成を止め、旧ノートの結果が後から混入するのを防ぐ。 */
    fun cancelAndClear() {
        activeRequestId++
        createJob?.cancel()
        downloadJob?.cancel()
        createJob = null
        downloadJob = null
        pending = null
        state.update { current -> current.copy(annotationState = AnnotationState.Idle) }
    }

    /**
     * Vault切替時に NoteViewModel の saveVault() から呼ばれる契約。
     *
     * 一覧は [cancelAndClear]（ノート切替）では止めない。補記管理画面はノートと
     * 無関係なので、ノートを開き直しただけで一覧が消えるのは誤りになる。
     * 止めるのはVaultが変わったときだけで、そのとき旧Vaultの一覧は無効になる。
     */
    fun onVaultChanged() {
        listJob?.cancel()
        listJob = null
        state.update { current -> current.copy(annotationListState = AnnotationListState.Idle) }
    }

    fun loadList(contentResolver: ContentResolver) {
        val uri = vaultUri()
        if (uri == null) {
            state.update { current ->
                current.copy(annotationListState = AnnotationListState.Error("Vault が選択されていません。"))
            }
            return
        }
        val generation = vaultGeneration()
        listJob?.cancel()
        listJob = scope.launch {
            state.update { current -> current.copy(annotationListState = AnnotationListState.Loading) }
            reloadList(contentResolver, generation)
        }
    }

    fun delete(contentResolver: ContentResolver, uri: Uri) {
        val generation = vaultGeneration()
        listJob?.cancel()
        listJob = scope.launch {
            val deleted = repository.deleteDocument(contentResolver, uri)
            reloadList(contentResolver, generation, failureCount = if (deleted) 0 else 1)
        }
    }

    /**
     * 表示中の一覧をまとめて削除する。
     *
     * 削除対象は「起動時に表示されていた一覧」で固定する。走行中にVaultが
     * 切り替わっても、拾い直した新Vaultのファイルを消しにいかないようにするため。
     */
    fun deleteAll(contentResolver: ContentResolver) {
        val current = state.current.annotationListState as? AnnotationListState.Success ?: return
        val generation = vaultGeneration()
        listJob?.cancel()
        listJob = scope.launch {
            var failureCount = 0
            current.files.forEach { file ->
                // 旧Vaultのファイルを消し続けないよう、1件ごとに世代を見る。
                // 永続URI権限が残っている端末では、切替後もURIが有効なまま消せてしまう。
                if (generation != vaultGeneration()) return@launch
                if (!repository.deleteDocument(contentResolver, file.uri)) failureCount++
            }
            reloadList(contentResolver, generation, failureCount)
        }
    }

    /**
     * 一覧を読み直して [AnnotationListState] へ反映する。
     *
     * 起動時の世代と食い違っていたら書かない。`cancel()` だけに頼ると、
     * SAF列挙から戻った直後にVault切替が起きた場合に旧Vaultの補記が並ぶ。
     *
     * @param failureCount 直前の削除で失敗した件数。読み直した一覧に添えて表示する。
     */
    private suspend fun reloadList(
        contentResolver: ContentResolver,
        generation: Long,
        failureCount: Int = 0
    ) {
        val uri = vaultUri() ?: return
        try {
            val files = repository.listAnnotationFiles(contentResolver, uri)
            if (generation != vaultGeneration()) return
            state.update { current ->
                current.copy(
                    annotationListState = AnnotationListState.Success(files, failureCount)
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (generation != vaultGeneration()) return
            state.update { current ->
                current.copy(annotationListState = AnnotationListState.Error(e.message ?: "Unknown error"))
            }
        }
    }

    private suspend fun createWithAvailableModel(
        contentResolver: ContentResolver,
        vault: Uri,
        annotation: PendingAnnotation
    ) {
        try {
            val generatedAt = Date()
            val displayTimestamp = AnnotationComposer.formatDisplayTimestamp(generatedAt)
            val fileTimestamp = AnnotationComposer.formatFileTimestamp(generatedAt)
            val excerpt = withContext(excerptDispatcher) {
                buildNoteExcerpt(annotation.content, NoteExcerptLimits.ANNOTATION)
            }
            val prompt = PromptBuilder.buildAnnotationPrompt(
                title = annotation.title,
                excerpt = excerpt,
                summary = annotation.summary,
                relatedTitles = annotation.relatedNotes.map { it.title.toObsidianNoteTitle() },
                aiRecommendedTitles = annotation.aiNotes.map { it.title.toObsidianNoteTitle() },
                wikilinkTitles = annotation.wikilinkTitles,
                createdAt = displayTimestamp
            )
            val generated = aiClient.generate(prompt).trim()
            if (!isCurrent(annotation.requestId)) return
            if (!AnnotationComposer.hasAnnotationBody(generated)) {
                updateError(
                    requestId = annotation.requestId,
                    sourceTitle = annotation.title,
                    message = "補記メモの生成結果が空でした。"
                )
                return
            }

            val sourceTitle = annotation.title.toObsidianNoteTitle()
            val fileTitle = sanitizeAnnotationFileTitle(sourceTitle)
            val fileName = "${fileTitle}__補記_$fileTimestamp.md"
            val markdown = AnnotationComposer.buildAnnotationMarkdown(
                title = sourceTitle,
                createdAt = displayTimestamp,
                generatedBody = generated
            )
            if (!isCurrent(annotation.requestId)) return
            val savedUri = repository.createAnnotationFile(
                contentResolver = contentResolver,
                vaultUri = vault,
                sanitizedTitle = fileTitle,
                timestamp = fileTimestamp,
                content = markdown
            )
            if (!isCurrent(annotation.requestId)) return
            state.update { current ->
                current.copy(
                    annotationState = AnnotationState.Success(
                        sourceTitle = sourceTitle,
                        savedUri = savedUri,
                        fileName = fileName,
                        content = markdown
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            updateError(
                requestId = annotation.requestId,
                sourceTitle = annotation.title,
                message = e.message ?: "Unknown error"
            )
        }
    }

    private fun startModelDownload(contentResolver: ContentResolver) {
        downloadJob?.cancel()
        downloadJob = scope.launch {
            try {
                aiClient.downloadModel().collect { status ->
                    when (status) {
                        is DownloadStatus.DownloadStarted,
                        is DownloadStatus.DownloadProgress -> {
                            // Loadingには開始時の対象タイトルを保持したままにする。
                        }
                        is DownloadStatus.DownloadCompleted -> {
                            val annotation = pending ?: return@collect
                            if (!isCurrent(annotation.requestId)) return@collect
                            val vault = vaultUri() ?: return@collect
                            pending = null
                            createWithAvailableModel(
                                contentResolver = contentResolver,
                                vault = vault,
                                annotation = annotation
                            )
                        }
                        is DownloadStatus.DownloadFailed -> {
                            val annotation = pending ?: return@collect
                            pending = null
                            updateError(
                                requestId = annotation.requestId,
                                sourceTitle = annotation.title,
                                message = "モデルのダウンロードに失敗しました: ${status.e.message}"
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val annotation = pending ?: return@launch
                pending = null
                updateError(
                    requestId = annotation.requestId,
                    sourceTitle = annotation.title,
                    message = "ダウンロードエラー: ${e.message}"
                )
            }
        }
    }

    private fun isCurrent(requestId: Long): Boolean = activeRequestId == requestId

    private fun updateError(requestId: Long, sourceTitle: String, message: String) {
        if (!isCurrent(requestId)) return
        state.update { current ->
            current.copy(
                annotationState = AnnotationState.Error(
                    message = message,
                    sourceTitle = sourceTitle.toObsidianNoteTitle()
                )
            )
        }
    }

    private data class PendingAnnotation(
        val requestId: Long,
        val title: String,
        val content: String,
        val summary: String?,
        val relatedNotes: List<RelatedNote>,
        val aiNotes: List<RelatedNote>,
        val wikilinkTitles: Set<String>
    )
}
