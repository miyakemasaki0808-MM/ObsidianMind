package com.example.newproject.ui

import com.example.newproject.ui.screen.ReaderLayout
import com.example.newproject.ui.screen.readerLayoutFor
import com.example.newproject.ui.screen.sideColumnWidthDp
import org.junit.Assert.assertEquals
import org.junit.Test

// ノート画面の並べ方（→ features/rediscover.md 判断6）。縦の見た目を変えないことも同じ表で見る。
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
}
