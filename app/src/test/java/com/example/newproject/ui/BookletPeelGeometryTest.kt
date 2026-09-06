package com.example.newproject.ui

import androidx.compose.ui.geometry.Offset
import com.example.newproject.ui.screen.peelFlapPolygon
import com.example.newproject.ui.screen.peelFlatPolygon
import com.example.newproject.ui.screen.peelFoldDistance
import com.example.newproject.ui.screen.peelLift
import com.example.newproject.ui.screen.sheetPeel
import java.io.File
import kotlin.math.absoluteValue
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * めくりの幾何。**折り目がどこを走り、紙がどう分かれるかを値で固定する。**
 *
 * ## ここで見るもの
 *
 * めくりは**折り目1本**で表す（→ docs/dev/features/booklet_mode.md 判断11）。
 * 折り目は右下の角から左上の角へ走り、紙を2つに分ける。
 *
 * | 壊れ方 | 画面でどう出るか |
 * |---|---|
 * | 折り目の向きが違う | 別の角からめくれる（左下からめくれる、など） |
 * | 表と裏の面積が合わない | 折り返した紙が伸び縮みする（紙ではなくゴムになる） |
 * | 裏が紙の枠から出る | めくった角が画面の外へはみ出す |
 * | 静止時に紙が欠ける | **止まっている絵が変わる**（判断9） |
 *
 * ## ここで見ないもの
 *
 * **「めくったように見えるか」は実機ケースが持つ。** 折り目の位置は値として正しくても、
 * **紙をめくった感じになるかは時間と目の中にしかない。**
 */
class BookletPeelGeometryTest {

    // ── 静止時 ────────────────────────────────────────────────────────────

    /**
     * **静止時は紙が丸ごと残り、めくれた角は無い。**
     *
     * 判断9 で実機確認した佇まいは、めくりの外にある。
     */
    @Test
    fun `静止時は紙が丸ごと残る`() {
        val flat = peelFlatPolygon(WIDTH, HEIGHT, peel = 0f)

        assertEquals("静止時の紙が4隅を保っていません。", 4, flat.size)
        assertEquals("静止時の紙の面積が欠けています。", WIDTH * HEIGHT, area(flat), TOLERANCE)
        assertEquals(
            "静止時にめくれた角があります。",
            0f,
            area(peelFlapPolygon(WIDTH, HEIGHT, 0f)),
            TOLERANCE
        )
    }

    /** **送り出される側だけがめくれる。** 下から現れる紙は平らに置かれたまま待つ。 */
    @Test
    fun `これから出てくる紙はめくれない`() {
        assertEquals("戻す側の紙がめくれています。", 0f, sheetPeel(-0.5f), TOLERANCE)
        assertEquals(0f, sheetPeel(-1f), TOLERANCE)
        assertEquals("送り切りを越えてめくれ続けています。", 1f, sheetPeel(1.5f), TOLERANCE)
    }

    // ── 折り目の走り方 ────────────────────────────────────────────────────

    /**
     * **これがめくる向きの受け入れ条件。折り目は右下の角から左上の角へ走る。**
     *
     * オーナーの体感が「**右下から左上にめがけて捲るイメージ**」だったので、
     * ここが逆を向くと、めくり始める角そのものが変わる。
     *
     * 見るのは**最初に紙から消える角**である — わずかに送ったとき、
     * 欠けるのは右下だけで、残り3隅は動かない。
     */
    @Test
    fun `めくれ始めるのは右下の角だけ`() {
        val flat = peelFlatPolygon(WIDTH, HEIGHT, peel = 0.05f)

        assertTrue("左上の角が欠けています。めくる向きが逆です。", flat.holds(Offset(0f, 0f)))
        assertTrue("右上の角が欠けています。", flat.holds(Offset(WIDTH, 0f)))
        assertTrue("左下の角が欠けています。", flat.holds(Offset(0f, HEIGHT)))
        assertTrue(
            "右下の角が残っています。めくりが始まっていないか、別の角からめくれています。",
            !flat.holds(Offset(WIDTH, HEIGHT))
        )
    }

    /** **折り目は対角線ぶんだけ進む。** 紙の縦横比が変わっても「角から角へ」は保たれる。 */
    @Test
    fun `折り目は角から角へ進む`() {
        val diagonal = hypot(WIDTH, HEIGHT)

        assertEquals("送り切りで折り目が対角線を渡り切っていません。", diagonal, peelFoldDistance(WIDTH, HEIGHT, 1f)!!, TOLERANCE)
        assertEquals("静止時に折り目が進んでいます。", 0f, peelFoldDistance(WIDTH, HEIGHT, 0f)!!, TOLERANCE)
        assertEquals("進み方が送りに比例していません。", diagonal / 2f, peelFoldDistance(WIDTH, HEIGHT, 0.5f)!!, TOLERANCE)
    }

    /** **測る前の紙（面積ゼロ）ではめくらない。** 0除算で形が壊れる。 */
    @Test
    fun `面積の無い紙ではめくらない`() {
        assertEquals(null, peelFoldDistance(0f, 0f, 0.5f))
        assertEquals("面積の無い紙が欠けています。", 4, peelFlatPolygon(0f, 0f, 0.5f).size)
    }

    /**
     * **送るほど表は減り、増えることはない。**
     *
     * 途中で増えると、めくった紙が戻ってくる。
     */
    @Test
    fun `送るほど表の面積は減り続ける`() {
        val areas = (0..20).map { area(peelFlatPolygon(WIDTH, HEIGHT, it / 20f)) }

        assertTrue(
            "めくっている途中で表の面積が増えています: $areas",
            areas.zipWithNext().all { (before, after) -> after <= before + TOLERANCE }
        )
        assertEquals("送り切っても紙が残っています。", 0f, areas.last(), TOLERANCE)
    }

    // ── 折り返した紙 ──────────────────────────────────────────────────────

    /**
     * **これが「紙である」ことの受け入れ条件。折り返しても伸び縮みしない。**
     *
     * 折り返した角は、折り目を鏡にした像である。**紙の枠から出た分を落とす前**なら、
     * めくれた領域と同じ面積でなければならない。伸びていたらゴム、縮んでいたら消えている。
     *
     * **枠に収まっている間で見る。** 折り目が対角線の3割を越えると折り返しは枠から出始め、
     * そこは意図して落としている（→ [peelFlapPolygon]）。
     */
    @Test
    fun `折り返した角は元の面積を保つ`() {
        listOf(0.05f, 0.1f, 0.2f, 0.3f).forEach { peel ->
            val peeled = WIDTH * HEIGHT - area(peelFlatPolygon(WIDTH, HEIGHT, peel))
            val flap = area(peelFlapPolygon(WIDTH, HEIGHT, peel))

            assertEquals(
                "送り $peel でめくれた面積（$peeled）と折り返した面積（$flap）が違います。" +
                    "折り返しが鏡になっていません。",
                peeled,
                flap,
                peeled * 0.02f + TOLERANCE
            )
        }
    }

    /**
     * **折り返した紙は、めくれた領域と重ならない側にある。**
     *
     * 折り返しは折り目の向こうへ倒れるので、**空いた角には次の紙が見える。**
     * ここが逆だと、めくったのに角が塞がったままになる。
     */
    @Test
    fun `めくれた角は空き、折り返しはその反対側へ出る`() {
        val flap = peelFlapPolygon(WIDTH, HEIGHT, peel = 0.3f)

        assertTrue("折り返した紙が描かれていません。", flap.size >= 3)
        assertTrue(
            "折り返した紙が、めくれた右下の角を塞いでいます。次の紙が見えません。",
            !flap.holds(Offset(WIDTH - 1f, HEIGHT - 1f))
        )
    }

    /**
     * **折り目が進むほど、折り返しは枠に収まらなくなる。**
     *
     * 本のページなら外へはみ出すが、冊子の紙は画面の中に置かれた1枚なので落とす。
     * **落ちた分は、そこに次の紙が見えているだけ**である。
     */
    @Test
    fun `折り目が進むと折り返しは枠で削られる`() {
        val half = area(peelFlapPolygon(WIDTH, HEIGHT, 0.5f))
        val peeled = WIDTH * HEIGHT - area(peelFlatPolygon(WIDTH, HEIGHT, 0.5f))

        assertTrue("半分めくった時点で折り返しが削られていません。", half < peeled)
        assertTrue("半分めくった時点で折り返しが消えています。", half > peeled * 0.5f)
    }

    /** **折り返した紙は紙の枠から出ない。** 出た分は画面の外への流出になる。 */
    @Test
    fun `折り返した紙は枠から出ない`() {
        (1..20).forEach { step ->
            val flap = peelFlapPolygon(WIDTH, HEIGHT, step / 20f)
            val outside = flap.filter {
                it.x < -TOLERANCE || it.y < -TOLERANCE ||
                    it.x > WIDTH + TOLERANCE || it.y > HEIGHT + TOLERANCE
            }

            assertTrue("送り ${step / 20f} で折り返した紙が枠の外へ出ています: $outside", outside.isEmpty())
        }
    }

    /**
     * **持ち上がりは折り目の長さに従う。** 角に触れた瞬間と渡り切りではゼロ、途中で最大。
     *
     * これが影の深さになる。**影だけが「角が浮いている」ことを言う**ので、
     * ここが常に 0 なら折り返しは平らな模様にしか見えない。
     */
    @Test
    fun `持ち上がりは途中で最大になる`() {
        assertEquals("静止時に角が浮いています。", 0f, peelLift(0f), TOLERANCE)
        assertEquals("渡り切りで角が浮いたままです。", 0f, peelLift(1f), TOLERANCE)
        assertEquals("途中で持ち上がりが最大になっていません。", 1f, peelLift(0.5f), TOLERANCE)
    }

    // ── 本番がその値を使っていること（走査） ──────────────────────────────

    /**
     * **めくりが本番へ配線されていること。**
     *
     * 純関数を残したまま配線だけ外す変異は、上の値の検査を全部通る（→ L55）。
     * **受理条件は代入の形で書く** — 表と裏の2つの形が、同じ送りから作られていること。
     */
    @Test
    fun `表と裏の形が本番へ配線されている`() {
        val sheet = screen.bodyOf("private fun BookletSheet(")

        assertTrue(
            "紙の表が折り目で切られていません（`PeelShape(peel = sheetPeel(turn()), flap = false)` が本番にありません）。",
            sheet.contains("shape = PeelShape(peel = sheetPeel(turn()), flap = false)")
        )
        assertTrue(
            "めくれた角が描かれていません（`flap = true` の形が本番にありません）。",
            sheet.contains("shape = PeelShape(peel = peel, flap = true)")
        )
        assertTrue(
            "形で切っていません（`clip = true` が無いと、折り目の向こうまで描かれます）。",
            sheet.contains("clip = true")
        )
        assertTrue(
            "めくれた角の影が持ち上がりから決まっていません。影だけが「角が浮いている」ことを言います。",
            sheet.contains("peelLift(peel)")
        )
    }

    /**
     * **静止時は紙の形をそのまま返すこと。**
     *
     * 多角形にすると角丸が失われ、**判断9 で実機確認した佇まいが変わる。**
     */
    @Test
    fun `静止時は紙の形をそのまま返す`() {
        assertTrue(
            "静止時に紙の形（`BrowsingSheetShape`）へ戻していません。角丸が失われます。",
            screen.bodyOf("internal class PeelShape(")
                .contains("BrowsingSheetShape.createOutline(size, layoutDirection, density)")
        )
    }

    // ── 補助 ──────────────────────────────────────────────────────────────

    /** 多角形の面積（靴紐公式）。 */
    private fun area(polygon: List<Offset>): Float {
        if (polygon.size < 3) return 0f
        var doubled = 0f
        polygon.forEachIndexed { index, point ->
            val next = polygon[(index + 1) % polygon.size]
            doubled += point.x * next.y - next.x * point.y
        }
        return doubled.absoluteValue / 2f
    }

    /** その点を多角形が含むか（凸多角形として、全部の辺の同じ側にあるか）。 */
    private fun List<Offset>.holds(point: Offset): Boolean {
        if (size < 3) return false
        var sign = 0
        forEachIndexed { index, current ->
            val next = this[(index + 1) % size]
            val cross = (next.x - current.x) * (point.y - current.y) -
                (next.y - current.y) * (point.x - current.x)
            if (cross.absoluteValue > TOLERANCE) {
                val side = if (cross > 0f) 1 else -1
                if (sign == 0) sign = side else if (sign != side) return false
            }
        }
        return true
    }

    private val screen: String by lazy {
        File("src/main/java/com/example/newproject/ui/screen/BookletScreen.kt").readText()
    }

    /** その宣言の本体。**コメントは落とす**（走査が文章で緩まないように）。 */
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
        const val WIDTH = 400f
        const val HEIGHT = 600f
        const val TOLERANCE = 0.01f
    }
}
