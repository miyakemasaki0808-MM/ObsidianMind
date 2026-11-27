package com.example.newproject.ui

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.newproject.ui.screen.spreadPageContentHeightPx
import com.example.newproject.ui.screen.spreadTextWidthPx
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 見開きのページの本文の幅と高さ（→ `docs/dev/features/note_typesetting.md` §7・`note_spread.md` 判断8）。
 * 描画は余白を片側ずつ画素へ丸めるので、計測も同じ順で引く。片側 18dp が端数になる密度で確かめる。
 */
class SpreadPageSizeTest {

    private val density = Density(2.75f)

    @Test
    fun `本文の列の幅は、ページの幅から片側ずつ丸めた余白を引く`() {
        // ページ 816px、余白 18dp は 49.5px → 片側 50px。足してから丸めると 717px になり、描画より1画素広い。
        val pageWidth = with(density) { 816.toDp() }
        assertEquals(716, spreadTextWidthPx(pageWidth, density))
    }

    @Test
    fun `本文の列の幅は、列の上限を超えない`() {
        assertEquals(with(density) { 520.dp.roundToPx() }, spreadTextWidthPx(900.dp, density))
    }

    @Test
    fun `本文を置ける高さは、ページの高さから片側ずつ丸めた余白を引く`() {
        assertEquals(1000f - 100f, spreadPageContentHeightPx(1000, density))
    }
}
