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
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 部分要約を持つ規則（→ features/section_ai_chat.md §5）。 */
class SectionSummariesTest {

    private fun summary(title: String, requestId: Long, loading: Boolean = false, text: String? = null) =
        SectionSummary(SectionRef(title), requestId, title, "本文", summary = text, isSummaryLoading = loading)

    @Test
    fun `新しく始めた要約は先頭に入り、同じ節の古い要約を置き換える`() {
        val state = SectionChatState(listOf(summary("B", 2, text = "B"), summary("A", 1, text = "A")))

        val next = state.withStarted(summary("A", 3, loading = true))

        assertEquals(listOf(3L, 2L), next.summaries.map { it.requestId })
    }

    /** **生成中の別の節は取り除く。** 生成は一度に1本で、取り消された節はボタンに戻る。 */
    @Test
    fun `生成中の別の節は取り除き、できた要約は残す`() {
        val state = SectionChatState(listOf(summary("B", 2, loading = true), summary("A", 1, text = "A")))

        val next = state.withStarted(summary("C", 3, loading = true))

        assertEquals(listOf("C", "A"), next.summaries.map { it.section.title })
    }

    /** **作った要約はノートを開いている間すべて持つ。** 全節の要約を読み進めて面に出すため。 */
    @Test
    fun `作った要約は数で落とさない`() {
        val many = (1..200).map { summary("節$it", it.toLong(), text = "要約") }

        val next = SectionChatState(many).withStarted(summary("新", 201, loading = true))

        assertEquals(201, next.summaries.size)
        assertNotNull(next.summaryOf(SectionRef("節1")))
    }

    @Test
    fun `始めても全節の状態は残る`() {
        val batch = SectionBatch(listOf(SectionRef("A")), isRunning = true)

        assertEquals(batch, SectionChatState(batch = batch).withStarted(summary("A", 1, loading = true)).batch)
    }

    /** 番を待っている節だけが「次に作ります」になる。**短くて対象でない節はボタンのまま。** */
    @Test
    fun `全節の間に番を待っているのは、対象で要約をまだ持たない節だけ`() {
        val (made, waiting, outside) = listOf(SectionRef("済"), SectionRef("待"), SectionRef("外"))
        val running = SectionChatState(
            listOf(summary("済", 1, text = "要約")),
            SectionBatch(listOf(made, waiting), isRunning = true)
        )

        assertFalse(running.isQueued(made))
        assertTrue(running.isQueued(waiting))
        assertFalse(running.isQueued(outside))
        assertFalse(running.isQueued(null))
        assertFalse("止まった後はボタンに戻す", running.withBatchStopped().isQueued(waiting))
    }

    @Test
    fun `次に作ることにした節は理由を下げ、対象でなければ対象へ足す`() {
        val failed = summary("A", 1).copy(summaryProblem = SectionChatProblem.GenerationFailed("失敗"))
        val running = SectionChatState(listOf(failed), SectionBatch(listOf(SectionRef("A")), isRunning = true))

        val again = running.withQueued(SectionRef("A"))
        assertNull(again.summaryOf(SectionRef("A")))
        assertEquals(listOf(SectionRef("A")), again.batch?.targets)

        assertEquals(listOf(SectionRef("A"), SectionRef("B")), running.withQueued(SectionRef("B")).batch?.targets)
    }

    @Test
    fun `全節で作るのは持っていない節と出せなかった節で、生成中の節は作らない`() {
        assertTrue((null as SectionSummary?).needsBatchGeneration())
        assertTrue(summary("A", 1).copy(summaryProblem = SectionChatProblem.GenerationFailed("x")).needsBatchGeneration())
        assertFalse(summary("A", 1, text = "要約").needsBatchGeneration())
        assertFalse(summary("A", 1, loading = true).needsBatchGeneration())
    }

    /** **後で使えるようになる説明だけは、頼み直すと確かめ直す**（準備待ちには再試行のボタンが無い）。 */
    @Test
    fun `頼み直したときに確かめ直すのは、後で使えるようになる説明だけ`() {
        fun notice(canTryAgainLater: Boolean) = SectionChatProblem.AiStatus(
            AiStatusNotice("いま使えません。", AiNoticeAction.None, canTryAgainLater = canTryAgainLater)
        )
        val base = summary("B", 1)

        assertFalse(base.copy(summaryProblem = notice(canTryAgainLater = true)).isShownAsIsOnRequest())
        assertTrue(base.copy(summaryProblem = notice(canTryAgainLater = false)).isShownAsIsOnRequest())
        assertTrue(base.copy(summaryProblem = SectionChatProblem.GenerationFailed("失敗")).isShownAsIsOnRequest())
        assertTrue(base.copy(isSummaryLoading = true).isShownAsIsOnRequest())
        assertTrue(base.copy(summary = "要約").isShownAsIsOnRequest())
    }

    /** **取り消された要求の結果は、どこにも書かない。** 同じ節を頼み直した後なら、番号が違う。 */
    @Test
    fun `書き換えは要求の番号が一致する要約にだけ効く`() {
        val state = SectionChatState(listOf(summary("B", 3, loading = true)))

        val next = state.updated(requestId = 1) { it.copy(summary = "古い結果", isSummaryLoading = false) }

        assertEquals(state, next)
    }

    /**
     * **まだ見せていない理由があるのは、面を出さずに頼んだ要求に端末AIが使えない理由が届いたときだけ**（→ features/margin_pane.md §5.4）。
     * 生成中・完成・生成の失敗は記号が示すので、見せる理由として数えない。
     */
    @Test
    fun `まだ見せていない理由は、面を出さずに頼んだ要求の端末AIの理由だけ`() {
        val notice = SectionChatProblem.AiStatus(AiStatusNotice("使えません", AiNoticeAction.None, canTryAgainLater = false))
        val quiet = summary("A", 1, loading = true).copy(noticePending = true)

        assertTrue(quiet.copy(isSummaryLoading = false, summaryProblem = notice).hasUnshownNotice())
        assertFalse(quiet.hasUnshownNotice())
        assertFalse(quiet.copy(isSummaryLoading = false, summary = "要約").hasUnshownNotice())
        assertFalse(quiet.copy(isSummaryLoading = false, summaryProblem = SectionChatProblem.GenerationFailed("x")).hasUnshownNotice())
        assertFalse(quiet.copy(isSummaryLoading = false, summaryProblem = notice, noticePending = false).hasUnshownNotice())
    }
}
