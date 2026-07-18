package com.example.newproject.ui.markdown

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
    // 描画側（MarkdownNoteContent）で再パースせず使い回すためのパース済みブロック列
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

/** パース済みブロック列を LLM 入力用の Markdown 文字列に再構成する。 */
internal fun blocksToMarkdown(blocks: List<MarkdownBlock>): String =
    blocks.joinToString("\n\n") { block ->
        when (block) {
            is MarkdownBlock.Heading -> "#".repeat(block.level) + " " + block.text
            is MarkdownBlock.Paragraph -> block.text
            is MarkdownBlock.ListBlock -> block.items.joinToString("\n") { "- $it" }
            is MarkdownBlock.CodeBlock -> "```\n" + block.code + "\n```"
            is MarkdownBlock.HorizontalRule -> "---"
            is MarkdownBlock.Blockquote -> block.lines.joinToString("\n") { "> $it" }
            is MarkdownBlock.TaskListBlock -> block.items.joinToString("\n") { (checked, t) ->
                "- [${if (checked) "x" else " "}] $t"
            }
            is MarkdownBlock.Table -> buildString {
                append("| ").append(block.headers.joinToString(" | ")).append(" |\n")
                append("|").append(block.headers.joinToString("|") { "---" }).append("|\n")
                block.rows.forEach { row ->
                    append("| ").append(row.joinToString(" | ")).append(" |\n")
                }
            }.trimEnd()
        }
    }
