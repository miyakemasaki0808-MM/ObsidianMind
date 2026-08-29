package com.example.newproject.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import com.example.newproject.domain.markdown.InlineSpan
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
 * **構文の解釈は自分で持たない。** どこからどこまでが1つの記法かは [scanInlineSyntax] が決め、
 * ここは種別を色・太さ・下線へ写すだけを担う。
 * **解釈を写し取って別に持つと、書き込み側（蒸留）が表示を壊す**（→ lessons L51）。
 *
 * **入れ子も描く。** `**A *B* C**` の内側の斜体を捨てると、蒸留が文を太字にした瞬間に
 * ユーザーの装飾が表示から消える。
 */
internal fun inlineMarkdown(
    text: String,
    colors: InlineMarkdownColors = InlineMarkdownColors.Light
) = buildAnnotatedString {
    val scan = scanInlineSyntax(text)
    val escapedBackslashes = scan.escapes.mapTo(mutableSetOf()) { it.start }
    appendSpans(text, scan.spans, 0, text.length, escapedBackslashes, colors)
}

/** [from]〜[to] を、[spans] の範囲だけ装飾しながら描く。範囲の内側は子で描き直す。 */
private fun AnnotatedString.Builder.appendSpans(
    text: String,
    spans: List<InlineSpan>,
    from: Int,
    to: Int,
    escapedBackslashes: Set<Int>,
    colors: InlineMarkdownColors
) {
    var index = from
    spans.forEach { span ->
        appendPlain(text, index, span.start, escapedBackslashes)
        withStyle(styleFor(span, colors)) {
            when (span.kind) {
                // `[[note|表示名]]` は表示名だけを出す。内側は解釈しない。
                InlineSpanKind.WikiLink ->
                    append(text.substring(span.contentStart, span.contentEnd).split("|").last())
                InlineSpanKind.Code, InlineSpanKind.Link ->
                    append(text.substring(span.contentStart, span.contentEnd))
                else -> appendSpans(
                    text, span.children, span.contentStart, span.contentEnd, escapedBackslashes, colors
                )
            }
        }
        index = span.endExclusive
    }
    appendPlain(text, index, to, escapedBackslashes)
}

/** 装飾の外側。エスケープは記号だけを出す（`\*` → `*`）。Obsidianと同じ見え方にする。 */
private fun AnnotatedString.Builder.appendPlain(
    text: String,
    from: Int,
    to: Int,
    escapedBackslashes: Set<Int>
) {
    var index = from
    while (index < to) {
        if (index in escapedBackslashes && index + 1 < to) {
            append(text[index + 1])
            index += 2
            continue
        }
        append(text[index])
        index++
    }
}

private fun styleFor(span: InlineSpan, colors: InlineMarkdownColors): SpanStyle = when (span.kind) {
    InlineSpanKind.BoldItalic -> SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
    InlineSpanKind.Bold -> SpanStyle(fontWeight = FontWeight.Bold)
    InlineSpanKind.Italic -> SpanStyle(fontStyle = FontStyle.Italic)
    InlineSpanKind.Strikethrough ->
        SpanStyle(textDecoration = TextDecoration.LineThrough, color = colors.strikethrough)
    InlineSpanKind.Code -> SpanStyle(fontFamily = FontFamily.Monospace, background = colors.codeBackground)
    InlineSpanKind.WikiLink, InlineSpanKind.Link ->
        SpanStyle(color = colors.link, textDecoration = TextDecoration.Underline)
}
