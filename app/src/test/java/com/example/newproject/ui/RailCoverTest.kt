package com.example.newproject.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * レールが覆う幅だけ、始まりの側の Insets を消費する（→ features/margin_pane.md §5.4）。
 * 本文の `safeDrawingPadding()` に残るのは、切り欠きからレールの幅を引いた残り（0未満にならない）。
 * 外殻が測るときに幅を書く経路は `ReaderWindowShellTest`（端末）が見る。
 */
class RailCoverTest {

    private val density = Density(1f)

    @Test
    fun `左から右の並びでは左を、右から左の並びでは右を覆う`() {
        val cover = RailCover().apply { width = 80 }

        assertEquals(80, cover.getLeft(density, LayoutDirection.Ltr))
        assertEquals(0, cover.getRight(density, LayoutDirection.Ltr))
        assertEquals(0, cover.getLeft(density, LayoutDirection.Rtl))
        assertEquals(80, cover.getRight(density, LayoutDirection.Rtl))
        assertEquals(0, cover.getTop(density))
        assertEquals(0, cover.getBottom(density))
    }

    @Test
    fun `レールより浅い切り欠きは残らず、深い切り欠きはレールを越えた分だけ残る`() {
        val cover = RailCover().apply { width = 80 }

        assertEquals(0, WindowInsets(left = 40).exclude(cover).getLeft(density, LayoutDirection.Ltr))
        assertEquals(80, WindowInsets(left = 160).exclude(cover).getLeft(density, LayoutDirection.Ltr))
        assertEquals(80, WindowInsets(right = 160).exclude(cover).getRight(density, LayoutDirection.Rtl))
    }

    @Test
    fun `レールを出していない間は何も消費しない`() {
        val cover = RailCover()

        assertEquals(40, WindowInsets(left = 40).exclude(cover).getLeft(density, LayoutDirection.Ltr))
    }
}
