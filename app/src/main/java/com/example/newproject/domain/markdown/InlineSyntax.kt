package com.example.newproject.domain.markdown

/**
 * インライン記法の種別。**見た目ではなく構文としての種類**を持つ。
 *
 * 色や太さは表示側が決める。ここが決めるのは「どこからどこまでが1つの記法か」だけで、
 * 蒸留の保護範囲もその答えを使う。
 */
internal enum class InlineSpanKind { BoldItalic, Bold, Italic, Strikethrough, Code, WikiLink, Link }

/**
 * インライン記法1つ分。
 *
 * [start]〜[endExclusive] は記号を含む全体、[contentStart]〜[contentEnd] は内側。
 * 表示側は内側を描き、蒸留は**全体を境界の置けない範囲**として使う。
 */
internal data class InlineSpan(
    val kind: InlineSpanKind,
    val start: Int,
    val endExclusive: Int,
    val contentStart: Int,
    val contentEnd: Int
)

/** `\*` のようにエスケープされた1文字。[start] が `\`、[charIndex] が記号本体。 */
internal data class InlineEscape(val start: Int, val charIndex: Int)

internal data class InlineSyntaxScan(
    val spans: List<InlineSpan>,
    val escapes: List<InlineEscape>
) {
    fun spanAt(index: Int): InlineSpan? = spans.firstOrNull { index >= it.start && index < it.endExclusive }
}

/**
 * インライン記法を1回の走査でトークン化する。**表示と蒸留の唯一の解釈器。**
 *
 * **同じ文字列を2つの解釈器が別の規則で読むと、書き込みが表示を壊す**（→ lessons L51）。
 * 実際に、コードのバッククォート数とエスケープの扱いが表示側と保護側で違ったために、
 * 表示上は1つの斜体の内側へ蒸留が候補境界を置ける状態が残っていた。
 * **記法の一覧を揃えるだけでは足りず、解釈規則そのものを1つにする必要がある。**
 *
 * 規則は次のとおりで、CommonMark（＝Obsidianの解釈）へ寄せてある。
 *
 * - **エスケープ** — `\` ＋ ASCII記号は記法を開かない。表示側は `\` を出さず記号だけを描く
 * - **コード** — 開いたバッククォートと**同じ数**で閉じる（`` ``a*b`` `` は1つのコード）
 * - **リンク** — `[[...]]` と `[ラベル](URL)` は構文全体を消費し、**内側の記号は装飾に使わない**。
 *   `[` は直後の `]` に `(` が続くときだけリンク（`arr[0]` の誤検出を防ぐ → M7）
 * - **強調** — `***` → `**` → `*` → `~~` の順に見る。`~~` 以外は**内側が空白で始まる／終わる対を作らない**
 *   （`2 * 3 * 4` を斜体にしないため → M7）。対の探索はコード・リンク・エスケープを飛ばす
 *
 * 入力は**表示側が1つの文字列として受け取る単位**（段落・リスト項目・引用行など）に揃える。
 * 別々に描く行を連結して渡すと、行をまたぐ偽の対ができる。
 */
internal fun scanInlineSyntax(text: String): InlineSyntaxScan {
    val escapes = mutableListOf<InlineEscape>()
    val atomic = mutableListOf<InlineSpan>()

    var i = 0
    while (i < text.length) {
        val char = text[i]
        if (char == '\\' && i + 1 < text.length && isAsciiPunctuation(text[i + 1])) {
            escapes += InlineEscape(i, i + 1)
            i += 2
            continue
        }
        when {
            char == '`' -> {
                val ticks = runLength(text, i, '`')
                val marker = "`".repeat(ticks)
                val close = text.indexOf(marker, i + ticks)
                if (close >= 0) {
                    atomic += InlineSpan(InlineSpanKind.Code, i, close + ticks, i + ticks, close)
                    i = close + ticks
                } else {
                    i++
                }
            }
            text.startsWith("[[", i) -> {
                val close = text.indexOf("]]", i + 2)
                if (close >= 0) {
                    atomic += InlineSpan(InlineSpanKind.WikiLink, i, close + 2, i + 2, close)
                    i = close + 2
                } else {
                    i++
                }
            }
            char == '[' -> {
                val closeBracket = text.indexOf(']', i + 1)
                val isLink = closeBracket >= 0 && text.startsWith("](", closeBracket)
                val closeUrl = if (isLink) text.indexOf(')', closeBracket + 2) else -1
                if (isLink && closeUrl >= 0) {
                    atomic += InlineSpan(InlineSpanKind.Link, i, closeUrl + 1, i + 1, closeBracket)
                    i = closeUrl + 1
                } else {
                    i++
                }
            }
            else -> i++
        }
    }

    val escapedStarts = escapes.mapTo(mutableSetOf()) { it.start }
    val escapedChars = escapes.mapTo(mutableSetOf()) { it.charIndex }
    val emphasis = mutableListOf<InlineSpan>()
    i = 0
    while (i < text.length) {
        val enclosing = atomic.firstOrNull { i >= it.start && i < it.endExclusive }
        if (enclosing != null) {
            i = enclosing.endExclusive
            continue
        }
        if (i in escapedStarts) {
            i += 2
            continue
        }
        if (i in escapedChars) {
            i++
            continue
        }
        val marker = when {
            text.startsWith("***", i) -> "***"
            text.startsWith("**", i) -> "**"
            text[i] == '*' -> "*"
            text.startsWith("~~", i) -> "~~"
            else -> null
        }
        if (marker == null) {
            i++
            continue
        }
        val close = findEmphasisClose(text, marker, i, atomic, escapedChars)
        if (close == null) {
            i++
            continue
        }
        emphasis += InlineSpan(
            kind = when (marker) {
                "***" -> InlineSpanKind.BoldItalic
                "**" -> InlineSpanKind.Bold
                "*" -> InlineSpanKind.Italic
                else -> InlineSpanKind.Strikethrough
            },
            start = i,
            endExclusive = close + marker.length,
            contentStart = i + marker.length,
            contentEnd = close
        )
        i = close + marker.length
    }

    return InlineSyntaxScan((atomic + emphasis).sortedBy { it.start }, escapes)
}

/**
 * 対の閉じ位置。**コード・リンクの内側とエスケープ済みの記号は閉じに使わない。**
 *
 * 飛ばさないと、リンクのラベルやURLに入っている `*` が外側の記号と対になり、
 * 表示側が装飾と読まない範囲を蒸留だけが保護してしまう（候補が消える）。
 */
private fun findEmphasisClose(
    text: String,
    marker: String,
    start: Int,
    atomic: List<InlineSpan>,
    escapedChars: Set<Int>
): Int? {
    var from = start + marker.length
    while (from <= text.length - marker.length) {
        val close = text.indexOf(marker, from)
        if (close < 0) return null
        val enclosing = atomic.firstOrNull { close >= it.start && close < it.endExclusive }
        if (enclosing != null) {
            from = enclosing.endExclusive
            continue
        }
        if (close in escapedChars) {
            from = close + 1
            continue
        }
        if (marker == "~~") return close
        val inner = text.substring(start + marker.length, close)
        // **空白規則で外れたら、そこで諦める。** 先の記号まで探しに行くと
        // `*a * b*` のような入力が新たに強調へ変わる。飛ばしてよいのは
        // 「表示側も読まない範囲」（コード・リンク・エスケープ）だけである。
        if (inner.isEmpty() || inner.first().isWhitespace() || inner.last().isWhitespace()) return null
        return close
    }
    return null
}

private fun runLength(text: String, start: Int, char: Char): Int {
    var i = start
    while (i < text.length && text[i] == char) i++
    return i - start
}

/** CommonMark がエスケープを認めるのは ASCII の記号だけ。`\n` の `n` などは対象外。 */
private fun isAsciiPunctuation(char: Char): Boolean =
    char.code in 0x21..0x2F || char.code in 0x3A..0x40 ||
        char.code in 0x5B..0x60 || char.code in 0x7B..0x7E
