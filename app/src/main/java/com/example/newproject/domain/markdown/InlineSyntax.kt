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
 * [children] は内側にある記法で、**入れ子はここで保つ** — 保たないと、外側を太字にした瞬間に
 * 内側の斜体やコードが表示から消える（`**A *B* C**` の `*B*`）。
 */
internal data class InlineSpan(
    val kind: InlineSpanKind,
    val start: Int,
    val endExclusive: Int,
    val contentStart: Int,
    val contentEnd: Int,
    val children: List<InlineSpan> = emptyList()
)

/** `\*` のようにエスケープされた1文字。[start] が `\`、[charIndex] が記号本体。 */
internal data class InlineEscape(val start: Int, val charIndex: Int)

internal data class InlineSyntaxScan(
    val spans: List<InlineSpan>,
    val escapes: List<InlineEscape>
) {
    /** 入れ子を含めた全範囲。蒸留はこちらを使う（内側のリンクも判定に要る）。 */
    fun flatten(): List<InlineSpan> {
        val result = mutableListOf<InlineSpan>()
        fun walk(spans: List<InlineSpan>) {
            spans.forEach {
                result += it
                walk(it.children)
            }
        }
        walk(spans)
        return result
    }
}

/**
 * インライン記法を1回の走査でトークン化する。**表示と蒸留の唯一の解釈器。**
 *
 * **同じ文字列を2つの解釈器が別の規則で読むと、書き込みが表示を壊す**（→ lessons L51）。
 * 記法の一覧を揃えるだけでは足りず、**解釈規則そのもの**を1つにする必要がある。
 *
 * 規則は次のとおりで、CommonMark（＝Obsidianの解釈）へ寄せてある。
 *
 * - **エスケープ** — `\` ＋ ASCII記号は記法を開かない。表示側は `\` を出さず記号だけを描く
 * - **コード** — 開いたバッククォートと**同じ数**で閉じる（`` ``a*b`` `` は1つのコード）
 * - **リンク** — `[[...]]` と `[ラベル](URL)` は構文全体を消費し、**内側の記号は装飾に使わない**。
 *   `[` は直後の `]` に `(` が続くときだけリンク（`arr[0]` の誤検出を防ぐ → M7）
 * - **強調** — `***` → `**` → `*` の順に試し、**長い記号で閉じられなければ同じ位置で短い記号を試す**。
 *   `~~` 以外は内側が空白で始まる／終わる対を作らない（`2 * 3 * 4` を斜体にしない → M7）。
 *   対の探索はコード・リンク・エスケープを飛ばす
 * - **入れ子** — 強調の内側はもう一度解釈する。`**A *B* C**` は太字の子として斜体を持つ
 *
 * 入力は**表示側が1つの文字列として受け取る単位**（段落・リスト項目・引用行など）に揃える。
 * 別々に描く行を連結して渡すと、行をまたぐ偽の対ができる。
 */
internal fun scanInlineSyntax(text: String): InlineSyntaxScan {
    val escapes = mutableListOf<InlineEscape>()
    val atomic = mutableListOf<InlineSpan>()
    val search = ForwardSearch(text)

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
                val close = search.indexOf("`".repeat(ticks), i + ticks)
                if (close >= 0) {
                    atomic += InlineSpan(InlineSpanKind.Code, i, close + ticks, i + ticks, close)
                    i = close + ticks
                } else {
                    i++
                }
            }
            text.startsWith("[[", i) -> {
                val close = search.indexOf("]]", i + 2)
                if (close >= 0) {
                    atomic += InlineSpan(InlineSpanKind.WikiLink, i, close + 2, i + 2, close)
                    i = close + 2
                } else {
                    i++
                }
            }
            char == '[' -> {
                val closeBracket = search.indexOf("]", i + 1)
                val isLink = closeBracket >= 0 && text.startsWith("](", closeBracket)
                val closeUrl = if (isLink) search.indexOf(")", closeBracket + 2) else -1
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

    val escapedChars = escapes.mapTo(mutableSetOf()) { it.charIndex }
    val escapedStarts = escapes.mapTo(mutableSetOf()) { it.start }
    val spans = scanRegion(text, 0, text.length, atomic, escapedChars, escapedStarts)
    return InlineSyntaxScan(spans, escapes)
}

/**
 * [from]〜[to] の範囲を解釈する。強調の内側は同じ関数で読み直す（入れ子）。
 *
 * **atomic の照合はカーソルで進める。** 位置ごとに一覧を先頭から探すと、
 * リンクの多いノートで記法数×文字数になり、最大サイズの入力でMainが数秒止まる。
 */
private fun scanRegion(
    text: String,
    from: Int,
    to: Int,
    atomic: List<InlineSpan>,
    escapedChars: Set<Int>,
    escapedStarts: Set<Int>
): List<InlineSpan> {
    val result = mutableListOf<InlineSpan>()
    val search = ForwardSearch(text)
    var cursor = firstAtomicIndexEndingAfter(atomic, from)
    var i = from
    while (i < to) {
        while (cursor < atomic.size && atomic[cursor].endExclusive <= i) cursor++
        val enclosing = atomic.getOrNull(cursor)?.takeIf { it.start <= i && i < it.endExclusive }
        if (enclosing != null) {
            if (enclosing.endExclusive <= to) result += enclosing
            i = enclosing.endExclusive
            continue
        }
        if (i in escapedStarts) {
            i += 2
            continue
        }
        val markers = when {
            text.startsWith("***", i) -> STAR_MARKERS
            text.startsWith("**", i) -> DOUBLE_STAR_MARKERS
            text[i] == '*' -> SINGLE_STAR_MARKER
            text.startsWith("~~", i) -> STRIKE_MARKER
            else -> null
        }
        if (markers == null) {
            i++
            continue
        }
        // **長い記号で閉じられなければ、同じ位置で短い記号を試す。**
        // 1文字進めてしまうと `***斜体*。**` の先頭 `*` が本文として見えてしまう。
        var matchedMarker: String? = null
        var matchedClose = -1
        for (marker in markers) {
            if (i + marker.length > to) continue
            val close = findEmphasisClose(text, search, marker, i, to, atomic, escapedChars)
            if (close != null) {
                matchedMarker = marker
                matchedClose = close
                break
            }
        }
        if (matchedMarker == null) {
            i++
            continue
        }
        val contentStart = i + matchedMarker.length
        result += InlineSpan(
            kind = when (matchedMarker) {
                "***" -> InlineSpanKind.BoldItalic
                "**" -> InlineSpanKind.Bold
                "*" -> InlineSpanKind.Italic
                else -> InlineSpanKind.Strikethrough
            },
            start = i,
            endExclusive = matchedClose + matchedMarker.length,
            contentStart = contentStart,
            contentEnd = matchedClose,
            children = scanRegion(text, contentStart, matchedClose, atomic, escapedChars, escapedStarts)
        )
        i = matchedClose + matchedMarker.length
    }
    return result
}

/**
 * 対の閉じ位置。**コード・リンクの内側とエスケープ済みの記号は閉じに使わない。**
 *
 * 飛ばさないと、リンクのラベルやURLに入っている `*` が外側の記号と対になり、
 * 表示側が装飾と読まない範囲を蒸留だけが保護してしまう（候補が消える）。
 */
private fun findEmphasisClose(
    text: String,
    search: ForwardSearch,
    marker: String,
    start: Int,
    limit: Int,
    atomic: List<InlineSpan>,
    escapedChars: Set<Int>
): Int? {
    var from = start + marker.length
    var cursor = firstAtomicIndexEndingAfter(atomic, from)
    while (from <= limit - marker.length) {
        val close = search.indexOf(marker, from)
        if (close < 0 || close + marker.length > limit) return null
        while (cursor < atomic.size && atomic[cursor].endExclusive <= close) cursor++
        val enclosing = atomic.getOrNull(cursor)?.takeIf { it.start <= close && close < it.endExclusive }
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

/**
 * 前方だけへ進む文字列検索。**同じ記号を何度も末尾まで探し直さないための器。**
 *
 * 走査は左から右へ進むので、ある位置で見つけた閉じ記号は、それより手前から探しても同じ答えになる。
 * **見つからなかったことも覚える** — 閉じ記号の無い `[` が並ぶ本文で、開始記号ごとに末尾まで
 * 走査し直して二乗時間になっていた（250,000文字で8.4秒。表示側も同じ関数を通るのでMainが止まる）。
 */
private class ForwardSearch(private val text: String) {
    private val found = HashMap<String, Int>()

    fun indexOf(needle: String, from: Int): Int {
        val cached = found[needle]
        // -1（この先には無い）は、より後ろから探しても -1 のままなので再利用してよい。
        if (cached != null && (cached < 0 || cached >= from)) return cached
        val result = text.indexOf(needle, from)
        found[needle] = result
        return result
    }
}

/** 開始順に並んだ atomic のうち、[offset] より後ろで終わる最初の要素の位置。 */
private fun firstAtomicIndexEndingAfter(atomic: List<InlineSpan>, offset: Int): Int {
    var low = 0
    var high = atomic.size
    while (low < high) {
        val mid = (low + high) / 2
        if (atomic[mid].endExclusive <= offset) low = mid + 1 else high = mid
    }
    return low
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

private val STAR_MARKERS = listOf("***", "**", "*")
private val DOUBLE_STAR_MARKERS = listOf("**", "*")
private val SINGLE_STAR_MARKER = listOf("*")
private val STRIKE_MARKER = listOf("~~")
