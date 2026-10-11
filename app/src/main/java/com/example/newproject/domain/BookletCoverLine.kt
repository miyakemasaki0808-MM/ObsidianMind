package com.example.newproject.domain

// ---------------------------------------------------------------------------
// 冊子モードの「扉」— めくっている最中に1行だけ出す代表文。
//
// **生成せずに選ぶ。** Nano は Mutex 直列で1回数十秒かかるので、1枚1秒未満でめくる
// 速度には原理的に間に合わない。本文から1文を**抽出**すれば即時に出せる
// （→ features/booklet_mode.md 判断5）。
//
// **前処理（記法の除去・行の種類の判定）は `MarkdownPlainText.kt` と共有し、選ぶ規則はここに持つ。**
// 再会カードは「問い」や「古びうる記述」を探すが、扉が欲しいのは**最初に読める1文**で、
// 探しているものが違う。インラインコードの中身を残す（[unwrapInlineCode]）のも、再会カードとは意図的に違う。
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
 * **行を落としてから記法を落とす順序が要る。** `stripMarkdownMarkers` は見出しの `#` や表の `|` を
 * 消してしまうので、先に均すと見出しと表の行が本文の文と見分けられなくなる。
 * 日付・ナビ・頭のラベル行の塊は逆に**均した後**で見る — `- **作成日**: …` や `> 🧭 …` も同じ形として落とすため。
 */
private fun firstReadableSentence(content: String): String? {
    // フェンスの中は空行へ替える。詰めると、フェンスをはさんだラベル行が1つの塊につながる。
    val lines = withoutFencedCode(stripFrontmatter(content), keepBlankLines = true).lines()
    val tableLines = lines.tableLineIndices()
    // 扉にしない行は空文字にしておく。頭の情報の塊を探すときも、塊を切る行として数える。
    // ラベルの無い日付だけの行も空にする — 頭の塊の手前にあっても、塊を頭から外さない。
    val plain = lines.mapIndexed { index, line ->
        if (line.isHeadingLine() || index in tableLines || line.isLinkOnly()) return@mapIndexed ""
        val text = line.unwrapInlineCode().stripMarkdownMarkers().collapseSpaces()
        if (text.isDateOnly() && !text.isLabelLine()) "" else text
    }
    val metadata = headMetadataRun(plain)
    return plain.indices.asSequence()
        .filter { it !in metadata }
        .map { plain[it] }
        .filterNot { it.isDateOnly() || it.isNavigation() }
        .firstOrNull { it.hasReadableText() }
        ?.let { splitIntoSentences(it).first().trim() }
        ?.takeIf { it.hasReadableText() }
        ?.let(::truncateForCover)
}

/**
 * 表をなす行（見出し行・区切り・本文の行）の番号。**表はどの行も扉にしない。**
 * 見出し行は列の名前で、本文の行は ID・タグ・日付の並びになりやすく、どちらも何のノートかを言わない。
 * 表しか無い索引ノートはタイトルへ縮退する — タイトルのほうが何のノートかを言う。
 *
 * 表とみなすのは区切りの行があるときだけで、**区切りの無い `|` を含む行は本文の文として残す。**
 */
private fun List<String>.tableLineIndices(): Set<Int> {
    val table = mutableSetOf<Int>()
    for (index in indices) {
        if (!this[index].isTableDelimiter()) continue
        table += index
        if (index > 0 && '|' in this[index - 1]) table += index - 1
        var row = index + 1
        while (row < size && '|' in this[row] && !this[row].isTableDelimiter()) table += row++
    }
    return table
}

/**
 * 値が日付だけの行（「開始日　　：2025/05/23」「作成日: 2026-03-28」、ラベルの無い日付）。
 * **ラベル付きの行を一律には落とさない** — 「ゴール: …」「書籍名：…」は値が読めるので良い扉になる。
 */
private fun String.isDateOnly(): Boolean = DATE_ONLY.matches(this)

/** 🧭 で始まるナビの行（「🧭 クイックナビ: …」）。ほかの絵文字の行は見出し代わりの良い扉でありうるので落とさない。 */
private fun String.isNavigation(): Boolean = startsWith(NAVIGATION_MARK)

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


/**
 * ラベル（20字まで）と区切りは任意。曜日の括弧と時刻は付いてよい。`\s` は全角空白を含まないので並べて書く。
 * **括弧の中は曜日だけを受ける** — 「2026-03-28（設計を見直した理由）」は説明が読めるので扉に残す。
 */
private val DATE_ONLY = Regex(
    """^(?:[^:：]{1,20}[:：][\s\u3000]*)?\d{4}[-/.年][\s\u3000]*\d{1,2}[-/.月][\s\u3000]*\d{1,2}日?""" +
        """(?:[\s\u3000]*[（(](?:[月火水木金土日](?:曜日?)?|Mon|Tue|Wed|Thu|Fri|Sat|Sun)[）)])?""" +
        """(?:[\s\u3000]+\d{1,2}:\d{2}(?::\d{2})?)?$"""
)
private const val NAVIGATION_MARK = "🧭"
private val WHITESPACE_RUN = Regex("""\s+""")
