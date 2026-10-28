package com.example.newproject.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import com.example.newproject.domain.markdown.buildNoteSectionModel
import com.example.newproject.ui.markdown.MarkdownNoteContent
import com.example.newproject.ui.markdown.QuoteOffers
import com.example.newproject.ui.screen.QuoteChipEffects
import com.example.newproject.ui.screen.quoteChipTouch
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.example.newproject.ui.screen.quoteChipPosition
import com.example.newproject.ui.screen.QuoteChipEvent
import com.example.newproject.ui.screen.QUOTE_CHIP_WIDTH
import com.example.newproject.ui.screen.QUOTE_CHIP_HEIGHT
import com.example.newproject.ui.screen.QUOTE_CHIP_GAP
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.newproject.ui.markdown.QuoteOffer
import com.example.newproject.ui.markdown.QuoteSelection
import com.example.newproject.ui.screen.DeskSpreadGesture
import com.example.newproject.ui.screen.QUOTE_CHIP_DESCRIPTION
import com.example.newproject.ui.screen.QUOTE_DROP_CREASE_BAND
import com.example.newproject.ui.screen.QuoteChip
import com.example.newproject.ui.screen.QuoteChipLayer
import com.example.newproject.ui.screen.detectDeskSpread
import com.example.newproject.ui.screen.quoteChipAfter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 机の札の指（→ features/margin_quote.md §5.1・§5.7・§11）。**札は面の上に重なる兄弟の層に置くので、札から始まった指は、
 * 面の中の横の払い（`detectDeskSpread`）に届かない。** 付けるかは離した指の位置で決め、面の大きさが変わったら札を消す。
 * 座標はすべて窓（Compose の根）の座標で、層は根の左上に置く。
 */
@RunWith(AndroidJUnit4::class)
class QuoteChipUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val selection = QuoteSelection("運ぶ一節", source = 2, hint = 2)
    private val home = Offset(40f, 40f)
    private var chip by mutableStateOf<QuoteChip>(QuoteChip.Holding(selection, home))
    private var main by mutableStateOf<Rect?>(null)
    private var supporting by mutableStateOf<Rect?>(null)
    private val spreads = mutableListOf<DeskSpreadGesture>()
    private val attached = mutableListOf<QuoteSelection>()
    private var widthPx = 0f
    private var heightPx = 0f
    private var bandPx = 0f

    private fun setDesk() {
        composeRule.setContent {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                widthPx = with(density) { maxWidth.toPx() }
                heightPx = with(density) { maxHeight.toPx() }
                bandPx = with(density) { QUOTE_DROP_CREASE_BAND.toPx() }
                SideEffect {
                    if (main == null) {
                        main = Rect(0f, 0f, widthPx / 2 - 20f, heightPx)
                        supporting = Rect(widthPx / 2 + 20f, 0f, widthPx, heightPx)
                    }
                }
                // 面の中で横の払いを見る（机の画面と同じく、面の根の修飾子に付ける）。
                Box(modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectDeskSpread { spreads += it } })
                QuoteChipLayer(
                    chip = chip,
                    onEvent = { event -> chip = quoteChipAfter(chip, event) },
                    main = { main },
                    supporting = { supporting },
                    onAttach = { attached += it }
                )
            }
        }
        composeRule.waitForIdle()
    }

    private val chipNode get() = composeRule.onNodeWithContentDescription(QUOTE_CHIP_DESCRIPTION)

    /** 窓の座標 [root] を、札の元の位置からの局所座標へ直す（札の操作は運び始める前の札の位置で座標を決める）。 */
    private fun local(root: Offset): Offset = root - home

    @Test
    fun 札を余白の面へ運んで離すと付き_横の払いには届かない() {
        setDesk()

        chipNode.performTouchInput { swipe(center, center + Offset(widthPx * 0.6f, 0f), durationMillis = 400) }

        composeRule.runOnIdle {
            assertEquals(listOf(selection), attached)
            assertTrue("札から始まった指が横の払いに届いた: $spreads", spreads.isEmpty())
            assertEquals(QuoteChip.Hidden, chip)
        }
    }

    /** **余白の外で離したら、持ったまま元の位置へ戻る**（→ 判断12）。もう一度押せば付く。 */
    @Test
    fun 余白の外で離すと持ったまま戻り_押せば付く() {
        setDesk()

        chipNode.performTouchInput { swipe(center, center + Offset(0f, 300f), durationMillis = 300) }
        composeRule.runOnIdle {
            assertTrue(attached.isEmpty())
            assertEquals(QuoteChip.Holding(selection, home), chip)
        }

        chipNode.performTouchInput { click() }
        composeRule.runOnIdle {
            assertEquals(listOf(selection), attached)
            assertTrue(spreads.isEmpty())
        }
    }

    /** 札の無い場所の指は、層を素通りして面の横の払いに届く。 */
    @Test
    fun 札の無い場所の指は面の横の払いに届く() {
        setDesk()

        chipNode.assertExists()
        composeRule.onRoot().performTouchInput {
            swipe(Offset(widthPx * 0.3f, height * 0.7f), Offset(widthPx * 0.05f, height * 0.7f), durationMillis = 200)
        }

        composeRule.runOnIdle { assertTrue("面の横の払いに届かなかった", spreads.isNotEmpty()) }
    }

    /** **付けるかは離した指の位置で決める。** 最後の動きと離す間にフレームを進めなくても、面の外→中なら付き、中→外なら付かない。 */
    @Test
    fun 最後の動きを描く前に離しても離した位置で決まる() {
        setDesk()
        val inside = Offset(widthPx * 0.8f, heightPx * 0.5f)
        val outside = Offset(widthPx * 0.2f, heightPx * 0.6f)

        composeRule.mainClock.autoAdvance = false
        chipNode.performTouchInput {
            down(center)
            moveTo(local(outside))
            moveTo(local(inside))
            up()
        }
        composeRule.mainClock.autoAdvance = true
        composeRule.runOnIdle { assertEquals(listOf(selection), attached) }

        chip = QuoteChip.Holding(selection, home)
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        chipNode.performTouchInput {
            down(center)
            moveTo(local(inside))
            moveTo(local(outside))
            up()
        }
        composeRule.mainClock.autoAdvance = true
        composeRule.runOnIdle {
            assertEquals("面の外で離したのに付いた", 1, attached.size)
            assertEquals(QuoteChip.Holding(selection, home), chip)
        }
    }

    /** **運び始めにしきい値を大きく越えても、指の位置を二重に動かさない。** 折り目の側の帯の直前では付かず、直後では付く。 */
    @Test
    fun 運び始めに大きく動かしても折り目の側の帯は実際の指の位置で決まる() {
        setDesk()
        val y = heightPx * 0.5f
        val inBand = Offset(checkNotNull(supporting).left + bandPx - 2f, y)
        val pastBand = Offset(checkNotNull(supporting).left + bandPx + 2f, y)

        listOf(Offset(90f, 0f), Offset(30f, 0f)).forEach { firstStep ->
            chipNode.performTouchInput {
                down(center)
                moveBy(firstStep)
                moveTo(local(inBand))
                up()
            }
            composeRule.runOnIdle { assertTrue("帯の中で付いた（最初の動き $firstStep）", attached.isEmpty()) }
        }

        chipNode.performTouchInput {
            down(center)
            moveBy(Offset(90f, 0f))
            moveTo(local(pastBand))
            up()
        }
        composeRule.runOnIdle { assertEquals(listOf(selection), attached) }
    }

    /** **面の高さだけが変わったら札を消す**（キーボードを出したときなど）。元の高さへ戻しても出てこない。 */
    @Test
    fun 面の高さだけが変わると持った札は消え_戻しても出てこない() {
        setDesk()
        val tall = checkNotNull(main) to checkNotNull(supporting)

        composeRule.runOnIdle {
            main = tall.first.copy(bottom = heightPx * 0.6f)
            supporting = tall.second.copy(bottom = heightPx * 0.6f)
        }
        composeRule.waitForIdle()
        chipNode.assertDoesNotExist()

        composeRule.runOnIdle {
            main = tall.first
            supporting = tall.second
        }
        composeRule.waitForIdle()
        chipNode.assertDoesNotExist()
        composeRule.runOnIdle { assertEquals(QuoteChip.Hidden, chip) }
    }

    @Test
    fun 面の高さだけが変わると選択メニューと出ている札も消える() {
        chip = QuoteChip.Offered(QuoteOffer(Rect(60f, 60f, 200f, 100f)) { selection })
        setDesk()
        chipNode.assertExists()

        composeRule.runOnIdle { main = checkNotNull(main).copy(bottom = heightPx * 0.6f) }
        composeRule.waitForIdle()

        chipNode.assertDoesNotExist()
    }

    /** 運んでいる途中に面が変わったら、その後に前の指を離しても付かない。 */
    @Test
    fun 運んでいる途中に面が変わると離しても付かない() {
        setDesk()

        chipNode.performTouchInput {
            down(center)
            moveBy(Offset(widthPx * 0.6f, 0f))
        }
        composeRule.runOnIdle { supporting = checkNotNull(supporting).copy(bottom = heightPx * 0.6f) }
        composeRule.waitForIdle()
        composeRule.onRoot().performTouchInput { up() }

        composeRule.runOnIdle {
            assertTrue("面が変わった後の指で付いた", attached.isEmpty())
            assertEquals(QuoteChip.Hidden, chip)
        }
    }

    /** 札の左上（窓の座標）。 */
    private fun chipTopLeft(): Offset = chipNode.fetchSemanticsNode().boundsInRoot.topLeft

    /**
     * **運び始めた後の動きも札と指に入る**（使用済みにする前に動いた量を読む）。本文側で運び始め、別の動きで余白の面へ運んで離すと付き、
     * 途中でも札が指に追従する。最初に面へ入ってから折り目の側の帯へ戻して離すと付かない。
     */
    @Test
    fun 運び始めた後の動きに札が追従し_離した位置で付くかが決まる() {
        setDesk()
        val y = heightPx * 0.5f
        val mid = Offset(widthPx * 0.3f, y)
        val inside = Offset(widthPx * 0.8f, y)
        val inBand = Offset(checkNotNull(supporting).left + bandPx - 2f, y)
        val grab = Offset(20f, 10f)

        chipNode.performTouchInput {
            down(grab)
            moveBy(Offset(30f, 0f))
            moveTo(local(mid))
        }
        composeRule.waitForIdle()
        val followed = chipTopLeft()
        assertEquals("札が途中の指に追従しない", mid.x - grab.x, followed.x, 2f)
        composeRule.onRoot().performTouchInput {
            moveTo(inside)
            up()
        }
        composeRule.runOnIdle { assertEquals(listOf(selection), attached) }

        chip = QuoteChip.Holding(selection, home)
        composeRule.waitForIdle()
        chipNode.performTouchInput {
            down(grab)
            moveTo(local(inside))
            moveTo(local(inBand))
            up()
        }
        composeRule.runOnIdle {
            assertEquals("帯へ戻して離したのに付いた", 1, attached.size)
            assertEquals(QuoteChip.Holding(selection, home), chip)
        }
    }

    /**
     * **運ぶ途中で取り消されても、動いた分を次の札へ残さない。** 面が変わって札が外れた後に別の一節を選ぶと、新しい札は新しい置く位置に出て、
     * 運び直して離した場所で付くかが決まる。
     */
    @Test
    fun 運ぶ途中で取り消された後の次の札は新しい位置に出て運び直せる() {
        setDesk()
        chipNode.performTouchInput {
            down(center)
            moveBy(Offset(widthPx * 0.4f, 0f))
        }
        composeRule.runOnIdle { supporting = checkNotNull(supporting).copy(bottom = heightPx * 0.9f) }
        composeRule.waitForIdle()
        composeRule.onRoot().performTouchInput { up() }
        composeRule.runOnIdle { assertEquals(QuoteChip.Hidden, chip) }

        val offer = QuoteOffer(Rect(60f, 200f, 220f, 240f)) { selection }
        composeRule.runOnIdle { chip = quoteChipAfter(chip, QuoteChipEvent.MenuShown(offer)) }
        composeRule.waitForIdle()
        val density = composeRule.density
        val expected = with(density) {
            quoteChipPosition(offer.rect, checkNotNull(main), Size(QUOTE_CHIP_WIDTH.toPx(), QUOTE_CHIP_HEIGHT.toPx()), QUOTE_CHIP_GAP.toPx())
        }
        assertEquals(expected.x, chipTopLeft().x, 1f)
        assertEquals(expected.y, chipTopLeft().y, 1f)

        val target = Offset(widthPx * 0.8f, heightPx * 0.5f)
        chipNode.performTouchInput { swipe(center, center + (target - (expected + center)), durationMillis = 300) }
        composeRule.runOnIdle { assertEquals(listOf(selection), attached) }
    }

    /**
     * **本物の選択の容器で文字を選んで本文をスクロールすると、スクロールの後も札が出ない**（→ features/margin_quote.md §5.1）。
     * 選択メニューは動いた選択の位置で出し直されるが、スクロールで消した札はその選択では出し直さない。本文に触れて選び直せば出る。
     * 札の配線は本番と同じ [QuoteChipEffects] と [quoteChipTouch] を使う（札の層と擬似の範囲だけでは、メニューの出し直しを通らない）。
     */
    @Test
    fun 選んでスクロールした後は札が出ず_選び直せば出る() {
        val model = buildNoteSectionModel((1..60).joinToString("\n\n") { "段落${it}の文。選んで引用する。" })
        val chip = mutableStateOf<QuoteChip>(QuoteChip.Hidden)
        val offers = QuoteOffers()
        composeRule.setContent {
            val listState = rememberLazyListState()
            QuoteChipEffects(chip, offers, listState)
            MarkdownNoteContent(
                content = "",
                modifier = Modifier.fillMaxSize().testTag("body").quoteChipTouch(chip),
                listState = listState,
                precomputedBlocks = model.blocks,
                quotable = true,
                onQuote = {},
                quoteOffers = offers
            )
        }

        composeRule.onNodeWithText("段落2の文。選んで引用する。").performTouchInput { longClick() }
        composeRule.waitUntil(5_000) { chip.value is QuoteChip.Offered }

        // 選択の端のつまみも根になるので、根ではなく本文の上で払う。
        composeRule.onNodeWithTag("body").performTouchInput { swipe(Offset(centerX, bottom * 0.8f), Offset(centerX, bottom * 0.3f), durationMillis = 300) }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertTrue("スクロールの後も選択メニューは出し直されている（試験の前提）", offers.current != null)
            assertEquals(QuoteChip.Withdrawn, chip.value)
        }

        composeRule.onAllNodesWithText("選んで引用する", substring = true).onFirst().performTouchInput { longClick() }
        composeRule.waitUntil(5_000) { chip.value is QuoteChip.Offered }
    }

}
