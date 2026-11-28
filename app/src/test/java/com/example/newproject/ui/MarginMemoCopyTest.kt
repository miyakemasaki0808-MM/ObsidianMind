package com.example.newproject.ui

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    fun `入力欄の上の行は文字の倍率に合わせて2行分を取り、32dpを下回らない`() {
        assertEquals(32.dp, writeTargetLineHeight(Density(2.75f, fontScale = 1f)))
        assertEquals(32.dp, writeTargetLineHeight(Density(2.75f, fontScale = 0.85f)))
        // 倍率 2.0 では、文字が組む1行の高さ（16sp を換算した値）の2行分。大きい倍率の換算は線形でない。
        val large = Density(2.75f, fontScale = 2f)
        val oneLine = with(large) { 16.sp.toDp() }
        assertEquals(oneLine * 2, writeTargetLineHeight(large))
        assertTrue("2行分が 32dp を超える", writeTargetLineHeight(large) > 32.dp)
    }
}
