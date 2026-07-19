package com.example.newproject

import android.content.ContentResolver
import android.net.Uri
import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.example.newproject.ai.PromptBuilder
import com.example.newproject.domain.RelatedNote
import com.google.mlkit.genai.common.DownloadStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
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
    private val uiState: MutableStateFlow<NoteUiState>,
    private val vaultUri: () -> Uri?
) {
    // モデルDL完了後に作成を再開するために保持
    private var pending: PendingAnnotation? = null
    private var createJob: Job? = null
    private var downloadJob: Job? = null
    private var activeRequestId = 0L

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
        if (uiState.value.annotationState is AnnotationState.Loading) return

        val vault = vaultUri()
        if (vault == null) {
            uiState.value = uiState.value.copy(
                annotationState = AnnotationState.Error(
                    message = "Vault が選択されていません。",
                    sourceTitle = title
                )
            )
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

        uiState.value = uiState.value.copy(
            annotationState = AnnotationState.Loading(title.toObsidianNoteTitle())
        )
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
        val next = when (val state = uiState.value.annotationState) {
            is AnnotationState.Success -> state.copy(isViewed = true)
            is AnnotationState.Error -> state.copy(isViewed = true)
            else -> return
        }
        uiState.value = uiState.value.copy(annotationState = next)
    }

    /** ノート・Vault切替時に生成を止め、旧ノートの結果が後から混入するのを防ぐ。 */
    fun cancelAndClear() {
        activeRequestId++
        createJob?.cancel()
        downloadJob?.cancel()
        createJob = null
        downloadJob = null
        pending = null
        uiState.value = uiState.value.copy(annotationState = AnnotationState.Idle)
    }

    fun loadList(contentResolver: ContentResolver) {
        val uri = vaultUri()
        if (uri == null) {
            uiState.value = uiState.value.copy(
                annotationListState = AnnotationListState.Error("Vault が選択されていません。")
            )
            return
        }
        scope.launch {
            uiState.value = uiState.value.copy(annotationListState = AnnotationListState.Loading)
            try {
                val files = repository.listAnnotationFiles(contentResolver, uri)
                uiState.value = uiState.value.copy(
                    annotationListState = AnnotationListState.Success(files)
                )
            } catch (e: Exception) {
                uiState.value = uiState.value.copy(
                    annotationListState = AnnotationListState.Error(e.message ?: "Unknown error")
                )
            }
        }
    }

    fun delete(contentResolver: ContentResolver, uri: Uri) {
        scope.launch {
            repository.deleteDocument(contentResolver, uri)
            reloadList(contentResolver)
        }
    }

    fun deleteAll(contentResolver: ContentResolver) {
        val current = uiState.value.annotationListState as? AnnotationListState.Success ?: return
        scope.launch {
            current.files.forEach { file ->
                repository.deleteDocument(contentResolver, file.uri)
            }
            reloadList(contentResolver)
        }
    }

    private suspend fun reloadList(contentResolver: ContentResolver) {
        val uri = vaultUri() ?: return
        try {
            val files = repository.listAnnotationFiles(contentResolver, uri)
            uiState.value = uiState.value.copy(
                annotationListState = AnnotationListState.Success(files)
            )
        } catch (e: Exception) {
            uiState.value = uiState.value.copy(
                annotationListState = AnnotationListState.Error(e.message ?: "Unknown error")
            )
        }
    }

    private suspend fun createWithAvailableModel(
        contentResolver: ContentResolver,
        vault: Uri,
        annotation: PendingAnnotation
    ) {
        try {
            val displayTimestamp = AnnotationComposer.DISPLAY_TIMESTAMP_FORMAT.format(Date())
            val fileTimestamp = AnnotationComposer.FILE_TIMESTAMP_FORMAT.format(Date())
            val prompt = PromptBuilder.buildAnnotationPrompt(
                title = annotation.title,
                content = annotation.content,
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
            uiState.value = uiState.value.copy(
                annotationState = AnnotationState.Success(
                    sourceTitle = sourceTitle,
                    savedUri = savedUri,
                    fileName = fileName,
                    content = markdown
                )
            )
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
        uiState.value = uiState.value.copy(
            annotationState = AnnotationState.Error(
                message = message,
                sourceTitle = sourceTitle.toObsidianNoteTitle()
            )
        )
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
