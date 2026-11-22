package com.example.newproject.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.domain.markdown.parseMarkdownBlocks
import com.example.newproject.ui.markdown.InlineMarkdownColors
import com.example.newproject.ui.markdown.MarkdownBlockquote
import com.example.newproject.ui.markdown.SpreadMeasureInputs
import com.example.newproject.ui.markdown.SpreadSliceView
import com.example.newproject.ui.markdown.measureSpreadBlock
import com.example.newproject.ui.screen.SpreadSlice
import com.example.newproject.ui.screen.paginateSpread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * コールアウトの頭の並べ方（→ docs/dev/system/markdown_rendering.md §8.2）。
 * 知らない種類名が長くても題が読めること、言葉と題を同じ行に置くか次の行へ送るかを本文と見開きの計測が同じに決めること、
 * 見開きで題を割った切れ端の描く高さが計測と合うこと（→ features/note_spread.md §8 判断8）を、幅300dp・文字の倍率1.0で確かめる。
 */
@RunWith(AndroidJUnit4::class)
class CalloutHeaderUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val longType = "独自".repeat(40)

    private fun quoteOf(markdown: String): MarkdownBlock.Blockquote = parseMarkdownBlocks(markdown).single() as MarkdownBlock.Blockquote

    private fun setQuote(markdown: String) {
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1f)) {
                Box(modifier = Modifier.width(300.dp)) { MarkdownBlockquote(quoteOf(markdown).content) }
            }
        }
    }

    private fun boundsOfText(text: String): Rect = composeRule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot
    private fun boundsOfLabel(label: String): Rect = composeRule.onNodeWithContentDescription(label).fetchSemanticsNode().boundsInRoot

    @Test
    fun 長い知らない種類名では題を次の行へ送り_題を全幅で読める() {
        setQuote("> [!$longType] 確認する題")

        val label = boundsOfLabel(longType)
        val title = boundsOfText("確認する題")
        assertTrue("題に幅が無い: $title", title.width > 0f)
        assertTrue("題が言葉と重なった: 言葉 $label 題 $title", title.top >= label.bottom - 1f)
    }

    @Test
    fun 短い種類名では題を言葉と同じ行に続ける() {
        setQuote("> [!note] 確認する題")

        val label = boundsOfLabel("まとめ")
        val title = boundsOfText("確認する題")
        assertTrue("題に幅が無い: $title", title.width > 0f)
        assertTrue("題が次の行へ送られた: 言葉 $label 題 $title", title.top < label.bottom && title.left >= label.right)
    }

    @Test
    fun 見開きで長い種類名の題を割った切れ端は_描く高さが計測と合う() {
        assertSlicesMatchMeasured("> [!$longType] " + "確認する題の文字".repeat(12))
    }

    @Test
    fun 見開きで短い種類名の題を割った切れ端は_描く高さが計測と合う() {
        assertSlicesMatchMeasured("> [!note] " + "題の文字を長くする".repeat(10))
    }

    /**
     * 引用を見開きの計測で測ってページ（高さ120dp）へ割り、切れ端を1つずつ描いて、描いた高さと組版の高さを比べる。
     * 引用の上下の余白と文字の行の高さは、計測が実数で数え描画が整数の画素に丸めるので、3画素まではずれてよい（子の間の余白は8dpで、これより大きい）。
     */
    private fun assertSlicesMatchMeasured(markdown: String) {
        val drawn = mutableMapOf<Int, Int>()
        var slices: List<SpreadSlice> = emptyList()
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1f)) {
                val density = LocalDensity.current
                val measurer = rememberTextMeasurer()
                val style = LocalTextStyle.current
                val widthPx = with(density) { 300.dp.roundToPx() }
                val pageHeight = with(density) { 120.dp.toPx() }
                val measured = remember {
                    val inputs = SpreadMeasureInputs(measurer, style, density, widthPx, 0, InlineMarkdownColors.Light, null) { 0f }
                    measureSpreadBlock(quoteOf(markdown), inputs)
                }
                slices = remember { paginateSpread(listOf(measured.spread), pageHeight, blockSpacing = 0f).flatMap { it.slices } }
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    slices.forEachIndexed { k, slice ->
                        Box(modifier = Modifier.width(300.dp).onGloballyPositioned { drawn[k] = it.size.height }) {
                            SpreadSliceView(measured, slice, pageHeight, null, null, null)
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()

        assertTrue("題がページをまたいでいない: ${slices.size}", slices.size >= 2)
        slices.forEachIndexed { k, slice ->
            assertEquals("切れ端 $k（$slice）", slice.height, drawn.getValue(k).toFloat(), 3f)
        }
    }
}
