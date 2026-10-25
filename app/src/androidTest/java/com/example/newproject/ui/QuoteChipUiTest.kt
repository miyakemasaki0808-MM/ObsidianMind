package com.example.newproject.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
}
