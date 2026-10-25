package com.example.newproject.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
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
import com.example.newproject.ui.markdown.QuoteSelection
import com.example.newproject.ui.screen.DeskSpreadGesture
import com.example.newproject.ui.screen.QUOTE_CHIP_DESCRIPTION
import com.example.newproject.ui.screen.QuoteChip
import com.example.newproject.ui.screen.QuoteChipEvent
import com.example.newproject.ui.screen.QuoteChipLayer
import com.example.newproject.ui.screen.detectDeskSpread
import com.example.newproject.ui.screen.quoteChipAfter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 机の札の指（→ features/margin_quote.md §5.1・§11）。**札は面の上に重なる兄弟の層に置くので、札から始まった指は、
 * 面の中の横の払い（`detectDeskSpread`）に届かない。** 余白の面へ運んで離すと付き、外で離すと持ったまま元の位置へ戻る。
 */
@RunWith(AndroidJUnit4::class)
class QuoteChipUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val selection = QuoteSelection("運ぶ一節", source = 2, hint = 2)
    private var chip by mutableStateOf<QuoteChip>(QuoteChip.Holding(selection, Offset(40f, 40f)))
    private val spreads = mutableListOf<DeskSpreadGesture>()
    private val attached = mutableListOf<QuoteSelection>()
    private var widthPx = 0f

    private fun setDesk() {
        composeRule.setContent {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val density = LocalDensity.current
                widthPx = with(density) { maxWidth.toPx() }
                val heightPx = with(density) { maxHeight.toPx() }
                // 面の中で横の払いを見る（机の画面と同じく、面の根の修飾子に付ける）。
                Box(modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectDeskSpread { spreads += it } })
                QuoteChipLayer(
                    chip = chip,
                    onEvent = { event -> chip = quoteChipAfter(chip, event) },
                    main = Rect(0f, 0f, widthPx / 2 - 20f, heightPx),
                    supporting = Rect(widthPx / 2 + 20f, 0f, widthPx, heightPx),
                    onAttach = { attached += it }
                )
            }
        }
    }

    @Test
    fun 札を余白の面へ運んで離すと付き_横の払いには届かない() {
        setDesk()

        composeRule.onNodeWithContentDescription(QUOTE_CHIP_DESCRIPTION).performTouchInput {
            swipe(center, center + Offset(widthPx * 0.6f, 0f), durationMillis = 400)
        }

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

        composeRule.onNodeWithContentDescription(QUOTE_CHIP_DESCRIPTION).performTouchInput {
            swipe(center, center + Offset(0f, 300f), durationMillis = 300)
        }
        composeRule.runOnIdle {
            assertTrue(attached.isEmpty())
            assertEquals(QuoteChip.Holding(selection, Offset(40f, 40f)), chip)
        }

        composeRule.onNodeWithContentDescription(QUOTE_CHIP_DESCRIPTION).performTouchInput { click() }
        composeRule.runOnIdle {
            assertEquals(listOf(selection), attached)
            assertTrue(spreads.isEmpty())
        }
    }

    /** 札の無い場所の指は、層を素通りして面の横の払いに届く。 */
    @Test
    fun 札の無い場所の指は面の横の払いに届く() {
        setDesk()

        composeRule.onNodeWithContentDescription(QUOTE_CHIP_DESCRIPTION).assertExists()
        composeRule.onRoot().performTouchInput {
            swipe(Offset(widthPx * 0.3f, height * 0.7f), Offset(widthPx * 0.05f, height * 0.7f), durationMillis = 200)
        }

        composeRule.runOnIdle { assertTrue("面の横の払いに届かなかった", spreads.isNotEmpty()) }
    }
}
