package com.example.newproject.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.newproject.ui.component.GradientHeader
import com.example.newproject.ui.component.screenContentPadding
import com.example.newproject.ui.theme.AppTheme
import kotlin.math.roundToInt
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
        /** 黒とみなす上限。ライトの霞（白の α=0.35）を重ねた黒はこれより十分明るい。 */
        const val DARK = 0.1f
    }
}
