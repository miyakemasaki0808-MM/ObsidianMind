package com.example.newproject.controller

import com.example.newproject.model.SectionChatStateWriter
import com.example.newproject.model.SectionRef
import com.example.newproject.model.state.SectionChatProblem
import com.example.newproject.model.state.SectionChatState
import com.example.newproject.model.state.SectionSummary
import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.example.newproject.ai.PromptBuilder
import com.example.newproject.domain.aiStatusNotice
import com.example.newproject.domain.buildNoteExcerpt
import com.example.newproject.domain.isShownAsIsOnRequest
import com.example.newproject.domain.summaryOf
import com.example.newproject.domain.updated
import com.example.newproject.domain.withStarted
import com.example.newproject.domain.without
import com.example.newproject.model.NoteExcerptLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 節ごとの部分要約（→ features/margin_pane.md §5.8）。結果は「この部分」の面に出す。
 * NoteViewModel から scope と状態Flowを注入され、sectionChat の更新のみを行う。
 *
 * **ノートを開いている間、最近作った3節分を持つ。** 生成は一度に1本で、別の節を頼むと前の生成を取り消す。
 */
class SectionChatController(
    private val scope: CoroutineScope,
    private val aiClient: AiClient,
    private val state: SectionChatStateWriter,
    private val excerptDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    // 走っている生成。一度に1本だけ。
    private var job: Job? = null
    private var lastRequestId = 0L

    /**
     * [section] の要約を頼む。**持っていれば作り直さない** — 生成中ならその生成を、できていればその要約を、
     * 出せなかったならその理由を見せる（見せるのは画面）。ただし後で使えるようになる説明は確かめ直す
     * （→ [isShownAsIsOnRequest]）。
     *
     * 見出し名と本文は**頼んだ時点のもの**を要求に持たせる（→ lessons L26）。
     * [quietly] は面を出さずに頼んだか。端末AIが使えない理由が届いたら、画面が一度だけ面を開いて見せる（→ [SectionSummary.noticePending]）。
     */
    fun request(section: SectionRef, sectionTitle: String, sectionText: String, quietly: Boolean = false) {
        if (state.current.summaryOf(section)?.isShownAsIsOnRequest() == true) return
        start(section, sectionTitle, sectionText, noticePending = quietly)
    }

    /**
     * 出せなかった要約を作り直す。**頼んだときの本文のまま**で、今の解析から引き直さない。
     * 生成中か、要約を持っているなら何もしない。
     */
    fun retry(section: SectionRef) {
        val previous = state.current.summaryOf(section) ?: return
        if (previous.isSummaryLoading || previous.summary != null) return
        start(previous.section, previous.sectionTitle, previous.sectionContext, noticePending = false)
    }

    /**
     * 面を出さずに頼んだ要求の理由を、画面が見せた（→ [SectionSummary.noticePending]）。
     * **要求の番号で下ろす** — 見せた後に同じ節を頼み直していれば、新しい要求の印は残す。
     */
    fun acknowledgeNotice(requestId: Long) {
        state.update { it.updated(requestId) { summary -> summary.copy(noticePending = false) } }
    }

    /** 生成を中止する。**取り消した節はボタンに戻る。** 生成中でなければ何もしない。 */
    fun cancel(section: SectionRef) {
        if (state.current.summaryOf(section)?.isSummaryLoading != true) return
        job?.cancel()
        job = null
        state.update { it.without(section) }
    }

    private fun start(section: SectionRef, sectionTitle: String, sectionText: String, noticePending: Boolean) {
        // 別の節の生成が走っていれば、ここで止める（その節は withStarted が取り除く）。
        job?.cancel()
        val requestId = ++lastRequestId
        state.update {
            it.withStarted(
                SectionSummary(
                    section = section,
                    requestId = requestId,
                    sectionTitle = sectionTitle,
                    sectionContext = sectionText,
                    isSummaryLoading = true,
                    noticePending = noticePending
                )
            )
        }
        job = scope.launch {
            // **状態確認の例外も終端へ落とす。** `AiClient` は他実装を許す公開契約なので
            // `checkAvailability()` は投げうる。投げたまま launch を抜けると
            // **`isSummaryLoading` が真のまま残り、面が永久に待つ。**
            val availability = try {
                aiClient.checkAvailability()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateSummary(requestId) {
                    it.copy(
                        isSummaryLoading = false,
                        summaryProblem = SectionChatProblem.GenerationFailed(e.message ?: "Unknown error")
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
                            .generate(PromptBuilder.buildSectionSummaryPrompt(sectionTitle, sectionExcerpt))
                            .trim()
                        updateSummary(requestId) {
                            it.copy(
                                summary = summary.ifBlank { "（要約を生成できませんでした）" },
                                isSummaryLoading = false
                            )
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        updateSummary(requestId) {
                            it.copy(
                                isSummaryLoading = false,
                                summaryProblem = SectionChatProblem.GenerationFailed(e.message ?: "Unknown error")
                            )
                        }
                    }
                }
                // **`message` だけを取り出さない** — 導線を捨てると一時的な不可でも再試行できず、
                // エラーと同じ赤で出てしまう（状態の説明は失敗ではない）。
                AiAvailability.NeedsDownload,
                AiAvailability.Downloading,
                AiAvailability.Unsupported,
                is AiAvailability.TemporarilyUnavailable -> updateSummary(requestId) {
                    it.copy(
                        isSummaryLoading = false,
                        summaryProblem = sectionChatNotice(availability, OPEN_FEATURE_LABEL)
                    )
                }
            }
        }
    }

    /**
     * この面用の説明を作る。
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

    /** ノート・Vault切替と本文の解析し直しで、生成を止めて全部捨てる。 */
    fun cancelAndClear() {
        job?.cancel()
        job = null
        state.update { SectionChatState() }
    }

    /**
     * **要求の番号が一致する要約にだけ書く。** 取り消しに従わない生成（`AiClient` は他実装を許す）が
     * 遅れて届いても、取り除いた節や作り直した節を上書きしない。
     */
    private fun updateSummary(requestId: Long, block: (SectionSummary) -> SectionSummary) {
        state.update { it.updated(requestId, block) }
    }

    private companion object {
        /** 出せないときの説明へ埋め込む機能名。 */
        const val OPEN_FEATURE_LABEL = "この部分の要約"
    }
}
