package com.example.newproject

import android.content.ContentResolver
import android.net.Uri
import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.example.newproject.ai.PromptBuilder
import com.example.newproject.domain.RelatedNote
import com.google.mlkit.genai.common.DownloadStatus
import kotlinx.coroutines.CoroutineScope
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

    fun create(
        contentResolver: ContentResolver,
        title: String,
        content: String,
        summary: String?,
        relatedNotes: List<RelatedNote>,
        aiNotes: List<RelatedNote>,
        wikilinkTitles: Set<String>
    ) {
        val vault = vaultUri()
        if (vault == null) {
            uiState.value = uiState.value.copy(
                annotationState = AnnotationState.Error("Vault が選択されていません。")
            )
            return
        }

        val annotation = PendingAnnotation(
            title = title,
            content = content,
            summary = summary,
            relatedNotes = relatedNotes,
            aiNotes = aiNotes,
            wikilinkTitles = wikilinkTitles
        )

        uiState.value = uiState.value.copy(annotationState = AnnotationState.Loading)
        scope.launch {
            when (aiClient.checkAvailability()) {
                AiAvailability.Unavailable -> {
                    uiState.value = uiState.value.copy(
                        annotationState = AnnotationState.Error("補記メモはこの端末では利用できません。")
                    )
                }
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
        }
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
            if (!AnnotationComposer.hasAnnotationBody(generated)) {
                uiState.value = uiState.value.copy(
                    annotationState = AnnotationState.Error("補記メモの生成結果が空でした。")
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
            val savedUri = repository.createAnnotationFile(
                contentResolver = contentResolver,
                vaultUri = vault,
                sanitizedTitle = fileTitle,
                timestamp = fileTimestamp,
                content = markdown
            )
            uiState.value = uiState.value.copy(
                annotationState = AnnotationState.Success(
                    savedUri = savedUri,
                    fileName = fileName,
                    content = markdown
                )
            )
        } catch (e: Exception) {
            uiState.value = uiState.value.copy(
                annotationState = AnnotationState.Error(e.message ?: "Unknown error")
            )
        }
    }

    private fun startModelDownload(contentResolver: ContentResolver) {
        scope.launch {
            try {
                aiClient.downloadModel().collect { status ->
                    when (status) {
                        is DownloadStatus.DownloadStarted,
                        is DownloadStatus.DownloadProgress -> {
                            uiState.value = uiState.value.copy(annotationState = AnnotationState.Loading)
                        }
                        is DownloadStatus.DownloadCompleted -> {
                            val annotation = pending ?: return@collect
                            val vault = vaultUri() ?: return@collect
                            pending = null
                            createWithAvailableModel(
                                contentResolver = contentResolver,
                                vault = vault,
                                annotation = annotation
                            )
                        }
                        is DownloadStatus.DownloadFailed -> {
                            uiState.value = uiState.value.copy(
                                annotationState = AnnotationState.Error(
                                    "モデルのダウンロードに失敗しました: ${status.e.message}"
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                uiState.value = uiState.value.copy(
                    annotationState = AnnotationState.Error("ダウンロードエラー: ${e.message}")
                )
            }
        }
    }

    private data class PendingAnnotation(
        val title: String,
        val content: String,
        val summary: String?,
        val relatedNotes: List<RelatedNote>,
        val aiNotes: List<RelatedNote>,
        val wikilinkTitles: Set<String>
    )
}
