package com.example.newproject.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import com.example.newproject.ui.theme.ChatDivider
import com.example.newproject.ui.theme.OnSurface
import com.example.newproject.ui.theme.OnSurfaceMuted
import com.example.newproject.ui.theme.ReadingAccent
import com.example.newproject.ui.theme.ReadingChip

/**
 * フォルダの道筋。Vault の中の親フォルダを ` / ` でつなぐ（→ features/note_typesetting.md §5.3）。Vault の直下のノートと、
 * 場所が分からないノートは null（出さない）。
 */
internal fun noteFolderLabel(vaultRelativePath: String?): String? {
    val folders = vaultRelativePath
        ?.substringBeforeLast('/', missingDelimiterValue = "")
        ?.split('/')
        ?.filter { it.isNotBlank() }
        .orEmpty()
    return folders.takeIf { it.isNotEmpty() }?.joinToString(" / ")
}

/** 札に出す文字。frontmatter のタグに `#` を付ける。原文が `#` で始まっていれば重ねない。 */
internal fun tagChipText(tag: String): String = if (tag.startsWith("#")) tag else "#$tag"

/**
 * 幅 [widths] の札を、間 [gap] を空けて幅 [available] に左から詰めたときの行の数。**`FlowRow` と同じく、入らなければ次の行へ送る。**
 * 1つで幅を超える札も1行を取る。見開きは題の欄の高さをこれで測る（描画と別の詰め方をすると、1ページ目の高さがずれる）。
 */
internal fun chipRowCount(widths: List<Float>, gap: Float, available: Float): Int {
    if (widths.isEmpty()) return 0
    var rows = 1
    var used = widths.first()
    widths.drop(1).forEach { width ->
        if (used + gap + width > available) {
            rows++
            used = width
        } else {
            used += gap + width
        }
    }
    return rows
}

/** 札の行の上限。タグが多くても題の欄が紙の高さを使い切らず、本文と見開きの1ページ目に高さを残す。 */
internal const val TAG_MAX_ROWS = 2

/** 出し切れなかったタグの数を示す札の文字。 */
internal fun tagOverflowText(rest: Int): String = "+$rest"

/**
 * [count] 個の札（[index] 番目の幅は [widthOf]）のうち、[maxRows] 行に収めて並べる数。全部が収まれば全部。収まらなければ、残りの数の札
 * （幅は [overflowWidth]）を末尾に足しても [maxRows] 行に収まる最大の数（0 もある）。詰め方は [chipRowCount] と同じ。
 * **幅は先頭から上限の行が埋まるまでしか問い合わせない。** タグは何件でも読めるので、隠れる札まで測ると件数に比例して画面の処理を占有する。
 */
internal fun visibleChipCount(
    count: Int,
    widthOf: (index: Int) -> Float,
    gap: Float,
    available: Float,
    maxRows: Int,
    overflowWidth: (rest: Int) -> Float
): Int {
    val placed = ArrayList<Float>()
    var rows = 0
    var used = 0f
    while (placed.size < count) {
        val width = widthOf(placed.size)
        if (rows == 0 || used + gap + width > available) {
            if (rows == maxRows) break
            rows++
            used = width
        } else {
            used += gap + width
        }
        placed += width
    }
    if (placed.size == count) return count
    for (shown in placed.size downTo 0) {
        if (chipRowCount(placed.subList(0, shown) + overflowWidth(count - shown), gap, available) <= maxRows) return shown
    }
    return 0
}

/** 札の文字 [text] を描いたときの札の幅（字の幅＋左右の余白）。 */
private fun chipWidthPx(measurer: TextMeasurer, baseStyle: TextStyle, density: Density, text: String): Float {
    val textWidth = measurer.measure(AnnotatedString(text), baseStyle.merge(MarkdownBodyStyle.Tag), maxLines = 1, density = density).size.width
    return MarkdownBodyStyle.tagChipWidthPx(textWidth, density).toFloat()
}

/**
 * 幅 [widthPx] の欄に並べる札の幅（出すタグと、出し切れなければ残りの数の札）。**描画と見開きの計測が同じこの関数で決める。**
 * 札は [visibleChipCount] が問い合わせた分だけ測る。
 */
private fun tagChipWidths(measurer: TextMeasurer, baseStyle: TextStyle, density: Density, widthPx: Int, tags: List<String>): TagChips {
    val widths = HashMap<Int, Float>()
    val overflowWidths = HashMap<Int, Float>()
    fun widthOf(index: Int): Float = widths.getOrPut(index) { chipWidthPx(measurer, baseStyle, density, tagChipText(tags[index])) }
    fun overflowWidthOf(rest: Int): Float = overflowWidths.getOrPut(rest) { chipWidthPx(measurer, baseStyle, density, tagOverflowText(rest)) }
    val gap = with(density) { MarkdownBodyStyle.TagGap.roundToPx().toFloat() }
    val count = visibleChipCount(tags.size, ::widthOf, gap, widthPx.toFloat(), TAG_MAX_ROWS, ::overflowWidthOf)
    val rest = tags.size - count
    val texts = tags.take(count).map(::tagChipText) + if (rest > 0) listOf(tagOverflowText(rest)) else emptyList()
    val shownWidths = (0 until count).map(::widthOf) + if (rest > 0) listOf(overflowWidthOf(rest)) else emptyList()
    return TagChips(texts, shownWidths, gap)
}

private class TagChips(val texts: List<String>, val widths: List<Float>, val gap: Float)

/**
 * 題の欄。フォルダの道筋・題・タグの札を積み、下に罫を引く。本文の紙と見開きの1ページ目が同じものを描き、
 * 見開きは [noteTitleBlockHeightPx] で同じ高さを測る。道筋と札は本文ではないので、選択のコピーに入れない。
 */
@Composable
internal fun NoteTitleBlock(title: String, folder: String?, tags: List<String>, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        if (folder != null) {
            DisableSelection {
                Text(text = folder, color = ReadingAccent, style = LocalTextStyle.current.merge(MarkdownBodyStyle.FolderLabel))
            }
            Spacer(modifier = Modifier.height(MarkdownBodyStyle.HeaderGap))
        }
        Text(text = title, color = OnSurface, style = LocalTextStyle.current.merge(MarkdownBodyStyle.Title))
        if (tags.isNotEmpty()) {
            Spacer(modifier = Modifier.height(MarkdownBodyStyle.HeaderGap))
            DisableSelection {
                BoxWithConstraints {
                    val measurer = rememberTextMeasurer()
                    val density = LocalDensity.current
                    val baseStyle = LocalTextStyle.current
                    val widthPx = constraints.maxWidth
                    val chips = remember(tags, widthPx, density, baseStyle) { tagChipWidths(measurer, baseStyle, density, widthPx, tags) }
                    TagChipRow(chips.texts)
                }
            }
        }
        Spacer(modifier = Modifier.height(MarkdownBodyStyle.HeaderBottom))
        HorizontalDivider(thickness = MarkdownBodyStyle.HeaderRule, color = ChatDivider)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagChipRow(texts: List<String>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(MarkdownBodyStyle.TagGap),
        verticalArrangement = Arrangement.spacedBy(MarkdownBodyStyle.TagGap)
    ) {
        texts.forEach { text ->
            Text(
                text = text,
                color = OnSurfaceMuted,
                style = LocalTextStyle.current.merge(MarkdownBodyStyle.Tag),
                maxLines = 1,
                modifier = Modifier
                    .background(ReadingChip, RoundedCornerShape(percent = 50))
                    .padding(
                        horizontal = MarkdownBodyStyle.TagPaddingHorizontal,
                        vertical = MarkdownBodyStyle.TagPaddingVertical
                    )
            )
        }
    }
}

/**
 * [NoteTitleBlock] の高さ（罫の下の [MarkdownBodyStyle.TitleGap] は含めない）。[baseStyle] は描画が重ねる `LocalTextStyle`、
 * [widthPx] は欄の幅。描画と同じ書式と寸法から測り、**余白・間・罫は描画と同じく1つずつ画素へ丸めてから足す**。
 */
internal fun noteTitleBlockHeightPx(
    measurer: TextMeasurer,
    baseStyle: TextStyle,
    density: Density,
    widthPx: Int,
    title: String,
    folder: String?,
    tags: List<String>
): Float = with(density) {
    val constraints = Constraints(maxWidth = widthPx.coerceAtLeast(1))
    fun heightOf(text: String, style: TextStyle): Float =
        measurer.measure(AnnotatedString(text), baseStyle.merge(style), constraints = constraints, density = density).size.height.toFloat()
    var height = heightOf(title, MarkdownBodyStyle.Title)
    if (folder != null) height += heightOf(folder, MarkdownBodyStyle.FolderLabel) + MarkdownBodyStyle.HeaderGap.roundToPx()
    if (tags.isNotEmpty()) {
        val chips = tagChipWidths(measurer, baseStyle, density, widthPx, tags)
        val chipHeight = heightOf("#", MarkdownBodyStyle.Tag) + MarkdownBodyStyle.TagPaddingVertical.roundToPx() * 2
        val rows = chipRowCount(chips.widths, chips.gap, widthPx.toFloat())
        height += MarkdownBodyStyle.HeaderGap.roundToPx() + rows * chipHeight + (rows - 1) * chips.gap
    }
    height + MarkdownBodyStyle.HeaderBottom.roundToPx() + MarkdownBodyStyle.HeaderRule.roundToPx()
}
