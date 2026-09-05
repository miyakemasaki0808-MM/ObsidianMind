package com.example.newproject.ui

import androidx.compose.ui.graphics.DefaultCameraDistance
import com.example.newproject.ui.screen.CAMERA_DISTANCE_FACTOR
import com.example.newproject.ui.screen.sheetCameraDistance
import com.example.newproject.ui.screen.sheetAngleDegrees
import com.example.newproject.ui.screen.sheetShowsFace
import com.example.newproject.ui.screen.sheetShowsStack
import com.example.newproject.ui.screen.sheetSlotShift
import com.example.newproject.ui.screen.sheetStanding
import java.io.File
import kotlin.math.absoluteValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 天綴じの繰りの**向きと合成**を固定し、**本番がその値を実際に使っている**ことまで見る
 * （→ docs/dev/features/booklet_mode.md 判断10）。
 *
 * ## なぜ検査が要るか
 *
 * 前の版は「紙が2%縮む」だけの平行移動で、**設計どおりに動いていたのに実機で「めくった」と感じられなかった**
 * （→ docs/dev/lessons/L59.md）。足りなかったのは強度ではなく向きと変形である。
 * そこを直した以上、**向きが黙って戻る後退は、実機で触るまで誰も気づけない。**
 *
 * ## 3つの層で見る
 *
 * 1. **値**（純関数）— 倒れる向き・合成した角度・表裏・縁の在不在・定位置への付け替え
 * 2. **組合せ** — 繰りと積み直りは**同時に起きうる**ので、片方ずつでは面が永久に空く。
 *    実際にそこだけが空き、積み直り中に送ると表の文字が真横を越えて残った
 *    （2026-09-05 のレビュー `P2-1`・`P2-2`）
 * 3. **配線**（走査）— **純関数を残したまま本番の代入を外す変異**は、1と2を全部通す。
 *    受理条件は「名前があること」ではなく**どの層のどの代入か**で書く（→ docs/dev/lessons/L55.md）
 *
 * **見え方そのものは実機ケースが持つ** — 重さ・速さ・気持ちよさは時間の中にしかない
 * （→ docs/dev/system/bearing_channels.md §7）。
 */
class BookletTurnGeometryTest {

    // ── 倒れる向き ────────────────────────────────────────────────────────

    /** 定位置で積み終わっていれば、紙は寝ている。 */
    @Test
    fun `定位置の紙は倒れていない`() {
        assertEquals(0f, sheetAngleDegrees(turn = 0f, restack = 1f), TOLERANCE)
    }

    /**
     * **これが本体の受け入れ条件。** 倒れるのは*送り出される側*だけである。
     *
     * 天綴じでめくられるのはいま手前にある1枚で、下から現れる紙は平らに置かれたまま待つ。
     * **符号を捨てて絶対値にすると、戻す操作でも次の紙が倒れる。**
     */
    @Test
    fun `倒れるのは送り出される側だけで、これから出る紙は寝たまま`() {
        assertTrue("送り出される紙が倒れていません。", sheetAngleDegrees(0.5f, restack = 1f) < 0f)

        assertEquals(
            "これから出てくる紙まで倒れています。符号を捨てていないか確認してください。",
            0f,
            sheetAngleDegrees(-0.5f, restack = 1f),
            TOLERANCE
        )
        assertEquals(0f, sheetAngleDegrees(-1f, restack = 1f), TOLERANCE)
    }

    /**
     * **下端は手前へ出る。** 角度が負であることがそれを意味する。
     *
     * Compose の回転は蝶番より下の点を +Z（奥）へ送るので、
     * **正の角度を与えると下端が奥へ引っ込み、天綴じの逆さまになる。**
     */
    @Test
    fun `角度は常に負で、下端が手前へ持ち上がる`() {
        assertTrue(
            "角度に正の値が混じっています。下端が奥へ引っ込み、天綴じが裏返ります。",
            allCombinations().all { (turn, restack) -> sheetAngleDegrees(turn, restack) <= 0f }
        )
    }

    /** 送り切ると真上へ抜ける。**半分でちょうど真横。** */
    @Test
    fun `送り切ると半回転して上へ抜ける`() {
        assertEquals(-180f, sheetAngleDegrees(1f, restack = 1f), TOLERANCE)
        assertEquals(-90f, sheetAngleDegrees(0.5f, restack = 1f), TOLERANCE)
    }

    /** ずれが1ページを超えても倒れ続けない。**抜けた紙はそれ以上動かない。** */
    @Test
    fun `1ページ分を超えて倒れ続けない`() {
        assertEquals(
            sheetAngleDegrees(1f, restack = 1f),
            sheetAngleDegrees(2.5f, restack = 1f),
            TOLERANCE
        )
    }

    // ── 繰りと積み直りの合成（共存しうる2つ） ─────────────────────────────

    /** 積み直りの最中は少しだけ傾く。**繰り切らない。** */
    @Test
    fun `積み直りは傾くだけで繰り切らない`() {
        assertTrue(
            "積み直りが半分以上めくれています。「めくった」と嘘をつく強さです。",
            sheetAngleDegrees(turn = 0f, restack = 0f) > -90f
        )
    }

    /**
     * **これが `P2-2` の受け入れ条件。** どの組合せでも半回転を越えない。
     *
     * 越えると紙が裏から表へ戻り始め、**抜けたはずの紙がもう一度現れる。**
     * 繰りと積み直りを別々に持って足していたときは、送り切りで `-202°` まで進んでいた。
     */
    @Test
    fun `繰りと積み直りが重なっても半回転を越えない`() {
        val overshoot = allCombinations()
            .filter { (turn, restack) -> sheetAngleDegrees(turn, restack) < -180f - TOLERANCE }

        assertTrue(
            "合成した角度が半回転を越えています: " +
                overshoot.take(3).joinToString { (t, r) ->
                    "turn=$t restack=$r → ${sheetAngleDegrees(t, r)}"
                },
            overshoot.isEmpty()
        )
    }

    /**
     * **表裏は合成後の角度だけで決まる。**
     *
     * 送りの進み具合（`turn`）から決めていたときは、積み直りが重なった分だけ
     * **実際の紙面が真横を越えても表の文字が残り、鏡像になった。**
     */
    @Test
    fun `表裏は合成後の角度と食い違わない`() {
        val inconsistent = allCombinations().filter { (turn, restack) ->
            val angle = sheetAngleDegrees(turn, restack)
            sheetShowsFace(angle) != (angle >= -90f)
        }

        assertTrue(
            "紙面が真横を越えているのに表の文字が残っています（鏡像になります）: " +
                inconsistent.take(3).joinToString { (t, r) ->
                    "turn=$t restack=$r → ${sheetAngleDegrees(t, r)}"
                },
            inconsistent.isEmpty()
        )
    }

    /**
     * **これが再レビュー `P2-1` の受け入れ条件。指を止めたまま積み直りが終わっても紙は起き直らない。**
     *
     * 足し算にしていたときは、`turn=0.40` で指を止めたまま積み直りが終わるだけで
     * **角度が `-94°` から `-72°` へ22度*起き直り*（指と逆へ動き）、`-90°` を逆向きに横切って
     * 表裏まで切り替わった。** すべての引き直し直後に成立していた。
     *
     * **送りが傾きを上回った区間では、指が角度を所有する。**
     */
    @Test
    fun `指を止めたまま積み直りが終わっても紙は起き直らない`() {
        listOf(0.40f, 0.90f).forEach { turn ->
            val held = sheetAngleDegrees(turn, restack = 1f)
            val series = (0..20).map { sheetAngleDegrees(turn, restack = it / 20f) }

            assertTrue(
                "turn=$turn で指を止めているのに、積み直りの完了だけで紙が動きます: $series",
                series.all { (it - held).absoluteValue < TOLERANCE }
            )
        }
    }

    /**
     * **積み直りだけで表裏が入れ替わらない。**
     *
     * 指を動かしていないのに文字が出たり消えたりするのが、`P2-1` の一番目に見える形だった。
     * 傾きは半回転の半分に届かないので、**構造的に起こりえない**ことをここで固定する。
     */
    @Test
    fun `積み直りだけで表裏が入れ替わらない`() {
        val flipping = (-4..24).map { it / 20f }.filter { turn ->
            (0..20).map { sheetShowsFace(sheetAngleDegrees(turn, it / 20f)) }.distinct().size > 1
        }

        assertTrue(
            "指を止めたまま表裏が切り替わる送り位置があります: $flipping",
            flipping.isEmpty()
        )
    }

    /**
     * **定位置では積み直りが最後まで進む。**
     *
     * 大きい側を採る合成にすると、送りが浅い区間は**積み直りが角度を所有する** —
     * そこが起き上がるのは「積み直りが終わる」ことそのものなので、正しい。
     * ここを潰すと、束を引き直したときの置き直しが消える。
     */
    @Test
    fun `定位置では積み直りが最後まで進む`() {
        val tilted = sheetAngleDegrees(turn = 0f, restack = 0f)

        assertTrue("積み直りで紙が傾いていません。", tilted < 0f)
        assertEquals("積み終わっても傾きが残っています。", 0f, sheetAngleDegrees(0f, 1f), TOLERANCE)

        // 送りが傾きに満たないうちは、傾きのほうが深い（指はまだ角度を所有しない）。
        assertEquals(tilted, sheetAngleDegrees(turn = 0.05f, restack = 0f), TOLERANCE)
        assertTrue(
            "浅い送りで積み直りが終わったのに、紙が置き直されていません。",
            sheetAngleDegrees(0.05f, restack = 1f) > tilted
        )
    }

    /**
     * **積み直りの最中に1ページ送り切っても破綻しない**（実機ケースが明示している操作）。
     *
     * 角度は単調に倒れ、途中で戻らず、終端で半回転を越えない。
     * **戻る区間があると、抜けかけた紙がもう一度表を見せる。**
     */
    @Test
    fun `積み直りの最中に送り切っても角度は戻らない`() {
        val angles = (0..20).map { sheetAngleDegrees(turn = it / 20f, restack = 0f) }

        assertTrue(
            "送っている途中で紙が起き直っています: $angles",
            angles.zipWithNext().all { (before, after) -> after <= before + TOLERANCE }
        )
        assertEquals("送り切りが半回転になっていません。", -180f, angles.last(), TOLERANCE)
    }

    // ── 表と裏 ────────────────────────────────────────────────────────────

    /**
     * **裏返るのは真横を過ぎてから。**
     *
     * 早いと文字が載ったまま裏を向き、鏡像になる。遅いと裏に文字が残る。
     * 切り替わる点の紙は高さ0なので、**そこでだけ入れ替えれば入れ替わりは見えない。**
     */
    @Test
    fun `裏返るのは真横を過ぎてから`() {
        assertTrue("定位置の紙が裏を向いています。", sheetShowsFace(0f))
        assertTrue("真横の手前で裏返っています。", sheetShowsFace(-89f))
        assertTrue("真横のちょうどで裏返っています。", sheetShowsFace(-90f))

        assertFalse("真横を過ぎても表のままです。文字が鏡像になります。", sheetShowsFace(-91f))
        assertFalse(sheetShowsFace(-180f))
    }

    // ── 束の縁 ────────────────────────────────────────────────────────────

    /**
     * **これが `P2-1` の受け入れ条件。** 持ち上げられた紙は束を置いていく。
     *
     * 縁は回らない不透明な面なので、めくられる紙に付けたままにすると
     * **定位置に残って次の紙を覆う。** 次の紙がその場に現れなくなる。
     */
    @Test
    fun `持ち上げられた紙は束の縁を置いていく`() {
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
     * **紙は動かない。倒れるだけ。**
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

    /**
     * **影は「紙がどれだけ立っているか」で深くなる。** 真横で最大、寝ていればゼロ。
     *
     * **入力は合成後の角度**なので、繰りでも積み直りでも同じ尺度で深くなる。
     */
    @Test
    fun `影は紙が立っているあいだだけ深い`() {
        assertEquals("定位置で影が深くなっています。", 0f, sheetStanding(0f), TOLERANCE)
        assertEquals("真横で影が最大になっていません。", 1f, sheetStanding(-90f), TOLERANCE)
        assertEquals("抜け切った紙の影が残っています。", 0f, sheetStanding(-180f), TOLERANCE)

        assertTrue(
            "積み直りの最中に影が深くなっていません。",
            sheetStanding(sheetAngleDegrees(turn = 0f, restack = 0f)) > 0f
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
     * **純関数を残したまま本番の代入を外す変異は、上の値の検査を全部通る**（→ L55）。
     *
     * だから受理条件を**代入の形**で書く。名前がどこかに在ることでは満たされない —
     * コメントと import は落としてあり、未使用の関数を残しても代入の文字列は戻らない。
     *
     * **どの層のどの代入かまで見る。** 紙の面（回転・影・表裏）と束の縁（在不在）と
     * ページの枠（定位置への付け替え）は別の層で、混ぜると「誰かが使っている」に緩む。
     */
    @Test
    fun `紙の面が合成後の角度で回り、影と表裏もそこから決まる`() {
        val sheet = screen.bodyOf("private fun BookletSheet(")

        assertTrue(
            "紙が合成後の角度で回っていません（`rotationX = angle` が本番にありません）。",
            sheet.contains("val angle = sheetAngleDegrees(turn(), restack())") &&
                sheet.contains("rotationX = angle")
        )
        assertTrue(
            "影が合成後の角度から決まっていません（`sheetStanding(angle)` が本番にありません）。",
            sheet.contains("sheetStanding(angle)")
        )
        assertTrue(
            "表裏が合成後の角度から決まっていません。送りの進み具合だけで判定すると、" +
                "積み直りが重なった分だけ表の文字が真横を越えて残ります。",
            sheet.contains("alpha = if (sheetShowsFace(angle)) 1f else 0f")
        )
    }

    /** **束の縁の在不在が本番へ配線されていること。** 外すと回らない面が次の紙を覆う。 */
    @Test
    fun `束の縁の在不在が本番へ配線されている`() {
        assertTrue(
            "束の縁が `sheetShowsStack` で切られていません。めくられる紙が縁を連れて行き、" +
                "回らない面が定位置に残って次の紙を覆います。",
            screen.bodyOf("private fun BookletSheet(")
                .contains("alpha = if (sheetShowsStack(turn())) 1f else 0f")
        )
    }

    /**
     * **紙を定位置へ留め置く付け替えが本番へ配線されていること。**
     *
     * 外すと紙は倒れながら流れ、綴じてある感じが消える。
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
     * **倒しても何も起きていないように見える。** 角度がすべて正しくても絵にならない。
     */
    @Test
    fun `めくられる紙が次の紙より上に描かれる`() {
        assertTrue(
            "ページに重なりの指定（`zIndex`）がありません。番号順のままだと、" +
                "めくった紙が次の紙の下へ潜り、繰りが見えなくなります。",
            screen.bodyOf("private fun ColumnScope.BookletPager(").contains("zIndex(-page.toFloat())")
        )
    }

    /**
     * **カメラ距離は紙の高さより遠く、しかし遠すぎない。**
     *
     * **上限がこの検査の本体である。** 下限（高さより遠く）は公式の指示だが、
     * **遠すぎる側には指示が無く、超えても何も起きない** — 遠近が静かに消えて、
     * 倒れた紙がただ縦に潰れる板になるだけである。**実際にそうなっていた**（→ `sheetCameraDistance`）。
     *
     * **単位はピクセルではない。** `cameraDistance` に紙の高さ（ピクセル）を渡すと、
     * 既定の数百倍遠い値になり実質無限遠になる。**上限を既定の桁で押さえて、その後退を落とす。**
     */
    @Test
    fun `カメラ距離は紙の高さより遠く、しかし遠近が消えるほど遠くない`() {
        // 1080×2364・390dpi の実機で、紙が画面の高さいっぱいに近い場合。
        val density = 390f / 160f
        val sheetHeightPx = 1900f
        val distance = sheetCameraDistance(sheetHeightPx, density)

        assertTrue(
            "カメラ距離（$distance）が紙の高さより近くなっています。回した像に破綻が出ます。",
            distance * density * 160f >= sheetHeightPx
        )
        assertTrue(
            "カメラ距離（$distance）が既定（$DefaultCameraDistance）とかけ離れて遠いです。" +
                "単位はピクセルではないので、ピクセル値を渡すと遠近が丸ごと消えます。",
            distance <= DefaultCameraDistance * 3f
        )
        assertTrue(
            "遠近の倍率が 1 を下回っています。公式の指示（面の高さより遠く）の外です。",
            CAMERA_DISTANCE_FACTOR >= 1f
        )
    }

    /** 紙が小さくなればカメラも近づく。**寸法を直に書いていないこと。** */
    @Test
    fun `カメラ距離は紙の高さに比例する`() {
        val density = 2.75f

        assertEquals(
            sheetCameraDistance(2000f, density) / 2f,
            sheetCameraDistance(1000f, density),
            TOLERANCE
        )
    }

    /** 走査。**本番が高さと密度からカメラ距離を導いていること。** */
    @Test
    fun `カメラ距離は本番で紙の高さと密度から導かれる`() {
        assertTrue(
            "`cameraDistance` を紙の高さと密度から導いていません。" +
                "寸法を直に書くと、端末の解像度と分割画面で遠近の出方が変わります。",
            screen.bodyOf("private fun BookletSheet(")
                .contains("cameraDistance = sheetCameraDistance(size.height, density)")
        )
    }

    /**
     * **指を離したあとの倒れ切りは、ページャの既定より柔らかい。**
     *
     * 横送り版と同じスナップのまま運ぶ変位だけを半回転へ増やしたので、
     * **同じ時間に大きく動くことになり、実機で「速すぎる」と出た**（2026-09-05）。
     * 判断10 の「`flingBehavior` に触らない」は**動きが極小だった前提**の線なので、
     * ここで引き直した。**既定へ戻す後退は、実機で触るまで気づけない。**
     *
     * **触っているのはスナップの硬さだけ**であることも見る — 送り先の枚数や減衰まで
     * 差し替えると、「1回のフリックで1枚」という送りの性質そのものが動く。
     */
    @Test
    fun `指を離したあとの倒れ切りはページャの既定より柔らかい`() {
        val pager = screen.bodyOf("private fun ColumnScope.BookletPager(")

        assertTrue(
            "スナップの進み方を指定していません。ページャの既定（400）のままだと、" +
                "半回転を同じ時間で運ぶことになり速すぎます。",
            pager.contains("snapAnimationSpec = SHEET_SETTLE_SPEC")
        )
        assertTrue(
            "送り先の枚数や勢いの減衰まで差し替えています。触るのはスナップの進み方だけです。",
            !pager.contains("pagerSnapDistance") && !pager.contains("decayAnimationSpec")
        )
    }

    /**
     * **読み上げ操作の送りも、指を離したときと同じ進み方であること。**
     *
     * **手触りの入力を1つにしても、時間が違えば同じ手触りにはならない。**
     * `animateScrollToPage` の既定は硬さ 1500 で、指の側（200）より 7.5倍硬い。
     * 半回転を運ぶようになってから、**読み上げ操作では途中の紙が1〜2フレームしか見えなかった**
     * （2026-09-05 の実機検証）。判断10・§9 の「スワイプできない利用者にも同じ手触り」は、
     * **値だけでなく時間も揃えて初めて成立する。**
     */
    @Test
    fun `読み上げ操作の送りも指と同じ進み方をする`() {
        val pager = screen.bodyOf("private fun ColumnScope.BookletPager(")
        val defaulted = Regex("""animateScrollToPage\(\s*\w+\s*\)""").findAll(pager).count()

        assertTrue(
            "読み上げ操作の送りに進み方を渡していません。既定（1500）のままだと指より7.5倍速く、" +
                "途中の紙がほとんど見えません。",
            pager.contains("animationSpec = SHEET_SETTLE_SPEC")
        )
        assertEquals(
            "進み方を渡していない `animateScrollToPage` が残っています。" +
                "次へ・前への両方が指と同じ時間で進む必要があります。",
            0,
            defaulted
        )
    }

    // ── 補助 ──────────────────────────────────────────────────────────────

    /** 繰りと積み直りの組合せ。**共存しうるので、片方ずつでは面が空く。** */
    private fun allCombinations(): List<Pair<Float, Float>> =
        (-4..24).flatMap { t -> (0..20).map { r -> t / 20f to r / 20f } }

    /**
     * 関数1つ分の本体を切り出す。**同じ名前を複数の層が使うので、ファイル単位では足りない。**
     *
     * 次の行頭 `@Composable` までを本体とみなす。**厳密な構文解析はしない** —
     * 走査の役割は境界の固定までで、それ以上を背負わせない（→ docs/dev/lessons/L55.md）。
     */
    private fun String.bodyOf(signature: String): String {
        val start = indexOf(signature)
        require(start >= 0) { "走査対象の関数が見つかりません: $signature" }
        val next = indexOf("\n@Composable", start + signature.length)
        return if (next < 0) substring(start) else substring(start, next)
    }

    /** **コメントと import を落としてから走査する**（→ BearingChannelTest と同じ理由）。 */
    private val screen: String =
        File("src/main/java/com/example/newproject/ui/screen/BookletScreen.kt")
            .readText()
            .replace(Regex("""/\*[\s\S]*?\*/"""), " ")
            .replace(Regex("""//[^\n]*"""), " ")
            .lines()
            .filterNot { it.trimStart().startsWith("import ") }
            .joinToString("\n")

    private companion object {
        const val TOLERANCE = 0.0001f
    }
}
