package com.example.newproject.domain

// ---------------------------------------------------------------------------
// 他人の言葉を引いた行を見分ける純関数。**記法を落とす前の行で**判定する（落とすと地の文と見分けられない）。
// 再会カードは引用した問いを書き手の問いにしない（→ features/reunion_card.md §5「引用した問いは書き手の問いにしない」）。
//
// 純関数だが**軽くはない**（入力サイズに比例する）。呼び出し側は Dispatchers.Default へ逃がすこと（→ lessons L13）。
// ---------------------------------------------------------------------------

/**
 * 行ごとに、他人の言葉を引いた引用の中にあるか。
 *
 * 引用とみなすのは、素の引用（`> …`）と、引用・出典のコールアウト（`> [!quote]`・`> [!cite]`）。
 * ほかのコールアウト（`[!note]`・`[!question]` など）は書き手が書いたものなので引用にしないが、その中に置いた引用は引用。
 *
 * **記号の数ではなく、引用と箇条のまとまり（CommonMark の入れ物）の開閉で追う。** 行ごとに次の順で読む。
 * 1. 開いている入れ物を外から照らす（引用は `>`、箇条は項目の字下げ。空行は箇条にだけ合う）
 * 2. 合わない入れ物は、段落が開いていて、残りが段落を切るまとまり（引用・見出し・区切り線・段落を切れる箇条）も
 *    合わない箇条の次の項目も始めないとき（続きの行）だけ残し、それ以外は閉じる
 * 3. 残りから新しい引用と箇条を開く。段落の中で開ける箇条は、段落を切れるもの（中身のある記号の箇条か、1で始まる番号）だけ
 * 4. 行の中身が段落を開いたままにするかを記録する（見出し・区切り線・コード・空行は閉じる）
 *
 * コールアウトの種類は、その引用を開いた行の中身でだけ読む。HTML のまとまりと表は段落として扱う（引用の側へ倒れる）。
 */
internal fun quotationLines(lines: List<String>): BooleanArray {
    val quoted = BooleanArray(lines.size)
    val open = mutableListOf<Container>()
    var paragraphOpen = false
    lines.forEachIndexed { index, raw ->
        val line = expandTabs(raw)
        var pos = 0
        var matched = 0
        while (matched < open.size) {
            pos = open[matched].match(line, pos) ?: break
            matched++
        }
        if (matched < open.size) {
            val continues = paragraphOpen && !line.substring(pos).isMarkdownBlank() &&
                !interruptsParagraph(line, pos) && !open[matched].startsNextItem(line, pos)
            if (continues) {
                quoted[index] = open.any { it.quotation }
                return@forEachIndexed
            }
            while (open.size > matched) open.removeAt(open.lastIndex)
            paragraphOpen = false
        }
        while (true) {
            val quoteStart = quoteContentStart(line, pos)
            if (quoteStart != null) {
                open += QuoteContainer(quotation = !isWritersCallout(line.substring(quoteStart)))
                pos = quoteStart
                paragraphOpen = false
                continue
            }
            val item = listItemAt(line, pos)
            if (item != null && (!paragraphOpen || item.interruptsParagraph)) {
                open += ListItemContainer(item.width, item.kind)
                pos = item.contentStart
                paragraphOpen = false
                continue
            }
            break
        }
        val content = line.substring(pos)
        paragraphOpen = when {
            content.isMarkdownBlank() || ATX_HEADING.containsMatchIn(content) || THEMATIC_BREAK.matches(content) ||
                FENCE_OPEN.containsMatchIn(content) -> false
            // 段落の直後の下線は見出しにする。字下げ4つ以上は段落の中なら続き、外ならコード。
            paragraphOpen && SETEXT_UNDERLINE.matches(content) -> false
            !paragraphOpen && content.takeWhile { it == ' ' }.length >= INDENTED_CODE -> false
            else -> true
        }
        quoted[index] = open.any { it.quotation }
    }
    return quoted
}

/** 開いている入れ物。[match] は [line] の [from] から印か字下げを読み、中身の始まりを返す。合わなければ null。 */
private sealed interface Container {
    val quotation: Boolean
    fun match(line: String, from: Int): Int?

    /** [from] から先が、この入れ物の箇条の次の項目か。次の項目は新しい箇条ではないので、段落を切れる条件を問わない。 */
    fun startsNextItem(line: String, from: Int): Boolean = false
}

private class QuoteContainer(override val quotation: Boolean) : Container {
    override fun match(line: String, from: Int): Int? = quoteContentStart(line, from)
}

/**
 * 箇条の項目。[width] は項目を開いた位置から中身までの幅で、続く行はそれだけ字下げすれば項目の中。空行も項目の中。
 * [kind] は印の種類（記号そのものか番号の区切り）で、同じ種類の印が同じ箇条の次の項目になる。
 */
private class ListItemContainer(private val width: Int, private val kind: Char) : Container {
    override val quotation = false

    override fun startsNextItem(line: String, from: Int): Boolean = listItemAt(line, from)?.kind == kind

    override fun match(line: String, from: Int): Int? = when {
        line.substring(from).isMarkdownBlank() -> line.length
        line.substring(from).takeWhile { it == ' ' }.length >= width -> from + width
        else -> null
    }
}

/** 引用の印（3つまでの字下げと `>`、その後ろの空白1つ）の後ろの位置。印でなければ null。 */
private fun quoteContentStart(line: String, from: Int): Int? {
    var index = from
    while (index < line.length && index - from < MAX_MARKER_INDENT && line[index] == ' ') index++
    if (index >= line.length || line[index] != '>') return null
    index++
    if (index < line.length && line[index] == ' ') index++
    return index
}

/** 箇条の項目の印。[interruptsParagraph] は段落の途中でも新しい箇条を始められるか。 */
private class ListItemStart(val contentStart: Int, val width: Int, val kind: Char, val interruptsParagraph: Boolean)

private fun listItemAt(line: String, from: Int): ListItemStart? {
    val rest = line.substring(from)
    val marker = LIST_MARKER.find(rest) ?: return null
    val markerEnd = marker.range.last + 1
    val after = rest.substring(markerEnd)
    val empty = after.isMarkdownBlank()
    // 中身は印の後ろの空白の後ろから。CommonMark は空白5つ以上を1つとして残りを項目の中のコードにするが、
    // そこまで追うと字下げのコードを問いとして拾うので、引用の側へ倒す。
    val gap = if (empty) 1 else after.takeWhile { it == ' ' }.length
    val symbol = marker.groupValues[2]
    val interrupts = !empty && (symbol.length == 1 && symbol[0] in BULLETS || symbol.dropLast(1) == "1")
    return ListItemStart(
        contentStart = if (empty) line.length else from + markerEnd + gap,
        width = markerEnd + gap,
        kind = symbol.last(),
        interruptsParagraph = interrupts
    )
}

/**
 * [from] から先が、段落の途中でも新しいまとまりを始めるか。始めないなら、記号の無い続きの行になる。
 * コードの柵は見ない — 浅い柵は前処理（`withoutFencedCode`）で空行になり、深い柵の中身を引用の外へ出すとコードを問いとして拾う。
 */
private fun interruptsParagraph(line: String, from: Int): Boolean {
    val rest = line.substring(from)
    return quoteContentStart(line, from) != null ||
        ATX_HEADING.containsMatchIn(rest) || THEMATIC_BREAK.matches(rest) ||
        listItemAt(line, from)?.interruptsParagraph == true
}

/** 引用を開いた行の中身が、書き手のコールアウト（引用・出典以外の種類）か。 */
private fun isWritersCallout(content: String): Boolean =
    CALLOUT_TYPE.find(content)?.groupValues?.get(1)?.lowercase()?.let { it !in QUOTATION_CALLOUTS } ?: false

/**
 * Markdown の空行か（タブは広げた後）。半角空白だけの行に限る。`isBlank` は全角空白やノーブレークスペースも空白とみなすが、
 * それらは文字なので、見た目を空けるために置いた行で引用の段落を閉じてはいけない。
 */
private fun String.isMarkdownBlank(): Boolean = all { it == ' ' }

/** タブを4つ区切りの空白へ広げる。字下げの幅だけを測るので、中身の文字は替わってよい。 */
private fun expandTabs(line: String): String {
    if ('\t' !in line) return line
    val expanded = StringBuilder()
    for (char in line) {
        if (char == '\t') {
            do expanded.append(' ') while (expanded.length % TAB_STOP != 0)
        } else {
            expanded.append(char)
        }
    }
    return expanded.toString()
}

private const val MAX_MARKER_INDENT = 3
private const val INDENTED_CODE = 4
private const val TAB_STOP = 4
private const val BULLETS = "-*+"
private val QUOTATION_CALLOUTS = setOf("quote", "cite")
private val CALLOUT_TYPE = Regex("""^ {0,3}\[!([^\]\s]+)][+-]?""")
private val LIST_MARKER = Regex("""^( {0,3})([-*+]|\d{1,9}[.)])(?= |$)""")
private val ATX_HEADING = Regex("""^ {0,3}#{1,6}(?: |$)""")
private val THEMATIC_BREAK = Regex("""^ {0,3}([-*_])(?: *\1){2,} *$""")
/** コードの柵を開く行。バッククォートの柵は後ろにバッククォートを書けない（書いてあればインラインコードを含む段落）。 */
private val FENCE_OPEN = Regex("""^ {0,3}(?:`{3,}[^`]*$|~{3,})""")
private val SETEXT_UNDERLINE = Regex("""^ {0,3}(?:=+|-+) *$""")
