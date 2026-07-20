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
 * 入力はノート全体ではなく「フォーカスセクションの周辺テキスト」（呼び出し側が
 * NoteSectionModel.surroundingContext で構築）。1回の生成は2問固定で、
 * generateMore による追い生成で問題を積み増す。
 * AI補記とは独立したジョブを持ち、実際のモデル生成は AiClient 側のMutexで順番に処理される。
 */
class QuizController(
    private val scope: CoroutineScope,
    private val aiClient: AiClient,
    private val uiState: MutableStateFlow<NoteUiState>
) {
    private var pending: PendingQuiz? = null
    // 追い生成用に、直近の生成に使った入力を同じノート内で保持する
    private var activeSource: PendingQuiz? = null
    private var generateJob: Job? = null
    private var downloadJob: Job? = null
    private var activeRequestId = 0L

    fun create(title: String, content: String) {
        // 生成中の再タップは同じ要求として扱い、モデルの順番待ちを重複させない。
        val current = uiState.value.quizState
        if (current is QuizState.Loading) return
        if (current is QuizState.Success && current.isAppending) return

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

    /**
     * 「もう2問」。直近の入力（フォーカス周辺テキスト）で追加生成し、既存カードに積み増す。
     * 既出問題はプロンプトの除外リストで重複を避け、念のため同一問題文も弾く。
     */
    fun generateMore() {
        val current = uiState.value.quizState as? QuizState.Success ?: return
        if (current.isAppending) return
        val source = activeSource ?: return

        val requestId = ++activeRequestId
        uiState.value = uiState.value.copy(
            quizState = current.copy(isAppending = true, appendError = null)
        )
        generateJob = scope.launch {
            try {
                if (aiClient.checkAvailability() != AiAvailability.Available) {
                    failAppend(requestId, "この端末ではAIを利用できません。")
                    return@launch
                }
                val prompt = PromptBuilder.buildQuizPrompt(
                    sourceLabel = source.title,
                    content = source.content,
                    excludeQuestions = current.cards.map { it.question }
                )
                val raw = aiClient.generate(prompt)
                if (!isCurrent(requestId)) return@launch

                val known = current.cards.map { it.question }.toSet()
                val newCards = parseQuizResponse(raw).filterNot { it.question in known }
                if (newCards.isEmpty()) {
                    failAppend(requestId, "追加の問題を生成できませんでした。")
                    return@launch
                }
                val latest = uiState.value.quizState as? QuizState.Success ?: return@launch
                uiState.value = uiState.value.copy(
                    quizState = latest.copy(
                        cards = latest.cards + newCards,
                        isAppending = false,
                        appendError = null
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failAppend(requestId, e.message ?: "Unknown error")
            }
        }
    }

    /** ノート・Vault切替時に生成と順番待ちを止め、旧ノートの結果を破棄する。 */
    fun cancelAndClear() {
        activeRequestId++
        generateJob?.cancel()
        downloadJob?.cancel()
        generateJob = null
        downloadJob = null
        pending = null
        activeSource = null
        uiState.value = uiState.value.copy(quizState = QuizState.Idle)
    }

    private suspend fun generateWithAvailableModel(request: PendingQuiz) {
        try {
            val prompt = PromptBuilder.buildQuizPrompt(
                sourceLabel = request.title,
                content = request.content
            )
            val raw = aiClient.generate(prompt)
            if (!isCurrent(request.requestId)) return

            val cards = parseQuizResponse(raw)
            if (cards.isEmpty()) {
                updateError(request, "Q&Aの生成結果を読み取れませんでした。")
                return
            }
            activeSource = request
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

    // 追い生成の失敗では既存カードを保持したまま appendError だけを立てる
    private fun failAppend(requestId: Long, message: String) {
        if (!isCurrent(requestId)) return
        val current = uiState.value.quizState as? QuizState.Success ?: return
        uiState.value = uiState.value.copy(
            quizState = current.copy(isAppending = false, appendError = message)
        )
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
