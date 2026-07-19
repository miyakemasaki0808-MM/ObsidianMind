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
        cancelJobs()
        uiState.value = uiState.value.copy(
            sectionChat = SectionChatState(
                sectionTitle = section.title,
                sectionContext = section.text,
                isSummaryLoading = true
            )
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

    fun close() {
        // シートを閉じたら実行中の生成を止める（Nanoの無駄な稼働を防ぐ）
        cancelJobs()
        uiState.value = uiState.value.copy(sectionChat = null)
    }

    // ノート/Vault切替時に NoteViewModel の cancelNoteScopedJobs() から呼ばれる契約。
    fun cancelJobs() {
        openJob?.cancel()
        answerJob?.cancel()
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
