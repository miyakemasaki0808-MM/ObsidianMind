package com.example.newproject.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 冊子の紙の地色（分野）を固定する。
 *
 * **見ているのは値ではなく関係である**（→ `docs/dev/features/note_field_color.md` 判断15）。
 * 6色は OKLch で明度と彩度を固定し色相だけを等間隔にずらして作ったので、
 * 検査もその規則そのもの — **明度が揃っていること**と**色相が等間隔であること**を見る。
 * 個々の値を並べて突き合わせると、規則を破った差し替えでも「値を直したから」で通ってしまう。
 *
 * **コントラストの保証はここではない。** WCAG の比は相対輝度から計算する別の量なので、
 * 規則を満たしても基準を満たすとは限らない。**面としての合否は下の1件が見る。**
 */
class NoteFieldPaletteTest {

    // -----------------------------------------------------------------------
    // 面として成立しているか
    // -----------------------------------------------------------------------

    /**
     * **冊子の紙に載る文字が、6色すべての上で読める。**
     *
     * 載るのは `onSurface`（代表文・終端の文）・`onSurfaceMuted`（タイトル）・
     * `errorText`（開けなかったページ）の3つだけ。**本文の紙と面が違うので、
     * 最も弱い文字トークンはここへ来ない** — だから `panelChip` の床を流用していない。
     * 流用すると彩度が取れず、色相が sRGB の外で潰れる。
     */
    @Test
    fun `冊子の紙に載る文字は分野の6色すべてでAA基準を満たす`() {
        val cases = listOf(
            "ライト" to LightAppColors,
            "ダーク" to DarkAppColors
        )
        val violations = cases.flatMap { (theme, scheme) ->
            val tokens = listOf(
                "onSurface" to scheme.onSurface,
                "onSurfaceMuted" to scheme.onSurfaceMuted,
                "errorText" to scheme.errorText
            )
            val papers = scheme.noteField.fieldColors + scheme.noteField.neutral
            tokens.flatMap { (name, fg) ->
                papers.mapNotNull { paper ->
                    val ratio = contrastRatio(fg, paper)
                    if (ratio >= 4.5) null else "$theme: $name が %s の上で %.2f".format(paper.hex(), ratio)
                }
            }
        }
        assertTrue("冊子の紙で基準を割る組があります:\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    /**
     * **未判定と「該当なし」の紙は、分野色が入る前と同じ見た目である。**
     *
     * ここが `panel` からずれると、**機能をオフにしたときと有効なときで
     * 何も分類されていない紙の見た目が変わる。**
     */
    @Test
    fun `未判定の紙はパネル色と一致する`() {
        assertEquals(LightAppColors.panel, LightAppColors.noteField.neutral)
        assertEquals(DarkAppColors.panel, DarkAppColors.noteField.neutral)
    }

    /**
     * **ダークでは紙が背景から浮く。**
     *
     * 既存の「ダークではパネルが背景から浮いている」という関係を、分野色でも崩さない。
     * 6色のどれかが `panel` より暗いと、その分野のページだけ沈んで見える。
     */
    @Test
    fun `ダークの分野色はパネルより明るい`() {
        val panel = relativeLuminance(DarkAppColors.panel)
        val sunk = DarkAppColors.noteField.fieldColors
            .filter { relativeLuminance(it) <= panel }
            .map { it.hex() }
        assertTrue("ダークで背景へ沈む分野色があります: ${sunk.joinToString()}", sunk.isEmpty())
    }

    // -----------------------------------------------------------------------
    // 規則そのもの
    // -----------------------------------------------------------------------

    /**
     * **6色の明度が揃っている。**
     *
     * 揃っていないと、色ごとに紙の明るさが変わって**文字の読みやすさが分野で変わる**。
     * 1色だけ手で調整した、という差し替えはここで落ちる。
     */
    @Test
    fun `分野の6色は明度が揃っている`() {
        listOf("ライト" to LightAppColors, "ダーク" to DarkAppColors).forEach { (theme, scheme) ->
            val lightness = scheme.noteField.fieldColors.map { oklabLightness(it) }
            val spread = lightness.max() - lightness.min()
            assertTrue(
                "$theme の分野色で明度が揃っていません（幅 %.4f）".format(spread),
                spread <= 0.010
            )
        }
    }

    /**
     * **色相が等間隔に置かれている。**
     *
     * 6色なので隣どうしは60°。**丸めと sRGB の丸め込みでずれる**ので厳密には見ないが、
     * 「等間隔に置いた」と言える範囲に収める。**1色を好みで動かすと、
     * その両隣の間隔が同時に崩れる**ので、この1件で捕まる。
     */
    @Test
    fun `分野の色相は等間隔に置かれている`() {
        listOf("ライト" to LightAppColors, "ダーク" to DarkAppColors).forEach { (theme, scheme) ->
            val hues = scheme.noteField.fieldColors.map { oklabHue(it) }
            val gaps = hues.indices.map { i ->
                val next = hues[(i + 1) % hues.size]
                ((next - hues[i]) % 360.0 + 360.0) % 360.0
            }
            val worst = gaps.maxOf { abs(it - 60.0) }
            assertTrue(
                "$theme の色相が等間隔から外れています（最大ずれ %.1f°: %s）"
                    .format(worst, gaps.joinToString { "%.1f".format(it) }),
                worst <= 3.0
            )
        }
    }

    // -----------------------------------------------------------------------
    // 計算
    // -----------------------------------------------------------------------

    private fun Color.hex(): String =
        "#%02X%02X%02X".format((red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt())

    private fun linear(v: Float): Double {
        val d = v.toDouble()
        return if (d <= 0.04045) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
    }

    private fun relativeLuminance(color: Color): Double =
        0.2126 * linear(color.red) + 0.7152 * linear(color.green) + 0.0722 * linear(color.blue)

    private fun contrastRatio(a: Color, b: Color): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** sRGB → OKLab。**規則を確かめるためだけに置く**（本番は色空間の計算を持たない → 判断15）。 */
    private fun oklab(color: Color): Triple<Double, Double, Double> {
        val r = linear(color.red)
        val g = linear(color.green)
        val b = linear(color.blue)
        val l = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        val m = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        val s = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        return Triple(
            0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s,
            1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s,
            0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s
        )
    }

    private fun oklabLightness(color: Color): Double = oklab(color).first

    private fun oklabHue(color: Color): Double {
        val (_, a, b) = oklab(color)
        return (Math.toDegrees(atan2(b, a)) % 360.0 + 360.0) % 360.0
    }
}
