package com.example.newproject.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.newproject.ui.component.GradientHeader
import com.example.newproject.ui.component.IconPill
import com.example.newproject.ui.component.screenContentPadding
import com.example.newproject.ui.theme.AppTheme
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 見出しの霞が、画面の余白のどちらへ広がるか。
 *
 * 黒の上へ置き、左右の余白の中の色で霞の有無を見る（ライトの霞は白を薄く重ねるので、霞の下だけ黒が持ち上がる）。
 * 左右2列の左の列では右が画面の端ではなく列の間の溝なので、霞を右へ広げない（→ features/rediscover.md 判断6）。
 */
@RunWith(AndroidJUnit4::class)
class GradientHeaderTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 既定では霞は左右の余白まで広がる() {
        val (start, end) = paddingColors(bleedEnd = true)

        assertTrue("左の余白に霞が無い: $start", start.red > DARK)
        assertTrue("右の余白に霞が無い: $end", end.red > DARK)
    }

    @Test
    fun 右へ広げないと霞は右の余白へ出ない() {
        val (start, end) = paddingColors(bleedEnd = false)

        assertTrue("左の余白に霞が無い: $start", start.red > DARK)
        assertTrue("右の余白へ霞がはみ出している: $end", end.red <= DARK)
    }

    /**
     * **題名とボタンが1行に収まらないときだけ、ボタンを題名の下の行の右へ回す。** 題名は文字を縮めず、1行で置く
     * （大きい文字の左右2列で「Rediscove／r」と割れた → system/theme_and_ui_refactor.md）。
     * 書体で文字の幅が変わるので、広い枠で測った題名とボタンの幅から、ちょうど収まる枠と1dp足りない枠を作って比べる。
     */
    @Test
    fun 題名とボタンが収まらないときだけボタンを下の行へ回し_題名を1行で置く() {
        var columnWidth by mutableStateOf(WIDE_COLUMN)
        var fontScale by mutableStateOf(1f)
        composeRule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                AppTheme(darkTheme = false) {
                    Box(modifier = Modifier.requiredSize(width = columnWidth, height = 400.dp).screenContentPadding()) {
                        GradientHeader(
                            title = REDISCOVER,
                            bleedEnd = false,
                            trailing = {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    IconPill(symbol = "💬", contentDescription = FIRST_PILL) {}
                                    IconPill(symbol = "✎", contentDescription = "余白") {}
                                    IconPill(symbol = "⛶", contentDescription = LAST_PILL) {}
                                }
                            }
                        )
                    }
                }
            }
        }
        val density = composeRule.density.density
        fun bounds(description: String) = composeRule.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot
        fun title() = composeRule.onNodeWithText(REDISCOVER).fetchSemanticsNode()
        fun titleLayout(): TextLayoutResult {
            val results = mutableListOf<TextLayoutResult>()
            title().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            return results.single()
        }
        fun show(width: Dp) {
            composeRule.runOnIdle { columnWidth = width }
            composeRule.waitForIdle()
        }

        listOf(1f, 2f).forEach { scale ->
            composeRule.runOnIdle { fontScale = scale }
            show(WIDE_COLUMN)
            val layout = titleLayout()
            assertEquals("広い枠で題名が1行に収まらない（倍率 $scale）", 1, layout.lineCount)
            val titleDp = (layout.getLineRight(0) - layout.getLineLeft(0)) / density
            val pillsDp = (bounds(LAST_PILL).right - bounds(FIRST_PILL).left) / density
            // 題名の行の幅は、枠から左右の余白（20dp ずつ）を引いた幅（左へ広げた霞の分は内側の余白で戻す）。
            val fitting = (titleDp + TITLE_GAP + pillsDp + 2 * SCREEN_PADDING + 1f).dp

            show(fitting)
            assertTrue("収まる枠でボタンが題名の行に並ばない（倍率 $scale）", bounds(LAST_PILL).top < title().boundsInRoot.bottom)
            assertEquals("収まる枠で題名が割れた（倍率 $scale）", 1, titleLayout().lineCount)

            show(fitting - 2.dp)
            assertTrue("収まらない枠でボタンが題名の下の行へ回らない（倍率 $scale）", bounds(LAST_PILL).top >= title().boundsInRoot.bottom)
            assertEquals("収まらない枠で題名が割れた（倍率 $scale）", 1, titleLayout().lineCount)
        }
    }

    /** 題名の高さで、左右の余白の中ほどの色。 */
    private fun paddingColors(bleedEnd: Boolean): Pair<Color, Color> {
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                Box(
                    modifier = Modifier
                        .requiredSize(width = 360.dp, height = 200.dp)
                        .background(Color.Black)
                        .testTag(FRAME)
                        .screenContentPadding()
                ) {
                    GradientHeader(title = TITLE, bleedEnd = bleedEnd)
                }
            }
        }
        val frame = composeRule.onNodeWithTag(FRAME).fetchSemanticsNode().boundsInRoot
        val title = composeRule.onNodeWithText(TITLE).fetchSemanticsNode().boundsInRoot
        val image = composeRule.onNodeWithTag(FRAME).captureToImage().toPixelMap()
        val inset = with(composeRule.density) { 10.dp.toPx() }.roundToInt()
        val y = (title.center.y - frame.top).roundToInt()
        return image[inset, y] to image[image.width - 1 - inset, y]
    }

    private companion object {
        const val FRAME = "見出しの枠"
        const val TITLE = "題名"
        const val REDISCOVER = "Rediscover"
        const val FIRST_PILL = "要約"
        const val LAST_PILL = "全画面表示"
        val WIDE_COLUMN = 600.dp
        /** 題名とボタンの間（[GradientHeader] の題名の行の間隔）と、画面の左右の余白。 */
        const val TITLE_GAP = 12f
        const val SCREEN_PADDING = 20f
        /** 黒とみなす上限。ライトの霞（白の α=0.35）を重ねた黒はこれより十分明るい。 */
        const val DARK = 0.1f
    }
}
