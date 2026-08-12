package com.example.newproject

import com.example.newproject.controller.SectionChatController
import com.example.newproject.model.NoteUiState
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
