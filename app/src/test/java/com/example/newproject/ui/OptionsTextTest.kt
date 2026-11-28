package com.example.newproject.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** オプションの副題（→ `docs/dev/system/ui_copy.md` §2 の1）。状態を言い直さない。 */
class OptionsTextTest {

    private val all: List<String?> = listOf(
        vaultOptionSubtitle(true), vaultOptionSubtitle(false),
        notePaperOptionSubtitle(true), notePaperOptionSubtitle(false),
        vividGroundOptionSubtitle(true), vividGroundOptionSubtitle(false)
    )

    @Test
    fun `副題は今の状態を言い直さない`() {
        all.filterNotNull().forEach { assertFalse(it, it.contains("表示しています") || it.contains("選択済み")) }
    }

    @Test
    fun `選んであれば副題を出さず、選んでいなければそれを言う`() {
        assertNull(vaultOptionSubtitle(true))
        assertTrue(vaultOptionSubtitle(false)!!.contains("選んでいません"))
    }

    @Test
    fun `紙色はオンでもオフでも何が変わるかを言い、判定が更新の日時であることを言い切る`() {
        assertTrue(notePaperOptionSubtitle(false).contains("更新"))
    }

    @Test
    fun `ダークの間は効かないことを言い、ライトの背景の行は副題を出さない`() {
        assertEquals(DARK_THEME_NOT_APPLIED, notePaperOptionSubtitle(true))
        assertEquals(DARK_THEME_NOT_APPLIED, vividGroundOptionSubtitle(true))
        assertNull(vividGroundOptionSubtitle(false))
    }
}
