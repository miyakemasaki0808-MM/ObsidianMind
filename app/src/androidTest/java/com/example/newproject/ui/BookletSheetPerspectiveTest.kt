package com.example.newproject.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.absoluteValue
import com.example.newproject.ui.screen.SHEET_HINGE
import com.example.newproject.ui.screen.sheetAngleDegrees
import com.example.newproject.ui.screen.sheetCameraDistance
import com.example.newproject.ui.screen.sheetFitScale
import com.example.newproject.ui.screen.sheetStanding
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **倒れた紙の下端が、実際に手前へ出ていること。**
 *
 * ## なぜ値の検査では足りないのか
 *
 * `BookletTurnGeometryTest` は「角度が正であること」と「正の角度が蝶番より下の点を +Z へ送ること」まで
 * 固定できる。**しかし「+Z が画面の手前である」は値のどこにも現れない。**
 *
 * 前の版はそこを逆に思い込み、KDocと検査へ同じ形で書いた。**27件は全部通り、実機でだけ紙が奥へ倒れていた**
 * （2026-09-05 の実機レビュー `P2-1`）。**前提を共有した検査は、前提ごと間違える。**
 * だから、ここでだけは**描いた画素を数える**（→ docs/dev/lessons.md L61）。
 *
 * ## 何を見るか
 *
 * 本番と同じ角度・同じ蝶番・同じカメラ距離・同じ縮小で面を回し、**投影された幅を数える。**
 *
 * - **向き:** 天綴じの紙は下端が手前へ出るので、**下端のほうが広い。** 符号を戻すと下端が細くなる
 * - **枠:** 手前へ出た紙は広がるので、**縮小を掛けないと静止時の幅を越えて画面の外へ出る。**
 *   こちらも値には現れない — 縮小率が正しくても、掛かっていなければ画面でだけはみ出す
 *
 * **面は角丸にしない。** 見るのは投影の向きだけで、隅の形は幅の計測を濁らせるだけである
 * （静止時の佇まいは判断9・`BookletScreenTest` が持つ）。
 */
@RunWith(AndroidJUnit4::class)
class BookletSheetPerspectiveTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * **これが `P2-1` の受け入れ条件。** 実機レビューが再現した約10%保持と同じ送り位置で見る。
     */
    @Test
    fun 送り出される紙は下端が手前へ広がる() {
        showSheet(turn = 0.1f)

        val (top, bottom) = sheetWidthsAtEnds()

        assertTrue(
            "倒れた紙の下端（$bottom px）が上端（$top px）より広がっていません。" +
                "下端が奥へ退いており、天綴じの紙が束の中へ沈む向きになっています。",
            bottom > top * 1.05f
        )
    }

    /**
     * **計測そのものが正しいことを、寝た紙で確かめる。**
     *
     * 上の検査は「下端が広い」としか言わないので、**幅の数え方が壊れていても偶然通りうる。**
     * 倒れていない紙なら上端と下端は同じ幅なので、そこを基準点にする。
     */
    @Test
    fun 寝ている紙は上端と下端が同じ幅() {
        showSheet(turn = 0f)

        val (top, bottom) = sheetWidthsAtEnds()

        assertTrue(
            "倒れていない紙の幅が上端（$top px）と下端（$bottom px）で違います。幅の計測が壊れています。",
            isSameWidth(top, bottom)
        )
    }

    /**
     * **基準点の判定そのものを確かめる。**
     *
     * 当初は相対差を**符号つき**で見ていたので、**下端が細くなる側を素通ししていた** —
     * 上端100px・下端80pxでも成功し、下端が1pxでも通った（外部レビュー `P3-1`）。
     * **片側しか落とさない判定は、片側の誤描画を永久に見逃す。**
     */
    @Test
    fun 幅一致の判定は縮む側も広がる側も落とす() {
        assertTrue("同じ幅を一致と見なせていません。", isSameWidth(top = 100, bottom = 100))
        assertFalse("下端が細い側を素通ししています。", isSameWidth(top = 100, bottom = 80))
        assertFalse("下端が太い側を素通ししています。", isSameWidth(top = 100, bottom = 120))
    }

    /**
     * **倒れた紙が、静止時の紙より横へ広がらないこと。**
     *
     * ## なぜ値では足りないのか
     *
     * 縮小率そのものは `BookletTurnGeometryTest` が値で押さえている。
     * **しかし「掛かっているか」「掛ける場所が正しいか」は描いた画素にしか出ない。**
     * 実際、縮小が無かった版では 10%送っただけで紙が 1.26 倍に広がり、
     * **25/50%保持では拡大した紙面が表示域を覆って次の紙を確認できなかった**
     * （2026-09-05 の実機レビュー）。**それでも値の検査は1つも落ちなかった。**
     *
     * ## 何を見るか
     *
     * 送りを進めながら、**紙が写っている行のうち最も広い行**を数える。
     * 静止時の幅を超えたら枠の外へ出ている。**下回りすぎてもいけない** —
     * 縮めすぎれば「収まる」は満たすが、紙が豆粒になる。
     */
    @Test
    fun 倒れた紙は静止時の幅を越えない() {
        showSheet(turn = 0f)
        val resting = widestRow()

        val outside = TURNS.map { turn ->
            showSheet(turn)
            turn to widestRow()
        }.filterNot { (_, width) -> isWithinResting(resting, width) }

        assertTrue(
            "倒れた紙の幅が静止時（$resting px）と違います: " +
                outside.joinToString { (turn, width) -> "送り$turn→$width px" } +
                "。広い側は画面の外へ出ており、狭い側は縮めすぎです。",
            outside.isEmpty()
        )
    }

    /**
     * 静止時の幅から外れていないか。**広い側だけを厳しく見る。**
     *
     * 広がる側は**そのまま画面の外**なので許さない。狭い側は、倒れた紙の下端の行が
     * 補間で数え落とされるぶんだけ必ず細く出る（紙が寝ているほど1行に潰れる）ので、
     * **測り方の誤差として許す。** 縮めすぎの後退はここではなく値の検査が落とす。
     */
    private fun isWithinResting(resting: Int, width: Int): Boolean =
        width <= resting * (1f + SAME_WIDTH_TOLERANCE) && width >= resting * (1f - FIT_SHRINK_TOLERANCE)

    /** 上端と下端が同じ幅か。**両側へ同じだけ許す**（片側だけ緩くすると `P3-1` に戻る）。 */
    private fun isSameWidth(top: Int, bottom: Int): Boolean =
        ((bottom - top).toFloat() / top).absoluteValue < SAME_WIDTH_TOLERANCE

    /**
     * 本番と同じ角度・蝶番・カメラ距離・縮小で面を回す。**地と紙は単色で、混ざらない2色にする。**
     *
     * **`setContent` は1度だけ。** 1つの検査で送りを何度も動かすので、
     * 送り位置は状態で持つ（2度目の `setContent` は落ちる）。
     */
    private fun showSheet(turn: Float) {
        if (!composed) {
            composed = true
            composeRule.setContent {
                Box(
                    modifier = Modifier
                        .testTag(FRAME)
                        .size(width = 300.dp, height = 400.dp)
                        .background(GROUND),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 120.dp, height = 260.dp)
                            // **枠へ収める縮小は回転の外側**（→ 本番の `BookletSheet` と同じ順序）。
                            .graphicsLayer {
                                val fit = sheetFitScale(
                                    sheetStanding(sheetAngleDegrees(turnState.floatValue, restack = 1f))
                                )
                                scaleX = fit
                                scaleY = fit
                                transformOrigin = SHEET_HINGE
                            }
                            .graphicsLayer {
                                rotationX = sheetAngleDegrees(turnState.floatValue, restack = 1f)
                                transformOrigin = SHEET_HINGE
                                cameraDistance = sheetCameraDistance(size.height, density)
                            }
                            .background(SHEET)
                    )
                }
            }
        }
        composeRule.runOnUiThread { turnState.floatValue = turn }
        composeRule.waitForIdle()
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

    /** 紙が写っている行の幅（画素数）を、上から順に返す。 */
    private fun sheetRowWidths(): List<Int> {
        val pixels = composeRule.onNodeWithTag(FRAME).captureToImage().toPixelMap()

        val widths = (0 until pixels.height).map { y ->
            (0 until pixels.width).count { x -> pixels[x, y] == SHEET }
        }
        val rows = widths.indices.filter { widths[it] > 0 }

        require(rows.isNotEmpty()) { "紙が1画素も描かれていません。" }

        return rows.map { widths[it] }
    }

    private var composed = false
    private val turnState = mutableFloatStateOf(0f)

    private companion object {
        const val FRAME = "perspective-frame"
        const val SAME_WIDTH_TOLERANCE = 0.02f

        /** 倒れた紙の下端が補間で数え落とされるぶん。**広がる側には使わない。** */
        const val FIT_SHRINK_TOLERANCE = 0.10f

        /**
         * 幅を見る送り位置。**真横（0.5）より先は見ない。**
         *
         * 幅を決めるのは奥行き（傾きの正弦）で、**真横を挟んで左右対称**なので前半で足りる。
         * そのうえ後半の紙は蝶番より上へ抜けるので、**枠の中では捕まえられない。**
         */
        val TURNS = listOf(0.05f, 0.1f, 0.2f, 0.3f, 0.4f, 0.45f)
        val GROUND = Color(0xFFFFFFFF)
        val SHEET = Color(0xFF0000FF)
    }
}
