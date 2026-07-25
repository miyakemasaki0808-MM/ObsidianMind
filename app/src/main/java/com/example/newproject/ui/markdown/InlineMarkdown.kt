package com.example.newproject.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import com.example.newproject.ui.theme.LightAppColors

/**
 * インラインMarkdownの装飾色。
 *
 * [inlineMarkdown] は `remember{}` の中（＝非Composable）から呼ばれるため、
 * 役割トークンを直接引けない。テーマの現在値を呼び出し側で束ねて渡す。
 */
internal data class InlineMarkdownColors(
    val strikethrough: Color,
    val codeBackground: Color,
    val link: Color
) {
    companion object {
        /** テーマを持たない文脈（テスト等）用のフォールバック。 */
        val Light = InlineMarkdownColors(
            strikethrough = LightAppColors.onSurfaceHint,
            codeBackground = LightAppColors.codePanel,
            link = LightAppColors.linkText
        )
    }
}

internal fun inlineMarkdown(
    text: String,
    colors: InlineMarkdownColors = InlineMarkdownColors.Light
) = buildAnnotatedString {
    var index = 0

    while (index < text.length) {
        when {
            // 太字イタリック ***text*** (** より先にチェック)
            text.startsWith("***", index) -> {
                val end = findEmphasisEnd(text, "***", index)
                if (end != -1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                        append(text.substring(index + 3, end))
                    }
                    index = end + 3
                } else { append(text[index]); index++ }
            }
            // 太字 **text**
            text.startsWith("**", index) -> {
                val end = findEmphasisEnd(text, "**", index)
                if (end != -1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(index + 2, end))
                    }
                    index = end + 2
                } else { append(text[index]); index++ }
            }
            // イタリック *text*
            text[index] == '*' -> {
                val end = findEmphasisEnd(text, "*", index)
                if (end != -1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text.substring(index + 1, end))
                    }
                    index = end + 1
                } else { append(text[index]); index++ }
            }
            // 打ち消し線 ~~text~~
            text.startsWith("~~", index) -> {
                val end = text.indexOf("~~", startIndex = index + 2)
                if (end != -1) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = colors.strikethrough)) {
                        append(text.substring(index + 2, end))
                    }
                    index = end + 2
                } else { append(text[index]); index++ }
            }
            // インラインコード `code`
            text[index] == '`' -> {
                val end = text.indexOf('`', startIndex = index + 1)
                if (end != -1) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = colors.codeBackground)) {
                        append(text.substring(index + 1, end))
                    }
                    index = end + 1
                } else { append(text[index]); index++ }
            }
            // Obsidianリンク [[note]]
            text.startsWith("[[", index) -> {
                val end = text.indexOf("]]", startIndex = index + 2)
                if (end != -1) {
                    val linkText = text.substring(index + 2, end).split("|").last()
                    withStyle(SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline)) {
                        append(linkText)
                    }
                    index = end + 2
                } else { append(text[index]); index++ }
            }
            // 通常リンク [label](url)
            // 最初の ] の直後に ( が続く場合のみリンクとみなす。以前は後方の "](" を
            // 無制限に探していたため、配列表記 arr[0] などの [ が離れたリンクと
            // ペアリングされて間の本文を巻き込んでいた（M7）。
            text[index] == '[' -> {
                val closeBracket = text.indexOf(']', startIndex = index + 1)
                val isLink = closeBracket != -1 && text.startsWith("](", closeBracket)
                val closeUrl = if (isLink) text.indexOf(')', startIndex = closeBracket + 2) else -1
                if (isLink && closeUrl != -1) {
                    withStyle(SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline)) {
                        append(text.substring(index + 1, closeBracket))
                    }
                    index = closeUrl + 1
                } else { append(text[index]); index++ }
            }
            else -> { append(text[index]); index++ }
        }
    }
}

/**
 * 強調記号の閉じ位置を返す（見つからない・強調とみなせない場合は -1）。
 * 中身が空でなく、先頭・末尾が空白でない場合のみ強調とみなす。
 * 以前は次の記号と無条件にペアリングしていたため、「3 * 4 と 5 * 6」のような
 * スペース区切りの * が離れた * と結合し、間の本文を斜体に巻き込んでいた（M7）。
 */
private fun findEmphasisEnd(text: String, marker: String, start: Int): Int {
    val end = text.indexOf(marker, startIndex = start + marker.length)
    if (end == -1) return -1
    val content = text.substring(start + marker.length, end)
    if (content.isEmpty() || content.first().isWhitespace() || content.last().isWhitespace()) return -1
    return end
}
