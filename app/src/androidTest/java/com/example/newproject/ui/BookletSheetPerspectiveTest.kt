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
import com.example.newproject.ui.screen.BookletSheet
import com.example.newproject.ui.screen.CAMERA_DISTANCE_FACTOR
import com.example.newproject.ui.screen.SHEET_HINGE
import com.example.newproject.ui.screen.sheetCameraDistance
import com.example.newproject.ui.theme.AppTheme
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
 * ## 本番の紙をそのまま描く（2026-09-06 に作り替えた）
 *
 * 前の版は**本番と同じ構成を手で組み直した写し**を描いていた。
 * **写しは本番が壊れても通る** — 表と裏の切り抜きを片側ずつ落とす変異が、
 * JVM走査でも描画テストでも素通りした（レビュー `P2-1`）。
 * だから[BookletSheet]をそのまま描き、**本番のレイヤーを画素で踏む。**
 *
 * **例外はカメラ距離の実測だけ。** あれは*このアプリの層*ではなく
 * *プラットフォームの単位*を測るものなので、素の面を別に置く。
 *
 * ## 何を見るか
 *
 * - **向き:** 傾いた紙は下端が手前へ出るので、**下端のほうが広い**。
 *   前の版はここを逆に思い込み、KDocと検査へ同じ形で書いて**27件を全部通した**
 *   （→ docs/dev/lessons.md L61）
 * - **枠:** 手前へ出た紙は広がるので、**縮小を掛けないと静止時の幅を越えて画面の外へ出る**
 * - **カメラ距離の単位:** 渡した値が何画素の遠さになるかは、**KDocにも他所の実装にも書いていない**
 *   （2度取り違えた → L60）
 * - **めくり:** 折り目の向こうが本当に切り落とされ、**めくれた角が空いて裏が現れる**こと
 */
@RunWith(AndroidJUnit4::class)
class BookletSheetPerspectiveTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ── めくり ────────────────────────────────────────────────────────────

    /**
     * **静止時の紙は欠けない。** 判断9 で実機確認した佇まいは、めくりの外にある。
     */
    @Test
    fun 静止時の紙は欠けない() {
        showSheet(turn = 0f)

        assertEquals("静止時の紙に穴が空いています。", 0, groundInside(FULL))
        assertTrue("静止時に紙の表が描かれていません。", facePixels() > 0)
    }

    /**
     * **これがめくりの受け入れ条件のひとつ。右下の角が空き、次の紙（ここでは地）が見える。**
     *
     * **表の切り抜きを落とすと、ここが落ちる** — 元の矩形が残って角が空かない。
     * 形が値として正しくても、`clip` が無ければ画面では紙が丸ごと残る。
     */
    @Test
    fun めくると右下の角が空く() {
        showSheet(turn = 0.3f)

        assertTrue(
            "めくったのに右下の角が空いていません。表が折り目で切れていないか、" +
                "折り返した紙が角を塞いでいます。",
            groundInside(BOTTOM_RIGHT) > 0
        )
    }

    /**
     * **めくれた角は裏返って現れ、残った表は隠れない。**
     *
     * **裏の切り抜きを落とすと、ここが落ちる** — 無地の面が紙面を丸ごと覆い、表が消える。
     * 「裏が出ている」だけでは足りない（覆っていても出てはいる）ので、**表が残ることを対で見る。**
     */
    @Test
    fun `めくれた角の裏が出て、残った表は隠れない`() {
        showSheet(turn = 0.3f)

        assertTrue("めくれた角（裏）が1画素も描かれていません。", backPixels() > 0)
        assertTrue(
            "めくったら紙の表が消えました。折り返した無地の面が紙面を覆っています。",
            facePixels() > 0
        )
    }

    /**
     * **積み直りとめくりが同時に起きても、めくれた紙が紙面を覆わないこと。**
     *
     * ## これは 2026-09-07 の対応が自分で作った穴を塞ぐ検査である
     *
     * 実機で、**積み直りの最中にめくると折り返しから離れた大きな影**が出た（`P2-1`）。
     * 対応として**回っている間はめくりの2層の影を切った**。ところが Compose は
     * **影のために輪郭を要求する**ので、影を切ると**`clip` を別に置いていない限り
     * 切り抜きごと消える**（2026-09-06 に実測）。
     * つまり**この組み合わせでだけ、裏の切り抜きが `clip` だけで支えられている。**
     *
     * ## 変異で確かめた（2026-09-07）
     *
     * | 変異 | 結果 |
     * |---|---|
     * | **裏の `clip` を落とす** | **ここだけが落ちる**（無地の面が紙面を覆い、表が消える） |
     * | 表の `clip` を落とす | どの画素検査も落ちない。**そちらは走査が持つ**（→ `BookletPeelGeometryTest`） |
     *
     * **積み直りをしていない検査（他の8件）はこの面を1つも踏まない。**
     * 片方ずつの検査では面が永久に空く（→ docs/dev/lessons.md L14）。
     */
    @Test
    fun 積み直りの最中にめくっても紙面が覆われない() {
        showSheet(turn = 0.3f, restack = RESTACK_WHILE_TILTED)

        assertTrue(
            "傾いた紙をめくったら表が消えました。**回っている間は影を切るので、" +
                "裏の `clip` が無いと無地の面が紙面を丸ごと覆います。**",
            facePixels() > 0
        )
        assertTrue("傾いた紙のめくれた角（裏）が出ていません。", backPixels() > 0)
    }

    // ── 傾きの向きと、枠へ収まること ──────────────────────────────────────

    /**
     * **これが `P2-1`（2026-09-05）の受け入れ条件。** 積み直りで傾いた紙は、下端が手前へ出る。
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

    /** **計測そのものが正しいことを、寝た紙で確かめる。** */
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

    // ── カメラ距離の単位（ここだけ本番の層ではなく素の面を測る） ──────────

    /**
     * **`cameraDistance` に渡した値が、画面で何画素ぶんの遠さになるか。**
     *
     * この値の単位は**KDocにも実装にも素直には書いていない。** 2026-09-05 に一度
     * 「インチ相当」と結論したが、それは `View` 側の換算を読んだもので、
     * **Compose の `ViewLayer` はその換算を打ち消している**（→ docs/dev/lessons.md L60）。
     *
     * **測っているのはプラットフォームの単位**であって本番のレイヤーではないので、
     * ここだけは素の面を置く。倒した面の投影された縦の長さから逆算する。
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

    // ── 出し方 ────────────────────────────────────────────────────────────

    /**
     * **本番の紙をそのまま描く。** 送りと積み直りは状態で持つ（`setContent` は1度だけ）。
     *
     * 表には地とも裏とも違う色を敷いて、**表・裏・地の3つを画素で見分けられる**ようにする。
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

    /** 素の面をその角度だけ倒す。**本番の層は通さない**（カメラ距離の実測用）。 */
    private fun showPlainSheet(rotationDegrees: Float) {
        compose()
        composeRule.runOnUiThread { plainState.floatValue = rotationDegrees }
        composeRule.waitForIdle()
    }

    private fun compose() {
        if (composed) return
        composed = true
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                Box(
                    modifier = Modifier
                        .testTag(FRAME)
                        .size(width = FRAME_WIDTH.dp, height = FRAME_HEIGHT.dp)
                        .background(GROUND),
                    contentAlignment = Alignment.TopCenter
                ) {
                    if (plainState.floatValue > 0f) {
                        Box(
                            modifier = Modifier
                                .size(width = PROBE_WIDTH.dp, height = PROBE_HEIGHT.dp)
                                .graphicsLayer {
                                    rotationX = plainState.floatValue
                                    transformOrigin = SHEET_HINGE
                                    cameraDistance = sheetCameraDistance(size.height)
                                }
                                .background(FACE)
                        )
                        return@Box
                    }
                    // **本番の紙。** 束の縁は幅の計測を濁らせるだけなので付けない
                    // （縁の在不在は `BookletTurnGeometryTest` と実機ケースが持つ）。
                    BookletSheet(
                        isBundleSheet = false,
                        turn = { turnState.floatValue },
                        restack = { restackState.floatValue }
                    ) {
                        Box(modifier = Modifier.fillMaxSize().background(FACE))
                    }
                }
            }
        }
    }

    // ── 数え方 ────────────────────────────────────────────────────────────

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
     * **表の色だけで数えない。** 裏を向いた角は紙の面の色で描かれるので、
     * 表だけを数えると**折り返した部分が抜け落ちる。**
     */
    private fun rowWidths(): List<Int> = pixels().let { map ->
        (0 until map.height).map { y ->
            (0 until map.width).count { x -> map[x, y] != GROUND }
        }
    }

    /** 紙の表（文字が載る側）の画素数。 */
    private fun facePixels(): Int = count { it == FACE }

    /**
     * めくれて裏返った角の画素数。
     *
     * **裏は紙の面の色**（`Panel`）で、表にも地にも使っていない色である。
     * 影が乗る縁は数えないので、**内側の画素だけが数えられる**。
     */
    private fun backPixels(): Int = count { it == BACK }

    private fun count(matches: (Color) -> Boolean): Int = pixels().let { map ->
        var found = 0
        (0 until map.height).forEach { y ->
            (0 until map.width).forEach { x -> if (matches(map[x, y])) found++ }
        }
        found
    }

    /**
     * 紙が置かれているはずの範囲のうち、指定した区画に**地が見えている**画素数。
     *
     * 区画は枠に対する割合で採る。**紙の余白（四辺）と角丸を避けるため内側だけを見る。**
     */
    private fun groundInside(region: ClosedFloatingPointRange<Float>): Int {
        val map = pixels()
        var found = 0
        val fromY = (map.height * region.start).toInt()
        val toY = (map.height * region.endInclusive).toInt()
        val fromX = (map.width * region.start).toInt()
        val toX = (map.width * region.endInclusive).toInt()

        (fromY until toY).forEach { y ->
            (fromX until toX).forEach { x -> if (map[x, y] == GROUND) found++ }
        }
        return found
    }

    private fun pixels() = composeRule.onNodeWithTag(FRAME).captureToImage().toPixelMap()

    private var composed = false
    private val turnState = mutableFloatStateOf(0f)
    private val restackState = mutableFloatStateOf(1f)

    /** 0 なら本番の紙、正なら「素の面をその角度だけ倒す」（カメラ距離の実測用）。 */
    private val plainState = mutableFloatStateOf(0f)

    private companion object {
        const val FRAME = "perspective-frame"
        const val SAME_WIDTH_TOLERANCE = 0.02f

        /** 傾いた紙の下端の行が補間で数え落とされるぶん。**広がる側には使わない。** */
        const val FIT_SHRINK_TOLERANCE = 0.10f

        /** 枠の寸法（dp）。紙は [BookletSheet] が自分の余白で決める。 */
        const val FRAME_WIDTH = 300
        const val FRAME_HEIGHT = 400

        /** 紙の内側（四辺の余白と角丸を避ける）。 */
        val FULL = 0.15f..0.80f

        /** 紙の右下（めくれて空くところ）。 */
        val BOTTOM_RIGHT = 0.72f..0.88f

        /**
         * 積み直りとめくりを重ねて見るときの積み直り位置。
         *
         * **傾きは残しつつ、紙が枠から縮み上がらない値**を採る（→ `積み直りの最中にめくっても角は空く`）。
         * 大きく傾けると縮小で紙の下端が上がり、**切り抜きが壊れていても角は空いてしまう** —
         * それでは変異で落ちない検査になる（実測して 0.3 から変えた）。
         */
        const val RESTACK_WHILE_TILTED = 0.8f

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

        /** 紙の表に敷く色（本番では扉の中身が載る）。 */
        val FACE = Color(0xFF0000FF)

        /** めくれて裏返った角。**本番の紙の面の色そのもの**（ライトの `panel`）。 */
        val BACK = Color(0xFFFDFEFF)
    }
}
