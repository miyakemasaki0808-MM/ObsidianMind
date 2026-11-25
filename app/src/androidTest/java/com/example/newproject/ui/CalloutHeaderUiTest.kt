package com.example.newproject.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.domain.markdown.parseMarkdownBlocks
import com.example.newproject.ui.markdown.InlineMarkdownColors
import com.example.newproject.ui.markdown.MarkdownBlockquote
import com.example.newproject.ui.markdown.MarkdownBodyStyle
import com.example.newproject.ui.markdown.SpreadMeasureInputs
import com.example.newproject.ui.markdown.SpreadSliceView
import com.example.newproject.ui.markdown.measureSpreadBlock
import com.example.newproject.ui.screen.SpreadSlice
import com.example.newproject.ui.screen.paginateSpread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // 全文が欠けずに組まれている（組みがはみ出さず、最後の行が題の末尾で終わる）。
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText("確認する題").fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val layout = layouts.single()
        assertFalse("題の組みがはみ出した", layout.hasVisualOverflow)
        assertEquals("確認する題".length, layout.getLineEnd(layout.lineCount - 1))
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
        val (slices, drawn) = drawSpread("> [!$longType] " + "確認する題の文字".repeat(12)) { density, _, _ -> with(density) { 300.dp.roundToPx() } }

        assertTrue("題がページをまたいでいない: ${slices.size}", slices.size >= 2)
        assertSlicesMatch(slices, drawn)
    }

    @Test
    fun 見開きで短い種類名の題を割った切れ端は_描く高さが計測と合う() {
        val (slices, drawn) = drawSpread("> [!note] " + "題の文字を長くする".repeat(10)) { density, _, _ -> with(density) { 300.dp.roundToPx() } }

        assertTrue("題がページをまたいでいない: ${slices.size}", slices.size >= 2)
        assertSlicesMatch(slices, drawn)
    }

    /**
     * **言葉の幅がちょうど内側の幅の半分になる外の幅（とその前後1画素）でも、描画と計測は同じ並べ方を選ぶ。**
     * 密度 2.53125 では引用の左の余白16dpが40.5pxになり、引いてから丸める計測は描画より1画素広く数えて、境い目で題を送るかが分かれた。
     * 同じ行と2段では高さが言葉の1行ぶん違うので、高さの比べ方で並べ方の食い違いが見える。
     * 1つのテストで `setContent` は1回しか呼べないので、外の幅ごとにテストを分ける。
     */
    @Test
    fun 余白が半画素の密度で内側が言葉の2倍より1画素狭くても_描く並べ方と高さが計測と合う() {
        assertBoundaryMatches(shift = -1)
    }

    @Test
    fun 余白が半画素の密度で内側が言葉のちょうど2倍でも_描く並べ方と高さが計測と合う() {
        assertBoundaryMatches(shift = 0)
    }

    @Test
    fun 余白が半画素の密度で内側が言葉の2倍より1画素広くても_描く並べ方と高さが計測と合う() {
        assertBoundaryMatches(shift = 1)
    }

    /** 外の幅を、引用の内側が言葉の幅のちょうど2倍になる幅から [shift] 画素動かして描き、切れ端の高さを計測と比べる。 */
    private fun assertBoundaryMatches(shift: Int) {
        val type = "独自".repeat(6)
        val (slices, drawn) = drawSpread("> [!$type] 確認する題", density = 2.53125f) { _, measurer, style ->
            val labelWidth = measurer.measure(AnnotatedString("≡ $type"), style.merge(MarkdownBodyStyle.CalloutLabelText)).size.width
            // 引用の左の余白は描画と同じく40.5pxを41pxへ丸める。
            labelWidth * 2 + 41 + shift
        }

        assertSlicesMatch(slices, drawn, "外の幅を $shift 画素動かした")
    }

    /** 引用の外の幅（[widthPxOf] が密度・文字の計測・書式から決める）で見開きの計測をし、ページ（高さ120dp）へ割って切れ端を1つずつ描く。 */
    private fun drawSpread(
        markdown: String,
        density: Float? = null,
        widthPxOf: (Density, TextMeasurer, TextStyle) -> Int
    ): Pair<List<SpreadSlice>, Map<Int, Int>> {
        val drawn = mutableMapOf<Int, Int>()
        var slices: List<SpreadSlice> = emptyList()
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density ?: LocalDensity.current.density, 1f)) {
                val current = LocalDensity.current
                val measurer = rememberTextMeasurer()
                val style = LocalTextStyle.current
                val widthPx = remember { widthPxOf(current, measurer, style) }
                val widthDp = with(current) { widthPx.toDp() }
                val pageHeight = with(current) { 120.dp.toPx() }
                val measured = remember {
                    val inputs = SpreadMeasureInputs(measurer, style, current, widthPx, 0, InlineMarkdownColors.Light, null) { 0f }
                    measureSpreadBlock(quoteOf(markdown), inputs)
                }
                slices = remember { paginateSpread(listOf(measured.spread), pageHeight, blockSpacing = 0f).flatMap { it.slices } }
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    slices.forEachIndexed { k, slice ->
                        Box(modifier = Modifier.requiredWidth(widthDp).onGloballyPositioned { drawn[k] = it.size.height }) {
                            SpreadSliceView(measured, slice, pageHeight, null, null, null)
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        return slices to drawn.toMap()
    }

    /**
     * 切れ端ごとに、描いた高さと組版の高さを比べる。引用の上下の余白と文字の行の高さは、計測が実数で数え描画が整数の画素に丸めるので、
     * 3画素まではずれてよい（子の間の余白8dpや、言葉の1行ぶんの違いはこれより大きい）。
     */
    private fun assertSlicesMatch(slices: List<SpreadSlice>, drawn: Map<Int, Int>, context: String = "") {
        assertTrue("切れ端が無い $context", slices.isNotEmpty())
        slices.forEachIndexed { k, slice ->
            assertEquals("$context 切れ端 $k（$slice）", slice.height, drawn.getValue(k).toFloat(), 3f)
        }
    }
}
