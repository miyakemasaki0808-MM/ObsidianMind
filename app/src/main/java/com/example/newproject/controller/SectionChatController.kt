package com.example.newproject.controller

import com.example.newproject.model.state.ChatMessage
import com.example.newproject.model.state.ChatRole
import com.example.newproject.model.SectionChatStateWriter
import com.example.newproject.model.state.SectionChatState
import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.example.newproject.ai.PromptBuilder
import com.example.newproject.domain.aiStatusNotice
import com.example.newproject.domain.buildNoteExcerpt
import com.example.newproject.domain.markdown.NoteSection
import com.example.newproject.model.NoteExcerptLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * セクション単位のAIチャット（吹き出し→ボトムシート）を担当する。
 * NoteViewModel から scope と状態Flowを注入され、sectionChat の更新のみを行う。
 */
class SectionChatController(
    private val scope: CoroutineScope,
    private val aiClient: AiClient,
    private val state: SectionChatStateWriter,
    private val excerptDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    // 前のセクションの生成が後から届いて新しいシートを上書きしないよう保持する
    private var openJob: Job? = null
    private var answerJob: Job? = null

    // 吹き出しタップで開く。要約と候補質問をまとめて用意する。
    fun open(section: NoteSection) {
        // 生成中・完了済みのセッションがあれば、その結果を再表示する。
        // スクロール先の別セクションで重複生成しないよう、対象は開始時のものに固定する。
        if (state.current.sectionChat != null) {
            showSheet()
            return
        }
        cancelJobs()
        state.update { current ->
            current.copy(
                sectionChat = SectionChatState(
                    sectionTitle = section.title,
                    sectionContext = section.text,
                    isSummaryLoading = true
                ),
                isSectionChatSheetVisible = true
            )
        }
        startSummary(section.title, section.text)
    }

    /**
     * いま使えなかった機能をもう一度試す。
     *
     * **`open()` は再入できない**（`sectionChat != null` なら再表示するだけ）ので、
     * 説明に添えた再試行導線はここへ来る。対象は開いているセッションの本文で、
     * 別のセクションへは移らない。
     */
    fun retryAi() {
        val chat = state.current.sectionChat ?: return

        // **答えを返せていない質問があるなら、それを再実行する。**
        // ログの末尾がユーザー発言のままなのは「回答を作れなかった」場合だけで、
        // 説明を畳むだけだと**未回答の発言だけが残り、同じ候補を押すと質問が重複する。**
        val unanswered = chat.messages.lastOrNull()?.takeIf { it.role == ChatRole.User }
        if (unanswered != null) {
            answerJob?.cancel()
            updateChat { it.copy(isGenerating = true, aiNotice = null, answerError = null) }
            answerJob = scope.launch {
                // 履歴は「その質問の直前まで」。再実行なのでログへは積み直さない。
                runAnswer(chat, unanswered.text, historyOf(chat.messages.dropLast(1)))
            }
            return
        }

        // 要約が既にあるなら作り直さない（説明を畳むだけでよい）。
        if (chat.summary != null) {
            updateChat { it.copy(aiNotice = null, answerError = null) }
            return
        }
        cancelJobs()
        updateChat {
            it.copy(isSummaryLoading = true, aiNotice = null, error = null, answerError = null)
        }
        startSummary(chat.sectionTitle, chat.sectionContext)
    }

    private fun startSummary(sectionTitle: String, sectionText: String) {
        openJob = scope.launch {
            when (val availability = aiClient.checkAvailability()) {
                AiAvailability.Ready -> {
                    try {
                        val sectionExcerpt = withContext(excerptDispatcher) {
                            buildNoteExcerpt(sectionText, NoteExcerptLimits.SECTION)
                        }
                        val summary = aiClient
                            .generate(
                                PromptBuilder.buildSectionSummaryPrompt(
                                    sectionTitle,
                                    sectionExcerpt
                                )
                            )
                            .trim()
                        updateChat {
                            it.copy(
                                summary = summary.ifBlank { "（要約を生成できませんでした）" },
                                isSummaryLoading = false
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        updateChat { it.copy(isSummaryLoading = false, error = e.message ?: "Unknown error") }
                    }
                    fetchSuggestions(sectionTitle, sectionText)
                }
                // **旧文言は存在しない機能を案内していた** —「先にAI要約や補記メモを実行して
                // ダウンロードしてください」の補記メモは 2026-08-09 にひとことへ置き換わっている。
                // **`message` だけを取り出さない** — 導線を捨てると一時的な不可でも再試行できず、
                // エラーと同じ赤で出てしまう（状態の説明は失敗ではない）。
                AiAvailability.NeedsDownload,
                AiAvailability.Downloading,
                AiAvailability.Unsupported,
                is AiAvailability.TemporarilyUnavailable -> updateChat {
                    it.copy(
                        isSummaryLoading = false,
                        aiNotice = aiStatusNotice(availability, OPEN_FEATURE_LABEL)
                    )
                }
            }
        }
    }

    fun sendMessage(text: String) {
        val chat = state.current.sectionChat ?: return
        val question = text.trim()
        if (question.isBlank() || chat.isGenerating) return

        val history = historyOf(chat.messages)
        updateChat {
            it.copy(
                messages = it.messages + ChatMessage(ChatRole.User, question),
                isGenerating = true,
                // 前回の説明・失敗は畳む。新しい試行が古い理由を上書きする。
                answerError = null,
                aiNotice = null
            )
        }
        answerJob = scope.launch { runAnswer(chat, question, history) }
    }

    /**
     * 質問1件ぶんの回答を作る。**ログへ質問は積まない**（積むのは呼び出し側）。
     *
     * [retryAi] からも呼ぶので、ここで積むと再実行のたびに質問が重複する。
     */
    private suspend fun runAnswer(
        chat: SectionChatState,
        question: String,
        history: List<Pair<String, String>>
    ) {
        // **未取得を非対応と同じ文言へ畳まない。** ここは `!= Available` の1行だったため、
        // モデルが未取得なだけの端末にも「この端末ではAIを利用できません。」と出ていた。
        when (val availability = aiClient.checkAvailability()) {
            AiAvailability.Ready -> Unit
            AiAvailability.NeedsDownload,
            AiAvailability.Downloading,
            AiAvailability.Unsupported,
            is AiAvailability.TemporarilyUnavailable -> {
                updateChat {
                    it.copy(
                        isGenerating = false,
                        aiNotice = aiStatusNotice(availability, ANSWER_FEATURE_LABEL)
                    )
                }
                return
            }
        }
        try {
            val sectionExcerpt = withContext(excerptDispatcher) {
                buildNoteExcerpt(chat.sectionContext, NoteExcerptLimits.SECTION)
            }
            val answer = aiClient.generate(
                PromptBuilder.buildSectionChatPrompt(
                    sectionTitle = chat.sectionTitle,
                    sectionExcerpt = sectionExcerpt,
                    history = history,
                    question = question
                )
            ).trim()
            updateChat {
                it.copy(
                    messages = it.messages + ChatMessage(
                        ChatRole.Ai,
                        answer.ifBlank { "（回答を生成できませんでした）" }
                    ),
                    isGenerating = false
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // **要約側の `error` へ入れない。** 要約が出ていると表示が優先されて見えなくなる。
            updateChat { it.copy(isGenerating = false, answerError = e.message ?: "Unknown error") }
        }
    }

    private fun historyOf(messages: List<ChatMessage>): List<Pair<String, String>> =
        messages.map { (if (it.role == ChatRole.User) "User" else "AI") to it.text }

    /** 生成中・完了済みのセッションをシートに再表示する。 */
    fun showSheet() {
        if (state.current.sectionChat == null) return
        state.update { current -> current.copy(isSectionChatSheetVisible = true) }
    }

    /**
     * スワイプ・背景タップ・戻る操作では表示だけ閉じる。
     * AI生成と結果は同じノート内に保持し、読書を妨げない。
     */
    fun dismissSheet() {
        state.update { current -> current.copy(isSectionChatSheetVisible = false) }
    }

    /** 明示キャンセル・確認終了・ノート/Vault切替時にセッション全体を破棄する。 */
    fun cancelAndClear() {
        cancelJobs()
        state.update { current ->
            current.copy(
                sectionChat = null,
                isSectionChatSheetVisible = false
            )
        }
    }

    // 新規セッション開始・明示終了時に実行中の生成を止める内部処理。
    private fun cancelJobs() {
        openJob?.cancel()
        answerJob?.cancel()
        openJob = null
        answerJob = null
    }

    private suspend fun fetchSuggestions(sectionTitle: String, sectionText: String) {
        try {
            val sectionExcerpt = withContext(excerptDispatcher) {
                buildNoteExcerpt(sectionText, NoteExcerptLimits.SECTION)
            }
            val raw = aiClient.generate(
                PromptBuilder.buildSectionSuggestionsPrompt(
                    sectionTitle,
                    sectionExcerpt
                )
            )
            val questions = raw.lineSequence()
                .map { it.trim().removePrefix("-").trim().trim('"').trim() }
                .filter { it.isNotBlank() }
                .take(3)
                .toList()
            updateChat { it.copy(suggestions = questions) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // サジェストは失敗しても本体機能に影響させない
        }
    }

    // sectionChat が開いている場合のみ安全に更新する
    private fun updateChat(block: (SectionChatState) -> SectionChatState) {
        state.update { state ->
            val current = state.sectionChat ?: return@update state
            state.copy(sectionChat = block(current))
        }
    }

    private companion object {
        /** シートを開いたときの説明へ埋め込む機能名。 */
        const val OPEN_FEATURE_LABEL = "この部分の要約と質問"
        /** 質問を送ったときの説明へ埋め込む機能名。 */
        const val ANSWER_FEATURE_LABEL = "質問への回答"
    }
}
