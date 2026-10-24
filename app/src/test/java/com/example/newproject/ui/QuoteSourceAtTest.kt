package com.example.newproject.ui

import com.example.newproject.domain.isQuotable
import com.example.newproject.ui.markdown.BlockBox
import com.example.newproject.ui.markdown.QuoteSourceAt
import com.example.newproject.ui.markdown.quoteSourceAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 選択メニューが渡す範囲の矩形から、範囲の始まりを含むブロックを決める（→ features/margin_quote.md §5.2・§11）。
 * 矩形は選択の容器の見えている範囲に切り詰めて渡ってくる。
 */
class QuoteSourceAtTest {

    // 見えている範囲の上端は 100。ブロック 4 は上が見えている範囲の外へはみ出している。
    private val boxes = listOf(BlockBox(4, 60f, 180f), BlockBox(5, 196f, 300f), BlockBox(6, 316f, 420f))

    @Test
    fun `矩形の上端を含むブロックを始まりにする`() {
        assertEquals(QuoteSourceAt(5, 5), quoteSourceAt(rectTop = 250f, visibleTop = 100f, boxes, contentAbove = true))
        assertEquals(QuoteSourceAt(4, 4), quoteSourceAt(rectTop = 120f, visibleTop = 100f, boxes, contentAbove = true))
    }

    @Test
    fun `ブロックの間の余白に落ちたら、その下のブロックで始まっている`() {
        assertEquals(QuoteSourceAt(6, 6), quoteSourceAt(rectTop = 305f, visibleTop = 100f, boxes, contentAbove = true))
    }

    /** **見えている範囲の上端に張り付いていれば、始まりは画面の外にありうる。** 引用元は決めず、見えている最初のブロックを手掛かりにだけ渡す。 */
    @Test
    fun `前に本文があるのに上端が張り付いていれば引用元を決めない`() {
        assertEquals(QuoteSourceAt(null, 4), quoteSourceAt(rectTop = 100f, visibleTop = 100f, boxes, contentAbove = true))
        assertEquals(QuoteSourceAt(null, 4), quoteSourceAt(rectTop = 100.6f, visibleTop = 100f, boxes, contentAbove = true))
    }

    /** 一覧のいちばん上や見開きのページでは前に本文が無いので、張り付いていても始まりは見えている。 */
    @Test
    fun `前に本文が無ければ張り付いていても始まりを決める`() {
        val top = listOf(BlockBox(0, 100f, 180f), BlockBox(1, 196f, 300f))

        assertEquals(QuoteSourceAt(0, 0), quoteSourceAt(rectTop = 100f, visibleTop = 100f, top, contentAbove = false))
    }

    @Test
    fun `ブロックが組まれていなければ決めない`() {
        assertEquals(QuoteSourceAt(null, null), quoteSourceAt(rectTop = 250f, visibleTop = 100f, emptyList(), contentAbove = true))
        assertEquals(QuoteSourceAt(null, null), quoteSourceAt(rectTop = 500f, visibleTop = 100f, boxes, contentAbove = true))
    }

    /** 見開きでは1つのブロックが2ページに割れる。切れ端ごとの位置でも、決まるのは元のブロック。 */
    @Test
    fun `割れたブロックの切れ端でも元のブロックに決まる`() {
        val page = listOf(BlockBox(7, 40f, 300f), BlockBox(8, 316f, 600f))

        assertEquals(QuoteSourceAt(8, 8), quoteSourceAt(rectTop = 320f, visibleTop = 0f, page, contentAbove = false))
    }

    @Test
    fun `空白と制御文字だけの選択は引用にできない`() {
        assertFalse(isQuotable(" \n\t\u0007 "))
        assertFalse(isQuotable(""))
        assertTrue(isQuotable(" 一 "))
    }
}
