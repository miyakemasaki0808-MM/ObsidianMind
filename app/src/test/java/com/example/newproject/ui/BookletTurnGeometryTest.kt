package com.example.newproject.ui

import androidx.compose.ui.graphics.Matrix
import com.example.newproject.ui.screen.CAMERA_DISTANCE_FACTOR
import com.example.newproject.ui.screen.CAMERA_UNIT_PX
import com.example.newproject.ui.screen.SHEET_SETTLE_MILLIS
import com.example.newproject.ui.screen.sheetCameraDistance
import com.example.newproject.ui.screen.sheetCastsShadow
import com.example.newproject.ui.screen.sheetFitScale
import com.example.newproject.ui.screen.sheetShowsStack
import com.example.newproject.ui.screen.sheetSlotShift
import com.example.newproject.ui.screen.sheetStanding
import com.example.newproject.ui.screen.sheetTiltDegrees
import java.io.File
import kotlin.math.absoluteValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 冊子の紙の**置き方**を固定する — 積み直りの傾き・定位置への付け替え・束の縁・影・遠近。
 *
 * **めくりそのものはここには無い。** 折り目が走る形になったので（2026-09-06）、
 * めくりの幾何は `BookletPeelGeometryTest` が持つ。
 * ここに残るのは「**紙が置かれている**」側の値である。
 *
 * **見え方そのものは実機ケースが持つ** — 重さ・速さ・気持ちよさは時間の中にしかない
 * （→ docs/dev/system/bearing_channels.md §7）。
 * **ただし符号の意味だけは例外で、画素を数えないと分からない**（→ `BookletSheetPerspectiveTest`）。
 */
class BookletTurnGeometryTest {

    // ── 積み直りの傾き ────────────────────────────────────────────────────

    /**
     * **積み直りは傾くだけで、めくり切らない。**
     *
     * 束が届いたのは*めくった*からではないので、浮いて置き直される以上のことをしない。
     */
    @Test
    fun `積み直りは傾くだけで、めくり切らない`() {
        val tilted = sheetTiltDegrees(restack = 0f)

        assertTrue("積み直りで紙が傾いていません。", tilted > 0f)
        assertTrue("積み直りが半分以上めくれています。「めくった」と嘘をつく強さです。", tilted < 90f)
        assertEquals("積み終わっても傾きが残っています。", 0f, sheetTiltDegrees(1f), TOLERANCE)
    }

    /**
     * **傾きは積み直りだけで決まる。** 送りの進み具合は入らない。
     *
     * **これが 2026-09-06 の作り替えの要点である。** 以前は繰りと積み直りが
     * **同じ値（角度）を取り合って**いたので、「足すか、大きい側を採るか」で2度直した。
     * めくりが折り目になって角度を持たなくなり、**取り合う構図そのものが消えた。**
     */
    @Test
    fun `傾きは積み直りだけで決まる`() {
        val progress = (0..10).map { it / 10f }

        progress.forEach { restack ->
            assertEquals(
                "積み直り $restack の傾きが単調ではありません。",
                22f * (1f - restack),
                sheetTiltDegrees(restack),
                TOLERANCE
            )
        }
    }

    /**
     * **正の角度が、蝶番より下の点を +Z へ送ること。** 符号の意味をここで固定する。
     *
     * 「どちらの符号が手前か」は**値のどこにも現れない**。前の版はそこを取り違えたまま
     * 27件を全部通した。だから **`rotateX` が実際に何をするかを Compose の実装から観測する** —
     * `Matrix` の索引は `out[column] = Σ m[row, column]·in[row]` なので、
     * `m[1, 2]` が「入力の y が出力の z へ渡る係数」そのものである。
     *
     * **残る片方（+Z が画面の手前であること）は値では確かめられない。**
     * そこは実機の投影が持つ（`BookletSheetPerspectiveTest`）。
     */
    @Test
    fun `傾いた紙は蝶番より下がZの手前側へ出る`() {
        val yToZ = Matrix().apply { rotateX(sheetTiltDegrees(restack = 0f)) }[1, 2]

        assertTrue(
            "蝶番より下の点が -Z（奥）へ送られています。紙が束へ沈む向きです。",
            yToZ > 0f
        )
    }

    // ── 束の縁 ────────────────────────────────────────────────────────────

    /**
     * **これが `P2-1` の受け入れ条件。** めくり始めた紙は束の縁を置いていく。
     *
     * 縁は**回らない不透明な面**なので、めくられる紙に付けたままにすると
     * **定位置に残って次の紙を覆う。** めくれた角に次の紙が現れなくなる。
     */
    @Test
    fun `めくり始めた紙は束の縁を置いていく`() {
        assertTrue("定位置の紙に束の縁がありません。", sheetShowsStack(0f))
        assertTrue("これから出てくる紙に束の縁がありません。", sheetShowsStack(-0.5f))
        assertTrue(sheetShowsStack(-1f))

        assertFalse(
            "めくられている紙が束の縁を連れています。回らない面が次の紙を覆います。",
            sheetShowsStack(0.01f)
        )
        assertFalse(sheetShowsStack(0.5f))
        assertFalse(sheetShowsStack(1f))
    }

    // ── 置き直しと影 ──────────────────────────────────────────────────────

    /**
     * **紙は動かない。めくれるだけ。**
     *
     * ページャが送るために与えた変位をそのまま打ち消す。
     */
    @Test
    fun `前後1枚は定位置へ置き直される`() {
        assertEquals(0f, sheetSlotShift(0f), TOLERANCE)
        assertEquals(0.4f, sheetSlotShift(0.4f), TOLERANCE)
        assertEquals(-0.4f, sheetSlotShift(-0.4f), TOLERANCE)
        assertEquals(1f, sheetSlotShift(1f), TOLERANCE)
        assertEquals(-1f, sheetSlotShift(-1f), TOLERANCE)
    }

    /**
     * **前後1枚より遠い紙は動かさない。**
     *
     * 頭打ちにすると、1.5枚離れた紙が半分だけ引き寄せられて**画面の下半分へ顔を出す**。
     * ページャが置いた場所に残せば、主軸の切り抜きが消してくれる。
     */
    @Test
    fun `遠い紙は引き寄せない`() {
        assertEquals("遠い紙を内側へ引き寄せています。", 0f, sheetSlotShift(1.5f), TOLERANCE)
        assertEquals(0f, sheetSlotShift(-1.5f), TOLERANCE)
        assertEquals(0f, sheetSlotShift(2.5f), TOLERANCE)
        assertEquals(0f, sheetSlotShift(-2.5f), TOLERANCE)
    }

    /** **影は「紙がどれだけ立っているか」で深くなる。** 真横で最大、寝ていればゼロ。 */
    @Test
    fun `影は紙が立っているあいだだけ深い`() {
        assertEquals("定位置で影が深くなっています。", 0f, sheetStanding(0f), TOLERANCE)
        assertEquals("真横で影が最大になっていません。", 1f, sheetStanding(90f), TOLERANCE)
        assertEquals("抜け切った紙の影が残っています。", 0f, sheetStanding(180f), TOLERANCE)

        assertTrue(
            "積み直りの最中に影が深くなっていません。",
            sheetStanding(sheetTiltDegrees(restack = 0f)) > 0f
        )
    }

    /**
     * **回っている間は影を出さない**（2026-09-07 の実機レビュー `P2-1`）。
     *
     * 積み直りの最中にめくると、**折り返しから離れた大きな灰色の影**が数フレーム出た。
     * 材料は「親が遠近つきで3Dに回っている」×「子の輪郭が多角形で影を出している」の2つで、
     * **両方揃ったときだけ**壊れる。Android の影は輪郭を親の空間へ変換して作るが、
     * **遠近の入った射影変換の中ではパスの影が崩れる。**
     *
     * **束の縁（角丸矩形）は同じ形にならない**ので、切るのはめくりの2層だけでよい。
     */
    @Test
    fun `回っている間は影を出さない`() {
        assertFalse("傾いている紙が影を出しています。", sheetCastsShadow(restack = 0f))
        assertFalse(sheetCastsShadow(restack = 0.5f))
        assertFalse("積み終わる直前に影が戻っています。", sheetCastsShadow(restack = 0.99f))

        assertTrue("積み終わった紙が影を出していません。", sheetCastsShadow(restack = 1f))
    }

    // ── 枠の中に収まる ────────────────────────────────────────────────────

    /**
     * **静止時は1ピクセルも縮まない。** 判断9 で実機確認した佇まいは、縮小の外にある。
     */
    @Test
    fun `静止時は縮まない`() {
        assertEquals("定位置の紙が縮んでいます。", 1f, sheetFitScale(0f), TOLERANCE)
        assertEquals("積み終わった紙が縮んでいます。", 1f, sheetFitScale(sheetTiltDegrees(1f)), TOLERANCE)
    }

    /**
     * **これが枠へ収める受け入れ条件。** 縮小は、遠近で広がった倍率をちょうど打ち消す。
     *
     * 傾いた紙は `d / (d - z)` 倍に広がるので、**遠近を効かせた時点で必ず画面から出る** —
     * 積み直りの 22 度でも 1.33 倍で、左右の余白は 1.05 倍ぶんしかない。
     *
     * **等号で押さえる。** 「収まる」だけを見ると、縮めすぎて紙が豆粒になっても通る。
     * **ただし、掛かっていることと掛ける場所が正しいことは画素でしか分からない**
     * （→ `BookletSheetPerspectiveTest`）。
     */
    @Test
    fun `縮小は遠近の広がりをちょうど打ち消す`() {
        val leftovers = (0..90).map { it.toFloat() }
            .map { angle ->
                val spread = CAMERA_DISTANCE_FACTOR / (CAMERA_DISTANCE_FACTOR - sheetStanding(angle))
                angle to sheetFitScale(angle) * spread
            }
            .filter { (_, width) -> (width - 1f).absoluteValue > TOLERANCE }

        assertTrue(
            "縮小したあとの幅が静止時と違います: " +
                leftovers.take(3).joinToString { (angle, width) -> "%.0f度→%.3f倍".format(angle, width) },
            leftovers.isEmpty()
        )
    }

    /** **倒れるほど強く縮み、消えるほどは縮まない。** */
    @Test
    fun `倒れるほど強く縮み、消えるほどは縮まない`() {
        val scales = (0..90).map { sheetFitScale(it.toFloat()) }

        assertTrue(
            "倒すほど縮む形になっていません: $scales",
            scales.zipWithNext().all { (before, after) -> after <= before + TOLERANCE }
        )
        assertTrue("真横で紙が消えています。", sheetFitScale(90f) > 0.2f)
    }

    // ── 遠近 ──────────────────────────────────────────────────────────────

    /**
     * **カメラ距離は紙の高さの指定倍になる。**
     *
     * **単位はここでは決まらない。** `cameraDistance` は渡した値の [CAMERA_UNIT_PX] 倍が画素になるが、
     * それは**画素を数えて初めて分かった**ことである（→ `BookletSheetPerspectiveTest`）。
     * ここで固定できるのは「実効距離が高さに比例し、指定した倍率になる」という**式の形**までで、
     * **その 72 が正しいかは値の側からは永久に分からない。**
     *
     * **2度取り違えている。** 遠すぎた版（高さを画素のまま渡し、実効は約350倍で遠近が消えた）と、
     * 近すぎた版（画面のインチ相当と読み、実効は約0.28倍で**紙の下端がカメラの裏へ回った**）。
     */
    @Test
    fun `カメラ距離は紙の高さに比例し、指定した倍率になる`() {
        val sheetHeightPx = 1900f

        assertEquals(
            "実効カメラ距離が紙の高さの $CAMERA_DISTANCE_FACTOR 倍になっていません。",
            sheetHeightPx * CAMERA_DISTANCE_FACTOR,
            sheetCameraDistance(sheetHeightPx) * CAMERA_UNIT_PX,
            1f
        )
        assertEquals(
            "紙が小さくなってもカメラが近づいていません。寸法を直に書くと、" +
                "端末の解像度と分割画面で遠近の出方が変わります。",
            sheetCameraDistance(2000f) / 2f,
            sheetCameraDistance(1000f),
            TOLERANCE
        )
        assertTrue(
            "遠近の倍率が 1 を下回っています。公式の指示（面の高さより遠く）の外です。",
            CAMERA_DISTANCE_FACTOR >= 1f
        )
        assertTrue(
            "遠近の倍率が大きすぎます。**遠すぎる側はエラーも警告も出ず、遠近が静かに消えるだけ**です。",
            CAMERA_DISTANCE_FACTOR <= 4f
        )
    }

    // ── 本番がその値を使っていること（走査） ──────────────────────────────

    /**
     * **綴じの向きは判断であって好みではない**（→ booklet_mode 判断6）。
     *
     * 横へ戻す後退は、上の純関数を1つも落とさずに成立する。
     * **向きは値として観測できない**ので、ここだけは走査で見る。
     */
    @Test
    fun `ページャは縦である`() {
        assertTrue(
            "冊子が `VerticalPager` を使っていません。天綴じは下端を上へ送る向きです" +
                "（→ docs/dev/features/booklet_mode.md 判断6）。",
            screen.contains("VerticalPager(")
        )
        assertFalse(
            "冊子に `HorizontalPager` が残っています。左綴じ横は 2026-09-04 に天綴じへ差し替えました。",
            screen.contains("HorizontalPager")
        )
    }

    /**
     * **積み直りの傾き・遠近・影が本番へ配線されていること。**
     *
     * **純関数を残したまま本番の代入を外す変異は、値の検査を全部通る**（→ L55）。
     * だから受理条件を**代入の形**で書く。
     */
    @Test
    fun `積み直りの傾きと遠近が本番へ配線されている`() {
        val sheet = screen.bodyOf("internal fun BookletSheet(")

        assertTrue(
            "紙が積み直りの傾きで回っていません（`rotationX = sheetTiltDegrees(restack())` が本番にありません）。",
            sheet.contains("rotationX = sheetTiltDegrees(restack())")
        )
        assertTrue(
            "紙が天綴じの蝶番で回っていません（`transformOrigin = SHEET_HINGE` が本番にありません）。",
            sheet.contains("transformOrigin = SHEET_HINGE")
        )
        assertTrue(
            "`cameraDistance` を紙の高さから導いていません。" +
                "寸法を直に書くと、端末の解像度と分割画面で遠近の出方が変わります。",
            sheet.contains("cameraDistance = sheetCameraDistance(size.height)")
        )
    }

    /**
     * **紙が枠の中へ収まる縮小が、本番へ配線されていること。**
     *
     * **外しても値の検査は1つも落ちない** — 縮小率は純関数として正しいまま、
     * 画面でだけ紙が横へはみ出す。**実機で3件の判定を同時に塞いでいた形**なので、代入で見る。
     *
     * **回転より外側にあることも見る。** 内側へ移すと奥行きまで縮み、遠近が弱まる。
     */
    @Test
    fun `枠へ収める縮小が回転より外側で配線されている`() {
        val sheet = screen.bodyOf("internal fun BookletSheet(")
        val fit = "val fit = sheetFitScale(sheetTiltDegrees(restack()))"

        assertTrue(
            "紙が枠へ収める縮小（`sheetFitScale`）を掛けていません。" +
                "遠近を効かせた紙は 22 度の傾きでも 1.33 倍に広がり、画面の外へ出ます。",
            sheet.contains(fit) && sheet.contains("scaleX = fit") && sheet.contains("scaleY = fit")
        )
        assertTrue(
            "縮小が回転より内側に置かれています。奥行きまで縮み、台形が浅くなって遠近が弱まります。",
            sheet.indexOf(fit) < sheet.indexOf("rotationX = sheetTiltDegrees(restack())")
        )
    }

    /** **束の縁の在不在が本番へ配線されていること。** 外すと回らない面が次の紙を覆う。 */
    @Test
    fun `束の縁の在不在が本番へ配線されている`() {
        assertTrue(
            "束の縁が `sheetShowsStack` で切られていません。めくられる紙が縁を連れて行き、" +
                "回らない面が定位置に残って次の紙を覆います。",
            screen.bodyOf("internal fun BookletSheet(")
                .contains("alpha = if (sheetShowsStack(turn())) 1f else 0f")
        )
    }

    /**
     * **紙を定位置へ留め置く付け替えが本番へ配線されていること。**
     *
     * 外すと紙はめくれながら流れ、綴じてある感じが消える。
     */
    @Test
    fun `定位置への付け替えが本番へ配線されている`() {
        assertTrue(
            "ページの枠が `sheetSlotShift` で定位置へ戻されていません。",
            screen.bodyOf("private fun ColumnScope.BookletPager(")
                .contains("translationY = size.height * sheetSlotShift(turn())")
        )
    }

    /**
     * **めくられる紙が上に描かれること。**
     *
     * ページャは紙を番号順に置くので、既定のままだと*手前の紙が次の紙の下*に描かれ、
     * **めくっても何も起きていないように見える。**
     */
    @Test
    fun `めくられる紙が次の紙より上に描かれる`() {
        assertTrue(
            "ページに重なりの指定（`zIndex`）がありません。番号順のままだと、" +
                "めくった紙が次の紙の下へ潜り、めくりが見えなくなります。",
            screen.bodyOf("private fun ColumnScope.BookletPager(").contains("zIndex(-page.toFloat())")
        )
    }

    /**
     * **指を離したあとのめくりは、時間で決まる。**
     *
     * **ばねから時間へ変えた（2026-09-06）** — ばねは「どれだけ残っているか」で速さが決まるので、
     * 少しだけ送って離したときと半分送って離したときで**かかる時間が変わる**。
     * めくりは紙をめくる動作なので、**どこで離しても同じ速さで走り切るほうが紙らしい。**
     *
     * **触っているのはスナップの進み方だけ**であることも見る — 送り先の枚数や減衰まで
     * 差し替えると、「1回のフリックで1枚」という送りの性質そのものが動く。
     */
    @Test
    fun `指を離したあとのめくりはページャの既定よりゆっくり`() {
        val pager = screen.bodyOf("private fun ColumnScope.BookletPager(")

        assertTrue(
            "スナップの進み方を指定していません。ページャの既定のままだと速すぎます。",
            pager.contains("snapAnimationSpec = SHEET_SETTLE_SPEC")
        )
        assertTrue(
            "送り先の枚数や勢いの減衰まで差し替えています。触るのはスナップの進み方だけです。",
            !pager.contains("pagerSnapDistance") && !pager.contains("decayAnimationSpec")
        )
        assertTrue(
            "めくりが速すぎます（$SHEET_SETTLE_MILLIS ミリ秒）。" +
                "「ゆっくり、紙を捲るイメージ」が実機での指示です。",
            SHEET_SETTLE_MILLIS >= 500
        )
        assertTrue(
            "めくりが遅すぎます（$SHEET_SETTLE_MILLIS ミリ秒）。" +
                "判断3（冊子で深く作業させない）＝眺めて捨てる速さを失います。",
            SHEET_SETTLE_MILLIS <= 900
        )
    }

    private val screen: String by lazy {
        File("src/main/java/com/example/newproject/ui/screen/BookletScreen.kt").readText()
    }

    /** その関数の本体。**コメントは落とす**（走査が文章で緩まないように）。 */
    private fun String.bodyOf(signature: String): String {
        val start = indexOf(signature)
        check(start >= 0) { "本番に $signature が見つかりません" }
        val end = indexOf("\n}", start)
        check(end > start) { "$signature の終わりを特定できません" }
        return substring(start, end)
            .lineSequence()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")
    }

    private companion object {
        const val TOLERANCE = 0.01f
    }
}
