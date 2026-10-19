package com.example.newproject.ui.markdown

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.newproject.domain.markdown.ListItem
import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.ui.screen.SpreadBlock
import com.example.newproject.ui.screen.SpreadLine
import com.example.newproject.ui.screen.SpreadSlice
import com.example.newproject.ui.screen.SpreadTableRow
import com.example.newproject.ui.screen.splitTableRow
import kotlin.math.roundToInt

// 本文の見開き — ブロックを描画と同じ書式で測り、ページの切れ端を描く（→ features/note_spread.md §5・§8 判断5・判断8）。
// 測るのも描くのも、解析済みの表示文字列（記法を消し、リンクを表示名へ替えたもの）。原文を切って解析し直さない。

/** 測ったブロック。ページへ詰める寸法（[spread]）と、切れ端を描くための文字（[render]）を一緒に持つ。 */
internal data class MeasuredSpreadBlock(val spread: SpreadBlock, val render: SpreadBlockRender)

/** 切れ端を描くための、ブロックの表示文字列。位置は [SpreadBlock] の行と同じ数え方。 */
internal sealed interface SpreadBlockRender {
    data class Paragraph(val text: AnnotatedString) : SpreadBlockRender
    data class Heading(val level: Int, val text: AnnotatedString) : SpreadBlockRender

    /** リストの項目。[offsets] は項目の文字がブロックの中で始まる位置（項目の間に1文字ぶんの区切りを数える）。 */
    data class ListItems(val items: List<ListItem>, val texts: List<AnnotatedString>, val offsets: List<Int>) : SpreadBlockRender

    /** 引用の行。[offsets] は行の文字がブロックの中で始まる位置。 */
    data class Quote(val lines: List<AnnotatedString>, val offsets: List<Int>) : SpreadBlockRender
    data class Code(val text: AnnotatedString) : SpreadBlockRender

    /** 表。最初の行は見出しの行。セルは行ごとの頭の位置（セルの中の位置）を持つ。 */
    data class Table(val rows: List<List<SpreadTableCell>>) : SpreadBlockRender
    data class Image(val block: MarkdownBlock.Image) : SpreadBlockRender
    data object Rule : SpreadBlockRender
}

/** 表の1セル。[lineStarts] はセルの中の行の頭の位置。 */
internal data class SpreadTableCell(val text: AnnotatedString, val lineStarts: List<Int>)

/**
 * 測るための入力。[baseStyle] は描画が重ねる `LocalTextStyle`、[textWidthPx] はページの本文の幅。
 * [headingAccessoryPx] は見出しの脇の印のために空ける幅（印を出すときだけ）。
 * [imageHeightPx] は画像の高さ。寸法が分からなければ大きい側（ページいっぱい）に取るよう、大きな値を返す。
 */
internal class SpreadMeasureInputs(
    val measurer: TextMeasurer,
    val baseStyle: TextStyle,
    val density: Density,
    val textWidthPx: Int,
    val headingAccessoryPx: Int,
    val colors: InlineMarkdownColors,
    val linkTaps: NoteLinkTaps?,
    val imageHeightPx: (MarkdownBlock.Image) -> Float
)

/** 1つのブロックを測る。**1回の呼び出しは途中で止められない**ので、巨大なブロックは見開きに入る前に弾く（→ `spreadAllowed`）。 */
internal fun measureSpreadBlock(block: MarkdownBlock, inputs: SpreadMeasureInputs): MeasuredSpreadBlock = with(inputs) {
    fun px(dp: Float): Float = with(density) { dp.dp.toPx() }
    fun measure(text: AnnotatedString, style: TextStyle, widthPx: Int): TextLayoutResult =
        measurer.measure(text, baseStyle.merge(style), constraints = Constraints(maxWidth = widthPx.coerceAtLeast(1)), density = density)
    fun inline(text: String): AnnotatedString = inlineMarkdown(text, colors, linkTaps)

    when (block) {
        is MarkdownBlock.Paragraph -> {
            val text = inline(block.text)
            val layout = measure(text, MarkdownBodyStyle.Paragraph, textWidthPx)
            MeasuredSpreadBlock(SpreadBlock.Lines(layout.spreadLines(0), text.length), SpreadBlockRender.Paragraph(text))
        }

        is MarkdownBlock.Heading -> {
            val text = inline(block.text)
            val layout = measure(text, MarkdownBodyStyle.heading(block.level), textWidthPx - headingAccessoryPx)
            MeasuredSpreadBlock(
                SpreadBlock.Lines(
                    layout.spreadLines(0),
                    text.length,
                    fragmentPadding = px(MarkdownBodyStyle.headingTopPadding(block.level).value),
                    heading = true
                ),
                SpreadBlockRender.Heading(block.level, text)
            )
        }

        is MarkdownBlock.ListBlock -> {
            val spacing = px(MarkdownBodyStyle.ListItemSpacing.value)
            val gap = px(MarkdownBodyStyle.ListMarkerGap.value)
            val texts = block.items.map { inline(it.text) }
            val offsets = texts.runningFold(0) { offset, text -> offset + text.length + 1 }.dropLast(1)
            val lines = block.items.flatMapIndexed { index, item ->
                val marker = if (item.checked != null) {
                    px(MarkdownBodyStyle.CheckboxSize.value)
                } else {
                    measure(AnnotatedString(listMarkerText(item)), MarkdownBodyStyle.Paragraph, textWidthPx).size.width.toFloat()
                }
                val indent = px(MarkdownBodyStyle.ListIndentPerDepth.value) * item.depth
                val width = (textWidthPx - indent - marker - gap).roundToInt()
                measure(texts[index], MarkdownBodyStyle.Paragraph, width).spreadLines(offsets[index], if (index > 0) spacing else 0f)
            }
            MeasuredSpreadBlock(
                SpreadBlock.Lines(lines, (offsets.lastOrNull() ?: 0) + (texts.lastOrNull()?.length ?: 0)),
                SpreadBlockRender.ListItems(block.items, texts, offsets)
            )
        }

        is MarkdownBlock.Blockquote -> {
            val texts = block.lines.map { inline(it) }
            val offsets = texts.runningFold(0) { offset, text -> offset + text.length + 1 }.dropLast(1)
            val width = (textWidthPx - px(MarkdownBodyStyle.QuoteBarWidth.value) - px(MarkdownBodyStyle.QuoteTextStart.value)).roundToInt()
            val lines = texts.flatMapIndexed { index, text -> measure(text, MarkdownBodyStyle.Quote, width).spreadLines(offsets[index]) }
            MeasuredSpreadBlock(
                SpreadBlock.Lines(
                    lines,
                    (offsets.lastOrNull() ?: 0) + (texts.lastOrNull()?.length ?: 0),
                    fragmentPadding = px(MarkdownBodyStyle.QuoteVerticalPadding.value) * 2
                ),
                SpreadBlockRender.Quote(texts, offsets)
            )
        }

        is MarkdownBlock.CodeBlock -> {
            val text = AnnotatedString(block.code.trimEnd())
            val padding = px(MarkdownBodyStyle.CodePadding.value)
            val layout = measure(text, MarkdownBodyStyle.Code, (textWidthPx - padding * 2).roundToInt())
            MeasuredSpreadBlock(
                SpreadBlock.Lines(layout.spreadLines(0), text.length, fragmentPadding = padding * 2),
                SpreadBlockRender.Code(text)
            )
        }

        is MarkdownBlock.Table -> {
            val columns = block.headers.size.coerceAtLeast(1)
            val cellWidth = (textWidthPx / columns - px(MarkdownBodyStyle.TableCellPaddingHorizontal.value) * 2).roundToInt()
            val cellPadding = px(MarkdownBodyStyle.TableCellPaddingVertical.value) * 2
            val texts = listOf(block.headers) + block.rows.map { row ->
                (if (row.size < block.headers.size) row + List(block.headers.size - row.size) { "" } else row).take(block.headers.size)
            }
            var offset = 0
            val rows = mutableListOf<SpreadTableRow>()
            val cells = texts.mapIndexed { rowIndex, row ->
                val style = if (rowIndex == 0) MarkdownBodyStyle.TableHeader else MarkdownBodyStyle.TableCell
                val divider = px(if (rowIndex == 0) 1f else 0.5f)
                val measured = row.map { cell ->
                    val text = inline(cell.trim())
                    val layout = measure(text, style, cellWidth)
                    val lines = layout.spreadLines(offset)
                    offset += text.length + 1
                    text to (lines to (0 until layout.lineCount).map { layout.getLineStart(it) })
                }
                rows += SpreadTableRow(measured.map { it.second.first }, padding = cellPadding + divider)
                measured.map { (text, lines) -> SpreadTableCell(text, lines.second) }
            }
            MeasuredSpreadBlock(SpreadBlock.Table(rows, offset), SpreadBlockRender.Table(cells))
        }

        is MarkdownBlock.Image -> MeasuredSpreadBlock(SpreadBlock.Image(imageHeightPx(block)), SpreadBlockRender.Image(block))

        MarkdownBlock.HorizontalRule -> MeasuredSpreadBlock(
            SpreadBlock.Rule(px(MarkdownBodyStyle.RuleThickness.value + MarkdownBodyStyle.RuleVerticalPadding.value * 2)),
            SpreadBlockRender.Rule
        )
    }
}

/** 測った行を、見開きの行（高さと、ブロックの中の頭の位置）にする。[firstExtra] は最初の行に足す高さ（リストの項目の間など）。 */
private fun TextLayoutResult.spreadLines(offset: Int, firstExtra: Float = 0f): List<SpreadLine> =
    (0 until lineCount).map { line ->
        SpreadLine(
            height = getLineBottom(line) - getLineTop(line) + if (line == 0) firstExtra else 0f,
            charStart = offset + getLineStart(line)
        )
    }

/**
 * ページの1つの切れ端を描く。[fitHeightPx] は組版が表の行を割ったときの高さ（`paginateSpread` の低いほうのページの高さ）で、
 * 表の切れ端を同じ割り方で引き直すのに使う。
 */
@Composable
internal fun SpreadSliceView(
    measured: MeasuredSpreadBlock,
    slice: SpreadSlice,
    fitHeightPx: Float,
    imageLoader: NoteImageLoader?,
    imageMeasurements: NoteImageMeasurements?,
    headingAccessory: (@Composable (blockIndex: Int) -> Unit)?
) {
    val range = slice.charStart until slice.charEnd
    when (val render = measured.render) {
        is SpreadBlockRender.Paragraph -> MarkdownParagraphText(render.text.cut(range))

        is SpreadBlockRender.Heading -> if (headingAccessory == null || slice.charStart > 0) {
            MarkdownHeadingText(render.level, render.text.cut(range))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) { MarkdownHeadingText(render.level, render.text.cut(range)) }
                Box(modifier = Modifier.width(HeadingAccessoryWidth)) { headingAccessory(slice.blockIndex) }
            }
        }

        is SpreadBlockRender.ListItems -> MarkdownListRows(
            render.items.indices.mapNotNull { index ->
                val start = render.offsets[index]
                val end = start + render.texts[index].length
                if (!overlaps(start, end, slice)) return@mapNotNull null
                MarkdownListRow(
                    item = render.items[index],
                    text = render.texts[index].cut(maxOf(slice.charStart, start) - start until minOf(slice.charEnd, end) - start),
                    showMarker = slice.charStart <= start
                )
            }
        )

        is SpreadBlockRender.Quote -> MarkdownBlockquoteLines(
            render.lines.indices.mapNotNull { index ->
                val start = render.offsets[index]
                val end = start + render.lines[index].length
                if (!overlaps(start, end, slice)) return@mapNotNull null
                render.lines[index].cut(maxOf(slice.charStart, start) - start until minOf(slice.charEnd, end) - start)
            }
        )

        is SpreadBlockRender.Code -> MarkdownCodeBlock(render.text.cut(range).text)

        is SpreadBlockRender.Table -> {
            val table = measured.spread as SpreadBlock.Table
            val units = table.rows.flatMapIndexed { row, spreadRow -> splitTableRow(spreadRow, fitHeightPx).map { row to it } }
            val shown = units.subList(slice.unitStart, slice.unitEnd).map { (row, chunk) ->
                row to render.rows[row].mapIndexed { cell, value ->
                    val lines = chunk.cellLines[cell]
                    if (lines.isEmpty()) AnnotatedString("") else value.text.cut(
                        value.lineStarts[lines.first] until (value.lineStarts.getOrNull(lines.last + 1) ?: value.text.length)
                    )
                }
            }
            val header = shown.firstOrNull { it.first == 0 }?.second
            MarkdownTableView(header, shown.filter { it.first > 0 }.map { it.second })
        }

        is SpreadBlockRender.Image -> {
            val heightDp = with(LocalDensity.current) { slice.height.toDp().value.toInt() }
            MarkdownImage(render.block, imageLoader, imageMeasurements, maxHeightDp = heightDp)
        }

        SpreadBlockRender.Rule -> MarkdownHorizontalRule()
    }
}

/** 見出しの脇の印のために空ける幅。印（`HeadingMemoMark`）は押せる大きさとして48dp四方を取る。 */
internal val HeadingAccessoryWidth = 48.dp

private fun overlaps(start: Int, end: Int, slice: SpreadSlice): Boolean =
    start < slice.charEnd && end > slice.charStart || start == end && start in slice.charStart until maxOf(slice.charEnd, slice.charStart + 1)

private fun AnnotatedString.cut(range: IntRange): AnnotatedString {
    val start = range.first.coerceIn(0, length)
    val end = (range.last + 1).coerceIn(start, length)
    return subSequence(start, end)
}
