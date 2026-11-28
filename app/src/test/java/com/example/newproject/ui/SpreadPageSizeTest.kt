package com.example.newproject.ui

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.newproject.ui.screen.spreadPageContentHeightPx
import com.example.newproject.ui.screen.spreadPageWidthPx
import com.example.newproject.ui.screen.spreadTextWidthPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        assertEquals(716, spreadTextWidthPx(816, density))
    }

    @Test
    fun `本文の列の幅は、列の上限を超えない`() {
        assertEquals(with(density) { 520.dp.roundToPx() }, spreadTextWidthPx(with(density) { 900.dp.roundToPx() }, density))
    }

    @Test
    fun `左右のページは同じ幅で、溝と合わせて親の幅に収まる`() {
        // 密度 2.4375・本文領域 800dp（1950px）・溝 16dp（39px）。ページ 392dp は 955.5px で、丸めた 956px を左右に取ると
        // 合計 1951px が親を超え、右のページだけ 955px に縮む。
        val wide = Density(2.4375f)
        val page = spreadPageWidthPx(392.dp, 16.dp, containerWidthPx = 1950, density = wide)
        assertEquals(955, page)
        assertTrue(page * 2 + with(wide) { 16.dp.roundToPx() } <= 1950)
        // 親に余裕があれば、求めたページの幅のまま。親の幅が分からなければ丸めるだけ。
        assertEquals(956, spreadPageWidthPx(392.dp, 16.dp, containerWidthPx = 2000, density = wide))
        assertEquals(956, spreadPageWidthPx(392.dp, 16.dp, containerWidthPx = null, density = wide))
    }

    @Test
    fun `本文を置ける高さは、ページの高さから片側ずつ丸めた余白を引く`() {
        assertEquals(1000f - 100f, spreadPageContentHeightPx(1000, density))
    }
}
