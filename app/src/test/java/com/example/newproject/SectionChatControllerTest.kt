package com.example.newproject

import com.example.newproject.controller.SectionChatController
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.state.AiNoticeAction
import com.example.newproject.ai.AiAvailability
import com.example.newproject.domain.markdown.NoteSection
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
        assertEquals(listOf("質問1", "質問2"), state.value.sectionChat?.suggestions)
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
        assertNull("エラー欄へ流し込まないこと", chat.error)
        assertEquals(AiNoticeAction.Retry, requireNotNull(chat.aiNotice).action)
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

        val notice = requireNotNull(state.value.sectionChat?.aiNotice)
        assertEquals(AiNoticeAction.None, notice.action)
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
        assertNotNull(state.value.sectionChat?.aiNotice)

        ai.availability = AiAvailability.Ready
        controller.retryAi()
        advanceUntilIdle()

        val chat = requireNotNull(state.value.sectionChat)
        assertNull(chat.aiNotice)
        assertEquals("生成された要約", chat.summary)
        assertEquals("対象セクション", chat.sectionTitle)
    }

    /**
     * 要約の生成だけを保留し、候補質問は即返すダブル。
     *
     * シートは「要約 → 候補質問」の順に2回生成するので、1回目だけ止めれば
     * 「要約待ちのあいだ何が起きるか」を作れる。
     */
    private fun sectionChatAi(): Pair<FakeAiClient, CompletableDeferred<String>> {
        val summaryResponse = CompletableDeferred<String>()
        val client = FakeAiClient {
            if (generateCalls == 1) summaryResponse.await() else "質問1\n質問2"
        }
        return client to summaryResponse
    }
}
