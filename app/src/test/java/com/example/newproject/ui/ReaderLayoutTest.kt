package com.example.newproject.ui

import com.example.newproject.ui.screen.ReaderFold
import com.example.newproject.ui.screen.ReaderLayout
import com.example.newproject.ui.screen.SPREAD_MAX_BLOCK_CHARS
import com.example.newproject.ui.screen.SpreadFit
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

    /**
     * **ペインが出ている間はレールを畳む**（→ features/margin_pane.md §5.1）。同じ Pixel 10 Pro Fold の値で、
     * 畳む前は本文が約318dp・ペインが約398dp、畳むと本文が左へ広がって両方約398dpになる。
     * **畳んでもペインの幅は減らない** — 減ると、畳んだせいでペインが出せなくなり、レールが戻ってまた畳む、を繰り返す。
     */
    @Test
    fun `レールを畳むと本文が左へ広がり、ペインの幅は変わらない`() {
        val foldDp = 1038f / 2.4375f
        val windowDp = 852f
        val padding = 20f
        val rail = 80f

        val withRail = readerLayoutFor(windowDp - rail - 2 * padding, 800f, paneOpen = true, expandedWidth = true, fold = vertical(foldDp), regionStartDp = rail + padding)
        val folded = readerLayoutFor(windowDp - 2 * padding, 800f, paneOpen = true, expandedWidth = true, fold = vertical(foldDp), regionStartDp = padding)

        assertTrue("レールがあるときに折り目で割れていません: $withRail", withRail is ReaderLayout.MarginPane)
        assertTrue("レールを畳むと折り目で割れません: $folded", folded is ReaderLayout.MarginPane)
        val before = withRail as ReaderLayout.MarginPane
        val after = folded as ReaderLayout.MarginPane
        val paneBefore = windowDp - rail - 2 * padding - before.bodyWidthDp - before.gutterDp
        val paneAfter = windowDp - 2 * padding - after.bodyWidthDp - after.gutterDp
        assertEquals(before.bodyWidthDp + rail, after.bodyWidthDp, 0.01f)
        assertEquals(paneBefore, paneAfter, 0.01f)
        assertTrue("畳んでも本文がペインより細い: 本文 ${after.bodyWidthDp} / ペイン $paneAfter", after.bodyWidthDp >= paneAfter - 2f)
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

    // ── 本文の見開き（→ features/note_spread.md §5「入る条件」）────────────────

    private val note = SpreadFit(fontScale = 1f, maxBlockChars = 500)

    /** 平らに開いた本の形でレールを畳んだ窓。本文領域は窓の20dpから812dp、折り目は窓の426dp。 */
    @Test
    fun `本の形でペインを閉じていれば、折り目をはさんで同じ幅の2ページに組む`() {
        assertEquals(
            ReaderLayout.Spread(pageStartDp = 0f, pageWidthDp = 398f, gutterDp = 16f),
            readerLayoutFor(812f, 820f, paneOpen = false, expandedWidth = true, fold = vertical(426f), regionStartDp = 20f, spread = note)
        )
    }

    /**
     * レールが出ている間（畳む前）は左のページが狭いので、2ページを狭いほうにそろえる。
     * **入れるかは右のページで決める** — 左で決めると、畳む前は入らず、入らないので畳まない、を繰り返す。
     */
    @Test
    fun `レールが出ている間は狭いほうにそろえ、入れるかは右のページで決める`() {
        assertEquals(
            ReaderLayout.Spread(pageStartDp = 0f, pageWidthDp = 318f, gutterDp = 16f),
            readerLayoutFor(732f, 820f, paneOpen = false, expandedWidth = true, fold = vertical(426f), regionStartDp = 100f, spread = note)
        )
    }

    /** 同じ窓で文字の倍率だけを上げる。1行の字数が16字を割ると縦積み（→ 判断9）。 */
    @Test
    fun `同じ窓で文字の倍率を上げて1行の字数が下限を割ると縦積み`() {
        fun at(scale: Float) = readerLayoutFor(812f, 820f, fold = vertical(426f), regionStartDp = 20f, spread = note.copy(fontScale = scale))
        assertTrue(at(1.3f) is ReaderLayout.Spread)
        assertEquals(ReaderLayout.Stacked, at(1.5f))
    }

    @Test
    fun `巨大なブロックのあるノート・ノートが無い・卓上の形・折り目の無い窓は縦積み`() {
        assertEquals(
            ReaderLayout.Stacked,
            readerLayoutFor(812f, 820f, fold = vertical(426f), regionStartDp = 20f, spread = note.copy(maxBlockChars = SPREAD_MAX_BLOCK_CHARS + 1))
        )
        assertEquals(ReaderLayout.Stacked, readerLayoutFor(812f, 820f, fold = vertical(426f), regionStartDp = 20f, spread = null))
        assertEquals(ReaderLayout.Stacked, readerLayoutFor(812f, 820f, fold = tabletop, regionStartDp = 20f, spread = note))
        assertEquals(ReaderLayout.Stacked, readerLayoutFor(1280f, 800f, expandedWidth = true, spread = note))
    }

    @Test
    fun `ペインを開けば机、高さの足りない横長は左右2列のまま`() {
        assertTrue(readerLayoutFor(812f, 820f, paneOpen = true, fold = vertical(426f), regionStartDp = 20f, spread = note) is ReaderLayout.MarginPane)
        assertEquals(ReaderLayout.SideBySide, readerLayoutFor(970f, 443f, spread = note))
    }
}
