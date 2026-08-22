package com.example.newproject.ui

import com.example.newproject.model.ReunionKind
import com.example.newproject.model.state.ReadingTraceCard
import com.example.newproject.ui.component.reunionLead
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 枠の1件に添える前置き。**種別から決まる**ことを固定する。
 *
 * 前置きを生成文の書き出しに混ぜる案を採らなかったのは、
 * **種別が文字列の中にしか無いと表示側が分岐できず、検査も書けない**ため
 * （→ features/reunion_card.md 判断2）。この検査が書けること自体が、その判断の裏付けになる。
 */
class ReunionLeadTest {

    @Test
    fun `種別ごとに前置きが変わる`() {
        assertEquals(
            "前回のあなたはこの問いで止まっていました",
            reunionLead(card(kind = ReunionKind.Question))
        )
        assertEquals(
            "今も有効か確認したい箇所があります",
            reunionLead(card(kind = ReunionKind.Staleness))
        )
    }

    /** 俯瞰要約には前置きを付けない。**現行の見え方をそのまま保つ。** */
    @Test
    fun `俯瞰要約には前置きを付けない`() {
        assertNull(reunionLead(card(kind = ReunionKind.Overview)))
    }

    /**
     * 印が付いていれば種別によらず印の前置きになる。
     * 出しているのは**押した時点で保存した内容**であって、いま作ったものではない。
     */
    @Test
    fun `印が付いていれば種別によらず印の前置きになる`() {
        ReunionKind.entries.forEach { kind ->
            assertEquals(
                kind.name,
                "前回「まだ考えたい」と印を付けています",
                reunionLead(card(kind = kind, isMarked = true))
            )
        }
    }

    /** 枠が空なら前置きも出さない（前置きだけが浮くのを防ぐ）。 */
    @Test
    fun `枠が空なら前置きは出ない`() {
        assertNull(reunionLead(card(kind = ReunionKind.Question, summary = null)))
        assertNull(reunionLead(card(kind = ReunionKind.Question, summary = "   ")))
        assertNull(reunionLead(card(kind = null, summary = null, isMarked = true)))
    }

    private fun card(
        kind: ReunionKind?,
        summary: String? = "枠に出ている1件",
        isMarked: Boolean = false
    ) = ReadingTraceCard(
        visitCount = 3,
        lastVisitAtMillis = 0L,
        lastSectionTitle = null,
        lastProgressPercent = 40,
        aiSummary = summary,
        aiSummaryKind = kind,
        isMarked = isMarked
    )
}
