package com.example.newproject.controller

import com.example.newproject.domain.markdown.NoteSectionModel
import com.example.newproject.domain.aiStatusNotice
import com.example.newproject.domain.buildNoteExcerpt
import com.example.newproject.domain.parseQuizResponse
import com.example.newproject.domain.profileQuizInput
import com.example.newproject.domain.toObsidianNoteTitle
import com.example.newproject.model.NoteExcerptLimits
import com.example.newproject.model.QuizStateWriter
import com.example.newproject.model.state.QuizFormat
import com.example.newproject.model.state.QuizState
import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.example.newproject.ai.PromptBuilder
import com.google.mlkit.genai.common.DownloadStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 入力内容に応じた○×・3択・4択Q&Aのバックグラウンド生成と結果の確認状態を担当する。
 * 入力はノート全体ではなく「フォーカスセクションの周辺テキスト」（呼び出し側が
 * NoteSectionModel.surroundingContext で構築）。出題形式と問題数は入力の情報量に応じて決める。
 * AI補記とは独立したジョブを持ち、実際のモデル生成は AiClient 側のMutexで順番に処理される。
 */
class QuizController(
    private val scope: CoroutineScope,
    private val aiClient: AiClient,
    private val state: QuizStateWriter,
    private val excerptDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private var pending: PendingQuiz? = null
    private var generateJob: Job? = null
    private var downloadJob: Job? = null
    private var activeRequestId = 0L

    fun create(title: String, content: String) {
        // 生成中の再タップは同じ要求として扱い、モデルの順番待ちを重複させない。
        if (state.current is QuizState.Loading) return

        val format = profileQuizInput(content).format
        val request = PendingQuiz(
            requestId = ++activeRequestId,
            title = title,
            content = content,
            format = format
        )
        state.update {
            QuizState.Loading(
                sourceTitle = title.toObsidianNoteTitle(),
                format = format
            )
        }
        generateJob = scope.launch {
            try {
                when (val availability = aiClient.checkAvailability()) {
                    AiAvailability.Ready -> generateWithAvailableModel(request)
                    // 自動DL方式。**`downloadModel()` を呼んでよいのはここだけ**
                    // （→ AiAvailability.Downloading）。
                    AiAvailability.NeedsDownload -> {
                        pending = request
                        startModelDownload()
                    }
                    // **エラーへ畳まない。** 非対応に再試行導線が付くのを避ける。
                    // DL中は待つだけ（合流できないので、完了後にもう一度押してもらう）。
                    AiAvailability.Downloading,
                    AiAvailability.Unsupported,
                    is AiAvailability.TemporarilyUnavailable -> updateAiNotice(request, availability)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateError(request, e.message ?: "Unknown error")
            }
        }
    }

    fun markViewed() {
        state.update { current ->
            when (current) {
                is QuizState.Success -> current.copy(isViewed = true)
                is QuizState.Error -> current.copy(isViewed = true)
                else -> current
            }
        }
    }

    /**
     * ノート・Vault切替や、セクション文脈の切り替わり（新しいセクションチャットの
     * 開始・終了）で生成と順番待ちを止め、古い結果を破棄する。
     */
    fun cancelAndClear() {
        activeRequestId++
        generateJob?.cancel()
        downloadJob?.cancel()
        generateJob = null
        downloadJob = null
        pending = null
        state.update { QuizState.Idle }
    }

    private suspend fun generateWithAvailableModel(request: PendingQuiz) {
        try {
            val excerpt = withContext(excerptDispatcher) {
                buildNoteExcerpt(request.content, NoteExcerptLimits.QUIZ)
            }
            val prompt = PromptBuilder.buildQuizPrompt(
                sourceLabel = request.title,
                excerpt = excerpt,
                format = request.format
            )
            val raw = aiClient.generate(prompt)
            if (!isCurrent(request.requestId)) return

            val cards = parseQuizResponse(raw, request.format)
            if (cards.isEmpty()) {
                updateError(request, "Q&Aの生成結果を読み取れませんでした。")
                return
            }
            state.update {
                QuizState.Success(
                    sourceTitle = request.title.toObsidianNoteTitle(),
                    cards = cards
                )
            }
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
        state.update {
            QuizState.Error(
                message = message,
                sourceTitle = request.title.toObsidianNoteTitle()
            )
        }
    }

    /** 端末AIの状態をそのまま説明へ移す。[AiAvailability.Ready] はここへ来ない。 */
    private fun updateAiNotice(request: PendingQuiz, availability: AiAvailability) {
        if (!isCurrent(request.requestId)) return
        val notice = aiStatusNotice(availability, QUIZ_FEATURE_LABEL) ?: return
        state.update { QuizState.AiNotice(notice, request.title.toObsidianNoteTitle()) }
    }

    private data class PendingQuiz(
        val requestId: Long,
        val title: String,
        val content: String,
        val format: QuizFormat
    )

    private companion object {
        /** 説明文へ埋め込む機能名（「この端末では**Q&A**を利用できません。」）。 */
        const val QUIZ_FEATURE_LABEL = "Q&A"
    }
}
