package com.example.newproject

import com.example.newproject.controller.SectionChatController
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.state.AiNoticeAction
import com.example.newproject.model.state.SectionChatProblem
import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiTimeoutException
import com.example.newproject.domain.markdown.NoteSection
import com.example.newproject.domain.SectionSummaryStatus
import com.example.newproject.domain.sectionSummaryStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.example.newproject.model.NoteUiStateStore
import com.example.newproject.fakes.FakeAiClient
import kotlinx.coroutines.test.StandardTestDispatcher
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

@OptIn(ExperimentalCoroutinesApi::class)
class SectionChatControllerTest {

    @Test
    fun `シートを閉じても要約生成が継続して結果が保持される`() = runTest {
        val (aiClient, summaryResponse) = sectionChatAi()
        val state = NoteUiStateStore(NoteUiState())
        val controller = SectionChatController(
            this,
            aiClient,
            state.sectionChatWriter,
            StandardTestDispatcher(testScheduler)
        )

        controller.open(NoteSection("対象セクション", 2, "## 対象セクション\n本文"))
        runCurrent()

        assertTrue(state.value.isSectionChatSheetVisible)
        assertTrue(state.value.sectionChat?.isSummaryLoading == true)

        controller.dismissSheet()
        assertFalse(state.value.isSectionChatSheetVisible)
        assertNotNull(state.value.sectionChat)

        summaryResponse.complete("生成された要約")
        advanceUntilIdle()

        assertFalse(state.value.isSectionChatSheetVisible)
        assertEquals("生成された要約", state.value.sectionChat?.summary)
        assertFalse(state.value.sectionChat?.isSummaryLoading ?: true)
    }

    @Test
    fun `生成中に再度開いても二重生成せず元のセクションを再表示する`() = runTest {
        val (aiClient, summaryResponse) = sectionChatAi()
        val state = NoteUiStateStore(NoteUiState())
        val controller = SectionChatController(
            this,
            aiClient,
            state.sectionChatWriter,
            StandardTestDispatcher(testScheduler)
        )

        controller.open(NoteSection("最初のセクション", 2, "最初の本文"))
        runCurrent()
        controller.dismissSheet()

        controller.open(NoteSection("スクロール先", 2, "別の本文"))
        runCurrent()

        assertTrue(state.value.isSectionChatSheetVisible)
        assertEquals("最初のセクション", state.value.sectionChat?.sectionTitle)
        assertEquals(1, aiClient.generateCalls)

        controller.cancelAndClear()
    }

    @Test
    fun `完了後に吹き出しを開くと再生成せず既存結果を表示する`() = runTest {
        val (aiClient, summaryResponse) = sectionChatAi()
        val state = NoteUiStateStore(NoteUiState())
        val controller = SectionChatController(
            this,
            aiClient,
            state.sectionChatWriter,
            StandardTestDispatcher(testScheduler)
        )

        controller.open(NoteSection("対象", 2, "本文"))
        runCurrent()
        summaryResponse.complete("完成した要約")
        advanceUntilIdle()
        val callsAfterCompletion = aiClient.generateCalls

        controller.dismissSheet()
        controller.open(NoteSection("別の位置", 2, "別本文"))
        runCurrent()

        assertTrue(state.value.isSectionChatSheetVisible)
        assertEquals("完成した要約", state.value.sectionChat?.summary)
        assertEquals(callsAfterCompletion, aiClient.generateCalls)
    }

    @Test
    fun `明示終了すると生成をキャンセルしてセッションを破棄する`() = runTest {
        val (aiClient, summaryResponse) = sectionChatAi()
        val state = NoteUiStateStore(NoteUiState())
        val controller = SectionChatController(
            this,
            aiClient,
            state.sectionChatWriter,
            StandardTestDispatcher(testScheduler)
        )

        controller.open(NoteSection("対象", 2, "本文"))
        runCurrent()
        controller.cancelAndClear()

        assertNull(state.value.sectionChat)
        assertFalse(state.value.isSectionChatSheetVisible)

        summaryResponse.complete("キャンセル後の結果")
        advanceUntilIdle()
        assertNull(state.value.sectionChat)
    }

    /**
     * **状態の説明を `error` へ文字列で入れない。**
     *
     * 文字列だけにすると導線（[AiNoticeAction]）が消えて再試行できず、
     * さらにシートが赤いエラー表示で描いてしまう（状態の説明は失敗ではない）。
     */
    @Test
    fun `一時的に使えないときは再試行できる説明を持つ`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = SectionChatController(
            this,
            FakeAiClient(
                AiAvailability.TemporarilyUnavailable(IllegalStateException("AICore not bound"))
            ),
            state.sectionChatWriter,
            StandardTestDispatcher(testScheduler)
        )

        controller.open(NoteSection("対象セクション", 2, "## 対象セクション\n本文"))
        advanceUntilIdle()

        val chat = requireNotNull(state.value.sectionChat)
        val problem = requireNotNull(chat.summaryProblem)
        assertTrue("生成の失敗として扱わないこと", problem is SectionChatProblem.AiStatus)
        assertEquals(AiNoticeAction.Retry, (problem as SectionChatProblem.AiStatus).notice.action)
        assertFalse(chat.isSummaryLoading)
    }

    /** **非対応には再試行導線を出さない。** 何度押しても同じ答えが返る。 */
    @Test
    fun `非対応の説明は再試行導線を持たない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = SectionChatController(
            this,
            FakeAiClient(AiAvailability.Unsupported),
            state.sectionChatWriter,
            StandardTestDispatcher(testScheduler)
        )

        controller.open(NoteSection("対象セクション", 2, "## 対象セクション\n本文"))
        advanceUntilIdle()

        val problem = requireNotNull(state.value.sectionChat?.summaryProblem)
        assertEquals(
            AiNoticeAction.None,
            (problem as SectionChatProblem.AiStatus).notice.action
        )
    }

    /** 再試行は開いているセクションのまま試し直す（別のセクションへは移らない）。 */
    @Test
    fun `再試行すると同じセクションで生成し直す`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val ai = FakeAiClient(
            AiAvailability.TemporarilyUnavailable(IllegalStateException("boom"))
        ) { "生成された要約" }
        val controller = SectionChatController(
            this,
            ai,
            state.sectionChatWriter,
            StandardTestDispatcher(testScheduler)
        )

        controller.open(NoteSection("対象セクション", 2, "## 対象セクション\n本文"))
        advanceUntilIdle()
        assertNotNull(state.value.sectionChat?.summaryProblem)

        ai.availability = AiAvailability.Ready
        controller.retrySummary()
        advanceUntilIdle()

        val chat = requireNotNull(state.value.sectionChat)
        assertNull(chat.summaryProblem)
        assertEquals("生成された要約", chat.summary)
        assertEquals("対象セクション", chat.sectionTitle)
    }

    /** 要約の生成が落ちた場合も、同じ導線で作り直せる。 */
    @Test
    fun `要約の生成が落ちても再試行で作り直せる`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val ai = FakeAiClient { throw AiTimeoutException("タイムアウト") }
        val controller = SectionChatController(
            this,
            ai,
            state.sectionChatWriter,
            StandardTestDispatcher(testScheduler)
        )

        controller.open(NoteSection("対象セクション", 2, "## 対象セクション\n本文"))
        advanceUntilIdle()
        assertEquals(
            SectionChatProblem.GenerationFailed("タイムアウト"),
            state.value.sectionChat?.summaryProblem
        )

        ai.onGenerate = { "生成された要約" }
        controller.retrySummary()
        advanceUntilIdle()

        val chat = requireNotNull(state.value.sectionChat)
        assertNull(chat.summaryProblem)
        assertEquals("生成された要約", chat.summary)
    }

    /**
     * **端末AIが使えないだけなら、派生状態は生成中にならない。** 生成していないのに
     * 通常画面と全画面の表示が「AI生成中」を出し続けないこと。
     */
    @Test
    fun `端末AIが使えないだけなら派生状態はWorkingにならない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val ai = FakeAiClient { "要約" }.apply { availability = AiAvailability.Unsupported }
        SectionChatController(this, ai, state.sectionChatWriter, StandardTestDispatcher(testScheduler))
            .open(SECTION)
        advanceUntilIdle()

        assertEquals(SectionSummaryStatus.Idle, sectionSummaryStatus(state.value.sectionChat))
    }

    @Test
    fun `生成が落ちたときだけ派生状態はErrorになる`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val ai = FakeAiClient { throw AiTimeoutException("タイムアウト") }
        SectionChatController(this, ai, state.sectionChatWriter, StandardTestDispatcher(testScheduler))
            .open(SECTION)
        advanceUntilIdle()

        assertEquals(SectionSummaryStatus.Error, sectionSummaryStatus(state.value.sectionChat))
    }

    /**
     * **部分要約は `Download` の導線を作らない。** ここではモデルDLを始めないので、
     * 押す操作の無い「開始してください」を出さない（→ features/section_ai_chat.md 判断4）。
     */
    @Test
    fun `未取得の案内は開始を求めず、DLも始めない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val ai = FakeAiClient { "要約" }.apply { availability = AiAvailability.NeedsDownload }
        SectionChatController(this, ai, state.sectionChatWriter, StandardTestDispatcher(testScheduler))
            .open(SECTION)
        advanceUntilIdle()

        val notice =
            (requireNotNull(state.value.sectionChat).summaryProblem as SectionChatProblem.AiStatus).notice
        assertNotEquals("ここから開始できないので Download を運ばない", AiNoticeAction.Download, notice.action)
        assertFalse("存在しない操作を求めない: ${notice.message}", notice.message.contains("開始してください"))
        assertEquals("シート操作でDLを始めない", 0, ai.downloadCalls)
        assertTrue("あとで使えるようになるので入口は閉じない", notice.canTryAgainLater)
    }

    /** 要約の生成を保留するダブル。「要約待ちのあいだ何が起きるか」を作る。 */
    private fun sectionChatAi(): Pair<FakeAiClient, CompletableDeferred<String>> {
        val summaryResponse = CompletableDeferred<String>()
        val client = FakeAiClient { summaryResponse.await() }
        return client to summaryResponse
    }

    private companion object {
        val SECTION = NoteSection("対象セクション", 2, "## 対象セクション\n本文")
    }
}
