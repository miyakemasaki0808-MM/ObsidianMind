package com.example.newproject.domain

// ---------------------------------------------------------------------------
// 冊子モードの「扉」— めくっている最中に1行だけ出す代表文。
//
// **生成せずに選ぶ。** Nano は Mutex 直列で1回数十秒かかるので、1枚1秒未満でめくる
// 速度には原理的に間に合わない。本文から1文を**抽出**すれば即時に出せる
// （→ features/booklet_mode.md 判断5）。
//
// **前処理は [withoutFencedCode] / [stripMarkdownMarkers] と共有し、選ぶ規則はここに持つ。**
// 再会カードは「問い」や「古びうる記述」を探すが、扉が欲しいのは**最初に読める1文**で、
// 探しているものが違う。
// ---------------------------------------------------------------------------

/**
 * 扉に出す最大文字数。**超えたら末尾を `…` にして、全体をこの長さに収める。**
 *
 * めくる速度（1枚1秒未満）で読み取れる量に合わせている。長さを揃えると
 * ページごとの高さも揃うので、ZINE の佇まいが崩れない。
 */
internal const val BOOKLET_COVER_MAX_CHARS = 40

/**
 * 本文から扉の1行を選ぶ。**同じ本文からは必ず同じ文を返す**（乱数・時刻・訪問回数を混ぜない）。
 *
 * 選べる文が無ければ [title] を返すので、**戻り値が空になることはない** —
 * 空のページは冊子の中で最も目立つ壊れ方なので、フォールバックを必ず持つ。
 *
 * 入力は8KBの境界読み出しで足りる（→ features/booklet_mode.md §5）。
 * 純関数だが入力サイズに比例するので、呼び出し側は `Dispatchers.Default` へ逃がすこと。
 */
internal fun selectCoverLine(content: String, title: String): String =
    firstReadableSentence(content) ?: truncateForCover(title.trim())

/**
 * 最初に「読める」1文。
 *
 * **行を落としてから記法を落とす順序が要る。** `stripMarkdownMarkers` は見出しの `#` を
 * 消してしまうので、先に均すと見出しが本文の文と見分けられなくなる。
 */
private fun firstReadableSentence(content: String): String? =
    withoutFencedCode(stripFrontmatter(content))
        .lineSequence()
        .filterNot { it.isHeading() || it.isTableDelimiter() || it.isLinkOnly() }
        .map { it.unwrapInlineCode().stripMarkdownMarkers().collapseSpaces() }
        .firstOrNull { it.hasReadableText() }
        ?.let { splitIntoSentences(it).first().trim() }
        ?.takeIf { it.hasReadableText() }
        ?.let(::truncateForCover)

/**
 * `` `NoteViewModel` `` のようなインラインコードを、**中身を残して**記法だけ落とす。
 *
 * **ここは再会カードと意図的に違う。** [stripMarkdownMarkers] はインラインコードを
 * 空白へ替える（問いや前提を探すのに、コードは邪魔なため）。
 * だが扉は**最初の1文をそのまま見せる**役なので、同じことをすると
 * 「`NoteViewModel` を分割した。」が「 を分割した。」になり、主語が消える。
 * このリポジトリのノートのように記法を多用する本文では、これが常時起きる。
 */
private fun String.unwrapInlineCode(): String = replace(INLINE_CODE_FENCE) { it.groupValues[1] }

/** 見出しは「何が書いてあるか」であって代表文ではない。 */
private fun String.isHeading(): Boolean = HEADING.containsMatchIn(this)

/** `|---|:--|` のような表の区切り。記法を落とすと `- :` だけが残り、意味を持たない。 */
private fun String.isTableDelimiter(): Boolean = TABLE_DELIMITER.matches(this)

/**
 * リンクだけの行。**ラベルへ畳んでから判定しない。**
 *
 * `stripMarkdownMarkers` はリンクをラベルへ置き換えるので、
 * 畳んだ後では「リンクだけの行」と「その語を書いた行」を区別できない。
 * 判定はリンクを**丸ごと消して**から行う（画像 `![alt](path)` も同じ経路で消える）。
 */
private fun String.isLinkOnly(): Boolean =
    !replace(WIKILINK, "").replace(MARKDOWN_LINK, "").hasReadableText()

/** 文字か数字が1つも無ければ、罫線・記号だけの行なので扉にならない。 */
private fun String.hasReadableText(): Boolean = READABLE.containsMatchIn(this)

/** 表の `|` を空白へ替えた後などに空白が続くので、1つへ均す。 */
private fun String.collapseSpaces(): String = replace(WHITESPACE_RUN, " ").trim()

/**
 * 表示幅で切る。**コードポイントで数える。**
 *
 * `String.length` は UTF-16 単位なので、絵文字（サロゲートペア）の途中で切ると
 * 壊れた文字が出る。蒸留が同じ理由で絵文字の保持を実機確認している。
 */
private fun truncateForCover(text: String): String {
    val codePoints = text.codePointCount(0, text.length)
    if (codePoints <= BOOKLET_COVER_MAX_CHARS) return text
    val end = text.offsetByCodePoints(0, BOOKLET_COVER_MAX_CHARS - 1)
    return text.substring(0, end).trimEnd() + "…"
}

private val INLINE_CODE_FENCE = Regex("""`([^`\n]*)`""")
private val HEADING = Regex("""^\s{0,3}#{1,6}\s""")
private val TABLE_DELIMITER = Regex("""^\s*\|?[\s:|-]*-[\s:|-]*\|[\s:|-]*$""")
private val READABLE = Regex("""[\p{L}\p{N}]""")
private val WHITESPACE_RUN = Regex("""\s+""")
