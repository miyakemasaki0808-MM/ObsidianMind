package com.example.newproject.controller

import com.example.newproject.model.SectionChatStateWriter
import com.example.newproject.model.SectionRef
import com.example.newproject.model.state.SectionBatch
import com.example.newproject.model.state.SectionChatProblem
import com.example.newproject.model.state.SectionChatState
import com.example.newproject.model.state.SectionSummary
import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.example.newproject.ai.PromptBuilder
import com.example.newproject.ai.isAiCoreRefusal
import com.example.newproject.domain.SECTION_BATCH_FAILURE_LIMIT
import com.example.newproject.domain.SECTION_BATCH_LIMIT
import com.example.newproject.domain.SECTION_BATCH_REFUSAL_LIMIT
import com.example.newproject.domain.SECTION_BATCH_REFUSAL_WAIT_MS
import com.example.newproject.domain.aiStatusNotice
import com.example.newproject.domain.buildNoteExcerpt
import com.example.newproject.domain.isBatchRunning
import com.example.newproject.domain.isShownAsIsOnRequest
import com.example.newproject.domain.needsBatchGeneration
import com.example.newproject.domain.summaryOf
import com.example.newproject.domain.updated
import com.example.newproject.domain.withBatchStopped
import com.example.newproject.domain.withQueued
import com.example.newproject.domain.withStarted
import com.example.newproject.domain.without
import com.example.newproject.model.NoteExcerptLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 1節の要約の材料。見出し名と本文は**頼んだ時点のもの**を要求に持たせる（→ lessons L26）。 */
class SectionSummarySource(val section: SectionRef, val sectionTitle: String, val sectionText: String)

/**
 * 節ごとの部分要約と全節の要約（→ features/section_ai_chat.md）。結果は「この部分」の面に出す。
 * NoteViewModel から scope と状態Flowを注入され、sectionChat の更新のみを行う。
 *
 * **作った要約はノートを開いている間すべて持つ。** 生成は一度に1本で、1つの生成係が作る節の列を先頭から消化する。
 * 全節の外で別の節を頼むと前の生成を取り消し、全節の間は取り消さず次に作る。
 */
class SectionChatController(
    private val scope: CoroutineScope,
    private val aiClient: AiClient,
    private val state: SectionChatStateWriter,
    private val excerptDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    /** [counted] は全節の上限に数える節か。 */
    private class Pending(val source: SectionSummarySource, val counted: Boolean)

    private class Started(val source: SectionSummarySource, val requestId: Long)

    private enum class Outcome { Done, Failed, Stop }

    // 作る節の列。生成係だけが取り出し、全節の間の要求は先頭へ割り込む。
    private val queue = ArrayDeque<Pending>()

    // 列を消化する生成係。一度に1本だけ。
    private var worker: Job? = null
    private var lastRequestId = 0L

    // 今回の全節であと何節作るか。
    private var remaining = 0

    // 全節を頼んだときの今の節と、面を出さずに頼んだか。端末AIが使えない理由はこの節に出す。
    private var noticeSource: SectionSummarySource? = null
    private var noticeQuietly = false

    // **ノートをまたいで残す。** 背面かどうかはノートと関係がない（→ ReadingPauseReason）。
    private val foreground = MutableStateFlow(true)

    /**
     * [section] の要約を頼む。**持っていれば作り直さない** — 生成中ならその生成を、できていればその要約を、
     * 出せなかったならその理由を見せる（見せるのは画面）。ただし後で使えるようになる説明は確かめ直す
     * （→ [isShownAsIsOnRequest]）。
     */
    fun request(section: SectionRef, sectionTitle: String, sectionText: String) {
        if (state.current.summaryOf(section)?.isShownAsIsOnRequest() == true) return
        startOrEnqueue(SectionSummarySource(section, sectionTitle, sectionText))
    }

    /**
     * 出せなかった要約を作り直す。**頼んだときの本文のまま**で、今の解析から引き直さない。
     * 生成中か、要約を持っているなら何もしない。
     */
    fun retry(section: SectionRef) {
        val previous = state.current.summaryOf(section) ?: return
        if (previous.isSummaryLoading || previous.summary != null) return
        startOrEnqueue(SectionSummarySource(previous.section, previous.sectionTitle, previous.sectionContext))
    }

    /**
     * 全節の要約を頼む。[targets] は作る順に並べる。要約を持っている節は飛ばし、生成中の節は取り消さない。
     * **続きからは、持っていない節を数え直すだけで済む** — 押し直しも同じ入口である。走っている間は何もしない。
     *
     * [current] は押した時点の今の節。端末AIが使えない理由はここに出す。
     * [quietly] は面を出さずに頼んだか。理由が届いたら、画面が一度だけ面を開いて見せる（→ [SectionSummary.noticePending]）。
     */
    fun requestAll(targets: List<SectionSummarySource>, current: SectionSummarySource?, quietly: Boolean) {
        val chat = state.current
        if (targets.isEmpty() || chat.isBatchRunning) return
        val generating = worker?.isActive == true
        state.update { it.copy(batch = SectionBatch(targets.map { source -> source.section }, isRunning = true)) }
        queue.clear()
        targets.filter { chat.summaryOf(it.section).needsBatchGeneration() }
            .mapTo(queue) { Pending(it, counted = true) }
        remaining = SECTION_BATCH_LIMIT
        noticeSource = current
        noticeQuietly = quietly
        // 1節の生成が走っていれば、その生成係が続けて列を消化する。
        if (!generating) launchWorker()
    }

    /** 全節の間、[section] を次に作る。**生成中の節は取り消さない。** 列に無ければ何もしない。 */
    fun prioritize(section: SectionRef?) {
        if (section == null || !state.current.isBatchRunning) return
        val index = queue.indexOfFirst { it.source.section == section }
        if (index > 0) queue.addFirst(queue.removeAt(index))
    }

    /** 全節の生成を止める。**生成中の節はボタンに戻り**、できた要約は残る。押し直すと続きから作る。 */
    fun stopAll() {
        if (!state.current.isBatchRunning) return
        worker?.cancel()
        worker = null
        queue.clear()
        state.update { chat ->
            chat.copy(summaries = chat.summaries.filterNot { it.isSummaryLoading }).withBatchStopped()
        }
    }

    /**
     * 面を出さずに頼んだ要求の理由を、画面が見せた（→ [SectionSummary.noticePending]）。
     * **要求の番号で下ろす** — 見せた後に同じ節を頼み直していれば、新しい要求の印は残す。
     */
    fun acknowledgeNotice(requestId: Long) {
        state.update { it.updated(requestId) { summary -> summary.copy(noticePending = false) } }
    }

    /**
     * 生成を中止する。**取り消した節はボタンに戻る。** 生成中でなければ何もしない。
     * 全節の間は何もしない — 止めるのは全節の口だけで、遅れて届いた押下で全節の生成係を止めない。
     */
    fun cancel(section: SectionRef) {
        val chat = state.current
        if (chat.summaryOf(section)?.isSummaryLoading != true || chat.isBatchRunning) return
        worker?.cancel()
        worker = null
        state.update { it.without(section) }
    }

    /** ノート・Vault切替と本文の解析し直しで、生成を止めて全部捨てる。 */
    fun cancelAndClear() {
        worker?.cancel()
        worker = null
        queue.clear()
        remaining = 0
        noticeSource = null
        noticeQuietly = false
        state.update { SectionChatState() }
    }

    /** アプリが背面へ回った。全節は次の節へ進まない。**冊子が前面なら止めない** — AICore は断らない。 */
    internal fun pause(reason: ReadingPauseReason) {
        if (reason == ReadingPauseReason.AppBackground) foreground.value = false
    }

    internal fun resume(reason: ReadingPauseReason) {
        if (reason == ReadingPauseReason.AppBackground) foreground.value = true
    }

    private fun startOrEnqueue(source: SectionSummarySource) {
        if (state.current.isBatchRunning) {
            val queued = queue.firstOrNull { it.source.section == source.section }
            if (queued != null) queue.remove(queued)
            queue.addFirst(Pending(source, counted = queued?.counted == true))
            state.update { it.withQueued(source.section) }
            return
        }
        // 別の節の生成が走っていれば、ここで止める（その節は withStarted が取り除く）。
        worker?.cancel()
        queue.clear()
        queue.addFirst(Pending(source, counted = false))
        launchWorker()
    }

    private fun launchWorker() {
        val first = startNext()
        if (first == null) {
            state.update { it.withBatchStopped() }
            return
        }
        worker = scope.launch {
            var started: Started? = first
            var failures = 0
            while (started != null) {
                when (generate(started)) {
                    Outcome.Done -> failures = 0
                    Outcome.Failed -> if (state.current.isBatchRunning && ++failures >= SECTION_BATCH_FAILURE_LIMIT) queue.clear()
                    Outcome.Stop -> queue.clear()
                }
                // 取り消しに従わない生成が遅れて返っても、新しい生成係の列に触らない。
                ensureActive()
                started = startNext()
            }
            state.update { it.withBatchStopped() }
        }
    }

    /** 列の先頭を生成中にする。**上限を使い切ったら全節の残りを捨てる**（押し直しは数え直すので控えない）。 */
    private fun startNext(): Started? {
        if (remaining <= 0) queue.removeAll { it.counted }
        val next = queue.removeFirstOrNull() ?: return null
        if (next.counted) remaining--
        val requestId = ++lastRequestId
        state.update {
            it.withStarted(
                SectionSummary(
                    section = next.source.section,
                    requestId = requestId,
                    sectionTitle = next.source.sectionTitle,
                    sectionContext = next.source.sectionText,
                    isSummaryLoading = true
                )
            )
        }
        return Started(next.source, requestId)
    }

    private suspend fun generate(started: Started): Outcome {
        // **状態確認の例外も終端へ落とす。** `AiClient` は他実装を許す公開契約なので
        // `checkAvailability()` は投げうる。投げたまま抜けると
        // **`isSummaryLoading` が真のまま残り、面が永久に待つ。**
        val availability = try {
            aiClient.checkAvailability()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            writeFailure(started, e)
            return Outcome.Failed
        }
        return when (availability) {
            AiAvailability.Ready -> summarize(started)
            // **`message` だけを取り出さない** — 導線を捨てると一時的な不可でも再試行できず、
            // エラーと同じ赤で出てしまう（状態の説明は失敗ではない）。
            AiAvailability.NeedsDownload,
            AiAvailability.Downloading,
            AiAvailability.Unsupported,
            is AiAvailability.TemporarilyUnavailable -> {
                writeNotice(started, sectionChatNotice(availability, OPEN_FEATURE_LABEL))
                Outcome.Stop
            }
        }
    }

    private suspend fun summarize(started: Started): Outcome {
        var refusals = 0
        while (true) {
            try {
                // 背面の AICore は要求を断るので、節を無駄に断られない。
                if (state.current.isBatchRunning) foreground.first { it }
                val sectionExcerpt = withContext(excerptDispatcher) {
                    buildNoteExcerpt(started.source.sectionText, NoteExcerptLimits.SECTION)
                }
                val summary = aiClient
                    .generate(PromptBuilder.buildSectionSummaryPrompt(started.source.sectionTitle, sectionExcerpt))
                    .trim()
                updateSummary(started.requestId) {
                    it.copy(
                        summary = summary.ifBlank { "（要約を生成できませんでした）" },
                        isSummaryLoading = false
                    )
                }
                return Outcome.Done
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 取り消しに従わない生成が遅れて断られても、新しい全節の状態に触らない。
                currentCoroutineContext().ensureActive()
                if (!state.current.isBatchRunning || !isAiCoreRefusal(e)) {
                    writeFailure(started, e)
                    return Outcome.Failed
                }
                if (++refusals >= SECTION_BATCH_REFUSAL_LIMIT) {
                    // **節の失敗にしない。** 断られたのは節のせいではなく、! を並べても次の行動を示せない。
                    state.update { chat ->
                        chat.without(started.source.section).copy(batch = chat.batch?.copy(stoppedBusy = true))
                    }
                    return Outcome.Stop
                }
            }
            delay(SECTION_BATCH_REFUSAL_WAIT_MS)
        }
    }

    private fun writeFailure(started: Started, error: Exception) {
        updateSummary(started.requestId) {
            it.copy(
                isSummaryLoading = false,
                summaryProblem = SectionChatProblem.GenerationFailed(error.message ?: "Unknown error")
            )
        }
    }

    /**
     * 端末AIが使えない理由を書く。**全節の間は全節を終える** — どの節でも同じ答えが返るので、節の数だけ理由を並べない。
     * 理由は押した時点の今の節に出す。その節の要約をすでに持っていれば、上書きせず作っていた節に出す。
     */
    private fun writeNotice(started: Started, problem: SectionChatProblem?) {
        if (!state.current.isBatchRunning) {
            updateSummary(started.requestId) { it.copy(isSummaryLoading = false, summaryProblem = problem) }
            return
        }
        val target = noticeSource?.takeIf { state.current.summaryOf(it.section)?.summary == null } ?: started.source
        state.update { chat ->
            chat.without(started.source.section).copy(batch = null).withStarted(
                SectionSummary(
                    section = target.section,
                    requestId = started.requestId,
                    sectionTitle = target.sectionTitle,
                    sectionContext = target.sectionText,
                    summaryProblem = problem,
                    noticePending = noticeQuietly
                )
            )
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
