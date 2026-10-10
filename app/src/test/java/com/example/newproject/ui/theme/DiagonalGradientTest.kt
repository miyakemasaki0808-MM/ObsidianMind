package com.example.newproject.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import org.junit.Assert.assertEquals
import org.junit.Test

// 背景のグラデーションの向き（左下から右上）を1箇所で決めていること（→ diagonalGradientOver）。
class DiagonalGradientTest {

    /** 画面の地は、どの要素に敷いても要素いっぱいに左下から右上へ流れる（端点を描く大きさに任せる）。 */
    @Test
    fun `画面の地は、端点を描く大きさに任せて左下から右上へ流れる`() {
        listOf(LightAppColors, DarkAppColors).forEach { colors ->
            assertEquals(diagonal(colors.readingGradientStops, Offset(0f, INF), Offset(INF, 0f)), colors.readingGradient)
            assertEquals(diagonal(colors.appGradientStops, Offset(0f, INF), Offset(INF, 0f)), colors.appGradient)
        }
    }

    /** 有限の矩形を渡せば、その左下から右上へ流れる。画面の地と同じ向き。 */
    @Test
    fun `有限の矩形を渡すと、その左下から右上へ流れる`() {
        val stops = LightAppColors.readingGradientStops
        assertEquals(
            diagonal(stops, Offset(-54f, 696f), Offset(906f, -24f)),
            diagonalGradientOver(stops, Rect(left = -54f, top = -24f, right = 906f, bottom = 696f))
        )
    }

    private fun diagonal(stops: List<androidx.compose.ui.graphics.Color>, start: Offset, end: Offset): Brush =
        Brush.linearGradient(colors = stops, start = start, end = end)

    private companion object {
        const val INF = Float.POSITIVE_INFINITY
    }
}
