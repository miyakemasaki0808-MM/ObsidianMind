package com.example.newproject.domain.markdown

// ---------------------------------------------------------------------------
// 引用とコールアウトの中身。**描く側（本文・見開き・照らし直しの文字）だけが読む形**で、
// AI への書き戻し・知識索引・反証の一文の候補は引用の原文の行（[MarkdownBlock.Blockquote.lines]）を読む
// （→ docs/dev/system/markdown_rendering.md §8）。
// ---------------------------------------------------------------------------

/**
 * コールアウトの頭の行（`[!note]- 題`）。引用の `>` を1つ外した後の中身で読む。
 * **種類を読むのはこの正規表現だけにする** — 他人の言葉の判定（`quotationLines`）と描く側で別々に書くと、判定が食い違う。
 */
internal val CalloutHeaderRegex = Regex("""^ {0,3}\[!([^\]\s]+)]([+-]?)(.*)$""")

/** 頭の行の種類（小文字）。コールアウトの頭でなければ null。`[!NOTE]` も note として読む。 */
internal fun calloutTypeOf(content: String): String? =
    CalloutHeaderRegex.find(content)?.groupValues?.get(1)?.lowercase()

/**
 * コールアウトの種類を、読み手にとっての役割でまとめたもの。描く側は役割を言葉と記号で示し、色に頼らない。
 * [Quote] だけが他人の言葉で、ほかは書き手が書いたもの（→ features/reunion_card.md §5）。
 */
internal enum class CalloutRole { Quote, Summary, Question, Caution, Example }

/**
 * 種類から役割を引く。知らない種類は null。
 * **important はまとめ、tips は例・ヒント。** important は Obsidian では tip の別名だが、要点のまとめに使われるので
 * まとめに置く。tips は Obsidian の種類に無いが tip と同じに使われる（→ docs/dev/system/markdown_rendering.md §8）。
 */
internal fun calloutRoleOf(type: String): CalloutRole? = CALLOUT_ROLES[type.lowercase()]

private val CALLOUT_ROLES: Map<String, CalloutRole> = buildMap {
    listOf("quote", "cite").forEach { put(it, CalloutRole.Quote) }
    listOf("note", "info", "summary", "abstract", "tldr", "important").forEach { put(it, CalloutRole.Summary) }
    listOf("question", "faq", "help").forEach { put(it, CalloutRole.Question) }
    listOf("warning", "caution", "attention", "danger", "error", "bug", "failure", "fail", "missing")
        .forEach { put(it, CalloutRole.Caution) }
    listOf("example", "tip", "tips", "hint", "todo", "success", "check", "done").forEach { put(it, CalloutRole.Example) }
}

/**
 * コールアウトの頭。[type] は書かれたままの種類で、知らない種類（[role] が null）はこれを見出しに出す。
 * [title] は題で、無ければ空。**畳み（`+`・`-`）は読まず、いつも開いて描く。**
 */
internal data class Callout(val type: String, val role: CalloutRole?, val title: String)

/**
 * 描くための引用の中身。[callout] はコールアウトの頭（素の引用なら null）、[blocks] は中のブロック。
 *
 * - **段落の行は改行でつなぐ。** 引用の中の行は1行ずつ描く（行をつながない）ので、描く側は改行で割って行に戻す
 * - **中の画像は原文の文字のまま段落にする。** 画像の寸法は本文のブロック番号で共有しているので、引用の中では描けない
 */
internal data class QuoteContent(val callout: Callout?, val blocks: List<MarkdownBlock>)

/**
 * 引用の行（`>` を1つ外したもの）を、描くための中身に読む。frontmatter は外さない（`> ---` は区切り線）。
 * [depth] は入れ子の段数で、[MAX_QUOTE_DEPTH] に達したら中を読まずに行のまま段落にする（`>` を何千も重ねた行で再帰が尽きないように）。
 */
internal fun parseQuoteContent(lines: List<String>, depth: Int = 0): QuoteContent {
    if (depth >= MAX_QUOTE_DEPTH) {
        return QuoteContent(null, if (lines.isEmpty()) emptyList() else listOf(MarkdownBlock.Paragraph(lines.joinToString("\n"))))
    }
    val header = lines.firstOrNull()?.let(CalloutHeaderRegex::find)
    val callout = header?.let {
        Callout(type = it.groupValues[1], role = calloutRoleOf(it.groupValues[1]), title = it.groupValues[3].trim())
    }
    val body = if (callout != null) lines.drop(1) else lines
    val blocks = parseBlockLines(body, quoteDepth = depth + 1) { it.joinToString("\n") }.map { block ->
        if (block is MarkdownBlock.Image) MarkdownBlock.Paragraph(block.sourceText()) else block
    }
    return QuoteContent(callout, blocks)
}

/** 引用の入れ子を中まで読む段数の上限。 */
internal const val MAX_QUOTE_DEPTH = 8
