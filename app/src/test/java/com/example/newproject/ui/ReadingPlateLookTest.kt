package com.example.newproject.ui

import com.example.newproject.ui.component.readingPlateLook
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 本文の紙の見た目（→ `docs/dev/features/note_typesetting.md` §5.1）。 */
class ReadingPlateLookTest {

    @Test
    fun `浮かせる紙は角と影と縁と上端の光を持つ`() {
        val look = readingPlateLook(floating = true)
        assertTrue(look.rounded)
        assertTrue(look.shadow)
        assertTrue(look.rim)
        assertTrue(look.topLight)
    }

    @Test
    fun `全画面の紙は画面そのものなので、角も影も縁も上端の光も持たない`() {
        // 角や光を残すと、安全領域の下から別の面が始まって見える（ダークで紙の輪郭と色の段差が出る）。
        val look = readingPlateLook(floating = false)
        assertFalse(look.rounded)
        assertFalse(look.shadow)
        assertFalse(look.rim)
        assertFalse(look.topLight)
    }
}
