package com.example.newproject.domain

import com.example.newproject.model.SectionRef
import com.example.newproject.model.state.SectionChatState
import com.example.newproject.model.state.SectionSummary
import org.junit.Assert.assertEquals
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

    /** **取り消された要求の結果は、どこにも書かない。** 同じ節を頼み直した後なら、番号が違う。 */
    @Test
    fun `書き換えは要求の番号が一致する要約にだけ効く`() {
        val state = SectionChatState(listOf(summary("B", 3, loading = true)))

        val next = state.updated(requestId = 1) { it.copy(summary = "古い結果", isSummaryLoading = false) }

        assertEquals(state, next)
    }
}
