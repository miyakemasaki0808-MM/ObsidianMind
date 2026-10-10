package com.example.newproject.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.example.newproject.ui.screen.deskBackdropInBar
import org.junit.Assert.assertEquals
import org.junit.Test

// 机の画面の帯の地が、後ろの背景の続きになること（→ features/margin_pane.md §5.1）。
// 地は左下から右上へ流れるグラデーションで、窓の同じ点は帯の中でも地の上でも同じ位置（同じ色）にならなければならない。
class DeskBackdropTest {

    /** 地の箱が窓の左上にあり、帯が箱の中の左上寄りにあるとき。 */
    @Test
    fun `帯の中の点は、同じ窓の点の地と同じ位置の色になる`() {
        assertSameColorsAcrossBar(backdropOrigin = Offset.Zero, backdropSize = Size(960f, 720f), barOrigin = Offset(36f, 60f), barSize = Size(440f, 120f))
    }

    /**
     * 地の箱が窓からはみ出して左にずれ、帯が降りる途中で箱の上の縁より上にあるとき。
     * 帯の位置を箱の左上に取り違えると、ここで色がずれる。
     */
    @Test
    fun `地の箱が窓からずれ、帯が降りる途中でも同じ色になる`() {
        assertSameColorsAcrossBar(backdropOrigin = Offset(-54f, 24f), backdropSize = Size(960f, 720f), barOrigin = Offset(-38f, -40f), barSize = Size(440f, 120f))
    }

    private fun assertSameColorsAcrossBar(backdropOrigin: Offset, backdropSize: Size, barOrigin: Offset, barSize: Size) {
        val inBar = deskBackdropInBar(backdropOrigin, backdropSize, barOrigin)
        // 地そのものは、自分の箱いっぱいに流れる（端点を描く大きさに任せた、画面の地）。
        val onBackdrop = Rect(Offset.Zero, backdropSize)
        listOf(0f, 0.25f, 0.5f, 1f).forEach { fx ->
            listOf(0f, 0.5f, 1f).forEach { fy ->
                val window = barOrigin + Offset(barSize.width * fx, barSize.height * fy)
                assertEquals(
                    "窓の点 $window の色の位置が、帯と地で違う",
                    fractionAlongDiagonal(onBackdrop, window - backdropOrigin),
                    fractionAlongDiagonal(inBar, window - barOrigin),
                    1e-4f
                )
            }
        }
    }

    /** 左下から右上へ流れるグラデーションの上で、[point] が何割の位置にあるか（0 が左下、1 が右上）。 */
    private fun fractionAlongDiagonal(area: Rect, point: Offset): Float {
        val start = area.bottomLeft
        val dir = area.topRight - start
        val rel = point - start
        return (rel.x * dir.x + rel.y * dir.y) / (dir.x * dir.x + dir.y * dir.y)
    }
}
