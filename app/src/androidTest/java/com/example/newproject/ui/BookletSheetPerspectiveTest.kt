package com.example.newproject.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.newproject.ui.screen.CAMERA_DISTANCE_FACTOR
import com.example.newproject.ui.screen.PeelShape
import com.example.newproject.ui.screen.SHEET_HINGE
import com.example.newproject.ui.screen.sheetCameraDistance
import com.example.newproject.ui.screen.sheetFitScale
import com.example.newproject.ui.screen.sheetTiltDegrees
import kotlin.math.PI
import kotlin.math.absoluteValue
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **描いた画素でしか確かめられないことだけを、ここで見る。**
 *
 * ## なぜ値の検査では足りないのか
 *
 * `BookletTurnGeometryTest` は「角度が正であること」まで固定できる。
 * **しかし「+Z が画面の手前である」は値のどこにも現れない。**
 * 前の版はそこを逆に思い込み、KDocと検査へ同じ形で書いた。**27件は全部通り、実機でだけ紙が奥へ倒れていた**
 * （2026-09-05 の実機レビュー `P2-1`）。**前提を共有した検査は、前提ごと間違える**
 * （→ docs/dev/lessons.md L61）。
 *
 * ## 何を見るか
 *
 * - **向き:** 傾いた紙は下端が手前へ出るので、**下端のほうが広い**
 * - **枠:** 手前へ出た紙は広がるので、**縮小を掛けないと静止時の幅を越えて画面の外へ出る。**
 *   縮小率が正しくても、掛かっていなければ画面でだけはみ出す
 * - **カメラ距離の単位:** 渡した値が何画素の遠さになるかは、**KDocにも他所の実装にも書いていない**
 *   （2度取り違えた → L60）。倒した面の投影された縦の長さから逆算する
 * - **めくり:** 折り目の向こうが本当に切り落とされ、**めくれた角が空いて裏が現れる**こと
 */
@RunWith(AndroidJUnit4::class)
class BookletSheetPerspectiveTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ── 傾きの向きと、枠へ収まること ──────────────────────────────────────

    /**
     * **これが `P2-1` の受け入れ条件。** 積み直りで傾いた紙は、下端が手前へ出る。
     *
     * 逆にすると下端が細くなり、**天綴じの紙が束の中へ沈む**向きになる。
     */
    @Test
    fun 傾いた紙は下端が手前へ広がる() {
        showSheet(restack = 0f)

        val (top, bottom) = sheetWidthsAtEnds()

        assertTrue(
            "傾いた紙の下端（$bottom px）が上端（$top px）より広がっていません。" +
                "下端が奥へ退いており、天綴じの紙が束の中へ沈む向きになっています。",
            bottom > top * 1.02f
        )
    }

    /**
     * **計測そのものが正しいことを、寝た紙で確かめる。**
     *
     * 上の検査は「下端が広い」としか言わないので、**幅の数え方が壊れていても偶然通りうる。**
     */
    @Test
    fun 寝ている紙は上端と下端が同じ幅() {
        showSheet(restack = 1f)

        val (top, bottom) = sheetWidthsAtEnds()

        assertTrue(
            "傾いていない紙の幅が上端（$top px）と下端（$bottom px）で違います。幅の計測が壊れています。",
            isSameWidth(top, bottom)
        )
    }

    /**
     * **基準点の判定そのものを確かめる。**
     *
     * 当初は相対差を**符号つき**で見ていたので、**下端が細くなる側を素通ししていた**
     * （外部レビュー `P3-1`）。**片側しか落とさない判定は、片側の誤描画を永久に見逃す。**
     */
    @Test
    fun 幅一致の判定は縮む側も広がる側も落とす() {
        assertTrue("同じ幅を一致と見なせていません。", isSameWidth(top = 100, bottom = 100))
        assertFalse("下端が細い側を素通ししています。", isSameWidth(top = 100, bottom = 80))
        assertFalse("下端が太い側を素通ししています。", isSameWidth(top = 100, bottom = 120))
    }

    /**
     * **傾いた紙が、静止時の紙より横へ広がらないこと。**
     *
     * 縮小率そのものは値の検査が押さえている。**しかし「掛かっているか」「掛ける場所が正しいか」は
     * 描いた画素にしか出ない。** 縮小が無かった版では紙が画面端で切れ、
     * **拡大した紙面が表示域を覆って次の紙を確認できなかった**（2026-09-05 の実機）。
     */
    @Test
    fun 傾いた紙は静止時の幅を越えない() {
        showSheet(restack = 1f)
        val resting = widestRow()

        val outside = RESTACKS.map { restack ->
            showSheet(restack = restack)
            restack to widestRow()
        }.filterNot { (_, width) -> isWithinResting(resting, width) }

        assertTrue(
            "傾いた紙の幅が静止時（$resting px）と違います: " +
                outside.joinToString { (restack, width) -> "積み直り$restack→$width px" } +
                "。広い側は画面の外へ出ており、狭い側は縮めすぎです。",
            outside.isEmpty()
        )
    }

    // ── カメラ距離の単位 ──────────────────────────────────────────────────

    /**
     * **`cameraDistance` に渡した値が、画面で何画素ぶんの遠さになるか。**
     *
     * この値の単位は**KDocにも実装にも素直には書いていない。** 2026-09-05 に一度
     * 「インチ相当」と結論したが、それは `View` 側の換算を読んだもので、
     * **Compose の `ViewLayer` はその換算を打ち消している**（→ docs/dev/lessons.md L60）。
     *
     * **だから、倒した面の投影された縦の長さから実効距離を逆算する。**
     * 縦の長さは行数なので、幅と違って端の補間にほとんど影響されない。
     */
    @Test
    fun カメラ距離は紙の高さの指定倍になっている() {
        showPlainSheet(rotationDegrees = PROBE_DEGREES)

        val heightPx = PROBE_HEIGHT * composeRule.density.density
        val radians = PROBE_DEGREES * PI / 180.0
        val reach = drawnRowRange().size.toFloat()
        val flat = heightPx * cos(radians).toFloat()
        val spread = reach / flat
        val distance = heightPx * sin(radians).toFloat() * spread / (spread - 1f)

        assertTrue(
            "倒した面が枠の中に収まっていません（縦 $reach px）。頭打ちになると逆算が狂います。",
            reach < FRAME_HEIGHT * composeRule.density.density * 0.9f
        )
        assertEquals(
            "実効カメラ距離が紙の高さの ${distance / heightPx} 倍です（投影された縦の長さ $reach px から逆算）。" +
                "設計は $CAMERA_DISTANCE_FACTOR 倍で、**近すぎれば倒した紙がカメラの裏へ回って画面を覆い、" +
                "遠すぎれば遠近が消えて縦に潰れた板になります。**",
            CAMERA_DISTANCE_FACTOR,
            distance / heightPx,
            0.15f
        )
    }

    // ── めくり ────────────────────────────────────────────────────────────

    /**
     * **静止時の紙は欠けない。** 判断9 で実機確認した佇まいは、めくりの外にある。
     */
    @Test
    fun 静止時の紙は欠けない() {
        showSheet(turn = 0f)

        assertTrue("静止時にめくれた角が出ています。", backPixels() == 0)
        assertTrue("静止時の紙が欠けています。", groundInsideSheet() == 0)
    }

    /**
     * **これがめくりの受け入れ条件。右下の角が空き、そこに次の紙（ここでは地）が見える。**
     *
     * 折り目で切っていなければ角は空かない。**形は値として正しくても、
     * `clip` を落とせば画面では紙が丸ごと残る** — そこは画素でしか分からない。
     */
    @Test
    fun めくると右下の角が空く() {
        showSheet(turn = 0.3f)

        assertTrue(
            "めくったのに右下の角が空いていません。折り目で切れていないか、" +
                "折り返した紙が角を塞いでいます。",
            groundAtBottomRight() > 0
        )
    }

    /**
     * **めくれた角は裏返って現れる。** 文字の載らない紙の面が、折り目の向こうへ倒れる。
     *
     * 出ていなければ、紙は消えただけで**めくれていない。**
     */
    @Test
    fun めくれた角は裏返って現れる() {
        showSheet(turn = 0.3f)

        assertTrue("めくれた角（裏）が1画素も描かれていません。", backPixels() > 0)
    }

    // ── 補助 ──────────────────────────────────────────────────────────────

    /**
     * 静止時の幅から外れていないか。**広い側だけを厳しく見る。**
     *
     * 広がる側は**そのまま画面の外**なので許さない。狭い側は、傾いた紙の下端の行が
     * 補間で数え落とされるぶんだけ細く出るので、**測り方の誤差として許す。**
     */
    private fun isWithinResting(resting: Int, width: Int): Boolean =
        width <= resting * (1f + SAME_WIDTH_TOLERANCE) && width >= resting * (1f - FIT_SHRINK_TOLERANCE)

    /** 上端と下端が同じ幅か。**両側へ同じだけ許す**（片側だけ緩くすると `P3-1` に戻る）。 */
    private fun isSameWidth(top: Int, bottom: Int): Boolean =
        ((bottom - top).toFloat() / top).absoluteValue < SAME_WIDTH_TOLERANCE

    /**
     * 本番と同じ傾き・蝶番・カメラ距離・縮小・折り目で紙を出す。
     *
     * **`setContent` は1度だけ。** 1つの検査で送りを何度も動かすので、状態で持つ。
     */
    private fun showSheet(turn: Float = 0f, restack: Float = 1f) {
        compose()
        composeRule.runOnUiThread {
            plainState.floatValue = 0f
            turnState.floatValue = turn
            restackState.floatValue = restack
        }
        composeRule.waitForIdle()
    }

    /** 素の面をその角度だけ倒す。**縮小も折り目も通さない。** */
    private fun showPlainSheet(rotationDegrees: Float) {
        compose()
        composeRule.runOnUiThread { plainState.floatValue = rotationDegrees }
        composeRule.waitForIdle()
    }

    private fun compose() {
        if (composed) return
        composed = true
        composeRule.setContent {
            Box(
                modifier = Modifier
                    .testTag(FRAME)
                    .size(width = FRAME_WIDTH.dp, height = FRAME_HEIGHT.dp)
                    .background(GROUND),
                contentAlignment = Alignment.TopCenter
            ) {
                if (plainState.floatValue > 0f) {
                    // **素の面。** 見るのはカメラ距離の効き方だけ。
                    Box(
                        modifier = Modifier
                            .size(width = PROBE_WIDTH.dp, height = PROBE_HEIGHT.dp)
                            .graphicsLayer {
                                rotationX = plainState.floatValue
                                transformOrigin = SHEET_HINGE
                                cameraDistance = sheetCameraDistance(size.height)
                            }
                            .background(SHEET)
                    )
                    return@Box
                }
                Box(
                    modifier = Modifier
                        .size(width = SHEET_WIDTH.dp, height = SHEET_HEIGHT.dp)
                        // **枠へ収める縮小は回転より外側**（→ 本番の `BookletSheet` と同じ順序）。
                        .graphicsLayer {
                            val fit = sheetFitScale(sheetTiltDegrees(restackState.floatValue))
                            scaleX = fit
                            scaleY = fit
                            transformOrigin = SHEET_HINGE
                        }
                        .graphicsLayer {
                            rotationX = sheetTiltDegrees(restackState.floatValue)
                            transformOrigin = SHEET_HINGE
                            cameraDistance = sheetCameraDistance(size.height)
                        }
                ) {
                    // **紙の表。** 本番と同じ形で切る。
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                shape = PeelShape(peel = turnState.floatValue, flap = false)
                                clip = true
                            }
                            .background(SHEET)
                    )
                    // **めくれて裏返った角。** 表とは別の色にして、画素で見分ける。
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                shape = PeelShape(peel = turnState.floatValue, flap = true)
                                clip = true
                            }
                            .background(BACK)
                    )
                }
            }
        }
    }

    /** 紙が写っている行のうち、**最も広い行**の幅（画素数）。 */
    private fun widestRow(): Int = sheetRowWidths().max()

    /**
     * 紙が写っている行のうち、**上端寄りと下端寄りの幅**（画素数）を返す。
     *
     * 端そのものは補間で色が混ざるので、**5%内側の行**を採る。
     */
    private fun sheetWidthsAtEnds(): Pair<Int, Int> {
        val widths = sheetRowWidths()

        return widths[widths.size / 20] to widths[widths.size - 1 - widths.size / 20]
    }

    /** 紙が最初に写る行から最後に写る行まで、**空の行も含めて**幅を返す。 */
    private fun drawnRowRange(): List<Int> {
        val widths = rowWidths()
        val first = widths.indexOfFirst { it > 0 }
        val last = widths.indexOfLast { it > 0 }

        require(first >= 0) { "紙が1画素も描かれていません。" }

        return widths.subList(first, last + 1)
    }

    /** 紙が写っている行の幅（画素数）を、上から順に返す。 */
    private fun sheetRowWidths(): List<Int> {
        val widths = rowWidths().filter { it > 0 }

        require(widths.isNotEmpty()) { "紙が1画素も描かれていません。" }

        return widths
    }

    /**
     * 枠の全行について、**地ではない画素**を数える。
     *
     * **紙の色では数えない。** 裏を向いた角は別の面として描かれるので、
     * 表の色だけを数えると**いちばん手前の部分が抜け落ちる。**
     */
    private fun rowWidths(): List<Int> = pixels().let { map ->
        (0 until map.height).map { y ->
            (0 until map.width).count { x -> map[x, y] != GROUND }
        }
    }

    /** めくれて裏返った角の画素数。 */
    private fun backPixels(): Int = pixels().let { map ->
        var count = 0
        (0 until map.height).forEach { y ->
            (0 until map.width).forEach { x -> if (map[x, y] == BACK) count++ }
        }
        count
    }

    /** 紙が置かれているはずの矩形の中に、地が見えている画素数。 */
    private fun groundInsideSheet(): Int = countGround(fromFraction = 0.05f, toFraction = 0.95f)

    /** 紙の**右下**に地が見えている画素数（めくれて空いたところ）。 */
    private fun groundAtBottomRight(): Int = countGround(fromFraction = 0.75f, toFraction = 0.95f)

    private fun countGround(fromFraction: Float, toFraction: Float): Int {
        val map = pixels()
        val scale = composeRule.density.density
        val left = ((FRAME_WIDTH - SHEET_WIDTH) / 2f * scale).toInt()
        val width = (SHEET_WIDTH * scale).toInt()
        val height = (SHEET_HEIGHT * scale).toInt()

        var count = 0
        ((height * fromFraction).toInt() until (height * toFraction).toInt()).forEach { y ->
            ((width * fromFraction).toInt() until (width * toFraction).toInt()).forEach { x ->
                if (map[left + x, y] == GROUND) count++
            }
        }
        return count
    }

    private fun pixels() = composeRule.onNodeWithTag(FRAME).captureToImage().toPixelMap()

    private var composed = false
    private val turnState = mutableFloatStateOf(0f)
    private val restackState = mutableFloatStateOf(1f)

    /** 0 なら紙、正なら「素の面をその角度だけ倒す」（カメラ距離の実測用）。 */
    private val plainState = mutableFloatStateOf(0f)

    private companion object {
        const val FRAME = "perspective-frame"
        const val SAME_WIDTH_TOLERANCE = 0.02f

        /** 傾いた紙の下端の行が補間で数え落とされるぶん。**広がる側には使わない。** */
        const val FIT_SHRINK_TOLERANCE = 0.10f

        /** 枠と紙の寸法（dp）。 */
        const val FRAME_WIDTH = 300
        const val FRAME_HEIGHT = 400
        const val SHEET_WIDTH = 120
        const val SHEET_HEIGHT = 260

        /** 幅を見る積み直りの位置。**0 が傾き最大、1 が水平。** */
        val RESTACKS = listOf(0f, 0.2f, 0.4f, 0.6f, 0.8f)

        /**
         * カメラ距離を逆算する素の面（dp）。**枠に対して十分小さく取る** —
         * 倒した面が枠の端で切れると測定が頭打ちになり、**逆算が静かに狂う。**
         */
        const val PROBE_WIDTH = 40
        const val PROBE_HEIGHT = 160

        /** 逆算に使う傾き。**枠で切れない範囲で、差がはっきり出る角度。** */
        const val PROBE_DEGREES = 10f

        /** 地。**紙の表とも裏とも似ない色にする。** */
        val GROUND = Color(0xFF00FF00)
        val SHEET = Color(0xFF0000FF)

        /** めくれて裏返った角。**表と見分けるために別の色**（本番では紙の面の色）。 */
        val BACK = Color(0xFFFF0000)
    }
}
