package com.example.newproject.ui

import com.example.newproject.ui.screen.overlapNotice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重なり解消の告知が、**開いている候補との関係を正しく言う**ことを固定する。
 *
 * 理由を時間で消さず再訪できるようにした結果、**外された候補自身のシートも開ける**。
 * 件数だけを言うと、目の前の候補を「ほか」と呼んで関係を逆に読ませる。
 */
class DistillRangeNoticeTest {

    @Test
    fun `解消を起こした候補では相手が外れたことを言う`() {
        val notice = requireNotNull(overlapNotice(isDeselectedByOverlap = false, otherDeselectedCount = 1))

        assertTrue(notice.contains("ほかの1箇所"))
    }

    @Test
    fun `外された候補自身のシートでは「ほか」と言わない`() {
        val notice = requireNotNull(overlapNotice(isDeselectedByOverlap = true, otherDeselectedCount = 0))

        assertTrue("主語は開いている候補自身", notice.contains("この箇所"))
        assertTrue("「ほか」と誤記しない", !notice.contains("ほか"))
    }

    @Test
    fun `自分も他も外れているときは開いている候補を優先する`() {
        val notice = requireNotNull(overlapNotice(isDeselectedByOverlap = true, otherDeselectedCount = 2))

        assertTrue(notice.contains("この箇所"))
        assertTrue(!notice.contains("ほか"))
    }

    @Test
    fun `外れた候補が無ければ何も言わない`() {
        assertNull(overlapNotice(isDeselectedByOverlap = false, otherDeselectedCount = 0))
    }

    @Test
    fun `件数の単位は箇所へ揃える`() {
        assertEquals(
            "! 重なるため、ほかの3箇所の選択を外しました。",
            overlapNotice(isDeselectedByOverlap = false, otherDeselectedCount = 3)
        )
    }
}
