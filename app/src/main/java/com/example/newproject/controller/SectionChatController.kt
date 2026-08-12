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
        openJob = scope.launch {
            when (val availability = aiClient.checkAvailability()) {
                AiAvailability.Ready -> {
                    try {
                        val sectionExcerpt = withContext(excerptDispatcher) {
                            buildNoteExcerpt(section.text, NoteExcerptLimits.SECTION)
                        }
                        val summary = aiClient
                            .generate(
                                PromptBuilder.buildSectionSummaryPrompt(
                                    section.title,
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
                    fetchSuggestions(section)
                }
                // **旧文言は存在しない機能を案内していた** —「先にAI要約や補記メモを実行して
                // ダウンロードしてください」の補記メモは 2026-08-09 にひとことへ置き換わっている。
                // 文言は aiStatusNotice に任せ、ここは沈黙しないことだけを決める。
                AiAvailability.NeedsDownload,
                AiAvailability.Downloading,
                AiAvailability.Unsupported,
                is AiAvailability.CheckFailed -> updateChat {
                    it.copy(
                        isSummaryLoading = false,
                        error = aiStatusNotice(availability, OPEN_FEATURE_LABEL)?.message
                    )
                }
            }
        }
    }

    fun sendMessage(text: String) {
        val chat = state.current.sectionChat ?: return
        val question = text.trim()
        if (question.isBlank() || chat.isGenerating) return

        val history = chat.messages.map {
            (if (it.role == ChatRole.User) "User" else "AI") to it.text
        }
        updateChat {
            it.copy(
                messages = it.messages + ChatMessage(ChatRole.User, question),
                isGenerating = true,
                error = null
            )
        }
        answerJob = scope.launch {
            // **未取得を非対応と同じ文言へ畳まない。** ここは `!= Available` の1行だったため、
            // モデルが未取得なだけの端末にも「この端末ではAIを利用できません。」と出ていた。
            when (val availability = aiClient.checkAvailability()) {
                AiAvailability.Ready -> Unit
                AiAvailability.NeedsDownload,
                AiAvailability.Downloading,
                AiAvailability.Unsupported,
                is AiAvailability.CheckFailed -> {
                    updateChat {
                        it.copy(
                            isGenerating = false,
                            error = aiStatusNotice(availability, ANSWER_FEATURE_LABEL)?.message
                        )
                    }
                    return@launch
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
                updateChat { it.copy(isGenerating = false, error = e.message ?: "Unknown error") }
            }
        }
    }

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

    private suspend fun fetchSuggestions(section: NoteSection) {
        try {
            val sectionExcerpt = withContext(excerptDispatcher) {
                buildNoteExcerpt(section.text, NoteExcerptLimits.SECTION)
            }
            val raw = aiClient.generate(
                PromptBuilder.buildSectionSuggestionsPrompt(
                    section.title,
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
