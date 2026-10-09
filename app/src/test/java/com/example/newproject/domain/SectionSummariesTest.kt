package com.example.newproject.domain

import com.example.newproject.model.SectionRef
import com.example.newproject.model.state.AiNoticeAction
import com.example.newproject.model.state.AiStatusNotice
import com.example.newproject.model.state.SectionChatProblem
import com.example.newproject.model.state.SectionChatState
import com.example.newproject.model.state.SectionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/** 部分要約を最近3節分持つ規則（→ features/margin_pane.md §5.8）。 */
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

    @Test
    fun `最近作った3節分を超えたら、最も古いものから落とす`() {
        val state = SectionChatState(listOf(summary("C", 3, text = "C"), summary("B", 2, text = "B"), summary("A", 1, text = "A")))

        val next = state.withStarted(summary("D", 4, loading = true))

        assertEquals(SECTION_SUMMARY_LIMIT, next.summaries.size)
        assertNull(next.summaryOf(SectionRef("A")))
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
