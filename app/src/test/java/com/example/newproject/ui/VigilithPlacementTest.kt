package com.example.newproject.ui

import com.example.newproject.ui.vigilith.VigilithPlacement
import com.example.newproject.ui.vigilith.VigilithPlacementOffset
import com.example.newproject.ui.vigilith.calculateVigilithBottomReserved
import com.example.newproject.ui.vigilith.calculateVigilithPlacementBounds
import com.example.newproject.ui.vigilith.moveVigilithPlacement
import com.example.newproject.ui.vigilith.resolveVigilithPlacement
import org.junit.Assert.assertEquals
import org.junit.Test

class VigilithPlacementTest {

    @Test
    fun `初期位置は予約領域を避けた右下になる`() {
        val bounds = bounds()

        val offset = resolveVigilithPlacement(VigilithPlacement(), bounds)

        assertEquals(304f, offset.x)
        assertEquals(491f, offset.y)
    }

    @Test
    fun `四辺を越えるドラッグは画面内にclampされる`() {
        val bounds = bounds()

        val topLeft = moveVigilithPlacement(
            placement = VigilithPlacement(),
            deltaX = -10_000f,
            deltaY = -10_000f,
            bounds = bounds
        )
        val bottomRight = moveVigilithPlacement(
            placement = topLeft,
            deltaX = 10_000f,
            deltaY = 10_000f,
            bounds = bounds
        )

        assertEquals(VigilithPlacement(0f, 0f), topLeft)
        assertEquals(VigilithPlacement(1f, 1f), bottomRight)
    }

    @Test
    fun `Fold展開後も配置可能領域に対する相対位置を維持する`() {
        val compactBounds = bounds()
        val placement = VigilithPlacement(horizontalFraction = 0.25f, verticalFraction = 0.6f)
        val compact = resolveVigilithPlacement(placement, compactBounds)

        val expandedBounds = calculateVigilithPlacementBounds(
            viewportWidthPx = 900f,
            viewportHeightPx = 700f,
            contentWidthPx = 100f,
            contentHeightPx = 140f,
            startReservedPx = 80f,
            topReservedPx = 24f,
            endReservedPx = 0f,
            bottomReservedPx = 24f,
            edgeMarginPx = 16f
        )
        val expanded = resolveVigilithPlacement(placement, expandedBounds)

        assertEquals(88f, compact.x)
        assertEquals(310.6f, compact.y, 0.01f)
        assertEquals(268f, expanded.x)
        assertEquals(328f, expanded.y)
    }

    @Test
    fun `状態ラベルで幅が増えても右端を越えない`() {
        val bounds = calculateVigilithPlacementBounds(
            viewportWidthPx = 360f,
            viewportHeightPx = 720f,
            contentWidthPx = 260f,
            contentHeightPx = 140f,
            startReservedPx = 0f,
            topReservedPx = 24f,
            endReservedPx = 0f,
            bottomReservedPx = 96f,
            edgeMarginPx = 16f
        )

        val offset = resolveVigilithPlacement(VigilithPlacement(), bounds)

        assertEquals(84f, offset.x)
        assertEquals(468f, offset.y)
        assertEquals(344f, offset.x + 260f)
    }

    @Test
    fun `SnackbarやIMEの予約領域が増えるとVigilithはその上へ退避する`() {
        val normalReserved = bottomReserved(snackbarVisible = false, imeBottomPx = 24f)
        val snackbarReserved = bottomReserved(snackbarVisible = true, imeBottomPx = 24f)
        val imeReserved = bottomReserved(snackbarVisible = true, imeBottomPx = 284f)
        val normal = bounds(bottomReservedPx = normalReserved)
        val snackbar = bounds(bottomReservedPx = snackbarReserved)
        val ime = bounds(bottomReservedPx = imeReserved)

        assertEquals(96f, normalReserved)
        assertEquals(168f, snackbarReserved)
        // IMEとSnackbarは加算せず、大きい方（284 + 16）を採る。
        assertEquals(300f, imeReserved)
        assertEquals(491f, resolveVigilithPlacement(VigilithPlacement(), normal).y)
        assertEquals(419f, resolveVigilithPlacement(VigilithPlacement(), snackbar).y)
        assertEquals(287f, resolveVigilithPlacement(VigilithPlacement(), ime).y)
    }

    @Test
    fun `表示領域より内容が大きい場合も不正な範囲を作らない`() {
        val bounds = calculateVigilithPlacementBounds(
            viewportWidthPx = 120f,
            viewportHeightPx = 120f,
            contentWidthPx = 260f,
            contentHeightPx = 220f,
            startReservedPx = 20f,
            topReservedPx = 20f,
            endReservedPx = 20f,
            bottomReservedPx = 20f,
            edgeMarginPx = 16f
        )

        assertEquals(bounds.minX, bounds.maxX)
        assertEquals(bounds.minY, bounds.maxY)
        assertEquals(
            VigilithPlacementOffset(36f, 36f),
            resolveVigilithPlacement(VigilithPlacement(), bounds)
        )
    }

    private fun bounds(bottomReservedPx: Float = 96f) = calculateVigilithPlacementBounds(
        viewportWidthPx = 400f,
        viewportHeightPx = 740f,
        contentWidthPx = 80f,
        contentHeightPx = 137f,
        startReservedPx = 0f,
        topReservedPx = 24f,
        endReservedPx = 0f,
        bottomReservedPx = bottomReservedPx,
        edgeMarginPx = 16f
    )

    private fun bottomReserved(
        snackbarVisible: Boolean,
        imeBottomPx: Float
    ) = calculateVigilithBottomReserved(
        safeBottomPx = 24f,
        navigationClearancePx = 72f,
        isSnackbarVisible = snackbarVisible,
        snackbarClearancePx = 72f,
        imeBottomPx = imeBottomPx,
        imeMarginPx = 16f
    )
}
