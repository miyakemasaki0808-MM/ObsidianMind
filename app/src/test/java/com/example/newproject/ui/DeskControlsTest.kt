package com.example.newproject.ui

import com.example.newproject.ui.screen.DESK_BAR_FLING_DP_PER_SEC
import com.example.newproject.ui.screen.DESK_BAR_SWIPE_DP
import com.example.newproject.ui.screen.DeskBarGesture
import com.example.newproject.ui.screen.deskBarGestureFor
import org.junit.Assert.assertEquals
import org.junit.Test

// 机の画面のつまみに触れて離したとき（→ features/margin_pane.md §5.1）。
class DeskControlsTest {

    /** 動かさずに離せば押した扱い。 */
    @Test
    fun `動かさずに離せば押した扱い`() {
        assertEquals(DeskBarGesture.Tap, deskBarGestureFor(moved = false, dragDp = 3f, velocityDpPerSec = 0f))
    }

    /** ゆっくりでも、十分な距離を払えば出し入れする。下へは出し、上へはしまう。 */
    @Test
    fun `ゆっくりでも十分に払えば、下へは出し上へはしまう`() {
        assertEquals(DeskBarGesture.Show, deskBarGestureFor(moved = true, dragDp = DESK_BAR_SWIPE_DP, velocityDpPerSec = 10f))
        assertEquals(DeskBarGesture.Hide, deskBarGestureFor(moved = true, dragDp = -DESK_BAR_SWIPE_DP, velocityDpPerSec = -10f))
    }

    /**
     * **短く素早く払う（フリック）でも出し入れする。** 距離だけで決めていたときは、押せば開閉するのに
     * フリックでは何も起きなかった。
     */
    @Test
    fun `短く素早く払うと、距離が足りなくても出し入れする`() {
        assertEquals(DeskBarGesture.Show, deskBarGestureFor(moved = true, dragDp = 12f, velocityDpPerSec = DESK_BAR_FLING_DP_PER_SEC))
        assertEquals(DeskBarGesture.Hide, deskBarGestureFor(moved = true, dragDp = -12f, velocityDpPerSec = -DESK_BAR_FLING_DP_PER_SEC))
    }

    /** 距離も速さも足りなければ何もしない。速さが距離と逆向きなら数えない。 */
    @Test
    fun `距離も速さも足りないか、速さが逆向きなら何もしない`() {
        assertEquals(DeskBarGesture.None, deskBarGestureFor(moved = true, dragDp = 12f, velocityDpPerSec = DESK_BAR_FLING_DP_PER_SEC - 1f))
        assertEquals(DeskBarGesture.None, deskBarGestureFor(moved = true, dragDp = 12f, velocityDpPerSec = -500f))
        assertEquals(DeskBarGesture.None, deskBarGestureFor(moved = true, dragDp = -12f, velocityDpPerSec = 500f))
    }

    /** 横へ払ったときは縦の距離も速さも小さいので、押した扱いにも出し入れにもしない（横の払いは片側を広げる操作に残す）。 */
    @Test
    fun `横へ払ったときは何もしない`() {
        assertEquals(DeskBarGesture.None, deskBarGestureFor(moved = true, dragDp = 2f, velocityDpPerSec = 20f))
        assertEquals(DeskBarGesture.None, deskBarGestureFor(moved = true, dragDp = 0f, velocityDpPerSec = 0f))
    }
}
