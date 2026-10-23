package com.example.newproject.ui

import com.example.newproject.ui.screen.CAMERA_UNIT_PX
import com.example.newproject.ui.screen.SPREAD_TURN_LIFT_SIGN
import com.example.newproject.ui.screen.SPREAD_TURN_SHADOW_DP
import com.example.newproject.ui.screen.spreadPositionDescription
import com.example.newproject.ui.screen.spreadSpinePivot
import com.example.newproject.ui.screen.spreadTurnCameraDistance
import com.example.newproject.ui.screen.spreadTurnEyeDistancePx
import com.example.newproject.ui.screen.spreadTurnLayers
import com.example.newproject.ui.screen.spreadTurnLiftPx
import com.example.newproject.ui.screen.spreadTurnShadowDp
import com.example.newproject.ui.screen.spreadTurnTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// 本文の見開きのめくり（→ features/note_spread.md 判断7）。めくる1枚が綴じ目を軸に立ち上がって倒れる。
// 向き（どちらが手前か）は値に現れないので、ここでは一貫性だけを見る。手前かどうかは描いた画素で確かめる（NoteReadingFlowTest）。
class SpreadTurnTest {

    /** 止まっている見開きは回さず、定位置で両ページを出す。上に重ねもしない。 */
    @Test
    fun `止まっている見開きは回さず両ページを出す`() {
        val layers = spreadTurnLayers(0f)
        assertEquals(0f, layers.slotShift, 0f)
        assertTrue(layers.left.shown && layers.right.shown)
        assertEquals(0f, layers.left.rotationY, 0f)
        assertEquals(0f, layers.right.rotationY, 0f)
        assertFalse(layers.onTop)
    }

    /**
     * **次へめくる途中、表（今の右ページ）が綴じ目を軸に回り、90度を越えると裏（次の左ページ）に替わる。**
     * めくり量 p のとき、今の見開きは p、次の見開きは p - 1 だけ離れている。
     */
    @Test
    fun `次へめくると表が回り、90度を越えると裏に替わる`() {
        val leaving = spreadTurnLayers(0.25f)
        val arriving = spreadTurnLayers(0.25f - 1f)
        assertEquals("表は45度", SPREAD_TURN_LIFT_SIGN * 45f, leaving.right.rotationY, 0.001f)
        assertTrue(leaving.right.shown && leaving.onTop)
        assertTrue("めくらない左ページは動かない", leaving.left.shown && leaving.left.rotationY == 0f)
        assertFalse("90度より前に裏を描いた", arriving.left.shown)
        assertTrue("めくった紙の下のページが出ていない", arriving.right.shown && arriving.right.rotationY == 0f)
        assertFalse(arriving.onTop)

        val leavingLate = spreadTurnLayers(0.75f)
        val arrivingLate = spreadTurnLayers(0.75f - 1f)
        assertFalse("90度を越えても表を描いた", leavingLate.right.shown)
        assertFalse(leavingLate.onTop)
        assertTrue(arrivingLate.left.shown && arrivingLate.onTop)
        assertEquals("裏は綴じ目の左で、寝るまで残り45度", -SPREAD_TURN_LIFT_SIGN * 45f, arrivingLate.left.rotationY, 0.001f)
    }

    /** **表と裏は同じ1枚。** 90度ちょうど以外は、どの量でもどちらか一方だけを描き、描いている見開きが上に来る。 */
    @Test
    fun `表と裏はどちらか一方だけを描き、描いている見開きが上に来る`() {
        (1 until 100).map { it / 100f }.filter { it != 0.5f }.forEach { p ->
            val leaving = spreadTurnLayers(p)
            val arriving = spreadTurnLayers(p - 1f)
            assertTrue("p=$p で表と裏を両方か、どちらも描かない", leaving.right.shown != arriving.left.shown)
            assertEquals("p=$p で描いていない見開きが上に来た", leaving.right.shown, leaving.onTop)
            assertEquals("p=$p で描いていない見開きが上に来た", arriving.left.shown, arriving.onTop)
            // 同じ1枚なので、表の角度と裏の角度は足して180度。
            val front = kotlin.math.abs(leaving.right.rotationY)
            val back = kotlin.math.abs(arriving.left.rotationY)
            assertEquals("p=$p で表と裏の角度が1枚の紙になっていない", 180f, front + back, 0.01f)
        }
    }

    /** 90度ちょうどは綴じ目の上の線になるので、表も裏も描かない。 */
    @Test
    fun `90度ちょうどは表も裏も描かない`() {
        assertFalse(spreadTurnLayers(0.5f).right.shown)
        assertFalse(spreadTurnLayers(-0.5f).left.shown)
        assertFalse(spreadTurnLayers(0.5f).onTop || spreadTurnLayers(-0.5f).onTop)
    }

    /**
     * **前へめくるときも同じ規則。** 今の見開きは負の向きに離れ、左ページが立ち上がって右へ倒れる。
     * 前の見開きの右ページ（裏）は90度を越えてから出て、それまでは前の左ページが下で動かない。
     */
    @Test
    fun `前へめくると左ページが立ち上がり、前の見開きの右ページが裏として出る`() {
        val leaving = spreadTurnLayers(-0.1f)
        val previous = spreadTurnLayers(-0.1f + 1f)
        assertTrue(leaving.left.shown && leaving.onTop)
        assertEquals(-SPREAD_TURN_LIFT_SIGN * 18f, leaving.left.rotationY, 0.001f)
        assertTrue("めくらない右ページは動かない", leaving.right.shown && leaving.right.rotationY == 0f)
        assertFalse(previous.right.shown)
        assertTrue("下に出てくる前の左ページが出ていない", previous.left.shown && previous.left.rotationY == 0f)
    }

    /**
     * **1つ以上離れた見開きは描かず、置き直しもしない** — 置き直すと画面の外の見開きが今の見開きに重なり、表示の判定から見えてしまう。
     * めくっている2つの見開きだけを定位置へ置き直す。
     */
    @Test
    fun `1つ以上離れた見開きは描かず置き直さず、めくっている見開きは定位置へ置き直す`() {
        listOf(1f, -1f, 1.5f, -2f).forEach { offset ->
            val layers = spreadTurnLayers(offset)
            assertFalse("$offset 離れた見開きを描いた", layers.left.shown || layers.right.shown || layers.onTop)
            assertEquals("$offset 離れた見開きを置き直した", 0f, layers.slotShift, 0f)
        }
        listOf(0.3f, -0.7f).forEach { assertEquals(it, spreadTurnLayers(it).slotShift, 0f) }
    }

    /** **軸は溝の中央。** 右ページは左端から溝の半分だけ左、左ページは右端から溝の半分だけ右で、どちらも同じ1本の線になる。 */
    @Test
    fun `軸は溝の中央で、表と裏が同じ線で回る`() {
        val page = 400f
        val gutter = 16f
        val spine = page + gutter / 2f
        // 右ページは見開きの左端から page + gutter の位置にある。
        assertEquals(spine, page + gutter + spreadSpinePivot(page, gutter, rightPage = true) * page, 0.001f)
        assertEquals(spine, spreadSpinePivot(page, gutter, rightPage = false) * page, 0.001f)
    }

    /**
     * **遠近は、持ち上がった縁が上下の余地に収まるいちばん強いもの。** 立った90度で余地をちょうど使い切り、どの角度でも越えない。
     * 弱すぎる側も挟む — 45度でも余地の半分以上は持ち上がる（遠すぎると遠近が静かに消える → docs/dev/lessons/L60.md）。
     */
    @Test
    fun `持ち上がった縁は上下の余地に収まり、45度でも余地の半分以上持ち上がる`() {
        val width = 400f
        val height = 650f
        val room = 24f
        val eye = spreadTurnEyeDistancePx(width, height, room)
        assertTrue("カメラが紙より近い（立った紙の縁がカメラの裏へ回る）", eye > width)
        assertEquals(room, spreadTurnLiftPx(width, height, eye, 90f), 0.01f)
        (0..90).forEach { angle ->
            assertTrue("$angle 度で余地を越えた", spreadTurnLiftPx(width, height, eye, angle.toFloat()) <= room + 0.01f)
        }
        assertTrue("45度でほとんど持ち上がらない", spreadTurnLiftPx(width, height, eye, 45f) >= room / 2f)
        assertEquals("cameraDistance の単位は渡した値の72倍が画素", eye / CAMERA_UNIT_PX, spreadTurnCameraDistance(width, height, room), 0.001f)
    }

    /** 影は立つほど深く、寝ている紙は落とさない。 */
    @Test
    fun `影は立つほど深く、寝ている紙は落とさない`() {
        assertEquals(0f, spreadTurnShadowDp(0f), 0.001f)
        assertEquals(SPREAD_TURN_SHADOW_DP, spreadTurnShadowDp(SPREAD_TURN_LIFT_SIGN * 90f), 0.001f)
        assertTrue(spreadTurnShadowDp(30f) < spreadTurnShadowDp(60f))
    }

    /** **読み上げの操作で送れるのは組み上がった見開きの中だけ。** 最初と最後ではその向きの操作を出さない。 */
    @Test
    fun `読み上げで送れるのは組み上がった見開きの中だけ`() {
        assertEquals(1, spreadTurnTargets(current = 0, available = 3).next)
        assertNull(spreadTurnTargets(current = 0, available = 3).previous)
        assertNull(spreadTurnTargets(current = 2, available = 3).next)
        assertEquals(1, spreadTurnTargets(current = 2, available = 3).previous)
        // 組み上がる途中で、次の見開きがまだ組まれていない。
        assertNull(spreadTurnTargets(current = 0, available = 1).next)
    }

    /** 送った後に読み上げる位置。総数は全体を組み終えるまで決まらないので言わない。 */
    @Test
    fun `読み上げる位置はn番目の見開きで、総数は言わない`() {
        assertEquals("1番目の見開き", spreadPositionDescription(0))
        assertEquals("3番目の見開き", spreadPositionDescription(2))
    }

    /**
     * **アニメーションの倍率を上書きしない**（→ features/note_spread.md 判断7）。上書きすると、設定を切っている利用者にだけ回転が戻る。
     * 読み上げからの送りと指を離した後の寄せは時間で進むので、倍率0なら途中の回転が省かれる。それは実機ケースが持つ。
     */
    @Test
    fun `見開きのめくりはアニメーションの倍率を上書きしない`() {
        val sources = listOf("NoteSpread.kt", "SpreadTurnGeometry.kt").map { File("src/main/java/com/example/newproject/ui/screen/$it") }
        assertTrue(sources.all { it.exists() })
        assertFalse(sources.any { it.readText().contains("MotionDurationScale") })
    }
}
