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

/** 閉じられていないフェンスは、開いた行以降を全部コードとみなす（未閉じの本文へ落とさない）。 */
internal fun withoutFencedCode(content: String): String {
    if (!content.contains("```")) return content
    var inside = false
    return content.lineSequence()
        .filter { line ->
            if (line.trimStart().startsWith("```")) {
                inside = !inside
                false
            } else {
                !inside
            }
        }
        .joinToString("\n")
}

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
