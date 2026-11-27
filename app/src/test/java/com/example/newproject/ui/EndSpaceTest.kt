package com.example.newproject.ui

import com.example.newproject.ui.markdown.EndSpaceSnapshot
import com.example.newproject.ui.markdown.endSpaceShortfall
import com.example.newproject.ui.markdown.endSpaceSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 見出しを上端に置くのに足りない末尾の余白（→ docs/dev/features/note_links.md §5）。 */
class EndSpaceTest {

    @Test
    fun `末尾で止まって見出しが上端より下にあれば_その差だけ足りない`() {
        assertEquals(180, endSpaceShortfall(targetOffset = 180, canScrollForward = false, paddingApplied = true))
    }

    @Test
    fun `見出しが上端にあるか上へはみ出していれば足りている`() {
        assertEquals(0, endSpaceShortfall(targetOffset = 0, canScrollForward = true, paddingApplied = true))
        assertEquals(0, endSpaceShortfall(targetOffset = 0, canScrollForward = false, paddingApplied = true))
        assertEquals(0, endSpaceShortfall(targetOffset = -4, canScrollForward = true, paddingApplied = true))
    }

    /** 全画面のような高い窓で足した余白を低い窓へ運んだとき（→ features/note_typesetting.md 判断12）。 */
    @Test
    fun `見出しが上端にあり余白が窓で要る分より多ければ_余りを返して減らす`() {
        // 最後のブロックの下に 300px の隙間があり、余白が 900px ある → 600px 余っている。
        assertEquals(-600, endSpaceShortfall(targetOffset = 0, canScrollForward = false, paddingApplied = true, freeBelowLast = 300, bottomPx = 900))
        // 最後のブロックが窓の外なら、余白は要らない。
        assertEquals(-900, endSpaceShortfall(targetOffset = 0, canScrollForward = true, paddingApplied = true, freeBelowLast = null, bottomPx = 900))
    }

    @Test
    fun `余白がちょうど要る分か1px以内の差なら減らさない`() {
        assertEquals(0, endSpaceShortfall(targetOffset = 0, canScrollForward = false, paddingApplied = true, freeBelowLast = 300, bottomPx = 300))
        assertEquals(0, endSpaceShortfall(targetOffset = 0, canScrollForward = false, paddingApplied = true, freeBelowLast = 300, bottomPx = 301))
        assertEquals(0, endSpaceShortfall(targetOffset = 0, canScrollForward = false, paddingApplied = true, freeBelowLast = 300, bottomPx = 0))
    }

    /** まだ先へ送れるのに上端に無いのは、送りが届く前の配置。そこから余白を決めると、足りない分を誤って足す。 */
    @Test
    fun `先へ送れるのに見出しが上端に無ければ_まだ決めない`() {
        assertNull(endSpaceShortfall(targetOffset = 180, canScrollForward = true, paddingApplied = true))
    }

    @Test
    fun `見出しが描かれていなければ_まだ決めない`() {
        assertNull(endSpaceShortfall(targetOffset = null, canScrollForward = false, paddingApplied = true))
    }

    /** 余白を替えた直後に、前の余白で組んだ配置から決めない。前の余白の分だけ足りない量を取り違える。 */
    @Test
    fun `今の余白で組まれた配置でなければ_まだ決めない`() {
        assertNull(endSpaceShortfall(targetOffset = 180, canScrollForward = false, paddingApplied = false))
        assertNull(endSpaceShortfall(targetOffset = 0, canScrollForward = false, paddingApplied = false))
    }

    // ── 保存値と全画面へ運ぶ形 ──────────────────────────────────────

    /** 置いている途中なら、一覧がまだ末尾で止まっていても置き直す。 */
    @Test
    fun `置いている途中なら置き直す`() {
        assertEquals(
            EndSpaceSnapshot(bottomPx = 240, block = 20, replace = true),
            endSpaceSnapshot(bottomPx = 240, placedBlock = 20, placing = true, firstIndex = 17, firstOffset = 30)
        )
    }

    /** 置き終えた後でも、一覧がそのブロックの上端にいれば置き直す（窓が変われば足りない分を足す）。 */
    @Test
    fun `そのブロックの上端にいれば置き直す`() {
        assertEquals(
            EndSpaceSnapshot(bottomPx = 240, block = 20, replace = true),
            endSpaceSnapshot(bottomPx = 240, placedBlock = 20, placing = false, firstIndex = 20, firstOffset = 0)
        )
    }

    /** 利用者が自分で送った後は、その位置を余白ごと戻すだけで、見出しへ引き戻さない。 */
    @Test
    fun `利用者が自分で送った後は余白だけを運ぶ`() {
        assertEquals(
            EndSpaceSnapshot(bottomPx = 240, block = 20, replace = false),
            endSpaceSnapshot(bottomPx = 240, placedBlock = 20, placing = false, firstIndex = 20, firstOffset = 50)
        )
        assertEquals(
            EndSpaceSnapshot(bottomPx = 240, block = 20, replace = false),
            endSpaceSnapshot(bottomPx = 240, placedBlock = 20, placing = false, firstIndex = 12, firstOffset = 0)
        )
    }

    @Test
    fun `置いたブロックが無ければ置き直さない`() {
        assertEquals(
            EndSpaceSnapshot(bottomPx = 0, block = null, replace = false),
            endSpaceSnapshot(bottomPx = 0, placedBlock = null, placing = false, firstIndex = 0, firstOffset = 0)
        )
    }
}
