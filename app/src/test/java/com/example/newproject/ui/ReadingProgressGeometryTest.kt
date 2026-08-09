package com.example.newproject.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingProgressGeometryTest {

    @Test
    fun `fully visible block reports one`() {
        // 高さ100のブロックが offset 0 にあり、viewport 下端が 300 → 全部見えている
        assertEquals(1f, visibleFractionOfBlock(itemOffset = 0, itemSize = 100, viewportEndOffset = 300), 0f)
    }

    @Test
    fun `block cut off by the viewport reports the visible part`() {
        // 高さ1000のブロックの先頭が offset 0、viewport 下端が 250 → 25%だけ見えている
        assertEquals(0.25f, visibleFractionOfBlock(itemOffset = 0, itemSize = 1000, viewportEndOffset = 250), 0.0001f)
    }

    // 上へスクロールアウトしたブロックは offset が負になる。見えている量は下端までの分。
    @Test
    fun `block scrolled above the viewport counts from its top`() {
        assertEquals(0.8f, visibleFractionOfBlock(itemOffset = -200, itemSize = 1000, viewportEndOffset = 600), 0.0001f)
    }

    // まだ viewport へ入っていないブロック（下端より下）は0。
    @Test
    fun `block below the viewport reports zero`() {
        assertEquals(0f, visibleFractionOfBlock(itemOffset = 500, itemSize = 100, viewportEndOffset = 400), 0f)
    }

    // 高さが測れていないブロックで到達率を不当に下げない。
    @Test
    fun `zero sized block reports one`() {
        assertEquals(1f, visibleFractionOfBlock(itemOffset = 0, itemSize = 0, viewportEndOffset = 300), 0f)
    }

    @Test
    fun `visible part never exceeds the block`() {
        assertEquals(1f, visibleFractionOfBlock(itemOffset = -50, itemSize = 100, viewportEndOffset = 9999), 0f)
    }

    // 量子化は切り捨て。末端まで見えたときだけ最大段階になる。
    @Test
    fun `quantization floors to steps`() {
        assertEquals(0, quantizeReadingFraction(0.04f))
        assertEquals(1, quantizeReadingFraction(0.05f))
        assertEquals(10, quantizeReadingFraction(0.5f))
        assertEquals(READING_FRACTION_STEPS - 1, quantizeReadingFraction(0.99f))
        assertEquals(READING_FRACTION_STEPS, quantizeReadingFraction(1f))
    }

    @Test
    fun `quantization clamps out of range values`() {
        assertEquals(0, quantizeReadingFraction(-1f))
        assertEquals(READING_FRACTION_STEPS, quantizeReadingFraction(2f))
    }

    // --- 寸法未確定の画像より後ろを報告しない -------------------------------

    @Test
    fun `未確定の画像が無ければ報告する`() {
        assertTrue(shouldReportReadingProgress(lastVisibleBlockIndex = 12, firstUnsettledImageIndex = null))
    }

    @Test
    fun `未確定の画像より手前なら報告する`() {
        assertTrue(shouldReportReadingProgress(lastVisibleBlockIndex = 4, firstUnsettledImageIndex = 5))
    }

    /** 画像そのものは「まだ高さが動く」ので、そこも報告しない。 */
    @Test
    fun `未確定の画像そのものは報告しない`() {
        assertFalse(shouldReportReadingProgress(lastVisibleBlockIndex = 5, firstUnsettledImageIndex = 5))
    }

    /**
     * **ここが本体。**
     *
     * 未確定の画像より後ろが報告されると、まだ読んでいない位置が
     * 最深到達点としてサイドカーへ永続化される（最深到達点は後から下がらない）。
     */
    @Test
    fun `未確定の画像より後ろは報告しない`() {
        assertFalse(shouldReportReadingProgress(lastVisibleBlockIndex = 9, firstUnsettledImageIndex = 5))
    }

    /** 複数あっても最も手前で止める（手前が未確定なら、その先の可視判定は信用できない）。 */
    @Test
    fun `未確定が複数あれば最も手前で止まる`() {
        assertFalse(shouldReportReadingProgress(lastVisibleBlockIndex = 8, firstUnsettledImageIndex = 2))
        assertTrue(shouldReportReadingProgress(lastVisibleBlockIndex = 1, firstUnsettledImageIndex = 2))
    }
}
