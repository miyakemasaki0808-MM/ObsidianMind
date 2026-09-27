package com.example.newproject.ui

import com.example.newproject.domain.ReunionSlot
import com.example.newproject.model.ReunionKind
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
        assertEquals("前回のあなたはこの問いで止まっていました", reunionLead(shown(ReunionKind.Question)))
        assertEquals("今も有効か確認したい箇所があります", reunionLead(shown(ReunionKind.Staleness)))
    }

    /** 前後の要約とノートの要約には前置きを付けない。見出しの1文がどこまで読んだかを既に言っている。 */
    @Test
    fun `前後の要約とノートの要約には前置きを付けない`() {
        assertNull(reunionLead(shown(ReunionKind.Passage)))
        assertNull(reunionLead(shown(ReunionKind.Overview)))
    }

    private fun shown(kind: ReunionKind) = ReunionSlot.Shown(text = "枠に出ている1件", kind = kind)
}
