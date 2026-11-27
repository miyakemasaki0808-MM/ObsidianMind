package com.example.newproject.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.domain.markdown.parseMarkdownBlocks
import com.example.newproject.ui.markdown.InlineMarkdownColors
import com.example.newproject.ui.markdown.MarkdownBodyStyle
import com.example.newproject.ui.markdown.NoteTitleBlock
import com.example.newproject.ui.markdown.SpreadMeasureInputs
import com.example.newproject.ui.markdown.SpreadSliceView
import com.example.newproject.ui.markdown.measureSpreadBlock
import com.example.newproject.ui.markdown.noteTitleBlockHeightPx
import com.example.newproject.ui.markdown.tagChipText
import com.example.newproject.ui.screen.paginateSpread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 見開きの計測が、描画と同じく余白を片側ずつ画素へ丸めること（→ docs/dev/features/note_typesetting.md・note_spread.md 判断8）。
 * 密度 2.75 では札の左右の余白9dpが片側24.75px、コードの枠の左右の余白14dpが片側38.5pxになる。
 * 折り返しの境い目の幅を作り、計測した高さが描いた高さと合うかを見る（1行ぶんの違いは数十画素なので、3画素までは許す）。
 * 1つのテストで `setContent` は1回しか呼べないので、幅ごとにテストを分ける。
 */
@RunWith(AndroidJUnit4::class)
class TypesetRoundingUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val density = 2.75f
    private val tags = listOf("組版の型を確かめる札", "画素の丸めを確かめる札")

    @Test
    fun 札2つがちょうど入る幅より1画素狭いと_計測も描画も2行にする() {
        assertTitleBlockMatches(shift = -1)
    }

    @Test
    fun 札2つがちょうど入る幅では_計測も描画も1行にする() {
        assertTitleBlockMatches(shift = 0)
    }

    @Test
    fun コードの1行がちょうど入る幅より1画素狭いと_計測も描画も折り返す() {
        assertCodeMatches(shift = -1)
    }

    @Test
    fun コードの1行がちょうど入る幅では_計測も描画も1行にする() {
        assertCodeMatches(shift = 0)
    }

    /** 欄の幅を、描画の札2つと間がちょうど入る幅から [shift] 画素動かし、題の欄の計測の高さと描いた高さを比べる。 */
    private fun assertTitleBlockMatches(shift: Int) {
        var measured = 0f
        var drawn = -1
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, 1f)) {
                val current = LocalDensity.current
                val measurer = rememberTextMeasurer()
                val style = LocalTextStyle.current
                val widthPx = remember {
                    with(current) {
                        val chips = tags.sumOf { tag ->
                            val text = measurer.measure(AnnotatedString(tagChipText(tag)), style.merge(MarkdownBodyStyle.Tag), maxLines = 1, density = current).size.width
                            text + MarkdownBodyStyle.TagPaddingHorizontal.roundToPx() * 2
                        }
                        chips + MarkdownBodyStyle.TagGap.roundToPx() + shift
                    }
                }
                measured = remember { noteTitleBlockHeightPx(measurer, style, current, widthPx, "題", null, tags) }
                Box(modifier = Modifier.requiredWidth(with(current) { widthPx.toDp() }).onGloballyPositioned { drawn = it.size.height }) {
                    NoteTitleBlock(title = "題", folder = null, tags = tags)
                }
            }
        }
        composeRule.waitForIdle()
        assertTrue("描いていない", drawn > 0)
        assertEquals("幅を $shift 画素動かした", measured, drawn.toFloat(), 3f)
    }

    /** 枠の幅を、描画の枠の中に1行がちょうど入る幅から [shift] 画素動かし、見開きの切れ端の高さと描いた高さを比べる。 */
    private fun assertCodeMatches(shift: Int) {
        val block = parseMarkdownBlocks("```python\n" + "x = 1  # 境い目".repeat(3) + "\n```").single() as MarkdownBlock.CodeBlock
        var measured = 0f
        var drawn = -1
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, 1f)) {
                val current = LocalDensity.current
                val measurer = rememberTextMeasurer()
                val style = LocalTextStyle.current
                val widthPx = remember {
                    with(current) {
                        val line = measurer.measure(AnnotatedString(block.code), style.merge(MarkdownBodyStyle.Code), density = current).size.width
                        line + MarkdownBodyStyle.CodePaddingHorizontal.roundToPx() * 2 + shift
                    }
                }
                val pageHeight = with(current) { 2000.dp.toPx() }
                val spread = remember {
                    val inputs = SpreadMeasureInputs(measurer, style, current, widthPx, 0, InlineMarkdownColors.Light, null) { 0f }
                    measureSpreadBlock(block, inputs)
                }
                val slice = remember { paginateSpread(listOf(spread.spread), pageHeight, blockSpacing = 0f).flatMap { it.slices }.single() }
                measured = slice.height
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Box(modifier = Modifier.requiredWidth(with(current) { widthPx.toDp() }).onGloballyPositioned { drawn = it.size.height }) {
                        SpreadSliceView(spread, slice, pageHeight, null, null, null)
                    }
                }
            }
        }
        composeRule.waitForIdle()
        assertTrue("描いていない", drawn > 0)
        assertEquals("幅を $shift 画素動かした", measured, drawn.toFloat(), 3f)
    }
}
