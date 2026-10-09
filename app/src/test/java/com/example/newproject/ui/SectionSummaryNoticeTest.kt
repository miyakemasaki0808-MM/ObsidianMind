package com.example.newproject.ui

import com.example.newproject.ai.AiAvailability
import com.example.newproject.controller.SectionChatController
import com.example.newproject.domain.summaryOf
import com.example.newproject.fakes.FakeAiClient
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.NoteUiStateStore
import com.example.newproject.model.SectionRef
import com.example.newproject.ui.screen.PendingNotice
import com.example.newproject.ui.screen.pendingNoticeFor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 面を出さずに頼んだ要約の、端末AIが使えない理由を**どの節で見せるか**を、本物の Controller の順序で確かめる
 * （→ features/margin_pane.md §5.4）。
 *
 * 画面は今の本文の節の要約を [pendingNoticeFor] に渡すので、ここでも「今の本文の節」を変えながら同じ判定を当てる。
 * 状態確認は保留できるダブルで止め、**頼んだ後・結果が届く前**に節を動かす。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SectionSummaryNoticeTest {

    /** 対照。頼んだ節に留まっていれば、届いた理由で面を開く。見せたら二度と開かない。 */
    @Test
    fun `頼んだ節に留まっていれば、届いた理由で一度だけ面を開く`() = runTest {
        val (state, controller, ai) = setUp()
        controller.request(A, "A", "Aの本文", quietly = true)
        runCurrent()
        assertEquals(PendingNotice.None, noticeAt(state, A))

        ai.arrive(AiAvailability.Unsupported)
        advanceUntilIdle()
        assertEquals(PendingNotice.Open, noticeAt(state, A))

        controller.acknowledgeNotice(requireNotNull(state.value.sectionChat.summaryOf(A)).requestId)
        assertEquals("見せた理由をもう一度開く（画面の作り直しでも同じ判定になる）", PendingNotice.None, noticeAt(state, A))
    }

    /**
     * **読み進めた先の節では、頼んだ節の理由で面を開かない。** 面は今の本文の節を映すので、開くと理由の無い面が出る。
     * 頼んだ節へ戻ったときに見せる。
     */
    @Test
    fun `読み進めた先のまだ頼んでいない節では開かず、頼んだ節へ戻ると開く`() = runTest {
        val (state, controller, ai) = setUp()
        controller.request(A, "A", "Aの本文", quietly = true)
        runCurrent()

        ai.arrive(AiAvailability.Unsupported)
        advanceUntilIdle()

        assertEquals(PendingNotice.None, noticeAt(state, B))
        assertEquals(PendingNotice.Open, noticeAt(state, A))
    }

    /** 読み進めた先の節が別の要約を持っていても、その要約で面を開かない。 */
    @Test
    fun `読み進めた先の節が要約を持っていても開かない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val ai = FakeAiClient { "Bの要約" }
        val controller = SectionChatController(this, ai, state.sectionChatWriter, StandardTestDispatcher(testScheduler))
        controller.request(B, "B", "Bの本文")
        advanceUntilIdle()

        ai.availability = AiAvailability.Unsupported
        controller.request(A, "A", "Aの本文", quietly = true)
        advanceUntilIdle()

        assertEquals("Bの要約", state.value.sectionChat.summaryOf(B)?.summary)
        assertEquals(PendingNotice.None, noticeAt(state, B))
        assertEquals(PendingNotice.Open, noticeAt(state, A))
    }

    /** ノート切替と本文の解析し直しでは、要約ごと捨てるので、古い要求から理由を見せない。 */
    @Test
    fun `ノート切替と本文の解析し直しの後は、古い要求の理由を見せない`() = runTest {
        val (state, controller, ai) = setUp()
        controller.request(A, "A", "Aの本文", quietly = true)
        runCurrent()

        controller.cancelAndClear()
        ai.arrive(AiAvailability.Unsupported)
        advanceUntilIdle()

        assertEquals(PendingNotice.None, noticeAt(state, A))
    }

    private fun noticeAt(state: NoteUiStateStore, bodySection: SectionRef): PendingNotice =
        pendingNoticeFor(state.value.sectionChat.summaryOf(bodySection), rowVisible = false, paneVisible = false)

    /** 状態確認を保留した偽物で組む。[arrive] で結果を届ける。 */
    private fun TestScope.setUp(): Triple<NoteUiStateStore, SectionChatController, FakeAiClient> {
        val ai = FakeAiClient().apply { availabilityGate = CompletableDeferred() }
        val state = NoteUiStateStore(NoteUiState())
        val controller = SectionChatController(this, ai, state.sectionChatWriter, StandardTestDispatcher(testScheduler))
        return Triple(state, controller, ai)
    }

    private fun FakeAiClient.arrive(result: AiAvailability) {
        availability = result
        requireNotNull(availabilityGate).complete(Unit)
    }

    private companion object {
        val A = SectionRef("A")
        val B = SectionRef("B")
    }
}
