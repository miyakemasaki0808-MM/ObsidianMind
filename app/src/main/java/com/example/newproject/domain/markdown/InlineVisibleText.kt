package com.example.newproject.domain.markdown

/**
 * インライン記法を落とした「本文に見える文字」。**本文の描画と同じ解釈器（[scanInlineSyntax]）から作る。**
 *
 * 記法を正規表現で別に落とすと、コードの中の `|` や `*` まで記法として消え、
 * `[[note|表示名]]` がリンク先の名前に化ける（本文は表示名を描く）。
 * 原文の1文をそのまま見せる用途では、**見える文字は描画と1つの規則で決める**（→ lessons L51）。
 *
 * - コードは中身をそのまま残す
 * - リンクは [linkLabel]（本文に見えるラベル）
 * - 強調は記号を落として中身を残す（入れ子も同じ）
 * - エスケープは記号だけを残す（`\*` → `*`）
 *
 * 入力の単位は [scanInlineSyntax] と同じく、表示側が1つの文字列として描く単位に揃える。
 */
internal fun inlineVisibleText(text: String): String {
    val scan = scanInlineSyntax(text)
    val escapedBackslashes = scan.escapes.mapTo(HashSet()) { it.start }
    return buildString { appendVisible(text, scan.spans, 0, text.length, escapedBackslashes) }
}

/** リンクの見える文字。`[[note|表示名]]` は表示名、`[[note]]` はノート名、`[ラベル](URL)` はラベル。 */
internal fun linkLabel(text: String, span: InlineSpan): String {
    val content = text.substring(span.contentStart, span.contentEnd)
    return if (span.kind == InlineSpanKind.WikiLink) content.split("|").last() else content
}

/** 記法の外側の文字。エスケープは記号だけを出す（`\*` → `*`）。Obsidianと同じ見え方にする。 */
internal fun plainVisibleText(text: String, from: Int, to: Int, escapedBackslashes: Set<Int>): String =
    buildString {
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

private fun StringBuilder.appendVisible(
    text: String,
    spans: List<InlineSpan>,
    from: Int,
    to: Int,
    escapedBackslashes: Set<Int>
) {
    var index = from
    spans.forEach { span ->
        append(plainVisibleText(text, index, span.start, escapedBackslashes))
        when (span.kind) {
            InlineSpanKind.WikiLink, InlineSpanKind.Link -> append(linkLabel(text, span))
            InlineSpanKind.Code -> append(text, span.contentStart, span.contentEnd)
            InlineSpanKind.BoldItalic,
            InlineSpanKind.Bold,
            InlineSpanKind.Italic,
            InlineSpanKind.Strikethrough ->
                appendVisible(text, span.children, span.contentStart, span.contentEnd, escapedBackslashes)
        }
        index = span.endExclusive
    }
    append(plainVisibleText(text, index, to, escapedBackslashes))
}
