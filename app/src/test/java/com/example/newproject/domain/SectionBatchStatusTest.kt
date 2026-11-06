package com.example.newproject.domain

import com.example.newproject.model.SectionRef
import com.example.newproject.model.state.AiNoticeAction
import com.example.newproject.model.state.AiStatusNotice
import com.example.newproject.model.state.SectionBatch
import com.example.newproject.model.state.SectionChatProblem
import com.example.newproject.model.state.SectionChatState
import com.example.newproject.model.state.SectionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 全節の要約の状態の導出（[sectionBatchStatus]）を固定する。見出しの要約ボタンの記号がこの導出で決まる。 */
class SectionBatchStatusTest {

    private val a = SectionRef("A")
    private val b = SectionRef("B")
    private val c = SectionRef("C")

    private fun made(section: SectionRef) =
        SectionSummary(section, requestId = 1L, sectionTitle = "節", sectionContext = "本文", summary = "要約")

    private fun failed(section: SectionRef) = made(section).copy(
        summary = null,
        summaryProblem = SectionChatProblem.GenerationFailed("失敗")
    )

    private fun chat(vararg summaries: SectionSummary, running: Boolean = false, busy: Boolean = false) =
        SectionChatState(summaries.toList(), SectionBatch(listOf(a, b, c), isRunning = running, stoppedBusy = busy))

    @Test
    fun `全節を頼んでいなければ、1節の要約を持っていてもIdle`() {
        assertEquals(SectionBatchStatus.Idle, sectionBatchStatus(SectionChatState()))
        assertEquals(SectionBatchStatus.Idle, sectionBatchStatus(SectionChatState(listOf(made(a)))))
    }

    /** 走っている間は、出せなかった節があっても生成中と示す。 */
    @Test
    fun `走っている間は進み具合を示し、失敗があっても生成中のまま`() {
        assertEquals(SectionBatchStatus.Working(1, 3), sectionBatchStatus(chat(made(a), running = true)))
        assertEquals(SectionBatchStatus.Working(1, 3), sectionBatchStatus(chat(made(a), failed(b), running = true)))
    }

    /** **生成中の節はできた数に入れない。** */
    @Test
    fun `生成中の節はできた数に入らない`() {
        val generating = made(a).copy(summary = null, isSummaryLoading = true)
        assertEquals(SectionBatchStatus.Working(0, 3), sectionBatchStatus(chat(generating, running = true)))
    }

    @Test
    fun `止まって作れる節が残っていればPartial、全部できていればReady`() {
        assertEquals(SectionBatchStatus.Partial(2, 3), sectionBatchStatus(chat(made(a), made(b))))
        assertEquals(SectionBatchStatus.Ready, sectionBatchStatus(chat(made(a), made(b), made(c))))
    }

    @Test
    fun `出せなかった節があればError`() {
        assertEquals(SectionBatchStatus.Error, sectionBatchStatus(chat(made(a), failed(b), made(c))))
        assertEquals(SectionBatchStatus.Error, sectionBatchStatus(chat(failed(a))))
    }

    /** 対象でない節の要約は数えない。**1節の要求で作った短い節が分子へ混ざらない。** */
    @Test
    fun `対象でない節の要約は進み具合に入らない`() {
        assertEquals(SectionBatchStatus.Partial(1, 3), sectionBatchStatus(chat(made(a), made(SectionRef("外")))))
    }

    /** 端末AIの理由は失敗として数えない。 */
    @Test
    fun `端末AIの理由はErrorにしない`() {
        val notice = SectionChatProblem.AiStatus(AiStatusNotice("使えません", AiNoticeAction.None, canTryAgainLater = false))
        val noticed = made(a).copy(summary = null, summaryProblem = notice)
        assertEquals(SectionBatchStatus.Partial(0, 3), sectionBatchStatus(chat(noticed)))
    }

    /** 押すと作るのは 💬 と !。生成中と作り終えた後は見に行く。 */
    @Test
    fun `押すのが見に行く操作になるのは生成中と作り終えた後だけ`() {
        assertTrue(SectionBatchStatus.Working(1, 3).showsResultOnPress())
        assertTrue(SectionBatchStatus.Ready.showsResultOnPress())
        assertFalse(SectionBatchStatus.Idle.showsResultOnPress())
        assertFalse(SectionBatchStatus.Partial(1, 3).showsResultOnPress())
        assertFalse(SectionBatchStatus.Error.showsResultOnPress())
    }

    @Test
    fun `記号は生成中と作り終えた後と失敗で変わり、部分的なら押せる記号のまま数を添える`() {
        assertEquals("💬", sectionBatchEntrySymbol(SectionBatchStatus.Idle))
        assertEquals("⏳", sectionBatchEntrySymbol(SectionBatchStatus.Working(3, 12)))
        assertEquals("💬", sectionBatchEntrySymbol(SectionBatchStatus.Partial(10, 12)))
        assertEquals("✓", sectionBatchEntrySymbol(SectionBatchStatus.Ready))
        assertEquals("!", sectionBatchEntrySymbol(SectionBatchStatus.Error))

        assertEquals("3/12", sectionBatchEntryCount(SectionBatchStatus.Working(3, 12)))
        assertEquals("10/12", sectionBatchEntryCount(SectionBatchStatus.Partial(10, 12)))
        assertNull(sectionBatchEntryCount(SectionBatchStatus.Idle))
        assertNull(sectionBatchEntryCount(SectionBatchStatus.Ready))
        assertNull(sectionBatchEntryCount(SectionBatchStatus.Error))
    }

    /** 節が1つのノートは今までの「この節」と同じ見え方にする。 */
    @Test
    fun `節が1つなら数を添えない`() {
        assertNull(sectionBatchEntryCount(SectionBatchStatus.Working(0, 1)))
        assertNull(sectionBatchEntryCount(SectionBatchStatus.Partial(0, 1)))
    }

    /** 読み上げ名は状態を言葉で伝える。**数は分母と分子を取り違えない。** */
    @Test
    fun `読み上げ名は全節を核にし、進み具合と押した結果を言う`() {
        assertEquals("全節を要約", sectionBatchEntryDescription(SectionBatchStatus.Idle))
        val states = listOf(
            SectionBatchStatus.Idle,
            SectionBatchStatus.Working(3, 12),
            SectionBatchStatus.Partial(10, 12),
            SectionBatchStatus.Ready,
            SectionBatchStatus.Error
        )
        states.forEach { assertTrue("$it", sectionBatchEntryDescription(it).startsWith("全節")) }
        assertEquals(states.size, states.map(::sectionBatchEntryDescription).toSet().size)
        assertTrue(sectionBatchEntryDescription(SectionBatchStatus.Working(3, 12)).contains("12節中3節"))
        assertTrue(sectionBatchEntryDescription(SectionBatchStatus.Partial(10, 12)).contains("続き"))
    }

    @Test
    fun `面の行の添え書きは状態ごとの次の行動を言う`() {
        assertNull(sectionBatchNote(SectionChatState()))
        assertNull(sectionBatchNote(chat(made(a), made(b), made(c))))
        assertEquals("全節を要約しています（3節中1節）", sectionBatchNote(chat(made(a), running = true)))
        assertEquals("3節中2節を要約しました。💬 を押すと続きを作ります", sectionBatchNote(chat(made(a), made(b))))
        assertTrue(sectionBatchNote(chat(made(a), failed(b)))!!.contains("作り直します"))
    }

    /** **断られて止まったときは、数字ではなく理由と待つことを言う。** 押し直してもすぐは通らないことがある。 */
    @Test
    fun `断られ続けて止まったときは混み合いと待つことを言う`() {
        val note = requireNotNull(sectionBatchNote(chat(made(a), busy = true)))
        assertTrue(note.contains("混み合っています"))
        assertTrue(note.contains("少し待って"))
        assertTrue(note.contains("3節中1節"))
    }
}
