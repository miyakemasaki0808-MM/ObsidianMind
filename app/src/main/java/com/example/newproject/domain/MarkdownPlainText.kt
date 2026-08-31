package com.example.newproject.domain

// ---------------------------------------------------------------------------
// Markdown を「人が読む文字列」へ均す純関数。**選ぶ規則は持たない。**
//
// 再会カードの候補列挙（[scanReunionCandidates]）と冊子モードの扉（[selectCoverLine]）が
// 同じ前処理を要求したので、**前処理だけ**をここへ出した。
// **選定規則は共有しない** — 候補として何を採るかは用途ごとに違い、
// そこまで束ねると片方の都合でもう片方の見え方が変わる。
//
// 純関数だが**軽くはない**（入力サイズに比例する）。呼び出し側は Dispatchers.Default
// へ逃がすこと（→ lessons L13）。
// ---------------------------------------------------------------------------

/**
 * コードフェンスの中を落とす。
 *
 * **開始記号の種類と長さを覚える。** 「``` で始まる行のたびに真偽を反転する」形だと、
 * 4本のバッククォートで開いたフェンスの中にある3本の行を閉じと誤読し、
 * **コードの中身が本文として出てくる**。チルダのフェンスも同じ理由で見落とす。
 *
 * 閉じとみなすのは**同じ記号・開始と同じ長さ以上・記号の後ろが空白だけ**の行に限る
 * （CommonMark と同じ規則）。後ろの検査を落とすと、コードの中に書いた
 * ```` ```` not-close ```` のような行が閉じになり、**続きの本文が外へ出てくる**。
 * 字下げは3空白までをフェンスとみなす（4空白以上はコードブロックの字下げ）。
 *
 * 閉じられていないフェンスは、開いた行以降を全部コードとみなす（未閉じの本文へ落とさない）。
 */
internal fun withoutFencedCode(content: String): String {
    if (!content.contains(BACKTICK_FENCE) && !content.contains(TILDE_FENCE)) return content
    var opened: FenceMarker? = null
    return content.lineSequence()
        .filter { line ->
            val marker = fenceMarkerOf(line)
            val open = opened
            when {
                open == null -> {
                    if (marker == null) true else { opened = marker; false }
                }
                marker != null &&
                    marker.char == open.char &&
                    marker.length >= open.length &&
                    // **閉じ行は記号だけ。** 情報文字列を書けるのは開始行だけである。
                    !marker.hasTrailingText -> {
                    opened = null
                    false
                }
                // フェンスの中身。短いフェンス行もここへ落ちる（閉じにならない）。
                else -> false
            }
        }
        .joinToString("\n")
}

/**
 * フェンスの開始・終了記号。
 *
 * `char` は `` ` `` か `~`、`length` は連なりの長さ、[hasTrailingText] は記号の後ろに
 * 空白以外があるか（開始行の情報文字列。**閉じ行には許されない**）。
 */
private data class FenceMarker(val char: Char, val length: Int, val hasTrailingText: Boolean)

private fun fenceMarkerOf(line: String): FenceMarker? {
    val indent = line.takeWhile { it == ' ' }.length
    // 4空白以上はフェンスではなく、字下げコードブロックの中身。
    if (indent > MAX_FENCE_INDENT) return null
    val rest = line.substring(indent)
    val char = rest.firstOrNull() ?: return null
    if (char != '`' && char != '~') return null
    val length = rest.takeWhile { it == char }.length
    if (length < MIN_FENCE_LENGTH) return null
    return FenceMarker(char, length, hasTrailingText = rest.drop(length).isNotBlank())
}

private const val MIN_FENCE_LENGTH = 3
private const val MAX_FENCE_INDENT = 3
private const val BACKTICK_FENCE = "```"
private const val TILDE_FENCE = "~~~"

/**
 * 見出し・引用・箇条書きの印と、インラインの記法を落とす。
 *
 * **表の行は落とさない。** このリポジトリの文書がそうであるように、
 * 判断の理由が表の中に書かれていることがある。区切りの `|` だけを空白へ替える。
 */
internal fun String.stripMarkdownMarkers(): String = this
    .replace(INLINE_CODE, " ")
    .replace(WIKILINK) { it.groupValues[1] }
    .replace(MARKDOWN_LINK) { it.groupValues[1] }
    .replace(LINE_PREFIX, "")
    .replace(TABLE_PIPE, " ")
    .replace(EMPHASIS, "")
    .trim()

internal val INLINE_CODE = Regex("`[^`\n]*`")
internal val WIKILINK = Regex("""\[\[([^\]|]+)(?:\|[^\]]*)?]]""")
internal val MARKDOWN_LINK = Regex("""\[([^\]]*)]\([^)]*\)""")
internal val LINE_PREFIX = Regex("""^\s{0,8}(?:>\s*|#{1,6}\s+|[-*+]\s+|\d+[.)]\s+)+""")
internal val TABLE_PIPE = Regex("""\|""")
internal val EMPHASIS = Regex("""\*{1,3}|_{2,3}|~~""")

/**
 * 1行を文へ割る。**括弧・鉤括弧の内側では切らない。**
 *
 * 素朴に終止符で切ると、引用した発言（「これでいいのか？」）の途中で割れ、
 * **閉じ括弧の無い断片**がそのまま候補になる。カードには1文をそのまま出すので、
 * 割れた断片が見えてしまう。
 */
internal fun splitIntoSentences(line: String): List<String> {
    val sentences = mutableListOf<String>()
    val buffer = StringBuilder()
    var depth = 0
    for (char in line) {
        buffer.append(char)
        when (char) {
            in OPENING_BRACKETS -> depth++
            in CLOSING_BRACKETS -> if (depth > 0) depth--
            in SENTENCE_TERMINATORS -> if (depth == 0) {
                sentences += buffer.toString()
                buffer.setLength(0)
            }
        }
    }
    if (buffer.isNotBlank()) sentences += buffer.toString()
    return sentences
}

internal const val OPENING_BRACKETS = "「『（(【［[{"
internal const val CLOSING_BRACKETS = "」』）)】］]}"
internal const val SENTENCE_TERMINATORS = "。！？!?"
