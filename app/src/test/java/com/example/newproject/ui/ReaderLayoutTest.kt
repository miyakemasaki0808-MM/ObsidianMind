package com.example.newproject.ui

import com.example.newproject.ui.screen.ReaderFold
import com.example.newproject.ui.screen.ReaderLayout
import com.example.newproject.ui.screen.canShowMarginPane
import com.example.newproject.ui.screen.readerLayoutFor
import com.example.newproject.ui.screen.sideColumnWidthDp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// ノート画面の並べ方（→ features/rediscover.md 判断6・features/margin_pane.md §5.1）。
// 縦の見た目を変えないことも同じ表で見る。
class ReaderLayoutTest {

    @Test
    fun `低い横長の画面だけ左右2列にする`() {
        // カバー画面の横向き・横にしたスマホ
        assertEquals(ReaderLayout.SideBySide, readerLayoutFor(widthDp = 970f, heightDp = 443f))
        assertEquals(ReaderLayout.SideBySide, readerLayoutFor(widthDp = 891f, heightDp = 411f))
        // 縦長の画面・開いた折りたたみ・タブレットは今までどおり
        assertEquals(ReaderLayout.Stacked, readerLayoutFor(widthDp = 443f, heightDp = 970f))
        assertEquals(ReaderLayout.Stacked, readerLayoutFor(widthDp = 841f, heightDp = 673f))
        assertEquals(ReaderLayout.Stacked, readerLayoutFor(widthDp = 1280f, heightDp = 800f))
    }

    @Test
    fun `境界 — 高さ480dpからは縦に積み、横長でなければ低くても縦に積む`() {
        assertEquals(ReaderLayout.SideBySide, readerLayoutFor(widthDp = 900f, heightDp = 479f))
        assertEquals(ReaderLayout.Stacked, readerLayoutFor(widthDp = 900f, heightDp = 480f))
        assertEquals(ReaderLayout.Stacked, readerLayoutFor(widthDp = 400f, heightDp = 400f))
    }

    @Test
    fun `左列は画面の4割か360dpの小さいほう`() {
        assertEquals(360f, sideColumnWidthDp(970f))
        assertEquals(240f, sideColumnWidthDp(600f))
    }

    // ── 余白ペイン（→ features/margin_pane.md §5.1）─────────────────────────

    private fun vertical(atDp: Float, widthDp: Float = 0f) = ReaderFold(isVertical = true, startDp = atDp, endDp = atDp + widthDp)
    private val tabletop = ReaderFold(isVertical = false, startDp = 0f, endDp = 0f)

    @Test
    fun `縦の折り目があれば折り目で割る`() {
        // レールの無い窓。本文領域は窓の20dpから始まり、折り目は窓の420dp。
        assertEquals(
            ReaderLayout.MarginPane(bodyWidthDp = 392f, gutterDp = 16f),
            readerLayoutFor(800f, 900f, paneOpen = true, fold = vertical(420f), regionStartDp = 20f)
        )
    }

    @Test
    fun `折り目は本文領域の座標へ直してから割る`() {
        // 左のレール（80dp）と余白（20dp）の分を引く。引かなければ本文は 442dp になる。
        assertEquals(
            ReaderLayout.MarginPane(bodyWidthDp = 342f, gutterDp = 16f),
            readerLayoutFor(780f, 900f, paneOpen = true, expandedWidth = true, fold = vertical(450f), regionStartDp = 100f)
        )
    }

    /** **中央で割ると、本文と見出しの操作が折り目をまたぐ。** 中央なら割れる幅でも、中央へ逃げない。 */
    @Test
    fun `本文領域の中の折り目で割れなければ、中央へ逃げず縦に積む`() {
        assertEquals(
            ReaderLayout.Stacked,
            readerLayoutFor(780f, 900f, paneOpen = true, fold = vertical(300f), regionStartDp = 100f)
        )
    }

    @Test
    fun `折り目がレールの中にあれば本文に重ならないので中央で割る`() {
        assertEquals(
            ReaderLayout.MarginPane(bodyWidthDp = 382f, gutterDp = 16f),
            readerLayoutFor(780f, 900f, paneOpen = true, fold = vertical(50f), regionStartDp = 100f)
        )
    }

    /**
     * **実機の報告値。** 平らに開いた Pixel 10 Pro Fold（density 2.4375、窓 852×883dp）は、
     * 平らでも縦の折り目を x=1038px に報告する。本文領域は左のレール80dpと余白20dpの後ろから始まり、幅は約732dp。
     * 本文の下限が320dpだったころは、折り目で割った本文（約318dp）が下限を割って中央へ落ち、折り目をまたいでいた。
     */
    @Test
    fun `実機の報告値では溝が折り目を含む`() {
        val foldDp = 1038f / 2.4375f
        val regionStart = 100f

        val layout = readerLayoutFor(732f, 800f, paneOpen = true, expandedWidth = true, fold = vertical(foldDp), regionStartDp = regionStart)

        assertTrue("折り目で割れていません: $layout", layout is ReaderLayout.MarginPane)
        val pane = layout as ReaderLayout.MarginPane
        val bodyEnd = regionStart + pane.bodyWidthDp
        assertTrue("本文の右端が折り目を越えています", bodyEnd < foldDp)
        assertTrue("ペインの左端が折り目より手前です", foldDp < bodyEnd + pane.gutterDp)
    }

    @Test
    fun `幅を持つ折り目は溝をその幅まで広げる`() {
        assertEquals(
            ReaderLayout.MarginPane(bodyWidthDp = 415f, gutterDp = 30f),
            readerLayoutFor(860f, 900f, paneOpen = true, fold = vertical(435f, widthDp = 30f), regionStartDp = 20f)
        )
    }

    @Test
    fun `折り目の無い広い窓は中央で割り、広くなければ縦に積む`() {
        assertEquals(
            ReaderLayout.MarginPane(bodyWidthDp = 492f, gutterDp = 16f),
            readerLayoutFor(1000f, 700f, paneOpen = true, expandedWidth = true)
        )
        assertEquals(ReaderLayout.Stacked, readerLayoutFor(1000f, 700f, paneOpen = true, expandedWidth = false))
    }

    @Test
    fun `横の折り目（卓上の形）は広くても縦に積む`() {
        assertEquals(
            ReaderLayout.Stacked,
            readerLayoutFor(1000f, 700f, paneOpen = true, expandedWidth = true, fold = tabletop)
        )
    }

    @Test
    fun `ペインの設定が閉じていれば今までの規則に戻る`() {
        assertEquals(ReaderLayout.Stacked, readerLayoutFor(800f, 900f, paneOpen = false, fold = vertical(420f)))
        assertEquals(ReaderLayout.Stacked, readerLayoutFor(1000f, 700f, paneOpen = false, expandedWidth = true))
    }

    @Test
    fun `高さの足りない横長は、折り目があっても左右2列を優先する`() {
        assertEquals(
            ReaderLayout.SideBySide,
            readerLayoutFor(900f, 443f, paneOpen = true, expandedWidth = true, fold = vertical(450f))
        )
    }

    @Test
    fun `境界 — 割った後の幅が下限を割るなら縦に積む`() {
        // 折り目で割って本文300dp・ペイン280dpちょうど
        assertEquals(
            ReaderLayout.MarginPane(bodyWidthDp = 300f, gutterDp = 16f),
            readerLayoutFor(596f, 900f, paneOpen = true, fold = vertical(308f))
        )
        // ペインが1dp足りない
        assertEquals(ReaderLayout.Stacked, readerLayoutFor(595f, 900f, paneOpen = true, fold = vertical(308f)))
        // 中央で割るときは、本文とペインがどちらも下限を満たす幅から
        assertEquals(
            ReaderLayout.MarginPane(bodyWidthDp = 300f, gutterDp = 16f),
            readerLayoutFor(616f, 900f, paneOpen = true, expandedWidth = true)
        )
        assertEquals(ReaderLayout.Stacked, readerLayoutFor(615f, 900f, paneOpen = true, expandedWidth = true))
    }

    /** ✎ がペインとシートのどちらを扱うかは、**今の設定によらず**窓だけで決まる。 */
    @Test
    fun `ペインを出せる窓かは設定を開いたとみなして決める`() {
        assertTrue(canShowMarginPane(800f, 900f, expandedWidth = false, fold = vertical(420f), regionStartDp = 20f))
        // スマホ・カバー画面・低い横長・卓上の形・狭い分割窓は出せない
        assertFalse(canShowMarginPane(411f, 890f, expandedWidth = false, fold = null, regionStartDp = 20f))
        assertFalse(canShowMarginPane(900f, 443f, expandedWidth = true, fold = vertical(450f), regionStartDp = 20f))
        assertFalse(canShowMarginPane(1000f, 700f, expandedWidth = true, fold = tabletop, regionStartDp = 20f))
        assertFalse(canShowMarginPane(500f, 900f, expandedWidth = false, fold = vertical(260f), regionStartDp = 20f))
    }
}
