package com.example.newproject.domain.markdown

import com.example.newproject.domain.HeadingIndex
import com.example.newproject.domain.MemoSectionMatch
import com.example.newproject.model.ReunionPassage
import com.example.newproject.model.SectionRef

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
    /**
     * 見出しの索引。**解析と一緒に作る**ので、画面は見出しをたどらずに節を引き、メモを照合できる（→ [HeadingIndex]）。
     * このモデルは Main の外で組み立てる（→ `NoteSectionController`）ので、見出しの数に比例する仕事は Main に載らない。
     */
    internal val headingIndex: HeadingIndex = HeadingIndex(sections.map { it.title })

    /** index 以下で最も近い見出しのセクション。見出し前／見出し無しは null。 */
    fun sectionForBlockIndex(index: Int): NoteSection? = headingAt(index)?.let { sections[it] }

    /**
     * index を含む節を、**見出し名と同名の中での順番**で指す（→ [SectionRef]）。
     * 見出しより前と見出しの無いノートは名前の無い節。区切り方は [sectionForBlockIndex] と同じ。
     */
    fun sectionRefAt(index: Int): SectionRef =
        headingAt(index)?.let { headingIndex.sections[it] } ?: SectionRef(title = null)

    /**
     * [ref] が指す節の始まりのブロック番号。見出しより前の節は先頭（0）。
     * **本文を解析し直した後もここで引き直す** — 同じ名前と順番の見出しが無ければ null（飛べない）。
     */
    fun startBlockOf(ref: SectionRef): Int? {
        if (ref.title == null) return 0
        return headingIndex.positionOf(ref)?.let { headingBlockIndices[it] }
    }

    /** 見出しが1つでもあるか。名前の無い節を「冒頭」と呼ぶか「全体」と呼ぶかが変わる。 */
    val hasHeadings: Boolean get() = sections.isNotEmpty()

    /**
     * index 以下で最も近い見出しの番号（[sections] の添字）。見出し前／見出し無しは null。
     * **二分探索で引く** — スクロールのたび・見出しの印を描くたびに呼ばれるので、見出しの数に比例させない。
     */
    private fun headingAt(index: Int): Int? {
        val found = headingBlockIndices.binarySearch(index)
        val k = if (found >= 0) found else -found - 2
        return k.takeIf { it >= 0 }
    }

    /**
     * 前回いちばん先まで読んだところ（→ features/reunion_card.md 判断6「読み進めたところの求め方」）。
     * 引数は痕跡の最後の訪問の値。ブロックが無ければ null。
     *
     * 到達率は「最深ブロックの番号＋そのブロックの見えている割合」を総ブロック数で割った整数の百分率なので、
     * **100分の1ブロック単位で割り戻し、ブロック番号とブロック内の割合の両方を返す。**
     * 割合を捨てると、長い1ブロックの途中まで読んだ回と末尾まで読んだ回が区別できない。
     * 長いノートでは数ブロック単位でしか戻せないので、
     * **節が今の本文にあれば、その節の範囲へ収める** — 節の外を編集しても、読み進めた節の中には留まる。
     * 節の範囲は [sectionForBlockIndex] と同じく「その見出しから次の見出しの直前まで」で数える。
     * 痕跡の節の名前はその関数で記録されているので、別の区切り方をすると食い違う。
     *
     * ブロック番号の見積もりは**手前へ寄せる**。最深ブロックが画面に全部入った回
     * （割合1.0）は、素直に割り戻すと1つ先の未表示のブロックを指してしまう。
     */
    fun readFrontier(sectionTitle: String?, progressPercent: Int): ReadFrontier? {
        if (blocks.isEmpty()) return null
        val lastIndex = blocks.lastIndex
        val reachedHundredths = progressPercent.coerceIn(0, 100).toLong() * blocks.size
        val estimate = ((reachedHundredths + 99) / 100 - 1).toInt().coerceIn(0, lastIndex)
        val fraction = ((reachedHundredths - estimate * 100L) / 100f).coerceIn(0f, 1f)
        // **余白メモと同じ照合で節を引く**（→ [HeadingIndex.match]）。訪問の見出しは保存の前に整えてあるので、
        // 生の見出しと比べると、長い見出しや制御文字を含む見出しの節が見つからない。
        val candidates = when (val match = headingIndex.match(sectionTitle?.takeIf { it.isNotBlank() })) {
            is MemoSectionMatch.Unique -> listOf(match.section)
            is MemoSectionMatch.Shared -> match.sections
            MemoSectionMatch.Opening, MemoSectionMatch.Missing -> return ReadFrontier(estimate, fraction)
        }
        val nearest = candidates
            .mapNotNull { headingIndex.positionOf(it) }
            .map { k -> headingBlockIndices[k]..((headingBlockIndices.getOrNull(k + 1) ?: blocks.size) - 1) }
            // **同名の見出しが複数あれば、見積もりに最も近いもの。** 先頭固定にすると、
            // 後ろの「まとめ」を読んでいたのに前の「まとめ」へ戻される。
            .minByOrNull { range -> distanceTo(estimate, range) }
            ?: return ReadFrontier(estimate, fraction)
        return when {
            estimate < nearest.first -> ReadFrontier(nearest.first, 0f)
            estimate > nearest.last -> ReadFrontier(nearest.last, 1f)
            else -> ReadFrontier(estimate, fraction)
        }
    }

    /**
     * 読み進めたところを境に、前後を合わせて [targetLength] 文字まで切り出す。
     *
     * **読み進めたブロックは、読んだ割合で前後へ割る**（[splitRead]）。丸ごと読んだ側へ入れると、
     * 長い1ブロックの途中までしか読んでいないのに、未読の末尾を「ここまで読んだ」と渡してしまう。
     * 前後はおおむね半分ずつにし、**片側が短ければ余りをもう片側へ回す。**
     * 境目から遠い側を削るので、どれだけ長いブロックがあっても境目の近くは残る。
     */
    fun passageAround(frontier: ReadFrontier, targetLength: Int): ReunionPassage {
        if (blocks.isEmpty() || targetLength <= 0) return ReunionPassage(before = "", after = "")
        val index = frontier.block.coerceIn(0, blocks.lastIndex)
        val (head, tail) = splitRead(blocks[index], frontier.fraction)

        // 窓は各側 targetLength ぶんあれば足りる。本文全体を文字列にしない（最大1MB）。
        var start = index
        var beforeLength = head.length
        while (start > 0 && beforeLength < targetLength) {
            start--
            beforeLength += blockLength(start)
        }
        var end = index + 1
        var afterLength = tail.length
        while (end < blocks.size && afterLength < targetLength) {
            afterLength += blockLength(end)
            end++
        }

        val before = joinPassageParts(blocksToMarkdown(blocks.subList(start, index)), head)
        val after = joinPassageParts(tail, blocksToMarkdown(blocks.subList(index + 1, end)))
        val afterText = after.take(targetLength - minOf(before.length, targetLength / 2))
        val beforeText = before.takeLast(targetLength - afterText.length)
        return ReunionPassage(before = beforeText, after = afterText)
    }

    /**
     * 読み進めたブロックを、読んだ側と読んでいない側に割る。
     *
     * **近似である。** 見えていた高さの割合を文字数の割合へ読み替え、**文や行の区切りまで手前へ寄せる**
     * （読んでいない文を読んだ側へ入れない）。文字数と高さが比例しないブロックではずれる。
     * 画像・見出し・区切り線は割れないので、全部見えたときだけ読んだ側へ入れる。
     */
    private fun splitRead(block: MarkdownBlock, fraction: Float): Pair<String, String> {
        val text = blocksToMarkdown(listOf(block))
        if (fraction >= 1f) return text to ""
        val splittable = block is MarkdownBlock.Paragraph || block is MarkdownBlock.ListBlock ||
            block is MarkdownBlock.CodeBlock || block is MarkdownBlock.Blockquote || block is MarkdownBlock.Table
        if (!splittable || fraction <= 0f) return "" to text
        val cut = snapBackToBoundary(text, (text.length * fraction).toInt().coerceIn(0, text.length))
        return text.substring(0, cut).trimEnd() to text.substring(cut).trimStart()
    }

    private fun snapBackToBoundary(text: String, index: Int): Int {
        val floor = (index - SPLIT_SNAP_REACH).coerceAtLeast(0)
        for (i in index downTo floor + 1) {
            if (text[i - 1] in SPLIT_BOUNDARIES) return i
        }
        return index
    }

    private fun joinPassageParts(first: String, second: String): String =
        listOf(first, second).filter { it.isNotEmpty() }.joinToString("\n\n")

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
        /** 続きから読むで、節の見出しまで戻してよい距離（ブロック数）。 */
        const val RESUME_HEADING_REACH = 3

        /** 読み進めたブロックを割るとき、区切りを探して手前へ寄せる距離（文字数）。 */
        private const val SPLIT_SNAP_REACH = 120

        /** 割るときの区切り。行と文の終わり。 */
        private const val SPLIT_BOUNDARIES = "\n。！？!?"
    }
}

/**
 * 前回いちばん先まで読んだところ。[block] は本文のブロック番号、[fraction] はそのブロックの中で読んだ割合（0〜1）。
 * 割合は見えていた高さから来るので、文字数との対応は近似である。
 */
data class ReadFrontier(val block: Int, val fraction: Float)

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
