package com.example.newproject.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import com.example.newproject.domain.markdown.InlineSpanKind
import com.example.newproject.domain.markdown.scanInlineSyntax
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
            strikethrough = LightAppColors.onSurfaceFaint,
            codeBackground = LightAppColors.codePanel,
            link = LightAppColors.linkText
        )
    }
}

/**
 * インラインMarkdownを [AnnotatedString] へ描く。
 *
 * **構文の解釈は自分で持たない。** どこからどこまでが1つの記法かは
 * [scanInlineSyntax] が決め、ここは種別を色・太さ・下線へ写すだけを担う。
 * **解釈を写し取って別に持つと、書き込み側（蒸留）が表示を壊す**（→ lessons L51）。
 */
internal fun inlineMarkdown(
    text: String,
    colors: InlineMarkdownColors = InlineMarkdownColors.Light
) = buildAnnotatedString {
    val scan = scanInlineSyntax(text)
    val escapedBackslashes = scan.escapes.mapTo(mutableSetOf()) { it.start }
    var index = 0

    while (index < text.length) {
        val span = scan.spanAt(index)
        if (span != null && span.start == index) {
            val content = text.substring(span.contentStart, span.contentEnd)
            when (span.kind) {
                InlineSpanKind.BoldItalic -> withStyle(
                    SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
                ) { append(content) }
                InlineSpanKind.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(content) }
                InlineSpanKind.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(content) }
                InlineSpanKind.Strikethrough -> withStyle(
                    SpanStyle(textDecoration = TextDecoration.LineThrough, color = colors.strikethrough)
                ) { append(content) }
                InlineSpanKind.Code -> withStyle(
                    SpanStyle(fontFamily = FontFamily.Monospace, background = colors.codeBackground)
                ) { append(content) }
                // `[[note|表示名]]` は表示名だけを出す。
                InlineSpanKind.WikiLink -> withStyle(
                    SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline)
                ) { append(content.split("|").last()) }
                InlineSpanKind.Link -> withStyle(
                    SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline)
                ) { append(content) }
            }
            index = span.endExclusive
            continue
        }
        // エスケープは記号だけを出す（`\*` → `*`）。Obsidianと同じ見え方にする。
        if (index in escapedBackslashes) {
            append(text[index + 1])
            index += 2
            continue
        }
        append(text[index])
        index++
    }
}
