package com.example.newproject.ui.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
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

/**
 * 題の欄。フォルダの道筋・題・タグの札を積み、下に罫を引く。本文の紙と見開きの1ページ目が同じものを描き、
 * 見開きは [noteTitleBlockHeightPx] で同じ高さを測る。道筋と札は本文ではないので、選択のコピーに入れない。
 */
@OptIn(ExperimentalLayoutApi::class)
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
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(MarkdownBodyStyle.TagGap),
                    verticalArrangement = Arrangement.spacedBy(MarkdownBodyStyle.TagGap)
                ) {
                    tags.forEach { tag ->
                        Text(
                            text = tagChipText(tag),
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
        }
        Spacer(modifier = Modifier.height(MarkdownBodyStyle.HeaderBottom))
        HorizontalDivider(thickness = MarkdownBodyStyle.HeaderRule, color = ChatDivider)
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
        val widths = tags.map { tag ->
            val textWidth = measurer.measure(AnnotatedString(tagChipText(tag)), baseStyle.merge(MarkdownBodyStyle.Tag), maxLines = 1, density = density)
                .size.width
            MarkdownBodyStyle.tagChipWidthPx(textWidth, density).toFloat()
        }
        val chipHeight = heightOf("#", MarkdownBodyStyle.Tag) + MarkdownBodyStyle.TagPaddingVertical.roundToPx() * 2
        val gap = MarkdownBodyStyle.TagGap.roundToPx().toFloat()
        val rows = chipRowCount(widths, gap, widthPx.toFloat())
        height += MarkdownBodyStyle.HeaderGap.roundToPx() + rows * chipHeight + (rows - 1) * gap
    }
    height + MarkdownBodyStyle.HeaderBottom.roundToPx() + MarkdownBodyStyle.HeaderRule.roundToPx()
}
