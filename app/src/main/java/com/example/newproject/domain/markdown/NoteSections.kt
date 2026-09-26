package com.example.newproject.domain.markdown

import com.example.newproject.model.ReunionPassage

/**
 * ノート本文のセクション（見出し＋その配下）。
 * text は LLM に渡すための再構成済み Markdown。
 */
data class NoteSection(
    val title: String,
    val level: Int,
    val text: String
)

/**
 * ブロック単位で算出したセクション情報。
 * [sectionForBlockIndex] に LazyColumn の先頭可視ブロックindexを渡すと、
 * その位置を含む「直近の見出し」のセクションを返す。
 */
class NoteSectionModel internal constructor(
    private val headingBlockIndices: List<Int>,
    val sections: List<NoteSection>,
    // 描画側（`MarkdownNoteContent`）で再パースせず使い回すためのパース済みブロック列
    internal val blocks: List<MarkdownBlock>
) {
    /** index 以下で最も近い見出しのセクション。見出し前／見出し無しは null。 */
    fun sectionForBlockIndex(index: Int): NoteSection? {
        var result: NoteSection? = null
        for (k in headingBlockIndices.indices) {
            if (headingBlockIndices[k] <= index) result = sections[k] else break
        }
        return result
    }

    /**
     * AI入力用に、指定セクションを核として前後のブロックを交互に加え、
     * targetLength 文字前後まで広げた「周辺テキスト」を作る。
     * セクション単位ではなくブロック単位で広げるので、親セクションが子を内包する
     * 構造でも本文が重複しない。section が null（見出し前・見出しなし）や
     * sections に無い擬似セクションの場合はノート先頭から切り出す。
     */
    fun surroundingContext(
        section: NoteSection?,
        targetLength: Int = SURROUNDING_CONTEXT_TARGET_LENGTH
    ): String {
        if (blocks.isEmpty()) return ""
        val k = section?.let { sections.indexOf(it) } ?: -1
        if (k < 0) return blocksToMarkdown(blocks).take(targetLength)

        var start = headingBlockIndices[k]
        // セクション終端 = 次の同レベル以下の見出しの直前（buildNoteSectionModel と同じ規則）
        val level = sections[k].level
        var end = blocks.size
        for (j in start + 1 until blocks.size) {
            val next = blocks[j]
            if (next is MarkdownBlock.Heading && next.level <= level) {
                end = j
                break
            }
        }

        // ブロック単位の概算長で拡張を判定する（+2 はブロック間の空行ぶん）
        val blockLengths = blocks.map { blocksToMarkdown(listOf(it)).length + 2 }
        var length = (start until end).sumOf { blockLengths[it] }
        var preferPrev = true
        while (length < targetLength && (start > 0 || end < blocks.size)) {
            val expandPrev = if (preferPrev) start > 0 else end >= blocks.size
            if (expandPrev) {
                start--
                length += blockLengths[start]
            } else {
                length += blockLengths[end]
                end++
            }
            preferPrev = !preferPrev
        }
        return blocksToMarkdown(blocks.subList(start, end))
    }

    /**
     * 前回いちばん先まで読んだブロック（→ features/reunion_card.md 判断6「読み進めたところの求め方」）。
     * 引数は痕跡の最後の訪問の値。ブロックが無ければ null。
     *
     * 到達率は整数の百分率なので、長いノートではそれだけだと数ブロックずれる。
     * **節が今の本文にあれば、その節の範囲へ収める** — 節の外を編集しても、読み進めた節の中には留まる。
     * 節の範囲は [sectionForBlockIndex] と同じく「その見出しから次の見出しの直前まで」で数える。
     * 痕跡の節の名前はその関数で記録されているので、別の区切り方をすると食い違う。
     *
     * 到達率からの見積もりは**手前へ寄せる**。最深ブロックが画面に全部入った回
     * （割合1.0）は、素直に割り戻すと1つ先の未表示のブロックを指してしまう。
     */
    fun readFrontierBlock(sectionTitle: String?, progressPercent: Int): Int? {
        if (blocks.isEmpty()) return null
        val lastIndex = blocks.lastIndex
        val reached = progressPercent.coerceIn(0, 100).toLong() * blocks.size
        val estimate = ((reached + 99) / 100 - 1).toInt().coerceIn(0, lastIndex)
        val title = sectionTitle?.takeIf { it.isNotBlank() } ?: return estimate
        val nearest = headingBlockIndices.indices
            .filter { sections[it].title == title }
            .map { k -> headingBlockIndices[k]..((headingBlockIndices.getOrNull(k + 1) ?: blocks.size) - 1) }
            // **同名の見出しが複数あれば、見積もりに最も近いもの。** 先頭固定にすると、
            // 後ろの「まとめ」を読んでいたのに前の「まとめ」へ戻される。
            .minByOrNull { range -> distanceTo(estimate, range) }
            ?: return estimate
        return estimate.coerceIn(nearest)
    }

    /**
     * 読み進めたブロックを境に、前後を合わせて [targetLength] 文字まで切り出す。
     *
     * 前後はおおむね半分ずつにし、**片側が短ければ余りをもう片側へ回す。**
     * 境目から遠い側を削るので、どれだけ長いブロックがあっても境目の近くは残る。
     */
    fun passageAround(frontierBlock: Int, targetLength: Int): ReunionPassage {
        if (blocks.isEmpty() || targetLength <= 0) return ReunionPassage(before = "", after = "")
        val frontier = frontierBlock.coerceIn(0, blocks.lastIndex)

        // 窓は各側 targetLength ぶんあれば足りる。本文全体を文字列にしない（最大1MB）。
        var start = frontier
        var beforeLength = blockLength(frontier)
        while (start > 0 && beforeLength < targetLength) {
            start--
            beforeLength += blockLength(start)
        }
        var end = frontier + 1
        var afterLength = 0
        while (end < blocks.size && afterLength < targetLength) {
            afterLength += blockLength(end)
            end++
        }

        val before = blocksToMarkdown(blocks.subList(start, frontier + 1))
        val after = blocksToMarkdown(blocks.subList(frontier + 1, end))
        val afterText = after.take(targetLength - minOf(before.length, targetLength / 2))
        val beforeText = before.takeLast(targetLength - afterText.length)
        return ReunionPassage(before = beforeText, after = afterText)
    }

    /**
     * 続きから読むの送り先。**読み進めたところの少し手前**を画面の先頭に置く。
     *
     * 読み進めたブロックは当時の画面の最下端なので、そこを先頭にすると直前の文脈が見えない。
     * 節の見出しが近ければ見出しへ、遠ければ1ブロック手前へ送る。長い節で見出しへ戻すと、
     * 読み進めたところが何画面も下になる。
     */
    fun resumeBlockFor(frontierBlock: Int): Int {
        if (blocks.isEmpty()) return 0
        val frontier = frontierBlock.coerceIn(0, blocks.lastIndex)
        val heading = headingBlockIndices.lastOrNull { it <= frontier }
        return if (heading != null && frontier - heading <= RESUME_HEADING_REACH) {
            heading
        } else {
            (frontier - 1).coerceAtLeast(0)
        }
    }

    private fun blockLength(index: Int): Int = blocksToMarkdown(listOf(blocks[index])).length + 2

    private fun distanceTo(index: Int, range: IntRange): Int = when {
        index < range.first -> range.first - index
        index > range.last -> index - range.last
        else -> 0
    }

    companion object {
        const val SURROUNDING_CONTEXT_TARGET_LENGTH = 1200

        /** 続きから読むで、節の見出しまで戻してよい距離（ブロック数）。 */
        const val RESUME_HEADING_REACH = 3
    }
}

/**
 * 本文を見出しごとのセクションに分割する。
 * 各セクションは「その見出し 〜 次の同レベル以下の見出しの直前」までを含む（配下の見出しも内包）。
 */
fun buildNoteSectionModel(content: String): NoteSectionModel {
    val blocks = parseMarkdownBlocks(content)
    val headingIndices = mutableListOf<Int>()
    val sections = mutableListOf<NoteSection>()

    blocks.forEachIndexed { i, block ->
        if (block is MarkdownBlock.Heading) {
            val level = block.level
            var end = blocks.size
            for (j in i + 1 until blocks.size) {
                val next = blocks[j]
                if (next is MarkdownBlock.Heading && next.level <= level) {
                    end = j
                    break
                }
            }
            headingIndices.add(i)
            sections.add(
                NoteSection(
                    title = block.text.trim(),
                    level = level,
                    text = blocksToMarkdown(blocks.subList(i, end))
                )
            )
        }
    }

    return NoteSectionModel(headingIndices, sections, blocks)
}

/**
 * 書き戻しの1段あたりインデント。**元の列幅は復元しない** — [ListItem.depth] は
 * 段数までしか保持しておらず、そもそも幅の絶対値には意味がないため。
 */
private const val LIST_INDENT = "  "

private fun ListMarker.render(): String = when (this) {
    is ListMarker.Bullet -> "-"
    is ListMarker.Ordered -> "$number$delimiter"
}

/** パース済みブロック列を LLM 入力用の Markdown 文字列に再構成する。 */
internal fun blocksToMarkdown(blocks: List<MarkdownBlock>): String =
    blocks.joinToString("\n\n") { block ->
        when (block) {
            is MarkdownBlock.Heading -> "#".repeat(block.level) + " " + block.text
            is MarkdownBlock.Paragraph -> block.text
            // 原文の記法へ忠実に戻す。alt だけにするとAI入力から意味が落ち、
            // 再パースで段落になって往復も壊れる（→ note_image_rendering §5）。
            is MarkdownBlock.Image -> block.sourceText()
            is MarkdownBlock.ListBlock -> block.items.joinToString("\n") { item ->
                val checkbox = item.checked?.let { if (it) "[x] " else "[ ] " } ?: ""
                LIST_INDENT.repeat(item.depth) + item.marker.render() + " " + checkbox + item.text
            }
            is MarkdownBlock.CodeBlock -> "```\n" + block.code + "\n```"
            is MarkdownBlock.HorizontalRule -> "---"
            is MarkdownBlock.Blockquote -> block.lines.joinToString("\n") { "> $it" }
            is MarkdownBlock.Table -> buildString {
                append("| ").append(block.headers.joinToString(" | ")).append(" |\n")
                append("|").append(block.headers.joinToString("|") { "---" }).append("|\n")
                block.rows.forEach { row ->
                    append("| ").append(row.joinToString(" | ")).append(" |\n")
                }
            }.trimEnd()
        }
    }
