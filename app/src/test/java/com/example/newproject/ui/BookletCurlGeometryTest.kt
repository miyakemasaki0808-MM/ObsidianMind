package com.example.newproject.ui

import com.example.newproject.ui.screen.CURL_BANDS
import com.example.newproject.ui.screen.CURL_SPAN_DEGREES
import com.example.newproject.ui.screen.sheetAngleDegrees
import com.example.newproject.ui.screen.sheetBandAngle
import com.example.newproject.ui.screen.sheetBandTilt
import com.example.newproject.ui.screen.sheetCurlDegrees
import com.example.newproject.ui.screen.sheetCurlSpread
import com.example.newproject.ui.screen.sheetLiesFlat
import com.example.newproject.ui.screen.sheetShowsFace
import com.example.newproject.ui.screen.sheetStanding
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 紙の曲がり（カール）の幾何。**帯の角度・深さ・順序を値で固定する。**
 *
 * ## ここで見るもの
 *
 * 曲がりは**帯を入れ子に積んで**作る（→ `CurledFace`）。積み方が壊れる形は3つあり、
 * どれも値として観測できる。
 *
 * | 壊れ方 | 画面でどう出るか |
 * |---|---|
 * | 帯の角度が 0〜180 の外へ出る | 蝶番側が束の中へ沈む／抜けた紙がもう一度表を見せる |
 * | 差分の積み上げが絶対角と食い違う | 表裏の判定だけが別の角度を見て、**鏡像の文字**が出る |
 * | 深さが単調でない | 描く順（奥→手前）が崩れ、**手前の帯が奥の帯に隠れる** |
 *
 * ## ここで見ないもの
 *
 * **「紙に見えるか」は実機ケースが持つ**（→ docs/dev/features/booklet_mode.md 判断11）。
 * 帯の枚数と曲がりの総量は値としては正しくても、**曲線に見えるかは時間と目の中にしかない。**
 */
class BookletCurlGeometryTest {

    // ── 曲がる量 ──────────────────────────────────────────────────────────

    /**
     * **静止時と抜け切りでは曲がらない。**
     *
     * 判断9 で実機確認した佇まいは曲がりの外にある。抜け切った紙も平らに戻っている。
     */
    @Test
    fun `静止時と抜け切りでは曲がらない`() {
        assertEquals("定位置の紙が曲がっています。", 0f, sheetCurlDegrees(0f), TOLERANCE)
        assertEquals("抜け切った紙が曲がっています。", 0f, sheetCurlDegrees(180f), TOLERANCE)
        assertEquals("真横で曲がりが最大になっていません。", CURL_SPAN_DEGREES, sheetCurlDegrees(90f), TOLERANCE)
    }

    /** **曲がりは影と同じ尺度から出る**（どちらも「どれだけ立っているか」）。 */
    @Test
    fun `曲がりは影と同じ尺度から出る`() {
        (0..180 step 5).forEach { angle ->
            assertEquals(
                "曲がりが影と別の尺度で動いています。同時に起きる2つを別々に持つと食い違います。",
                CURL_SPAN_DEGREES * sheetStanding(angle.toFloat()),
                sheetCurlDegrees(angle.toFloat()),
                TOLERANCE
            )
        }
    }

    /**
     * **これが曲がりの総量の上限。** 越えると紙が壊れる。
     *
     * 蝶番側の帯の角度は `angle - 曲がり/2` で、`angle` が 0 に近いところでは
     * `angle * (1 - 総量/2 * π/180)` に等しい。**総量が 114.6 度を越えると、ここが負になる** —
     * 蝶番の近くが束の中へ沈む向きへ曲がり、反対側では半回転を越えて
     * **抜けたはずの紙がもう一度表を見せる。**
     */
    @Test
    fun `曲がりの総量は紙が壊れない範囲にある`() {
        assertTrue(
            "曲がりの総量（$CURL_SPAN_DEGREES 度）が上限（114.6度）を越えています。" +
                "蝶番側の帯が負の角度になり、束の中へ沈みます。",
            CURL_SPAN_DEGREES < 114.6f
        )
    }

    // ── 帯の角度 ──────────────────────────────────────────────────────────

    /**
     * **自由端が先行する。** 蝶番の近くは浅く、下端ほど深い。
     *
     * レポート用紙の下端を押し上げると、綴じの近くはまだ平らで指のある側だけが起きている。
     * **逆にすると、綴じの近くだけが折れて自由端が平らなまま**という、紙では起きない形になる。
     */
    @Test
    fun `帯は蝶番側が浅く、自由端ほど深い`() {
        val angles = (0 until CURL_BANDS).map { sheetBandAngle(45f, it) }

        assertTrue(
            "帯の角度が自由端へ向かって深くなっていません: $angles",
            angles.zipWithNext().all { (near, far) -> far > near }
        )
    }

    /**
     * **曲げても紙全体としては同じだけ倒れている。**
     *
     * 帯の角度の平均が合成後の角度に一致しないと、**送りの進み具合と紙の見え方がずれる** —
     * 指を半分送ったのに紙が半分より進んでいる（あるいは遅れている）ことになる。
     */
    @Test
    fun `帯の平均は合成後の角度に一致する`() {
        (0..180 step 5).forEach { angle ->
            val mean = (0 until CURL_BANDS).map { sheetBandAngle(angle.toFloat(), it) }.average()

            assertEquals(
                "帯の平均が合成後の角度からずれています。曲がりの振り分けが対称ではありません。",
                angle.toDouble(),
                mean,
                TOLERANCE.toDouble()
            )
        }
    }

    /**
     * **これが入れ子で積むことの受け入れ条件。**
     *
     * 帯はそれぞれ「1つ上の帯との差分」だけ折れるので、**差分を積み上げた値が絶対角に一致する**
     * ことが、描かれる形と表裏の判定が同じ角度を見ていることの保証になる。
     * 食い違うと、**表裏だけが別の角度で切り替わって鏡像の文字が出る**
     * （1枚の面だったときに実際に起きた形 → 判断10）。
     */
    @Test
    fun `差分を積み上げると帯の絶対角になる`() {
        listOf(5f, 22f, 45f, 90f, 135f, 175f).forEach { angle ->
            var stacked = 0f
            (0 until CURL_BANDS).forEach { band ->
                stacked += sheetBandTilt(angle, band)
                assertEquals(
                    "帯 $band の積み上げ（$stacked）が絶対角と食い違います。角度 $angle 度。",
                    sheetBandAngle(angle, band),
                    stacked,
                    TOLERANCE
                )
            }
        }
    }

    /**
     * **どの帯も、逆へ曲がらず半回転を越えない。**
     *
     * 負の角度は「束の中へ沈む向き」（2026-09-05 に実機で出た形そのもの）で、
     * 半回転を越えた帯は**抜けたはずの紙がもう一度表を見せる。**
     * **積み直りと重なった角度でも見る** — 2つは同時に起きうる。
     */
    @Test
    fun `どの帯も逆へ曲がらず半回転を越えない`() {
        val broken = allAngles().flatMap { angle ->
            (0 until CURL_BANDS).map { band -> angle to sheetBandAngle(angle, band) }
        }.filter { (_, band) -> band < -TOLERANCE || band > 180f + TOLERANCE }

        assertTrue(
            "帯が 0〜180 度の外へ出ています: " +
                broken.take(3).joinToString { (angle, band) -> "角度%.0f度→帯%.1f度".format(angle, band) },
            broken.isEmpty()
        )
    }

    // ── 深さと描く順 ──────────────────────────────────────────────────────

    /**
     * **これが描く順の受け入れ条件。** 深さは蝶番から自由端へ単調に増える。
     *
     * 帯は入れ子の順（蝶番→自由端）に描かれるので、深さが単調でないと
     * **手前にあるはずの帯が奥の帯に隠れる。** 順序は構造が与えるが、
     * **それが正しい順であることは値の側の性質**である。
     */
    @Test
    fun `深さは蝶番から自由端へ単調に増える`() {
        val notMonotonic = allAngles().filter { angle ->
            if (sheetLiesFlat(angle)) return@filter false
            (0 until CURL_BANDS).map { sin(sheetBandAngle(angle, it) * PI / 180.0) }
                .any { it <= 0.0 }
        }

        assertTrue(
            "深さが増えない帯があります（描く順が奥→手前になりません）: ${notMonotonic.take(3)}",
            notMonotonic.isEmpty()
        )
    }

    /**
     * **帯に分けた深さの合計は、平らな紙より浅い。** 正弦は上に凸なので、これは値の性質である。
     *
     * **ただし画面の広がりは逆に大きくなる** — 入れ子は段ごとに遠近を掛けるので、
     * 浅くなったぶんを上回る（→ `BookletTurnGeometryTest` の「積み上げた広がり」）。
     * **深さの話と広がりの話を混ぜない。**
     */
    @Test
    fun `帯に分けると深さの合計は浅くなる`() {
        listOf(45f, 90f, 135f).forEach { angle ->
            val curled = (0 until CURL_BANDS).map { sheetStanding(sheetBandAngle(angle, it)) }.average()

            assertTrue(
                "$angle 度で、帯に分けても深さが浅くなっていません。",
                curled < sheetStanding(angle) - TOLERANCE
            )
        }
    }

    /** **静止時は広がらない。** 縮小もかからない（判断9 の絵をそのまま残す）。 */
    @Test
    fun `静止時は広がらない`() {
        assertEquals(1f, sheetCurlSpread(0f), TOLERANCE)
        assertEquals(1f, sheetCurlSpread(180f), TOLERANCE)
    }

    // ── 曲がっていることの現れ ────────────────────────────────────────────

    /**
     * **真横まで送っても紙は消えない。これが「剛体ではない」ことの現れである。**
     *
     * 平らな紙は真横でちょうど高さを失う（判断10 はそこを表裏の継ぎ目に使っていた）。
     * **曲がった紙は真横でも弓なりに残る** — 帯ごとに向きが違うので、全部が同時に真横になることはない。
     *
     * 逆に言えば、**この検査が落ちたら曲がりは効いていない。**
     */
    @Test
    fun `真横でも紙は高さを失わない`() {
        assertTrue(
            "真横で紙の高さが残っていません。帯が全部同じ向きを向いており、曲がっていません。",
            projectedReach(90f) > 0.05f
        )
        assertEquals("平らな紙は真横で高さを失う（比較のための基準）。", 0f, flatReach(90f), TOLERANCE)
    }

    /**
     * **表裏は帯ごとに分かれる。** 曲がった紙では、表を向く帯と裏を向く帯が同居する。
     *
     * 1枚の面だったときは全部が同時に入れ替わっていた。**帯ごとに判定しないと、
     * 半分裏を向いている紙に表の文字が残る**（＝鏡像）。
     */
    @Test
    fun `真横の前後では表の帯と裏の帯が同居する`() {
        val faces = (0 until CURL_BANDS).map { sheetShowsFace(sheetBandAngle(90f, it)) }

        assertTrue("真横で表を向く帯がありません。", faces.any { it })
        assertFalse("真横で裏を向く帯がありません。帯ごとに判定していない可能性があります。", faces.all { it })
    }

    /** **平らの判定は、指を置いただけのずれを曲げの経路へ入れない。** */
    @Test
    fun `平らの判定は定位置だけを平らと見なす`() {
        assertTrue("定位置が平らと判定されていません。", sheetLiesFlat(0f))
        assertTrue(sheetLiesFlat(sheetAngleDegrees(turn = 0f, restack = 1f)))

        assertFalse("送り始めた紙が平らのままです。曲がりの経路へ入りません。", sheetLiesFlat(1f))
        assertFalse("積み直りの傾きが平らと判定されています。", sheetLiesFlat(sheetAngleDegrees(0f, 0f)))
    }

    // ── 本番がその値を使っていること（走査） ──────────────────────────────

    /**
     * **帯が本番へ配線されていること。**
     *
     * 純関数を残したまま配線だけ外す変異は、上の値の検査を全部通る（→ L55）。
     * **受理条件は代入の形で書く** — 回す角度は差分、蝶番は帯ごとに下がる、表裏は帯の絶対角。
     */
    @Test
    fun `帯の角度と蝶番と表裏が本番へ配線されている`() {
        val band = screen.bodyOf("private fun BoxScope.CurlBand(")

        assertTrue(
            "帯が曲がりの差分で回っていません（`rotationX = sheetBandTilt(angle, band)` が本番にありません）。" +
                "絶対角を渡すと、入れ子のぶんだけ角度が積み上がって紙が丸まります。",
            band.contains("rotationX = sheetBandTilt(angle, band)")
        )
        assertTrue(
            "帯の蝶番が帯ごとに下がっていません。全部が紙の上端で折れると、曲がりではなく扇になります。",
            band.contains("transformOrigin = TransformOrigin(0.5f, band.toFloat() / CURL_BANDS)")
        )
        assertTrue(
            "表裏が帯ごとの角度で決まっていません。曲がった紙は表の帯と裏の帯が同居します。",
            band.contains("if (sheetShowsFace(sheetBandAngle(angle, band)))")
        )
    }

    /**
     * **記録した絵を帯で描いていること。**
     *
     * 記録を経由しないと、帯ごとに中身を組み立て直すことになり、
     * **読み上げの木に同じ文字が帯の数だけ並ぶ。**
     */
    @Test
    fun `帯は記録した絵を描いている`() {
        val face = screen.bodyOf("internal fun BoxScope.CurledFace(")

        assertTrue(
            "紙の面を記録していません（`face.record { drawContent() }` が本番にありません）。",
            face.contains("face.record { drawContent() }")
        )
        assertTrue(
            "帯が記録した絵を描いていません（`drawLayer(face)` が本番にありません）。",
            screen.bodyOf("private fun BoxScope.CurlBand(").contains("drawLayer(face)")
        )
    }

    /**
     * **静止時は記録を経由しないこと。**
     *
     * 判断9 で実機確認した佇まいは「1ピクセルも変えない」と決めてある。
     * **記録を経由すると文字の描かれ方が変わりうる**ので、平らなときは本物をそのまま描く。
     */
    @Test
    fun `静止時は記録を経由せず本物を描く`() {
        val face = screen.bodyOf("internal fun BoxScope.CurledFace(")

        assertTrue(
            "静止時に本物を描く経路がありません。記録した絵を経由すると、" +
                "判断9 で実機確認した静止時の佇まいが変わりえます。",
            face.contains("if (sheetLiesFlat(sheetAngleDegrees(turn(), restack()))) drawContent()")
        )
        assertTrue(
            "帯が静止時にも描いています。二重に描かれます。",
            screen.bodyOf("private fun BoxScope.CurlBand(")
                .contains("if (sheetLiesFlat(angle)) return@drawBehind")
        )
    }

    // ── 補助 ──────────────────────────────────────────────────────────────

    /** 曲がった紙が縦に届く距離（紙の長さを1とする）。**投影は掛けない。** */
    private fun projectedReach(angleDegrees: Float): Float {
        var reach = 0f
        var far = 0f
        (0 until CURL_BANDS).forEach { band ->
            reach += cos(sheetBandAngle(angleDegrees, band) * PI / 180.0).toFloat() / CURL_BANDS
            if (reach > far) far = reach
        }
        return far
    }

    /** 平らな紙が縦に届く距離。**比較のための基準。** */
    private fun flatReach(angleDegrees: Float): Float =
        cos(angleDegrees * PI / 180.0).toFloat().coerceAtLeast(0f)

    private fun allAngles(): List<Float> = (0..180).map { it.toFloat() }

    private val screen: String by lazy {
        File("src/main/java/com/example/newproject/ui/screen/BookletScreen.kt").readText()
    }

    /** その関数の本体。**コメントと空白は落とす**（走査が文章で緩まないように）。 */
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
