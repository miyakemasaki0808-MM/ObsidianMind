package com.example.newproject.ui

import com.example.newproject.ui.screen.DESK_BAR_FLING_DP_PER_SEC
import com.example.newproject.ui.screen.DESK_BAR_SWIPE_DP
import com.example.newproject.ui.screen.DeskBarGesture
import com.example.newproject.ui.screen.deskBarGestureFor
import com.example.newproject.ui.screen.isVerticalSwipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// 机の画面のつまみに触れて離したとき（→ features/margin_pane.md §5.1）。
class DeskControlsTest {

    /** 動かさずに離せば押した扱い（帯を開く側では何もしない）。 */
    @Test
    fun `動かさずに離せば押した扱い`() {
        assertEquals(DeskBarGesture.Tap, deskBarGestureFor(moved = false, vertical = false, dragDp = 3f, velocityDpPerSec = 0f))
    }

    /** ゆっくりでも、縦に十分な距離を払えば出し入れする。下へは出し、上へはしまう。 */
    @Test
    fun `ゆっくりでも縦に十分払えば、下へは出し上へはしまう`() {
        assertEquals(DeskBarGesture.Show, deskBarGestureFor(moved = true, vertical = true, dragDp = DESK_BAR_SWIPE_DP, velocityDpPerSec = 10f))
        assertEquals(DeskBarGesture.Hide, deskBarGestureFor(moved = true, vertical = true, dragDp = -DESK_BAR_SWIPE_DP, velocityDpPerSec = -10f))
    }

    /** **縦に短く素早く払う（フリック）でも出し入れする。** 距離だけで決めると、フリックでは何も起きない。 */
    @Test
    fun `縦に短く素早く払うと、距離が足りなくても出し入れする`() {
        assertEquals(DeskBarGesture.Show, deskBarGestureFor(moved = true, vertical = true, dragDp = 12f, velocityDpPerSec = DESK_BAR_FLING_DP_PER_SEC))
        assertEquals(DeskBarGesture.Hide, deskBarGestureFor(moved = true, vertical = true, dragDp = -12f, velocityDpPerSec = -DESK_BAR_FLING_DP_PER_SEC))
    }

    /** 縦でも、距離も速さも足りなければ何もしない。速さが距離と逆向きなら数えない。 */
    @Test
    fun `縦でも距離も速さも足りないか、速さが逆向きなら何もしない`() {
        assertEquals(DeskBarGesture.None, deskBarGestureFor(moved = true, vertical = true, dragDp = 12f, velocityDpPerSec = DESK_BAR_FLING_DP_PER_SEC - 1f))
        assertEquals(DeskBarGesture.None, deskBarGestureFor(moved = true, vertical = true, dragDp = 12f, velocityDpPerSec = -500f))
        assertEquals(DeskBarGesture.None, deskBarGestureFor(moved = true, vertical = true, dragDp = -12f, velocityDpPerSec = 500f))
    }

    /**
     * **横に払ったときは、縦に十分ずれていても帯の操作にしない**（横は片側を広げる操作に残す）。
     * 縦の成分だけで決めていたときは、横120dp・縦30dpの払いで帯が開閉した。
     */
    @Test
    fun `横に払ったときは、縦に十分ずれていても速くても何もしない`() {
        assertEquals(DeskBarGesture.None, deskBarGestureFor(moved = true, vertical = false, dragDp = 30f, velocityDpPerSec = 500f))
        assertEquals(DeskBarGesture.None, deskBarGestureFor(moved = true, vertical = false, dragDp = -30f, velocityDpPerSec = -500f))
    }

    /** 動いたと言えた時点の移動で縦横を決める。縦が横より大きいときだけ縦で、斜め45度は縦にしない。 */
    @Test
    fun `縦が横より大きいときだけ縦に払っている`() {
        assertTrue(isVerticalSwipe(dx = 2f, dy = 18f))
        assertTrue(isVerticalSwipe(dx = -5f, dy = -18f))
        // 横120dp・縦30dp の払いは、動き始めでも横が勝つ。
        assertFalse(isVerticalSwipe(dx = 18f, dy = 4.5f))
        assertFalse(isVerticalSwipe(dx = -18f, dy = -4.5f))
        assertFalse(isVerticalSwipe(dx = 10f, dy = 10f))
    }
}
