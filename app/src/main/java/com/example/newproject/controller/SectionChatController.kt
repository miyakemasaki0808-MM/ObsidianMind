package com.example.newproject.controller

import com.example.newproject.model.SectionChatStateWriter
import com.example.newproject.model.state.SectionChatProblem
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
 * セクション単位の部分要約（見出しの要約ボタン→ボトムシート）を担当する。
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

    // 入口から開く。今の節の要約を作る。
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
     * 要約をもう一度作る。
     *
     * **`open()` は再入できない**（`sectionChat != null` なら再表示するだけ）ので、
     * 要約エリアに添えた再試行導線はここへ来る。対象は開いているセッションの本文で、
     * 別のセクションへは移らない。
     */
    fun retrySummary() {
        val chat = state.current.sectionChat ?: return
        // 要約が既にあるなら作り直さない（説明を畳むだけでよい）。
        if (chat.summary != null) {
            updateChat { it.copy(summaryProblem = null) }
            return
        }
        openJob?.cancel()
        updateChat { it.copy(isSummaryLoading = true, summaryProblem = null) }
        startSummary(chat.sectionTitle, chat.sectionContext)
    }

    private fun startSummary(sectionTitle: String, sectionText: String) {
        openJob = scope.launch {
            // **状態確認の例外も終端へ落とす。** `AiClient` は他実装を許す公開契約なので
            // `checkAvailability()` は投げうる。投げたまま launch を抜けると
            // **`isSummaryLoading` が真のまま残り、シートが永久に待つ。**
            val availability = try {
                aiClient.checkAvailability()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateChat {
                    it.copy(
                        isSummaryLoading = false,
                        summaryProblem = SectionChatProblem.GenerationFailed(
                            e.message ?: "Unknown error"
                        )
                    )
                }
                return@launch
            }
            when (availability) {
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
                        updateChat {
                            it.copy(
                                isSummaryLoading = false,
                                summaryProblem = SectionChatProblem.GenerationFailed(
                                    e.message ?: "Unknown error"
                                )
                            )
                        }
                    }
                }
                // **`message` だけを取り出さない** — 導線を捨てると一時的な不可でも再試行できず、
                // エラーと同じ赤で出てしまう（状態の説明は失敗ではない）。
                AiAvailability.NeedsDownload,
                AiAvailability.Downloading,
                AiAvailability.Unsupported,
                is AiAvailability.TemporarilyUnavailable -> updateChat {
                    it.copy(
                        isSummaryLoading = false,
                        summaryProblem = sectionChatNotice(availability, OPEN_FEATURE_LABEL)
                    )
                }
            }
        }
    }

    /**
     * このシート用の説明を作る。
     *
     * **`canStartDownload = false`** — 部分要約は設計上ここでモデルDLを
     * 始めない（→ features/section_ai_chat.md 判断4）ので、`Download` の導線を持たせると
     * **「開始してください」と言いながら開始操作が無い**案内になる。
     */
    private fun sectionChatNotice(
        availability: AiAvailability,
        featureLabel: String
    ): SectionChatProblem? =
        aiStatusNotice(availability, featureLabel, canStartDownload = false)
            ?.let(SectionChatProblem::AiStatus)

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
        openJob = null
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
        const val OPEN_FEATURE_LABEL = "この部分の要約"
    }
}
