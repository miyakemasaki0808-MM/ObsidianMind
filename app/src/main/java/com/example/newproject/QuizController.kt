package com.example.newproject

import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.example.newproject.ai.PromptBuilder
import com.google.mlkit.genai.common.DownloadStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 4択Q&Aのバックグラウンド生成と結果の確認状態を担当する。
 * AI補記とは独立したジョブを持ち、実際のモデル生成は AiClient 側のMutexで順番に処理される。
 */
class QuizController(
    private val scope: CoroutineScope,
    private val aiClient: AiClient,
    private val uiState: MutableStateFlow<NoteUiState>
) {
    private var pending: PendingQuiz? = null
    private var generateJob: Job? = null
    private var downloadJob: Job? = null
    private var activeRequestId = 0L

    fun create(title: String, content: String) {
        // 生成中の再タップは同じ要求として扱い、モデルの順番待ちを重複させない。
        if (uiState.value.quizState is QuizState.Loading) return

        val request = PendingQuiz(
            requestId = ++activeRequestId,
            title = title,
            content = content
        )
        uiState.value = uiState.value.copy(
            quizState = QuizState.Loading(title.toObsidianNoteTitle())
        )
        generateJob = scope.launch {
            try {
                when (aiClient.checkAvailability()) {
                    AiAvailability.Unavailable -> updateError(
                        request = request,
                        message = "Q&Aはこの端末では利用できません。"
                    )
                    AiAvailability.NeedsDownload -> {
                        pending = request
                        startModelDownload()
                    }
                    AiAvailability.Available -> generateWithAvailableModel(request)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateError(request, e.message ?: "Unknown error")
            }
        }
    }

    fun markViewed() {
        val next = when (val state = uiState.value.quizState) {
            is QuizState.Success -> state.copy(isViewed = true)
            is QuizState.Error -> state.copy(isViewed = true)
            else -> return
        }
        uiState.value = uiState.value.copy(quizState = next)
    }

    /** ノート・Vault切替時に生成と順番待ちを止め、旧ノートの結果を破棄する。 */
    fun cancelAndClear() {
        activeRequestId++
        generateJob?.cancel()
        downloadJob?.cancel()
        generateJob = null
        downloadJob = null
        pending = null
        uiState.value = uiState.value.copy(quizState = QuizState.Idle)
    }

    private suspend fun generateWithAvailableModel(request: PendingQuiz) {
        try {
            val prompt = PromptBuilder.buildQuizPrompt(request.title, request.content)
            val raw = aiClient.generate(prompt)
            if (!isCurrent(request.requestId)) return

            val cards = parseQuizResponse(raw)
            if (cards.isEmpty()) {
                updateError(request, "Q&Aの生成結果を読み取れませんでした。")
                return
            }
            uiState.value = uiState.value.copy(
                quizState = QuizState.Success(
                    sourceTitle = request.title.toObsidianNoteTitle(),
                    cards = cards
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            updateError(request, e.message ?: "Unknown error")
        }
    }

    private fun startModelDownload() {
        downloadJob?.cancel()
        downloadJob = scope.launch {
            try {
                aiClient.downloadModel().collect { status ->
                    when (status) {
                        is DownloadStatus.DownloadStarted,
                        is DownloadStatus.DownloadProgress -> Unit
                        is DownloadStatus.DownloadCompleted -> {
                            val request = pending ?: return@collect
                            if (!isCurrent(request.requestId)) return@collect
                            pending = null
                            generateWithAvailableModel(request)
                        }
                        is DownloadStatus.DownloadFailed -> {
                            val request = pending ?: return@collect
                            pending = null
                            updateError(
                                request,
                                "モデルのダウンロードに失敗しました: ${status.e.message}"
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val request = pending ?: return@launch
                pending = null
                updateError(request, "ダウンロードエラー: ${e.message}")
            }
        }
    }

    private fun isCurrent(requestId: Long): Boolean = activeRequestId == requestId

    private fun updateError(request: PendingQuiz, message: String) {
        if (!isCurrent(request.requestId)) return
        uiState.value = uiState.value.copy(
            quizState = QuizState.Error(
                message = message,
                sourceTitle = request.title.toObsidianNoteTitle()
            )
        )
    }

    private data class PendingQuiz(
        val requestId: Long,
        val title: String,
        val content: String
    )
}
