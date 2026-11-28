package com.example.newproject.ui

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.newproject.ui.screen.MEMO_INPUT_HINT
import com.example.newproject.ui.screen.writeTargetLineHeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 余白メモの入力欄の上の行（→ `docs/dev/system/ui_copy.md` §2・`docs/dev/features/margin_pane.md`）。 */
class MarginMemoCopyTest {

    @Test
    fun `安全の1行はノート本体が変わらないことを言う`() {
        assertTrue(MEMO_INPUT_HINT, MEMO_INPUT_HINT.contains("ノート本体") && MEMO_INPUT_HINT.contains("変わりません"))
    }

    @Test
    fun `入力欄の上の行は測った2行分を取り、32dpを下回らない`() {
        val density = Density(2.75f, fontScale = 1f)
        // 倍率 1.0 の2行（16sp × 2 = 88px）は 32dp。
        assertEquals(32.dp, writeTargetLineHeight(88, density))
        // 倍率 2.0 で字の比で解いた2行（1行 32dp = 88px の2倍）は 64dp。sp の換算で出す 56dp では足りない。
        assertEquals(64.dp, writeTargetLineHeight(176, density))
        assertTrue(writeTargetLineHeight(176, density) > 32.dp)
    }
}
