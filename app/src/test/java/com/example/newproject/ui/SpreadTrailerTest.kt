package com.example.newproject.ui

import com.example.newproject.ui.screen.SpreadPage
import com.example.newproject.ui.screen.SpreadSlice
import com.example.newproject.ui.screen.placeSpreadTrailer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 見開きでタグの札の行を置く場所（→ `docs/dev/features/note_typesetting.md` §5.3）。札の行は切れ端にしない。 */
class SpreadTrailerTest {

    private fun page(vararg heights: Float) = SpreadPage(heights.mapIndexed { i, h -> SpreadSlice(i, 0, 1, 0, 1, h) })

    @Test
    fun `最後のページに間と一緒に入れば、そのページに置き、ページを増やさない`() {
        val pages = listOf(page(400f), page(300f, 200f))
        // 右ページ 1000、使った高さ 300 + 14 + 200 = 514、間 14 と札 100 で 628。
        val placed = placeSpreadTrailer(pages, trailerHeight = 100f, leftPageHeight = 1000f, blockSpacing = 14f)!!
        assertEquals(1, placed.pageIndex)
        assertEquals(pages, placed.pages)
    }

    @Test
    fun `入らなければ切れ端の無いページを1枚足して、そこに置く`() {
        val pages = listOf(page(900f))
        val placed = placeSpreadTrailer(pages, trailerHeight = 100f, leftPageHeight = 1000f, blockSpacing = 14f)!!
        assertEquals(1, placed.pageIndex)
        assertEquals(2, placed.pages.size)
        assertTrue("足したページに切れ端がある", placed.pages[1].slices.isEmpty())
        // ちょうど入るときは足さない（900 + 14 + 86 = 1000）。
        assertEquals(0, placeSpreadTrailer(pages, trailerHeight = 86f, leftPageHeight = 1000f, blockSpacing = 14f)!!.pageIndex)
    }

    @Test
    fun `ページの高さは1ページ目・左・右で決まる`() {
        // 1ページ目だけ低い（再会カードの下）。
        assertEquals(1, placeSpreadTrailer(listOf(page(500f)), 100f, leftPageHeight = 1000f, firstPageHeight = 600f, blockSpacing = 14f)!!.pageIndex)
        // 右ページは右の高さで見る。850 + 14 + 100 = 964 は左の 1000 には入るが、右の 900 には入らない。
        assertEquals(2, placeSpreadTrailer(listOf(page(10f), page(850f)), 100f, leftPageHeight = 1000f, rightPageHeight = 900f, blockSpacing = 14f)!!.pageIndex)
    }

    @Test
    fun `タグが無ければ何も置かず、本文が空なら1枚目に置く`() {
        assertNull(placeSpreadTrailer(listOf(page(10f)), trailerHeight = 0f, leftPageHeight = 1000f, blockSpacing = 14f))
        val empty = placeSpreadTrailer(emptyList(), trailerHeight = 100f, leftPageHeight = 1000f, blockSpacing = 14f)!!
        assertEquals(0, empty.pageIndex)
        assertEquals(1, empty.pages.size)
    }
}
