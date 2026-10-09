package com.example.newproject.ui

import com.example.newproject.ui.screen.DESK_BAR_SWIPE_DP
import com.example.newproject.ui.screen.DeskBarGesture
import com.example.newproject.ui.screen.deskBarGestureFor
import org.junit.Assert.assertEquals
import org.junit.Test

// 机の画面のつまみと帯を縦に払ったとき（→ features/margin_pane.md §5.1）。
class DeskControlsTest {

    /** 下へ払うと帯を出し、上へ払うとしまう。**向きだけで決める。** */
    @Test
    fun `下へ払うと出し、上へ払うとしまう`() {
        assertEquals(DeskBarGesture.Show, deskBarGestureFor(DESK_BAR_SWIPE_DP))
        assertEquals(DeskBarGesture.Show, deskBarGestureFor(200f))
        assertEquals(DeskBarGesture.Hide, deskBarGestureFor(-DESK_BAR_SWIPE_DP))
        assertEquals(DeskBarGesture.Hide, deskBarGestureFor(-200f))
    }

    /** 指が少し揺れただけでは出し入れしない。押した扱いは別に受ける。 */
    @Test
    fun `払った量が足りなければ何もしない`() {
        assertEquals(DeskBarGesture.None, deskBarGestureFor(0f))
        assertEquals(DeskBarGesture.None, deskBarGestureFor(DESK_BAR_SWIPE_DP - 1f))
        assertEquals(DeskBarGesture.None, deskBarGestureFor(-(DESK_BAR_SWIPE_DP - 1f)))
    }
}
