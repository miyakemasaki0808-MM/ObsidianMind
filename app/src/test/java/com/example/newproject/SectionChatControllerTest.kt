package com.example.newproject

import com.example.newproject.controller.ReadingPauseReason
import com.example.newproject.controller.SectionChatController
import com.example.newproject.controller.SectionSummarySource
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.SectionRef
import com.example.newproject.model.state.AiNoticeAction
import com.example.newproject.model.state.SectionChatProblem
import com.example.newproject.model.state.SectionChatState
import com.example.newproject.model.state.SectionSummary
import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiTimeoutException
import com.example.newproject.domain.SECTION_BATCH_FAILURE_LIMIT
import com.example.newproject.domain.SECTION_BATCH_LIMIT
import com.example.newproject.domain.SECTION_BATCH_REFUSAL_LIMIT
import com.example.newproject.domain.SECTION_BATCH_REFUSAL_WAIT_MS
import com.example.newproject.domain.SectionBatchStatus
import com.example.newproject.domain.sectionBatchStatus
import com.example.newproject.domain.isBatchRunning
import com.example.newproject.domain.isQueued
import com.example.newproject.domain.summaryOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.example.newproject.model.NoteUiStateStore
import com.example.newproject.fakes.FakeAiClient
import com.google.mlkit.genai.common.GenAiException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/** 節ごとの部分要約（→ features/margin_pane.md §5.8）。 */
@OptIn(ExperimentalCoroutinesApi::class)
class SectionChatControllerTest {

    @Test
    fun `生成が終わると、頼んだ節の要約として残る`() = runTest {
        val (ai, response) = pendingAi()
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, ai)

        controller.request(B, "B", "Bの本文")
        runCurrent()
        assertTrue(summaryOf(state, B)?.isSummaryLoading == true)

        response.complete("Bの要約")
        advanceUntilIdle()

        val summary = requireNotNull(summaryOf(state, B))
        assertEquals("Bの要約", summary.summary)
        assertFalse(summary.isSummaryLoading)
    }

    /** **同じ節を頼み直しても作り直さない。** 生成中ならその生成を、できていればその要約を見せる。 */
    @Test
    fun `同じ節を頼み直しても、生成中でも完成後でも作り直さない`() = runTest {
        val (ai, response) = pendingAi()
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, ai)

        controller.request(B, "B", "Bの本文")
        runCurrent()
        controller.request(B, "B", "Bの本文")
        runCurrent()
        assertEquals(1, ai.generateCalls)

        response.complete("Bの要約")
        advanceUntilIdle()
        controller.request(B, "B", "Bの本文")
        advanceUntilIdle()

        assertEquals(1, ai.generateCalls)
        assertEquals("Bの要約", summaryOf(state, B)?.summary)
    }

    /**
     * **準備待ちの説明は、頼み直すと確かめ直す。** 準備待ちには再試行のボタンが無いので、見せ続けると
     * モデルが揃った後も同じ節を要約できない。確かめ直すだけで、この面からモデルのDLは始めない。
     */
    @Test
    fun `準備待ちの説明を持つ節は、モデルが揃った後に頼み直すと生成する`() = runTest {
        listOf(AiAvailability.NeedsDownload, AiAvailability.Downloading).forEach { waiting ->
            val state = NoteUiStateStore(NoteUiState())
            val ai = FakeAiClient(waiting) { "Bの要約" }
            val controller = controller(state, ai)
            controller.request(B, "B", "Bの本文")
            advanceUntilIdle()
            assertTrue("$waiting: 準備待ちの説明が出ていない", summaryOf(state, B)?.summaryProblem is SectionChatProblem.AiStatus)

            ai.availability = AiAvailability.Ready
            controller.request(B, "B", "Bの本文")
            advanceUntilIdle()

            assertEquals("$waiting: 揃った後に生成しない", 1, ai.generateCalls)
            assertEquals("Bの要約", summaryOf(state, B)?.summary)
            assertEquals("$waiting: この面からDLを始めた", 0, ai.downloadCalls)
        }
    }

    /** 確かめ直しても変わらない説明と、再試行のボタンがある失敗は、頼み直してもそのまま見せる。 */
    @Test
    fun `非対応の説明と生成の失敗は、頼み直しても作り直さない`() = runTest {
        val unsupported = NoteUiStateStore(NoteUiState())
        val unsupportedAi = FakeAiClient(AiAvailability.Unsupported) { "要約" }
        controller(unsupported, unsupportedAi).apply {
            request(B, "B", "Bの本文")
            advanceUntilIdle()
            request(B, "B", "Bの本文")
            advanceUntilIdle()
        }
        assertEquals(0, unsupportedAi.generateCalls)

        val failed = NoteUiStateStore(NoteUiState())
        val failingAi = FakeAiClient { throw AiTimeoutException("タイムアウト") }
        controller(failed, failingAi).apply {
            request(B, "B", "Bの本文")
            advanceUntilIdle()
            request(B, "B", "Bの本文")
            advanceUntilIdle()
        }
        assertEquals("失敗は面の再試行から作り直す", 1, failingAi.generateCalls)
    }

    /** **生成は一度に1本。** 別の節を頼むと前の生成を取り消し、取り消された節はボタンに戻る。 */
    @Test
    fun `Bの生成中にCを頼むと、Bを取り消してCを始める`() = runTest {
        val ai = FakeAiClient().suspendGenerations()
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, ai)

        controller.request(B, "B", "Bの本文")
        runCurrent()
        controller.request(C, "C", "Cの本文")
        runCurrent()

        assertNull("取り消された節はボタンに戻る", summaryOf(state, B))
        assertTrue(summaryOf(state, C)?.isSummaryLoading == true)
        assertEquals(2, ai.generateCalls)

        ai.completeAll("要約")
        advanceUntilIdle()
        assertNull("取り消したBの結果は書かれない", summaryOf(state, B))
        assertEquals("要約", summaryOf(state, C)?.summary)
    }

    /**
     * **取り消しに従わない生成が遅れて届いても、今の要求を上書きしない。** `AiClient` は他実装を許す公開契約なので、
     * 取り消しだけでは止まらない生成がありうる。B→C→B と頼み直した後に、最初の B の結果が届く順序を作る。
     *
     * **判定は全部の生成を再開してから行う。** 途中で落ちると、取り消しに従わない生成が残ってテストが終われない。
     */
    @Test
    fun `取り消しに従わない生成の後着は、頼み直した節を上書きしない`() = runTest {
        val waiting = mutableListOf<Continuation<String>>()
        val ai = FakeAiClient { suspendCoroutine { waiting += it } }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, ai)

        controller.request(B, "B", "Bの本文")
        runCurrent()
        controller.request(C, "C", "Cの本文")
        runCurrent()
        controller.request(B, "B", "Bの本文")
        runCurrent()
        assertEquals(3, waiting.size)

        waiting[0].resume("最初のBの要約")
        waiting[1].resume("Cの要約")
        advanceUntilIdle()
        val bAfterLate = summaryOf(state, B)
        val cAfterLate = summaryOf(state, C)
        waiting[2].resume("今のBの要約")
        advanceUntilIdle()

        assertTrue("頼み直したBは生成中のまま", bAfterLate?.isSummaryLoading == true)
        assertNull("最初のBの結果を書いた", bAfterLate?.summary)
        assertNull("取り消したCは戻らない", cAfterLate)
        assertEquals("今のBの要約", summaryOf(state, B)?.summary)
    }

    @Test
    fun `作った要約は節の数によらず残る`() = runTest {
        val ai = FakeAiClient { prompt -> "要約:" + prompt.length }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, ai)

        listOf(A, B, C, D).forEach { section ->
            controller.request(section, section.title!!, "${section.title}の本文")
            advanceUntilIdle()
        }

        assertEquals(listOf(D, C, B, A), state.value.sectionChat.summaries.map { it.section })
        assertTrue(state.value.sectionChat.summaries.all { it.summary != null })
    }

    @Test
    fun `生成を中止すると、その節はボタンに戻り結果も書かれない`() = runTest {
        val (ai, response) = pendingAi()
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, ai)

        controller.request(B, "B", "Bの本文")
        runCurrent()
        controller.cancel(B)
        assertNull(summaryOf(state, B))

        response.complete("中止後の結果")
        advanceUntilIdle()
        assertNull(summaryOf(state, B))
    }

    /** できた要約は「中止」の対象ではない。中止の操作は生成中にしか出ないが、遅れて押しても消さない。 */
    @Test
    fun `生成中でない節を中止しても、要約は消えない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, FakeAiClient { "Bの要約" })

        controller.request(B, "B", "Bの本文")
        advanceUntilIdle()
        controller.cancel(B)

        assertEquals("Bの要約", summaryOf(state, B)?.summary)
    }

    @Test
    fun `ノートを替えると生成を止めて全部捨て、後着も書かない`() = runTest {
        val (ai, response) = pendingAi()
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, ai)

        controller.request(B, "B", "Bの本文")
        runCurrent()
        controller.cancelAndClear()
        assertTrue(state.value.sectionChat.summaries.isEmpty())

        response.complete("切替後の結果")
        advanceUntilIdle()
        assertTrue(state.value.sectionChat.summaries.isEmpty())
    }

    /**
     * **状態の説明を `error` へ文字列で入れない。**
     *
     * 文字列だけにすると導線（[AiNoticeAction]）が消えて再試行できず、
     * さらに面が赤いエラー表示で描いてしまう（状態の説明は失敗ではない）。
     */
    @Test
    fun `一時的に使えないときは再試行できる説明を持つ`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(
            state,
            FakeAiClient(AiAvailability.TemporarilyUnavailable(IllegalStateException("AICore not bound")))
        )

        controller.request(B, "B", "Bの本文")
        advanceUntilIdle()

        val summary = requireNotNull(summaryOf(state, B))
        val problem = requireNotNull(summary.summaryProblem)
        assertTrue("生成の失敗として扱わないこと", problem is SectionChatProblem.AiStatus)
        assertEquals(AiNoticeAction.Retry, (problem as SectionChatProblem.AiStatus).notice.action)
        assertFalse(summary.isSummaryLoading)
    }

    /** **非対応には再試行導線を出さない。** 何度押しても同じ答えが返る。 */
    @Test
    fun `非対応の説明は再試行導線を持たない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        controller(state, FakeAiClient(AiAvailability.Unsupported)).request(B, "B", "Bの本文")
        advanceUntilIdle()

        val problem = requireNotNull(summaryOf(state, B)?.summaryProblem)
        assertEquals(AiNoticeAction.None, (problem as SectionChatProblem.AiStatus).notice.action)
    }

    /** 再試行は頼んだときの本文のまま試し直す。今の解析から引き直さない。 */
    @Test
    fun `再試行すると、頼んだときの本文で生成し直す`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val ai = FakeAiClient(AiAvailability.TemporarilyUnavailable(IllegalStateException("boom"))) { "Bの要約" }
        val controller = controller(state, ai)

        controller.request(B, "B", "Bの本文")
        advanceUntilIdle()
        assertNotNull(summaryOf(state, B)?.summaryProblem)

        ai.availability = AiAvailability.Ready
        controller.retry(B)
        advanceUntilIdle()

        val summary = requireNotNull(summaryOf(state, B))
        assertNull(summary.summaryProblem)
        assertEquals("Bの要約", summary.summary)
        assertTrue("頼んだときの本文で生成する", ai.lastPrompt!!.contains("Bの本文"))
    }

    /** 要約の生成が落ちた場合も、同じ導線で作り直せる。 */
    @Test
    fun `要約の生成が落ちても再試行で作り直せる`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val ai = FakeAiClient { throw AiTimeoutException("タイムアウト") }
        val controller = controller(state, ai)

        controller.request(B, "B", "Bの本文")
        advanceUntilIdle()
        assertEquals(SectionChatProblem.GenerationFailed("タイムアウト"), summaryOf(state, B)?.summaryProblem)

        ai.onGenerate = { "Bの要約" }
        controller.retry(B)
        advanceUntilIdle()

        val summary = requireNotNull(summaryOf(state, B))
        assertNull(summary.summaryProblem)
        assertEquals("Bの要約", summary.summary)
    }

    /** 再試行も新しい生成なので、走っている別の節の生成を取り消す（一度に1本）。 */
    @Test
    fun `再試行は走っている別の節の生成を取り消す`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val ai = FakeAiClient { throw AiTimeoutException("タイムアウト") }
        val controller = controller(state, ai)
        controller.request(B, "B", "Bの本文")
        advanceUntilIdle()

        ai.suspendGenerations()
        controller.request(C, "C", "Cの本文")
        runCurrent()
        controller.retry(B)
        runCurrent()

        assertNull(summaryOf(state, C))
        assertTrue(summaryOf(state, B)?.isSummaryLoading == true)

        ai.completeAll("Bの要約")
        advanceUntilIdle()
        assertEquals("Bの要約", summaryOf(state, B)?.summary)
        assertNull("取り消したCの結果は書かれない", summaryOf(state, C))
    }

    /**
     * **部分要約は `Download` の導線を作らない。** ここではモデルDLを始めないので、
     * 押す操作の無い「開始してください」を出さない（→ features/section_ai_chat.md 判断4）。
     */
    @Test
    fun `未取得の案内は開始を求めず、DLも始めない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val ai = FakeAiClient { "要約" }.apply { availability = AiAvailability.NeedsDownload }
        controller(state, ai).request(B, "B", "Bの本文")
        advanceUntilIdle()

        val notice = (requireNotNull(summaryOf(state, B)).summaryProblem as SectionChatProblem.AiStatus).notice
        assertNotEquals("ここから開始できないので Download を運ばない", AiNoticeAction.Download, notice.action)
        assertFalse("存在しない操作を求めない: ${notice.message}", notice.message.contains("開始してください"))
        assertEquals("面の操作でDLを始めない", 0, ai.downloadCalls)
        assertTrue("あとで使えるようになるので入口は閉じない", notice.canTryAgainLater)
    }

    /**
     * **面を出さずに頼んだ要求だけが、理由を見せる印を持つ**（→ features/margin_pane.md §5.4）。印は要約の状態にあり、
     * 画面を作り直しても残る。面から頼んだ要求と、面の再試行は持たない — 理由はその面に出る。
     */
    @Test
    fun `面を出さずに頼んだ要求だけが、理由を見せる印を持つ`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val ai = FakeAiClient { "要約" }.apply { availability = AiAvailability.Unsupported }
        val controller = controller(state, ai)

        controller.requestAll(sources(A), source(A), quietly = true)
        advanceUntilIdle()
        controller.request(B, "B", "Bの本文")
        advanceUntilIdle()

        assertTrue(summaryOf(state, A)?.noticePending == true)
        assertTrue(summaryOf(state, A)?.summaryProblem is SectionChatProblem.AiStatus)
        assertFalse(summaryOf(state, B)?.noticePending == true)
    }

    /** 見せたら番号で下ろす。**下ろした後に同じ節を頼み直していれば、新しい要求の印は残す。** */
    @Test
    fun `見せた理由の印は要求の番号で下ろし、頼み直した要求の印は残す`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val ai = FakeAiClient { "要約" }.apply { availability = AiAvailability.NeedsDownload }
        val controller = controller(state, ai)

        controller.requestAll(sources(A), source(A), quietly = true)
        advanceUntilIdle()
        val first = requireNotNull(summaryOf(state, A))

        // 準備待ちの説明は頼み直すと確かめ直す（新しい要求になる）。
        controller.requestAll(sources(A), source(A), quietly = true)
        advanceUntilIdle()
        val second = requireNotNull(summaryOf(state, A))
        assertNotEquals(first.requestId, second.requestId)

        controller.acknowledgeNotice(first.requestId)
        assertTrue("古い要求の番号で新しい印を下ろした", summaryOf(state, A)?.noticePending == true)

        controller.acknowledgeNotice(second.requestId)
        assertFalse(summaryOf(state, A)?.noticePending == true)
    }

    /** 面の再試行は面から押すので、印を持たない。 */
    @Test
    fun `面の再試行は理由を見せる印を持たない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val (ai, response) = pendingAi()
        ai.availability = AiAvailability.NeedsDownload
        val controller = controller(state, ai)

        controller.requestAll(sources(A), source(A), quietly = true)
        advanceUntilIdle()
        ai.availability = AiAvailability.Ready
        controller.retry(A)
        runCurrent()

        assertFalse(summaryOf(state, A)?.noticePending == true)
        response.complete("要約")
        advanceUntilIdle()
    }

    // ── 全節の要約 ────────────────────────────────────────────────────

    /** 今の節から順に作り、作った要約は押さずに全部残る。走行の印も残らない。 */
    @Test
    fun `全節を頼むと順に作り、全部の要約を持って走行の印を下ろす`() = runTest {
        val ai = FakeAiClient { "要約" }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, ai)

        controller.requestAll(sources(B, C, A), source(B), quietly = true)
        assertTrue("頼んだ時点で走行の印が立たない", state.value.sectionChat.isBatchRunning)
        advanceUntilIdle()

        assertEquals(listOf("Bの本文", "Cの本文", "Aの本文"), ai.bodies())
        listOf(A, B, C).forEach { assertEquals("要約", summaryOf(state, it)?.summary) }
        assertFalse("走行の印が残った", state.value.sectionChat.isBatchRunning)
        assertEquals(listOf(B, C, A), state.value.sectionChat.batch?.targets)
    }

    /** **上限を超えるノートは止まり、押し直すと持っていない節から作る。** */
    @Test
    fun `上限で止まり、押し直すと続きから作る`() = runTest {
        val many = (1..SECTION_BATCH_LIMIT + 3).map { SectionRef("節$it") }
        val ai = FakeAiClient { "要約" }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, ai)

        controller.requestAll(sources(*many.toTypedArray()), source(many.first()), quietly = false)
        advanceUntilIdle()
        assertEquals(SECTION_BATCH_LIMIT, ai.generateCalls)
        assertFalse(state.value.sectionChat.isBatchRunning)
        assertNull(summaryOf(state, many.last()))

        controller.requestAll(sources(*many.toTypedArray()), source(many.first()), quietly = false)
        advanceUntilIdle()
        assertEquals(many.size, ai.generateCalls)
        assertEquals("要約", summaryOf(state, many.last())?.summary)
    }

    /** **作れる節が無ければ全節を立てない。** 立てると分母0で「全部できた」の ✓ が出る。 */
    @Test
    fun `作れる節が無ければ全節を立てない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())

        controller(state, FakeAiClient { "要約" }).requestAll(emptyList(), null, quietly = false)
        advanceUntilIdle()

        assertEquals(SectionChatState(), state.value.sectionChat)
    }

    /** **頼まれた対象外の節は上限に数えない。** 数えると、面で1節頼むたびに全節の作る数が減る。 */
    @Test
    fun `全節の間に頼んだ対象外の節は上限に数えない`() = runTest {
        val many = (1..SECTION_BATCH_LIMIT + 3).map { SectionRef("節$it") }
        val ai = FakeAiClient().suspendGenerations()
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, ai)

        controller.requestAll(sources(*many.toTypedArray()), source(many.first()), quietly = false)
        runCurrent()
        controller.request(D, "D", "Dの本文")
        finishGenerations(ai, "要約")

        assertEquals("頼んだ節の分だけ全節の作る数が減った", SECTION_BATCH_LIMIT + 1, ai.generateCalls)
        assertEquals("要約", summaryOf(state, D)?.summary)
    }

    /** 全部持っていれば押しても生成しない。走行の印も立ったまま残らない。 */
    @Test
    fun `全部の要約を持っていれば、全節を頼んでも生成しない`() = runTest {
        val ai = FakeAiClient { "要約" }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, ai)
        controller.requestAll(sources(A, B), source(A), quietly = false)
        advanceUntilIdle()

        controller.requestAll(sources(A, B), source(A), quietly = false)
        advanceUntilIdle()

        assertEquals(2, ai.generateCalls)
        assertFalse(state.value.sectionChat.isBatchRunning)
    }

    @Test
    fun `全節の生成中にもう一度頼んでも列を作り直さない`() = runTest {
        val ai = FakeAiClient().suspendGenerations()
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, ai)

        controller.requestAll(sources(A, B), source(A), quietly = false)
        runCurrent()
        controller.requestAll(sources(A, B), source(A), quietly = false)
        runCurrent()
        assertEquals(1, ai.generateCalls)

        finishGenerations(ai, "要約")
        assertEquals(2, ai.generateCalls)
        assertEquals("要約", summaryOf(state, B)?.summary)
    }

    /** **両方向の1: 全節の生成中に1節を頼む。** 取り消さず次に作り、できたら列の残りへ戻る。 */
    @Test
    fun `全節の生成中に1節を頼むと、生成を取り消さず次に作り、列の残りへ戻る`() = runTest {
        val ai = FakeAiClient().suspendGenerations()
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, ai)

        controller.requestAll(sources(A, B, C, D), source(A), quietly = false)
        runCurrent()
        controller.request(D, "D", "Dの本文")
        runCurrent()

        assertTrue("生成中のAを取り消した", summaryOf(state, A)?.isSummaryLoading == true)
        assertEquals(1, ai.generateCalls)
        assertTrue("頼んだ節の番を待っていない", state.value.sectionChat.isQueued(D))

        finishGenerations(ai, "要約")

        assertEquals(listOf("Aの本文", "Dの本文", "Bの本文", "Cの本文"), ai.bodies())
        listOf(A, B, C, D).forEach { assertEquals("要約", summaryOf(state, it)?.summary) }
        assertFalse(state.value.sectionChat.isBatchRunning)
    }

    /** **両方向の2: 1節の生成中に全節を頼む。** 走っている生成を取り消さず、その節を作り直さない。 */
    @Test
    fun `1節の生成中に全節を頼むと、その節を取り消さず残りを作る`() = runTest {
        val ai = FakeAiClient().suspendGenerations()
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, ai)

        controller.request(B, "B", "Bの本文")
        runCurrent()
        controller.requestAll(sources(A, B, C), source(A), quietly = false)
        runCurrent()

        assertTrue("生成中のBを取り消した", summaryOf(state, B)?.isSummaryLoading == true)
        assertEquals(1, ai.generateCalls)

        finishGenerations(ai, "要約")

        assertEquals(listOf("Bの本文", "Aの本文", "Cの本文"), ai.bodies())
        assertFalse(state.value.sectionChat.isBatchRunning)
    }

    /** 読む位置へ合わせる。**生成中の節は取り消さない。** */
    @Test
    fun `全節の生成中に面の節が替わると、その節を次に作る`() = runTest {
        val ai = FakeAiClient().suspendGenerations()
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, ai)

        controller.requestAll(sources(A, B, C, D), source(A), quietly = false)
        runCurrent()
        controller.prioritize(D)
        runCurrent()
        assertTrue(summaryOf(state, A)?.isSummaryLoading == true)

        finishGenerations(ai, "要約")

        assertEquals(listOf("Aの本文", "Dの本文", "Bの本文", "Cの本文"), ai.bodies())
    }

    /** 上限の先へ読み進めたら、読む位置を先に作る。**作る数は上限のまま。** */
    @Test
    fun `上限の先の節へ読み進めると、その節を作って上限で止まる`() = runTest {
        val many = (1..SECTION_BATCH_LIMIT + 3).map { SectionRef("節$it") }
        val ai = FakeAiClient().suspendGenerations()
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, ai)

        controller.requestAll(sources(*many.toTypedArray()), source(many.first()), quietly = false)
        runCurrent()
        controller.prioritize(many.last())
        finishGenerations(ai, "要約")

        assertEquals(SECTION_BATCH_LIMIT, ai.generateCalls)
        assertEquals("要約", summaryOf(state, many.last())?.summary)
    }

    @Test
    fun `全節でないときの面の節の変化は何も起こさない`() = runTest {
        val ai = FakeAiClient { "要約" }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, ai)

        controller.prioritize(A)
        controller.prioritize(null)
        advanceUntilIdle()

        assertEquals(0, ai.generateCalls)
        assertEquals(SectionChatState(), state.value.sectionChat)
    }

    /** 1節の失敗はその節を飛ばすだけ。理由はその節に残り、記号が ! になる。 */
    @Test
    fun `1節が失敗しても飛ばして残りを作る`() = runTest {
        val ai = FakeAiClient { prompt ->
            if (prompt.contains("Bの本文")) throw AiTimeoutException("タイムアウト") else "要約"
        }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, ai)

        controller.requestAll(sources(A, B, C), source(A), quietly = false)
        advanceUntilIdle()

        assertEquals("要約", summaryOf(state, A)?.summary)
        assertEquals(SectionChatProblem.GenerationFailed("タイムアウト"), summaryOf(state, B)?.summaryProblem)
        assertEquals("要約", summaryOf(state, C)?.summary)
        assertFalse(state.value.sectionChat.isBatchRunning)
    }

    /** **節によらない失敗を節の数だけ繰り返さない。** 間に成功を挟めば数え直す。 */
    @Test
    fun `失敗が3節続くと全節を止め、成功を挟むと数え直す`() = runTest {
        val always = FakeAiClient { throw AiTimeoutException("タイムアウト") }
        val allFailed = NoteUiStateStore(NoteUiState())
        controller(allFailed, always).requestAll(sources(A, B, C, D), source(A), quietly = false)
        advanceUntilIdle()
        assertEquals(SECTION_BATCH_FAILURE_LIMIT, always.generateCalls)
        assertFalse(allFailed.value.sectionChat.isBatchRunning)

        val mixed = FakeAiClient { prompt ->
            if (prompt.contains("Cの本文")) "要約" else throw AiTimeoutException("タイムアウト")
        }
        val interleaved = NoteUiStateStore(NoteUiState())
        controller(interleaved, mixed).requestAll(sources(A, B, C, D, E), source(A), quietly = false)
        advanceUntilIdle()
        assertEquals("成功を挟んだのに数え直していない", 5, mixed.generateCalls)
    }

    /** 失敗した節は、押し直しで作り直す。 */
    @Test
    fun `押し直すと出せなかった節を作り直す`() = runTest {
        val ai = FakeAiClient { prompt ->
            if (prompt.contains("Bの本文")) throw AiTimeoutException("タイムアウト") else "要約"
        }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, ai)
        controller.requestAll(sources(A, B), source(A), quietly = false)
        advanceUntilIdle()

        ai.onGenerate = { "作り直した要約" }
        controller.requestAll(sources(A, B), source(A), quietly = false)
        advanceUntilIdle()

        assertEquals("要約", summaryOf(state, A)?.summary)
        assertEquals("作り直した要約", summaryOf(state, B)?.summary)
    }

    /** **BUSY は節の失敗にせず、10秒待って同じ節をやり直す。** 待つ前にやり直さない。 */
    @Test
    fun `断られたら待って同じ節をやり直し、通れば続ける`() = runTest {
        var refusals = 1
        val ai = FakeAiClient {
            if (refusals-- > 0) throw GenAiException(RuntimeException(), GenAiException.ErrorCode.BUSY)
            "要約"
        }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, ai)

        controller.requestAll(sources(A, B), source(A), quietly = false)
        runCurrent()
        assertEquals(1, ai.generateCalls)
        assertTrue("待っている間も生成中のまま", summaryOf(state, A)?.isSummaryLoading == true)
        assertNull(summaryOf(state, A)?.summaryProblem)

        advanceTimeBy(SECTION_BATCH_REFUSAL_WAIT_MS - 1)
        runCurrent()
        assertEquals("待たずにやり直した", 1, ai.generateCalls)

        advanceUntilIdle()
        assertEquals(3, ai.generateCalls)
        assertEquals("要約", summaryOf(state, A)?.summary)
        assertEquals("要約", summaryOf(state, B)?.summary)
    }

    /** **断られ続けたら全節を止める。** 節を失敗にせず、押し直すとその節から作る。 */
    @Test
    fun `断られ続けると全節を止め、節を失敗にせず、押し直すと続きから作る`() = runTest {
        val ai = FakeAiClient { throw GenAiException(RuntimeException(), GenAiException.ErrorCode.BUSY) }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, ai)

        controller.requestAll(sources(A, B, C), source(A), quietly = false)
        advanceUntilIdle()

        assertEquals("同じ節を3回より多く試した", SECTION_BATCH_REFUSAL_LIMIT, ai.generateCalls)
        assertTrue("節を失敗にした", state.value.sectionChat.summaries.isEmpty())
        val stopped = requireNotNull(state.value.sectionChat.batch)
        assertFalse(stopped.isRunning)
        assertTrue(stopped.stoppedBusy)

        ai.onGenerate = { "要約" }
        controller.requestAll(sources(A, B, C), source(A), quietly = false)
        advanceUntilIdle()

        assertEquals("要約", summaryOf(state, A)?.summary)
        assertEquals("要約", summaryOf(state, C)?.summary)
        assertFalse("押し直した後も混み合いの印が残った", state.value.sectionChat.batch!!.stoppedBusy)
    }

    /** 1節だけの要求の BUSY は今までどおり失敗として見せ、再試行に任せる。 */
    @Test
    fun `全節の外の1節は断られても待たず失敗として見せる`() = runTest {
        val ai = FakeAiClient { throw GenAiException(RuntimeException(), GenAiException.ErrorCode.BUSY) }
        val state = NoteUiStateStore(NoteUiState())

        controller(state, ai).request(B, "B", "Bの本文")
        advanceUntilIdle()

        assertEquals(1, ai.generateCalls)
        assertTrue(summaryOf(state, B)?.summaryProblem is SectionChatProblem.GenerationFailed)
    }

    /** **背面の間は次の節を始めない。** 前面へ戻ると続ける。走り始めた生成は止めない。 */
    @Test
    fun `背面の間は次の節へ進まず、前面へ戻ると続ける`() = runTest {
        val ai = FakeAiClient().suspendGenerations()
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, ai)

        controller.requestAll(sources(A, B), source(A), quietly = false)
        runCurrent()
        controller.pause(ReadingPauseReason.AppBackground)
        ai.completeAll("Aの要約")
        advanceUntilIdle()

        assertEquals("Aの要約", summaryOf(state, A)?.summary)
        assertEquals("背面で次の節を始めた", 1, ai.generateCalls)
        assertTrue(state.value.sectionChat.isBatchRunning)

        controller.resume(ReadingPauseReason.AppBackground)
        runCurrent()
        assertEquals(2, ai.generateCalls)
        ai.completeAll("Bの要約")
        advanceUntilIdle()
        assertEquals("Bの要約", summaryOf(state, B)?.summary)
        assertFalse(state.value.sectionChat.isBatchRunning)
    }

    /** **生成中に背面へ回って断られても、節を失敗にしない。** 実機ではこの拒否が35msで返った。 */
    @Test
    fun `背面の拒否が返っても節を失敗にせず、前面へ戻ってから作り直す`() = runTest {
        var refusals = 1
        val ai = FakeAiClient {
            if (refusals-- > 0) throw GenAiException(RuntimeException(), GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED)
            "要約"
        }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, ai)

        controller.pause(ReadingPauseReason.AppBackground)
        controller.resume(ReadingPauseReason.AppBackground)
        controller.requestAll(sources(A), source(A), quietly = false)
        runCurrent()
        controller.pause(ReadingPauseReason.AppBackground)
        advanceUntilIdle()

        assertEquals(1, ai.generateCalls)
        assertNull(summaryOf(state, A)?.summaryProblem)

        controller.resume(ReadingPauseReason.AppBackground)
        advanceUntilIdle()
        assertEquals("要約", summaryOf(state, A)?.summary)
    }

    /** 冊子は前面なので AICore は断らない。全節を止めない。 */
    @Test
    fun `冊子が前面でも全節は進む`() = runTest {
        val ai = FakeAiClient { "要約" }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, ai)

        controller.pause(ReadingPauseReason.Booklet)
        controller.requestAll(sources(A, B), source(A), quietly = false)
        advanceUntilIdle()

        assertEquals("要約", summaryOf(state, B)?.summary)
    }

    /** **全節を止めると生成中の節はボタンへ戻り、できた要約は残る。** 押し直すと続きから作る。 */
    @Test
    fun `全節を止めると列を捨て、生成中の節はボタンへ戻り、後着も書かない`() = runTest {
        val ai = FakeAiClient().suspendGenerations()
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, ai)

        controller.requestAll(sources(A, B, C), source(A), quietly = false)
        runCurrent()
        ai.completeAll("Aの要約")
        runCurrent()
        assertTrue(summaryOf(state, B)?.isSummaryLoading == true)

        controller.stopAll()

        assertFalse(state.value.sectionChat.isBatchRunning)
        assertEquals("Aの要約", summaryOf(state, A)?.summary)
        assertNull("生成中の節がボタンへ戻っていない", summaryOf(state, B))
        ai.completeAll("後着")
        advanceUntilIdle()
        assertNull(summaryOf(state, B))
        assertEquals("止めた後に列を進めた", 2, ai.generateCalls)

        ai.onGenerate = { "要約" }
        controller.requestAll(sources(A, B, C), source(A), quietly = false)
        advanceUntilIdle()
        assertEquals("要約", summaryOf(state, B)?.summary)
        assertEquals("要約", summaryOf(state, C)?.summary)
        assertEquals("Aの要約", summaryOf(state, A)?.summary)
    }

    /** 全節の間の「生成を中止」は行に出さないが、遅れて届いた押下で全節を止めない。 */
    @Test
    fun `全節の生成中は1節の中止が効かない`() = runTest {
        val ai = FakeAiClient().suspendGenerations()
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, ai)

        controller.requestAll(sources(A, B), source(A), quietly = false)
        runCurrent()
        controller.cancel(A)
        runCurrent()

        assertTrue(summaryOf(state, A)?.isSummaryLoading == true)
        finishGenerations(ai, "要約")
        assertEquals("要約", summaryOf(state, B)?.summary)
    }

    /** 全節の間の再試行も取り消さず次に作る。**止まった全節の後の1節は今までどおり。** */
    @Test
    fun `全節の生成中の再試行は取り消さず次に作る`() = runTest {
        val ai = FakeAiClient { throw AiTimeoutException("タイムアウト") }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, ai)
        controller.request(D, "D", "Dの本文")
        advanceUntilIdle()

        ai.suspendGenerations()
        controller.requestAll(sources(A, B), source(A), quietly = false)
        runCurrent()
        controller.retry(D)
        runCurrent()
        assertTrue(summaryOf(state, A)?.isSummaryLoading == true)
        assertTrue("再試行の番を待っていない", state.value.sectionChat.isQueued(D))

        finishGenerations(ai, "要約")
        assertEquals(listOf("Dの本文", "Aの本文", "Dの本文", "Bの本文"), ai.bodies())
        assertEquals("要約", summaryOf(state, D)?.summary)
    }

    /**
     * **全節へ足した節は、失敗したら押し直しで作り直す。** 解析の対象で置き換えると、失敗が残ったまま ✓ になる。
     * 成功した節は作り直さない。
     */
    @Test
    fun `全節へ足した節が失敗したら、押し直すと作り直し、成功するまで全部できたとしない`() = runTest {
        val ai = FakeAiClient().suspendGenerations()
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, ai)
        controller.requestAll(sources(A, B), source(A), quietly = false)
        runCurrent()
        controller.request(X, "X", "Xの本文")
        ai.onGenerate = { prompt ->
            if (prompt.contains("Xの本文")) throw AiTimeoutException("タイムアウト") else "要約"
        }
        ai.completeAll("要約")
        advanceUntilIdle()
        assertEquals(SectionBatchStatus.Error, sectionBatchStatus(state.value.sectionChat))
        val calls = ai.generateCalls

        ai.onGenerate = { "Xの要約" }
        controller.requestAll(sources(A, B), source(A), quietly = false)
        advanceUntilIdle()

        assertEquals("足した節を作り直していない", "Xの要約", summaryOf(state, X)?.summary)
        assertEquals("成功した節を作り直した", calls + 1, ai.generateCalls)
        assertEquals(SectionBatchStatus.Ready, sectionBatchStatus(state.value.sectionChat))
    }

    /** **足した節は止めて押し直しても残る。** 落とすと、分母が減って頼んだ節が作られないまま ✓ になる。 */
    @Test
    fun `全節へ足した節は、止めて押し直しても対象に残って作られる`() = runTest {
        val ai = FakeAiClient().suspendGenerations()
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, ai)
        controller.requestAll(sources(A, B), source(A), quietly = false)
        runCurrent()
        controller.request(X, "X", "Xの本文")
        controller.stopAll()
        assertEquals(SectionBatchStatus.Partial(0, 3), sectionBatchStatus(state.value.sectionChat))

        ai.onGenerate = { "要約" }
        controller.requestAll(sources(A, B), source(A), quietly = false)
        advanceUntilIdle()

        assertEquals(setOf(A, B, X), state.value.sectionChat.batch?.targets?.toSet())
        assertEquals("要約", summaryOf(state, X)?.summary)
        assertEquals(SectionBatchStatus.Ready, sectionBatchStatus(state.value.sectionChat))
    }

    /** 足した節も押し直しでは上限に数える。**足した節が上限を広げない。** */
    @Test
    fun `足した節を引き継いでも、押し直しの上限は変わらない`() = runTest {
        val many = (1..SECTION_BATCH_LIMIT + 3).map { SectionRef("節$it") }
        val ai = FakeAiClient().suspendGenerations()
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, ai)
        controller.requestAll(sources(*many.toTypedArray()), source(many.first()), quietly = false)
        runCurrent()
        controller.request(X, "X", "Xの本文")
        controller.stopAll()
        val calls = ai.generateCalls

        ai.onGenerate = { "要約" }
        controller.requestAll(sources(*many.toTypedArray()), source(many.first()), quietly = false)
        advanceUntilIdle()

        assertEquals(calls + SECTION_BATCH_LIMIT, ai.generateCalls)
    }

    /** **足した節は本文に結びつく。** ノートを替えるか解析し直したら持ち越さない。 */
    @Test
    fun `足した節はノートを替えると持ち越さない`() = runTest {
        val ai = FakeAiClient().suspendGenerations()
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, ai)
        controller.requestAll(sources(A, B), source(A), quietly = false)
        runCurrent()
        controller.request(X, "X", "Xの本文")
        controller.cancelAndClear()

        ai.onGenerate = { "要約" }
        controller.requestAll(sources(C), source(C), quietly = false)
        advanceUntilIdle()

        assertEquals(listOf(C), state.value.sectionChat.batch?.targets)
        assertNull(summaryOf(state, X))
    }

    /** **切替の後に旧ノートの列が残らない。** 走行の印も残らず、次のノートは最初から作る。 */
    @Test
    fun `ノートを替えると全節を止めて全部捨て、後着も書かず、次のノートは最初から作る`() = runTest {
        val ai = FakeAiClient().suspendGenerations()
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, ai)

        controller.requestAll(sources(A, B, C), source(A), quietly = false)
        runCurrent()
        controller.cancelAndClear()
        assertEquals(SectionChatState(), state.value.sectionChat)

        ai.completeAll("切替後の結果")
        advanceUntilIdle()
        assertEquals(SectionChatState(), state.value.sectionChat)
        assertEquals("切替後に列を進めた", 1, ai.generateCalls)

        ai.onGenerate = { "新しいノートの要約" }
        controller.requestAll(sources(D), source(D), quietly = false)
        advanceUntilIdle()
        assertEquals("新しいノートの要約", summaryOf(state, D)?.summary)
        assertNull(summaryOf(state, B))
    }

    /**
     * **取り消しに従わない生成の後着が、新しい列を進めない。** 古い生成係が列から取り出すと同じ節を2本走らせる。
     * **判定は全部の生成を再開してから行う。** 途中で落ちると、取り消しに従わない生成が残ってテストが終われない。
     */
    @Test
    fun `取り消しに従わない生成の後着は、新しい全節の列を進めない`() = runTest {
        val waiting = mutableListOf<Continuation<String>>()
        val ai = FakeAiClient { suspendCoroutine { waiting += it } }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, ai)

        controller.requestAll(sources(A, B, C), source(A), quietly = false)
        runCurrent()
        controller.stopAll()
        controller.requestAll(sources(A, B, C), source(A), quietly = false)
        runCurrent()
        val startedBeforeLate = waiting.size

        waiting[0].resume("古いAの要約")
        advanceUntilIdle()
        val startedAfterLate = waiting.size
        val aAfterLate = summaryOf(state, A)?.summary

        waiting[1].resume("今のAの要約")
        runCurrent()
        val aAfterCurrent = summaryOf(state, A)?.summary
        val startedAfterCurrent = waiting.size
        controller.stopAll()
        resumeAll(waiting, from = 2)

        assertEquals(2, startedBeforeLate)
        assertEquals("古い生成係が列を進めた", 2, startedAfterLate)
        assertNull("古いAの結果を書いた", aAfterLate)
        assertEquals("今のAの要約", aAfterCurrent)
        assertEquals(3, startedAfterCurrent)
    }

    /**
     * **取り消しに従わない生成が遅れて断られても、新しい全節の状態に触らない。** 3回目の拒否が古い生成係へ届く順序を作る。
     * 届くと、走っている新しい全節に「混み合いで止まった」が立つ。判定は全部の生成を再開してから行う。
     */
    @Test
    fun `取り消しに従わない生成が遅れて断られても、新しい全節に混み合いを立てない`() = runTest {
        val waiting = mutableListOf<Continuation<String>>()
        var refusals = SECTION_BATCH_REFUSAL_LIMIT - 1
        val ai = FakeAiClient {
            if (refusals-- > 0) throw GenAiException(RuntimeException(), GenAiException.ErrorCode.BUSY)
            suspendCoroutine { waiting += it }
        }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, ai)
        controller.requestAll(sources(A, B), source(A), quietly = false)
        advanceUntilIdle()
        val startedFirst = waiting.size

        controller.stopAll()
        controller.requestAll(sources(A, B), source(A), quietly = false)
        runCurrent()
        val startedSecond = waiting.size

        waiting.getOrNull(0)?.resumeWithException(GenAiException(RuntimeException(), GenAiException.ErrorCode.BUSY))
        advanceUntilIdle()
        val stoppedBusy = state.value.sectionChat.batch?.stoppedBusy
        val aGenerating = summaryOf(state, A)?.isSummaryLoading

        controller.stopAll()
        resumeAll(waiting, from = 1)

        assertEquals(1, startedFirst)
        assertEquals(2, startedSecond)
        assertEquals("古い拒否で新しい全節に混み合いを立てた", false, stoppedBusy)
        assertEquals(true, aGenerating)
    }

    /**
     * **止めた後に古い状態確認が「使えない」を返しても、新しい全節を消さない。** 止め方は2通りある。
     * 判定は全部の生成を再開してから行う（→ 上のテスト）。
     */
    @Test
    fun `取り消しに従わない状態確認の後着は、新しい全節と列を変えない`() = runTest {
        val stops: List<Pair<String, (SectionChatController) -> Unit>> = listOf(
            "全節の停止" to { it.stopAll() },
            "ノート切替" to { it.cancelAndClear() }
        )
        stops.forEach { (name, stop) ->
            val held = mutableListOf<Continuation<Unit>>()
            val ai = FakeAiClient().suspendGenerations()
            ai.availabilityHold = { suspendCoroutine { held += it } }
            val state = NoteUiStateStore(NoteUiState())
            val controller = controller(state, ai)
            controller.requestAll(sources(A, B), source(A), quietly = false)
            runCurrent()

            stop(controller)
            ai.availabilityHold = null
            controller.requestAll(sources(A, B), source(A), quietly = false)
            runCurrent()

            ai.availability = AiAvailability.NeedsDownload
            held.single().resume(Unit)
            advanceUntilIdle()
            val runningAfterLate = state.value.sectionChat.isBatchRunning
            val aGeneratingAfterLate = summaryOf(state, A)?.isSummaryLoading
            ai.availability = AiAvailability.Ready

            finishGenerations(ai, "要約")

            assertTrue("$name: 古い確認で全節を消した", runningAfterLate)
            assertEquals("$name: 古い確認でAを書き換えた", true, aGeneratingAfterLate)
            assertEquals("$name: Aができていない", "要約", summaryOf(state, A)?.summary)
            assertEquals("$name: 列を消した", "要約", summaryOf(state, B)?.summary)
        }
    }

    /** **古い生成係が3節目の失敗で列を消さない。** 2節続けて失敗した係の状態確認が、止めた後に投げる順序。 */
    @Test
    fun `取り消しに従わない状態確認が止めた後に投げても、新しい列を消さない`() = runTest {
        val held = mutableListOf<Continuation<Unit>>()
        var checks = 0
        val ai = FakeAiClient { throw AiTimeoutException("タイムアウト") }
        ai.availabilityHold = { if (++checks == SECTION_BATCH_FAILURE_LIMIT) suspendCoroutine<Unit> { held += it } }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, ai)
        controller.requestAll(sources(A, B, C), source(A), quietly = false)
        advanceUntilIdle()

        controller.stopAll()
        ai.availabilityHold = null
        ai.suspendGenerations()
        controller.requestAll(sources(A, B, C), source(A), quietly = false)
        runCurrent()

        ai.availabilityFailure = { IllegalStateException("状態を取れない") }
        held.single().resume(Unit)
        advanceUntilIdle()
        ai.availabilityFailure = null

        finishGenerations(ai, "要約")

        assertEquals("古い係の失敗で列を消した", "要約", summaryOf(state, C)?.summary)
    }

    /** **全節の外の1節は今までどおり。** 背面でも待たず、断られたら失敗として見せる。 */
    @Test
    fun `全節の外の1節は背面でも待たない`() = runTest {
        val ai = FakeAiClient { "要約" }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, ai)

        controller.pause(ReadingPauseReason.AppBackground)
        controller.request(A, "A", "Aの本文")
        advanceUntilIdle()

        assertEquals("要約", summaryOf(state, A)?.summary)
    }

    /** **端末AIが使えなければ全節を始めず、押した時点の今の節に理由を出す。** 節の数だけ理由を並べない。 */
    @Test
    fun `端末AIが使えないと全節を終え、今の節にだけ理由を出す`() = runTest {
        val ai = FakeAiClient { "要約" }.apply { availability = AiAvailability.Unsupported }
        val state = NoteUiStateStore(NoteUiState())

        controller(state, ai).requestAll(sources(B, C), source(A), quietly = true)
        advanceUntilIdle()

        assertEquals(0, ai.generateCalls)
        assertNull("記号は 💬 のまま", state.value.sectionChat.batch)
        assertEquals(listOf(A), state.value.sectionChat.summaries.map { it.section })
        assertTrue(summaryOf(state, A)?.summaryProblem is SectionChatProblem.AiStatus)
        assertTrue("面を出さずに頼んだ印が無い", summaryOf(state, A)?.noticePending == true)
    }

    @Test
    fun `面を出して全節を頼んだ理由は印を持たない`() = runTest {
        val ai = FakeAiClient { "要約" }.apply { availability = AiAvailability.NeedsDownload }
        val state = NoteUiStateStore(NoteUiState())

        controller(state, ai).requestAll(sources(A), source(A), quietly = false)
        advanceUntilIdle()

        assertFalse(summaryOf(state, A)?.noticePending == true)
        assertEquals("面の操作でDLを始めない", 0, ai.downloadCalls)
    }

    /** **持っている要約を理由で上書きしない。** 途中で使えなくなったら全節を終え、作っていた節に出す。 */
    @Test
    fun `途中で使えなくなると全節を終え、持っている要約は上書きしない`() = runTest {
        val ai = FakeAiClient { "要約" }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, ai)
        controller.request(A, "A", "Aの本文")
        advanceUntilIdle()

        ai.availability = AiAvailability.TemporarilyUnavailable(IllegalStateException("AICore not bound"))
        controller.requestAll(sources(A, B, C), source(A), quietly = false)
        advanceUntilIdle()

        assertEquals("要約", summaryOf(state, A)?.summary)
        assertTrue(summaryOf(state, B)?.summaryProblem is SectionChatProblem.AiStatus)
        assertNull(summaryOf(state, C))
        assertNull(state.value.sectionChat.batch)
    }

    /** 走行の印が残らない。状態確認の例外もその節の失敗で終わる。 */
    @Test
    fun `状態確認が投げても全節は節を飛ばして終わる`() = runTest {
        val ai = FakeAiClient { "要約" }.apply { availabilityFailure = { IllegalStateException("状態を取れない") } }
        val state = NoteUiStateStore(NoteUiState())

        controller(state, ai).requestAll(sources(A, B), source(A), quietly = false)
        advanceUntilIdle()

        assertTrue(summaryOf(state, A)?.summaryProblem is SectionChatProblem.GenerationFailed)
        assertFalse(state.value.sectionChat.isBatchRunning)
    }

    private fun source(section: SectionRef) =
        SectionSummarySource(section, section.title!!, "${section.title}の本文")

    private fun sources(vararg sections: SectionRef) = sections.map(::source)

    /** 生成に渡った本文を順に取り出す。**どの節をどの順で作ったか**を見る。 */
    private fun FakeAiClient.bodies(): List<String> =
        prompts.map { prompt -> BODY_IN_PROMPT.find(prompt)!!.value }

    /** 取り消しに従わない生成を残らず返す。これが残るとテストが終われない。 */
    private fun TestScope.resumeAll(waiting: List<Continuation<String>>, from: Int) {
        var next = from
        while (next < waiting.size) {
            waiting[next++].resume("後着")
            advanceUntilIdle()
        }
    }

    /** 保留中の生成を、列が尽きるまで1本ずつ返す。 */
    private fun TestScope.finishGenerations(ai: FakeAiClient, result: String) {
        runCurrent()
        repeat(MAX_FINISHED) {
            ai.completeAll(result)
            runCurrent()
        }
        advanceUntilIdle()
    }

    private fun TestScope.controller(state: NoteUiStateStore, ai: FakeAiClient) =
        SectionChatController(this, ai, state.sectionChatWriter, StandardTestDispatcher(testScheduler))

    private fun summaryOf(state: NoteUiStateStore, section: SectionRef): SectionSummary? =
        state.value.sectionChat.summaryOf(section)

    /** 要約の生成を保留するダブル。「要約待ちのあいだ何が起きるか」を作る。 */
    private fun pendingAi(): Pair<FakeAiClient, CompletableDeferred<String>> {
        val response = CompletableDeferred<String>()
        return FakeAiClient { response.await() } to response
    }

    private companion object {
        val A = SectionRef("A")
        val B = SectionRef("B")
        val C = SectionRef("C")
        val D = SectionRef("D")
        val E = SectionRef("E")
        val X = SectionRef("X")
        val BODY_IN_PROMPT = Regex("""[A-EX]の本文|節\\d+の本文""")
        const val MAX_FINISHED = 40
    }
}
