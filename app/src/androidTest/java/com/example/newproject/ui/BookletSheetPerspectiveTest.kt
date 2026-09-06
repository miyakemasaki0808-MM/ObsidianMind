package com.example.newproject.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.absoluteValue
import kotlin.math.sin
import com.example.newproject.ui.screen.CAMERA_DISTANCE_FACTOR
import com.example.newproject.ui.screen.CurledFace
import com.example.newproject.ui.screen.SHEET_HINGE
import com.example.newproject.ui.screen.sheetAngleDegrees
import com.example.newproject.ui.screen.sheetCameraDistance
import com.example.newproject.ui.screen.sheetFitScale
import org.junit.Assert.assertEquals
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
 * - **カメラ距離の単位:** `cameraDistance` に渡した値が何画素の遠さになるかは、
 *   **KDocにも他所の実装にも書いていない**（2度取り違えた → docs/dev/lessons.md L60）。
 *   倒した面の投影された縦の長さから逆算する
 * - **曲がり:** 真横でも紙が消えないこと・帯の継ぎ目が裂けていないこと。
 *   **記録した絵が空でも値の検査は全部通る**ので、ここでだけ分かる
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
     * **`cameraDistance` に渡した値が、画面で何画素ぶんの遠さになるか。**
     *
     * ## なぜ画素で測るしかないのか
     *
     * この値の単位は**KDocにも実装にも素直には書いていない。** Compose は `RenderNode` へ素通しし、
     * `RenderNode` はさらに内部で換算する。**2026-09-05 に一度直したときは、
     * `View` 側の換算（`densityDpi` で割る）を読んで「インチ相当」と結論したが、
     * Compose の `ViewLayer` はその換算を打ち消している**（渡す前に `densityDpi` を掛ける）ので、
     * `View` の実装から Compose の単位は決まらない（→ docs/dev/lessons.md L60）。
     *
     * **だから、倒した面の幅から実効距離を逆算する。**
     * 上端は蝶番（深さ0）なので等倍、下端は `d / (d - z)` 倍。この比から `d` が出る。
     *
     * **ここが落ちたら、紙の遠近は設計した強さで効いていない。**
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
     * **真横まで送っても紙が消えないこと。これが「紙が曲がっている」ことの現れである。**
     *
     * 平らな1枚の面は真横でちょうど高さを失う。**曲がった紙は帯ごとに向きが違うので、
     * 全部が同時に真横になることがない** — 弓なりの形が残る。
     *
     * **この検査は同時に、記録した絵が実際に描かれていることも見ている。**
     * 帯は本物の面ではなく記録した絵を描くので、記録が空なら**紙は1画素も出ない。**
     * 値の検査では決して分からない側面である。
     */
    @Test
    fun 真横まで送っても紙は消えない() {
        showSheet(turn = 0.5f)

        val rows = sheetRowWidths()

        assertTrue(
            "真横で紙が${rows.size}行しか描かれていません。帯が全部同じ向きなら平らな板と同じで、" +
                "真横で高さを失います。記録した絵が空のときも同じ結果になります。",
            rows.size > 4
        )
    }

    /**
     * **帯の継ぎ目が裂けていないこと。**
     *
     * 帯は入れ子で積むので隣どうしは端を共有するが、**積み方を間違えると帯の間に地が覗く。**
     * 継ぎ目そのものは補間で1行ぶん薄くなりうるので、**2行以上続けて空くことだけ**を落とす。
     */
    @Test
    fun 曲がった紙の帯に隙間が空かない() {
        showSheet(turn = 0.3f)

        val gaps = drawnRowRange().windowed(2).count { (upper, lower) -> upper == 0 && lower == 0 }

        assertTrue(
            "帯の間に地が覗いています（空の行が2行以上続きます）。帯の積み方が裂けています。",
            gaps == 0
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
     * **本番の紙の面（`CurledFace`）をそのまま回す。**
     *
     * 写しを作らない — 帯の積み方が変わったときに検査が付いてこないため。
     * 外側の縮小だけは本番の [BookletSheet] と同じ順序でここに置く（枠が持つ層なので）。
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
                        .size(width = FRAME_WIDTH.dp, height = FRAME_HEIGHT.dp)
                        .background(GROUND),
                    contentAlignment = Alignment.TopCenter
                ) {
                    if (plainState.floatValue > 0f) {
                        // **素の面。** 縮小も曲がりも通さない — 見るのはカメラ距離の効き方だけ。
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
                            .size(width = 120.dp, height = SHEET_HEIGHT.dp)
                            // **枠へ収める縮小は帯より外側**（→ 本番の `BookletSheet` と同じ順序）。
                            .graphicsLayer {
                                val fit = sheetFitScale(
                                    sheetAngleDegrees(turnState.floatValue, restack = 1f)
                                )
                                scaleX = fit
                                scaleY = fit
                                transformOrigin = SHEET_HINGE
                            }
                    ) {
                        CurledFace(
                            turn = { turnState.floatValue },
                            restack = { 1f }
                        ) {
                            Box(modifier = Modifier.fillMaxSize().background(SHEET))
                        }
                    }
                }
            }
        }
        composeRule.runOnUiThread { turnState.floatValue = turn }
        composeRule.waitForIdle()
    }

    /** 素の面をその角度だけ倒す。**縮小も曲がりも通さない。** */
    private fun showPlainSheet(rotationDegrees: Float) {
        composeRule.runOnUiThread { plainState.floatValue = rotationDegrees }
        showSheet(turn = 0f)
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
     * **紙の色では数えない。** 裏を向いた帯は紙の面の色で描かれるので、
     * 表の色だけを数えると**いちばん手前（いちばん広い）帯が抜け落ちる。**
     * 地だけを別の色にしておけば、表も裏も同じ「紙の影」として数えられる。
     */
    private fun rowWidths(): List<Int> {
        val pixels = composeRule.onNodeWithTag(FRAME).captureToImage().toPixelMap()

        return (0 until pixels.height).map { y ->
            (0 until pixels.width).count { x -> pixels[x, y] != GROUND }
        }
    }

    private var composed = false
    private val turnState = mutableFloatStateOf(0f)

    /** 0 なら本番の紙、正なら「素の面をその角度だけ倒す」（カメラ距離の実測用）。 */
    private val plainState = mutableFloatStateOf(0f)

    private companion object {
        const val FRAME = "perspective-frame"
        const val SAME_WIDTH_TOLERANCE = 0.02f

        /** 倒れた紙の下端が補間で数え落とされるぶん。**広がる側には使わない。** */
        const val FIT_SHRINK_TOLERANCE = 0.10f

        /** 枠の幅と高さ（dp）。 */
        const val FRAME_WIDTH = 300
        const val FRAME_HEIGHT = 400

        /** 逆算に使う傾き。**枠で切れない範囲で、幅の差がはっきり出る角度。** */
        const val PROBE_DEGREES = 10f

        /**
         * 幅を見る送り位置。**真横（0.5）より先は見ない。**
         *
         * 幅を決めるのは奥行き（傾きの正弦）で、**真横を挟んで左右対称**なので前半で足りる。
         * そのうえ後半の紙は蝶番より上へ抜けるので、**枠の中では捕まえられない。**
         */
        val TURNS = listOf(0.05f, 0.1f, 0.2f, 0.3f, 0.4f, 0.45f)

        /** 紙の高さ（dp）。 */
        const val SHEET_HEIGHT = 260

        /**
         * カメラ距離を逆算する素の面（dp）。**枠に対して十分小さく取る** —
         * 倒した面が枠の端で切れると幅の測定が頭打ちになり、**逆算が静かに狂う。**
         */
        const val PROBE_WIDTH = 40
        const val PROBE_HEIGHT = 160
        /** 地。**紙の表とも裏とも似ない色にする**（裏は紙の面の色で、ほぼ白である）。 */
        val GROUND = Color(0xFF00FF00)
        val SHEET = Color(0xFF0000FF)
    }
}
