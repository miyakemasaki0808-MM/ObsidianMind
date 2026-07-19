package com.example.newproject

import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.example.newproject.ai.PromptBuilder
import com.example.newproject.ui.markdown.NoteSection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * セクション単位のAIチャット（吹き出し→ボトムシート）を担当する。
 * NoteViewModel から scope と状態Flowを注入され、sectionChat の更新のみを行う。
 */
class SectionChatController(
    private val scope: CoroutineScope,
    private val aiClient: AiClient,
    private val uiState: MutableStateFlow<NoteUiState>
) {
    // 前のセクションの生成が後から届いて新しいシートを上書きしないよう保持する
    private var openJob: Job? = null
    private var answerJob: Job? = null

    // 吹き出しタップで開く。要約と候補質問をまとめて用意する。
    fun open(section: NoteSection) {
        // 生成中・完了済みのセッションがあれば、その結果を再表示する。
        // スクロール先の別セクションで重複生成しないよう、対象は開始時のものに固定する。
        if (uiState.value.sectionChat != null) {
            showSheet()
            return
        }
        cancelJobs()
        uiState.value = uiState.value.copy(
            sectionChat = SectionChatState(
                sectionTitle = section.title,
                sectionContext = section.text,
                isSummaryLoading = true
            ),
            isSectionChatSheetVisible = true
        )
        openJob = scope.launch {
            when (aiClient.checkAvailability()) {
                AiAvailability.Unavailable ->
                    updateChat { it.copy(isSummaryLoading = false, error = "この端末ではAIを利用できません。") }
                AiAvailability.NeedsDownload ->
                    updateChat { it.copy(isSummaryLoading = false, error = "AIモデルの準備が必要です。先にAI要約や補記メモを実行してダウンロードしてください。") }
                AiAvailability.Available -> {
                    try {
                        val summary = aiClient
                            .generate(PromptBuilder.buildSectionSummaryPrompt(section.title, section.text))
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
            }
        }
    }

    fun sendMessage(text: String) {
        val chat = uiState.value.sectionChat ?: return
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
            if (aiClient.checkAvailability() != AiAvailability.Available) {
                updateChat { it.copy(isGenerating = false, error = "この端末ではAIを利用できません。") }
                return@launch
            }
            try {
                val answer = aiClient.generate(
                    PromptBuilder.buildSectionChatPrompt(
                        sectionTitle = chat.sectionTitle,
                        sectionText = chat.sectionContext,
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
        if (uiState.value.sectionChat == null) return
        uiState.value = uiState.value.copy(isSectionChatSheetVisible = true)
    }

    /**
     * スワイプ・背景タップ・戻る操作では表示だけ閉じる。
     * AI生成と結果は同じノート内に保持し、読書を妨げない。
     */
    fun dismissSheet() {
        uiState.value = uiState.value.copy(isSectionChatSheetVisible = false)
    }

    /** 明示キャンセル・確認終了・ノート/Vault切替時にセッション全体を破棄する。 */
    fun cancelAndClear() {
        cancelJobs()
        uiState.value = uiState.value.copy(
            sectionChat = null,
            isSectionChatSheetVisible = false
        )
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
            val raw = aiClient.generate(
                PromptBuilder.buildSectionSuggestionsPrompt(section.title, section.text)
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
        val current = uiState.value.sectionChat ?: return
        uiState.value = uiState.value.copy(sectionChat = block(current))
    }
}
