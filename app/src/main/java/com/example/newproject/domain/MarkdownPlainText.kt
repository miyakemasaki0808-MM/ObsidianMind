package com.example.newproject.domain

// ---------------------------------------------------------------------------
// Markdown を「人が読む文字列」へ均す純関数。**選ぶ規則は持たない。**
//
// 再会カードの候補列挙（[scanReunionCandidates]）と冊子モードの扉（[selectCoverLine]）が
// 同じ前処理を要求したので、**前処理だけ**をここへ出した。行の種類の判定（見出し・表の区切り・
// リンクだけの行・頭の情報の塊）も前処理であって選定規則ではないので、ここに置く
// （頭の情報の塊は AI の抜粋の冒頭 [buildNoteExcerpt] も使う）。
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
 *
 * [keepBlankLines] が真なら、落とす行を詰めずに空行へ替える。**段落の境目を保つ**ためで、
 * 詰めるとフェンスの前後の段落が1つにつながる（行ごとに見る用途では、どちらでも同じ）。
 */
internal fun withoutFencedCode(content: String, keepBlankLines: Boolean = false): String {
    if (!content.contains(BACKTICK_FENCE) && !content.contains(TILDE_FENCE)) return content
    var opened: FenceMarker? = null
    return content.lineSequence()
        .mapNotNull { line ->
            val marker = fenceMarkerOf(line)
            val open = opened
            val outside = when {
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
            when {
                outside -> line
                keepBlankLines -> ""
                else -> null
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
 * `` `NoteViewModel` `` のようなインラインコードを、**中身を残して**記法だけ落とす。
 *
 * [stripMarkdownMarkers] はインラインコードを空白へ替えるので、原文の1文をそのまま見せる用途では
 * **先にこちらを通す。** 通さないと「`NoteViewModel` を分割した。」が「 を分割した。」になり、主語が消える。
 */
internal fun String.unwrapInlineCode(): String = replace(INLINE_CODE_CONTENT) { it.groupValues[1] }

/** 見出しの行。**記法を落とす前に**判定する（落とすと本文の行と見分けられない）。 */
internal fun String.isHeadingLine(): Boolean = HEADING_LINE.containsMatchIn(this)

/** `|---|:--|` のような表の区切り。記法を落とすと `- :` だけが残り、意味を持たない。 */
internal fun String.isTableDelimiter(): Boolean = TABLE_DELIMITER_LINE.matches(this)

/**
 * リンクだけの行。**ラベルへ畳んでから判定しない。**
 *
 * [stripMarkdownMarkers] はリンクをラベルへ置き換えるので、
 * 畳んだ後では「リンクだけの行」と「その語を書いた行」を区別できない。
 * 判定はリンクを**丸ごと消して**から行う（画像 `![alt](path)` も同じ経路で消える）。
 */
internal fun String.isLinkOnly(): Boolean =
    !replace(WIKILINK, "").replace(MARKDOWN_LINK, "").hasReadableText()

/** 文字か数字が1つも無ければ、罫線・記号だけの行である。 */
internal fun String.hasReadableText(): Boolean = READABLE_CHAR.containsMatchIn(this)

/**
 * 「ラベル：値」の行。**記法を落とした後の行で**判定する（`- **状態:** …` も同じ形として見る）。
 * 値は空でもよい（`現在位置　：`）。ラベルは20字までで、文字を1つは含む — `10:30` のような時刻は
 * ラベルにしない。URL の `://` も区切りにしない。
 */
internal fun String.isLabelLine(): Boolean = LABEL_LINE.containsMatchIn(this)

/**
 * ノートの頭にある、何のノートかを言わない情報の塊（読書ノートの「開始日・現在位置・ステータス」、
 * 設計書の頭の「状態・最終検証・関連コード」）の行の範囲。無ければ空の範囲。
 *
 * 塊とみなすのは、**最初の読める行から始まり、空行を挟まずに [METADATA_RUN_MIN_LINES] 行以上続くラベル行**だけ。
 * - **頭に限る。** 本文の中の「用語：説明」の並びは定義の一覧で、中身そのものである
 * - **2行では塊にしない。** 「日付・プロジェクト」の2行のように、ラベルの値が何のノートかを言う頭がある。
 *   1行だけの「ゴール: …」「書籍名：…」も同じで、単独なら良い代表文になる
 *
 * [plainLines] は記法を落とした後の行。見出しなど、呼び出し側が数えない行は空文字で渡す（塊を切る）。
 */
internal fun headMetadataRun(plainLines: List<String>): IntRange {
    val start = plainLines.indexOfFirst { it.hasReadableText() }
    if (start < 0) return IntRange.EMPTY
    var end = start
    while (end < plainLines.size && plainLines[end].isLabelLine()) end++
    return if (end - start >= METADATA_RUN_MIN_LINES) start until end else IntRange.EMPTY
}

internal const val METADATA_RUN_MIN_LINES = 3

private val LABEL_LINE = Regex("""^(?=[^:：]*\p{L})[^:：]{1,20}[:：](?!//)""")

private val INLINE_CODE_CONTENT = Regex("""`([^`\n]*)`""")
private val HEADING_LINE = Regex("""^\s{0,3}#{1,6}\s""")
private val TABLE_DELIMITER_LINE = Regex("""^\s*\|?[\s:|-]*-[\s:|-]*\|[\s:|-]*$""")
private val READABLE_CHAR = Regex("""[\p{L}\p{N}]""")

/**
 * 1行を文へ割る。**括弧・鉤括弧の内側では切らない。**
 *
 * 素朴に終止符で切ると、引用した発言（「これでいいのか？」）の途中で割れ、
 * **閉じ括弧の無い断片**がそのまま候補になる。カードには1文をそのまま出すので、
 * 割れた断片が見えてしまう。
 *
 * [splitAtPeriods] が真なら、英文のピリオドでも割る（→ [endsSentenceAtPeriod]）。
 * 偽のときは割らない — 英文の複数文が1つの「文」になるが、用途によってはそれで足りる。
 */
internal fun splitIntoSentences(line: String, splitAtPeriods: Boolean = false): List<String> {
    val sentences = mutableListOf<String>()
    val buffer = StringBuilder()
    var depth = 0
    line.forEachIndexed { index, char ->
        buffer.append(char)
        val ends = when (char) {
            in OPENING_BRACKETS -> {
                depth++
                false
            }
            in CLOSING_BRACKETS -> {
                if (depth > 0) depth--
                false
            }
            in SENTENCE_TERMINATORS -> depth == 0
            '.' -> splitAtPeriods && depth == 0 && endsSentenceAtPeriod(line, index)
            else -> false
        }
        if (ends) {
            sentences += buffer.toString()
            buffer.setLength(0)
        }
    }
    if (buffer.isNotBlank()) sentences += buffer.toString()
    return sentences
}

/**
 * [index] のピリオドで英文が終わるか。**小数・略語・頭文字では切らない。**
 *
 * 終わりとみなすのは、直後が空白で、続く文字が小文字でないとき。直後が空白でなければ小数や版番号（`3.5`・`v1.2`）、
 * 続きが小文字なら文の途中の略語（`approx. three`）である。ピリオドの前の語が1文字（頭文字・`e.g.`）か
 * [PERIOD_ABBREVIATIONS] にあれば、続きが大文字でも切らない（`Dr. Smith`）。省略記号（`...`）でも切らない。
 */
private fun endsSentenceAtPeriod(text: String, index: Int): Boolean {
    if (text.getOrNull(index + 1)?.isWhitespace() != true) return false
    if (text.getOrNull(index - 1) == '.') return false
    val word = text.substring(0, index).takeLastWhile { it.isLetter() }
    if (word.length == 1 || word.lowercase() in PERIOD_ABBREVIATIONS) return false
    val following = text.substring(index + 1).trimStart().firstOrNull() ?: return false
    return !following.isLowerCase()
}

private val PERIOD_ABBREVIATIONS = setOf(
    "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc", "al",
    "inc", "ltd", "co", "no", "vol", "fig", "eq", "cf", "approx", "ver", "ed"
)

/**
 * 段落の中で折り返した行を、1行の文としてつなぐ。**日本語の側では空白を入れない。**
 *
 * 空白でつなぐと、どこで折り返したかで文が変わる（「場合に限り、 朝の光」「十分な睡眠を 取れて」）。
 * 改行の両側が英数字や記号のときだけ空白を入れる（`Remote work always` ＋ `improves` は空白が要る）。
 * 各行は前後の空白を落としてからつなぐ。
 */
internal fun joinSoftWrappedLines(lines: List<String>): String = buildString {
    lines.map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
        val previous = lastOrNull()
        if (previous != null && !previous.isJapanese() && !line.first().isJapanese()) append(' ')
        append(line)
    }
}

/** 漢字・かな・全角の記号。語の間に空白を置かない書き方の文字。 */
private fun Char.isJapanese(): Boolean =
    code in 0x3000..0x30FF || code in 0xFF00..0xFFEF ||
        Character.UnicodeScript.of(code) == Character.UnicodeScript.HAN

internal const val OPENING_BRACKETS = "「『（(【［[{"
internal const val CLOSING_BRACKETS = "」』）)】］]}"
internal const val SENTENCE_TERMINATORS = "。！？!?"
