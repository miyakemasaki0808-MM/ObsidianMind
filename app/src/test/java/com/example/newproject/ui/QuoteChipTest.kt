package com.example.newproject.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import com.example.newproject.ui.markdown.QuoteOffer
import com.example.newproject.ui.markdown.QuoteSelection
import com.example.newproject.ui.screen.QuoteChip
import com.example.newproject.ui.screen.QuoteChipEvent
import com.example.newproject.ui.screen.quoteChipAfter
import com.example.newproject.ui.screen.quoteChipPosition
import com.example.newproject.ui.screen.quoteDropTargetFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 机の札（→ features/margin_quote.md §5.1・判断12）。置く位置・落とせる場所・札の様子の移り方。 */
class QuoteChipTest {

    // 本文の面は 0〜500、溝の向こうの余白の面は 540〜1000。
    private val main = Rect(0f, 0f, 500f, 1000f)
    private val supporting = Rect(540f, 0f, 1000f, 1000f)
    private val chip = Size(80f, 40f)
    private val gap = 8f

    // ── 置く位置 ──────────────────────────────────────────────────────

    @Test
    fun `選んだ範囲の下に右寄せで置く`() {
        assertEquals(Offset(220f, 308f), quoteChipPosition(Rect(100f, 200f, 300f, 300f), main, chip, gap))
    }

    @Test
    fun `下に入らなければ範囲の上に置く`() {
        assertEquals(Offset(220f, 852f), quoteChipPosition(Rect(100f, 900f, 300f, 980f), main, chip, gap))
    }

    /** **本文の面の内側に収め、折り目へは出さない** — 本文の面の右の縁は溝に接する。 */
    @Test
    fun `本文の面の内側に収め、折り目の側の縁から離す`() {
        val wide = quoteChipPosition(Rect(0f, 100f, 560f, 200f), main, chip, gap)
        assertEquals(500f - gap - chip.width, wide.x)
        assertEquals(gap, quoteChipPosition(Rect(0f, 100f, 40f, 200f), main, chip, gap).x)
        assertEquals(gap, quoteChipPosition(Rect(100f, -300f, 300f, -280f), main, chip, gap).y)
    }

    // ── 落とせる場所 ────────────────────────────────────────────────────

    @Test
    fun `余白の面の中なら落とせる`() {
        assertTrue(quoteDropTargetFor(Offset(700f, 500f), supporting, creaseBand = 24f))
    }

    /** 折り目のすぐ横・溝・本文の面・余白の面の外では落とさない。 */
    @Test
    fun `折り目の側の帯と面の外では落とさない`() {
        assertFalse(quoteDropTargetFor(Offset(550f, 500f), supporting, creaseBand = 24f))
        assertFalse(quoteDropTargetFor(Offset(520f, 500f), supporting, creaseBand = 24f))
        assertFalse(quoteDropTargetFor(Offset(300f, 500f), supporting, creaseBand = 24f))
        assertFalse(quoteDropTargetFor(Offset(700f, 1200f), supporting, creaseBand = 24f))
        assertTrue(quoteDropTargetFor(Offset(564f, 500f), supporting, creaseBand = 24f))
    }

    // ── 札の様子 ──────────────────────────────────────────────────────

    private val selection = QuoteSelection("一節", source = 3, hint = 3)
    private val offer = QuoteOffer(Rect(100f, 200f, 300f, 300f)) { selection }
    private val home = Offset(220f, 308f)

    @Test
    fun `選択メニューが出ると札も出て、受け取る前に閉じれば消える`() {
        val offered = quoteChipAfter(QuoteChip.Hidden, QuoteChipEvent.MenuShown(offer))

        assertEquals(QuoteChip.Offered(offer), offered)
        assertEquals(QuoteChip.Hidden, quoteChipAfter(offered, QuoteChipEvent.MenuHidden))
    }

    /** 札を押すと一節を受け取り、受け取ったことで選択が解けてメニューが閉じても、札は一節を持ったまま残る。 */
    @Test
    fun `受け取った後はメニューが閉じても一節を持ったまま`() {
        val holding = quoteChipAfter(QuoteChip.Offered(offer), QuoteChipEvent.Taken(selection, home))

        assertEquals(QuoteChip.Holding(selection, home), holding)
        assertEquals(holding, quoteChipAfter(holding, QuoteChipEvent.MenuHidden))
    }

    /** **余白の外で離したら、持ったまま元の位置へ戻る**（→ 判断12）。もう一度運ぶか押せば付く。 */
    @Test
    fun `余白の外で離したら持ったまま元の位置へ戻る`() {
        val holding = QuoteChip.Holding(selection, home)

        assertEquals(holding, quoteChipAfter(holding, QuoteChipEvent.ReleasedOutside))
    }

    @Test
    fun `付いたとき・本文に触れたとき・スクロールや窓の変化では消える`() {
        val holding = QuoteChip.Holding(selection, home)

        assertEquals(QuoteChip.Hidden, quoteChipAfter(holding, QuoteChipEvent.Attached))
        assertEquals(QuoteChip.Hidden, quoteChipAfter(holding, QuoteChipEvent.Dismissed))
        assertEquals(QuoteChip.Hidden, quoteChipAfter(QuoteChip.Offered(offer), QuoteChipEvent.Dismissed))
    }

    /** 別の文字を選んだら、持っていた一節は捨てて新しい選択の札になる。 */
    @Test
    fun `別の文字を選んだら持っていた一節を捨てる`() {
        val other = QuoteOffer(Rect(0f, 500f, 100f, 520f)) { null }

        assertEquals(QuoteChip.Offered(other), quoteChipAfter(QuoteChip.Holding(selection, home), QuoteChipEvent.MenuShown(other)))
    }

    @Test
    fun `受け取れなければ札は消える`() {
        assertEquals(QuoteChip.Hidden, quoteChipAfter(QuoteChip.Offered(offer), QuoteChipEvent.Taken(null, home)))
    }
}
