package com.example.newproject.ui

import com.example.newproject.ui.markdown.endSpaceShortfall
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
}
