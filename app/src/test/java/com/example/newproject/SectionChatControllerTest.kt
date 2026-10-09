package com.example.newproject

import com.example.newproject.controller.SectionChatController
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.SectionRef
import com.example.newproject.model.state.AiNoticeAction
import com.example.newproject.model.state.SectionChatProblem
import com.example.newproject.model.state.SectionSummary
import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiTimeoutException
import com.example.newproject.domain.SectionSummaryStatus
import com.example.newproject.domain.sectionSummaryStatus
import com.example.newproject.domain.summaryOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.example.newproject.model.NoteUiStateStore
import com.example.newproject.fakes.FakeAiClient
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
    fun `4節目を作ると、最も古い要約から消える`() = runTest {
        val ai = FakeAiClient { prompt -> "要約:" + prompt.length }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, ai)

        listOf(A, B, C, D).forEach { section ->
            controller.request(section, section.title!!, "${section.title}の本文")
            advanceUntilIdle()
        }

        assertEquals(listOf(D, C, B), state.value.sectionChat.summaries.map { it.section })
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
     * **端末AIが使えないだけなら、派生状態は生成中にならない。** 生成していないのに
     * 見出しの要約ボタンが「AI生成中」を出し続けないこと。
     */
    @Test
    fun `端末AIが使えないだけなら派生状態はWorkingにならない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val ai = FakeAiClient { "要約" }.apply { availability = AiAvailability.Unsupported }
        controller(state, ai).request(B, "B", "Bの本文")
        advanceUntilIdle()

        assertEquals(SectionSummaryStatus.Idle, sectionSummaryStatus(summaryOf(state, B)))
    }

    @Test
    fun `生成が落ちたときだけ派生状態はErrorになる`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        controller(state, FakeAiClient { throw AiTimeoutException("タイムアウト") }).request(B, "B", "Bの本文")
        advanceUntilIdle()

        assertEquals(SectionSummaryStatus.Error, sectionSummaryStatus(summaryOf(state, B)))
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

        controller.request(A, "A", "Aの本文", quietly = true)
        advanceUntilIdle()
        // 生成中の別の節は取り除かれるので、A の結果が出てから B を頼む。
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

        controller.request(A, "A", "Aの本文", quietly = true)
        advanceUntilIdle()
        val first = requireNotNull(summaryOf(state, A))

        // 準備待ちの説明は頼み直すと確かめ直す（新しい要求になる）。
        controller.request(A, "A", "Aの本文", quietly = true)
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

        controller.request(A, "A", "Aの本文", quietly = true)
        advanceUntilIdle()
        ai.availability = AiAvailability.Ready
        controller.retry(A)
        runCurrent()

        assertFalse(summaryOf(state, A)?.noticePending == true)
        response.complete("要約")
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
    }
}
