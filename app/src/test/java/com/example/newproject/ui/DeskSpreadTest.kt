package com.example.newproject.ui

import com.example.newproject.ui.screen.DESK_SPREAD_FLING_DP_PER_SEC
import com.example.newproject.ui.screen.DESK_SPREAD_SWIPE_DP
import com.example.newproject.ui.screen.DeskFace
import com.example.newproject.ui.screen.DeskFacePlacement
import com.example.newproject.ui.screen.DeskSpread
import com.example.newproject.ui.screen.DeskSpreadGesture
import com.example.newproject.ui.screen.deskFacePlacement
import com.example.newproject.ui.screen.deskSpreadActionsFor
import com.example.newproject.ui.screen.deskSpreadAfter
import com.example.newproject.ui.screen.deskSpreadGestureFor
import com.example.newproject.ui.screen.deskSpreadShowing
import com.example.newproject.ui.screen.position
import org.junit.Assert.assertEquals
import org.junit.Test

// 机の画面で片側を払って1画面にする（→ features/margin_pane.md §5.1）。
class DeskSpreadTest {

    /** ゆっくりでも、横に十分な距離を払えば広げる。左へは左、右へは右。 */
    @Test
    fun `ゆっくりでも横に十分払えば、払った向きを返す`() {
        assertEquals(DeskSpreadGesture.Left, deskSpreadGestureFor(dragDp = -DESK_SPREAD_SWIPE_DP, velocityDpPerSec = -10f))
        assertEquals(DeskSpreadGesture.Right, deskSpreadGestureFor(dragDp = DESK_SPREAD_SWIPE_DP, velocityDpPerSec = 10f))
    }

    /** **短く素早く払う（フリック）でも広げる。** 距離だけで決めると、フリックでは何も起きない。 */
    @Test
    fun `横に短く素早く払うと、距離が足りなくても払った向きを返す`() {
        assertEquals(DeskSpreadGesture.Left, deskSpreadGestureFor(dragDp = -20f, velocityDpPerSec = -DESK_SPREAD_FLING_DP_PER_SEC))
        assertEquals(DeskSpreadGesture.Right, deskSpreadGestureFor(dragDp = 20f, velocityDpPerSec = DESK_SPREAD_FLING_DP_PER_SEC))
    }

    /** 距離も速さも足りなければ何もしない。速さが距離と逆向きなら数えない。 */
    @Test
    fun `距離も速さも足りないか、速さが逆向きなら何もしない`() {
        assertEquals(DeskSpreadGesture.None, deskSpreadGestureFor(dragDp = 40f, velocityDpPerSec = DESK_SPREAD_FLING_DP_PER_SEC - 1f))
        assertEquals(DeskSpreadGesture.None, deskSpreadGestureFor(dragDp = -40f, velocityDpPerSec = 1_000f))
        assertEquals(DeskSpreadGesture.None, deskSpreadGestureFor(dragDp = 40f, velocityDpPerSec = -1_000f))
    }

    /** 左へ払うと補助の面が、右へ払うと本文が広がる。1回の払いで1段だけ動く。 */
    @Test
    fun `払った向きへ仕切りを1段だけ動かす`() {
        assertEquals(DeskSpread.SupportingOnly, deskSpreadAfter(DeskSpread.Both, DeskSpreadGesture.Left))
        assertEquals(DeskSpread.MainOnly, deskSpreadAfter(DeskSpread.Both, DeskSpreadGesture.Right))
        // 1画面から逆へ払えば2面へ戻り、反対の片側へ一度に跳ばない。
        assertEquals(DeskSpread.Both, deskSpreadAfter(DeskSpread.MainOnly, DeskSpreadGesture.Left))
        assertEquals(DeskSpread.Both, deskSpreadAfter(DeskSpread.SupportingOnly, DeskSpreadGesture.Right))
    }

    /** 端ではそれ以上動かない。払わなかったら変えない。 */
    @Test
    fun `端と払わなかったときは変えない`() {
        assertEquals(DeskSpread.SupportingOnly, deskSpreadAfter(DeskSpread.SupportingOnly, DeskSpreadGesture.Left))
        assertEquals(DeskSpread.MainOnly, deskSpreadAfter(DeskSpread.MainOnly, DeskSpreadGesture.Right))
        DeskSpread.entries.forEach { assertEquals(it, deskSpreadAfter(it, DeskSpreadGesture.None)) }
    }

    /** **隠れた面の中身へ送る依頼が来たら2面へ戻す。** 見えている面へ送るなら広げ方を変えない。 */
    @Test
    fun `隠れた面へ送るときだけ2面へ戻す`() {
        assertEquals(DeskSpread.Both, deskSpreadShowing(DeskSpread.MainOnly, DeskFace.Supporting))
        assertEquals(DeskSpread.Both, deskSpreadShowing(DeskSpread.SupportingOnly, DeskFace.Main))
        assertEquals(DeskSpread.MainOnly, deskSpreadShowing(DeskSpread.MainOnly, DeskFace.Main))
        assertEquals(DeskSpread.SupportingOnly, deskSpreadShowing(DeskSpread.SupportingOnly, DeskFace.Supporting))
        DeskFace.entries.forEach { assertEquals(DeskSpread.Both, deskSpreadShowing(DeskSpread.Both, it)) }
    }

    /** 読み上げの操作は、払いで行ける先と同じ。1画面からは2面へ戻すだけ。 */
    @Test
    fun `読み上げの操作は払いで行ける先と同じ`() {
        DeskSpread.entries.forEach { spread ->
            val reachable = DeskSpreadGesture.entries.map { deskSpreadAfter(spread, it) }.filter { it != spread }.toSet()
            assertEquals(spread.name, reachable, deskSpreadActionsFor(spread).map { it.second }.toSet())
        }
    }

    /** 2面では、本文は左端から本文の幅、補助の面は溝の向こうから残りの幅。 */
    @Test
    fun `2面では本文と補助の面を溝を挟んで並べる`() {
        assertEquals(DeskFacePlacement(mainX = 0f, mainWidth = 398f, supportingX = 414f, supportingWidth = 398f), placementAt(DeskSpread.Both))
    }

    /** 本文だけでは本文が窓の幅いっぱいになり、補助の面は**元の幅のまま**窓の右の外にいる。 */
    @Test
    fun `本文だけでは本文が窓いっぱいで、補助の面は元の幅のまま右の外にいる`() {
        val placement = placementAt(DeskSpread.MainOnly)
        assertEquals(0f, placement.mainX)
        assertEquals(TOTAL, placement.mainWidth)
        assertEquals(398f, placement.supportingWidth)
        assertEquals(true, placement.supportingX >= TOTAL)
    }

    /** 補助の面だけでは補助の面が窓の幅いっぱいになり、本文は**元の幅のまま**窓の左の外にいる。 */
    @Test
    fun `補助の面だけでは補助の面が窓いっぱいで、本文は元の幅のまま左の外にいる`() {
        val placement = placementAt(DeskSpread.SupportingOnly)
        assertEquals(0f, placement.supportingX)
        assertEquals(TOTAL, placement.supportingWidth)
        assertEquals(398f, placement.mainWidth)
        assertEquals(true, placement.mainX + placement.mainWidth <= 0f)
    }

    /**
     * **滑っている途中は、広がる面だけが幅を変え、隠れる面は元の幅のまま。** 隠れる面まで縮めると、
     * 滑っている間ずっと中身が折り返し直される。面どうしは溝より近づかない。
     */
    @Test
    fun `滑っている途中は広がる面だけが幅を変え、溝を保つ`() {
        listOf(0.25f, 0.5f, 0.75f).forEach { t ->
            val toMain = deskFacePlacement(t, TOTAL, MAIN, GUTTER)
            assertEquals(398f, toMain.supportingWidth)
            assertEquals(GUTTER, toMain.supportingX - (toMain.mainX + toMain.mainWidth), 0.001f)

            val toSupporting = deskFacePlacement(-t, TOTAL, MAIN, GUTTER)
            assertEquals(398f, toSupporting.mainWidth)
            assertEquals(GUTTER, toSupporting.supportingX - (toSupporting.mainX + toSupporting.mainWidth), 0.001f)
        }
    }

    private fun placementAt(spread: DeskSpread) = deskFacePlacement(spread.position, TOTAL, MAIN, GUTTER)

    private companion object {
        // 平らに開いた Pixel 10 Pro Fold の机の画面に近い寸法。
        const val MAIN = 398f
        const val GUTTER = 16f
        const val TOTAL = MAIN + GUTTER + 398f
    }
}
