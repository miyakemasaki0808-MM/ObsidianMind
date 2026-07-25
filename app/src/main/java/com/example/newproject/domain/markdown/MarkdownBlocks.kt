package com.example.newproject.domain.markdown

import com.example.newproject.data.NoteRepository
// ---------------------------------------------------------------------------
// Markdownのブロック解析。Composeに依存しない純粋ロジックなので、UIではなく
// domain 側に置く。ViewModel・Controller は本文をセクションへ切るためにこれを使う
// （以前は ui.markdown にあり、ロジック層からUIパッケージを参照していた）。
// 装飾（AnnotatedString生成）は ui/markdown/InlineMarkdown.kt が持つ。
// ---------------------------------------------------------------------------

internal val HeadingRegex = Regex("^(#{1,6})\\s+(.+)$")
internal val UnorderedListRegex = Regex("^\\s*[-*+]\\s+(.+)$")
internal val OrderedListRegex = Regex("^\\s*\\d+[.)]\\s+(.+)$")
internal val HorizontalRuleRegex = Regex("^\\s*([-*_])\\s*(\\1\\s*){2,}$")
internal val BlockquoteRegex = Regex("^>\\s?(.*)")
internal val TaskListRegex = Regex("^\\s*[-*+]\\s+\\[([ xX])\\]\\s+(.+)$")
internal val TableRowRegex = Regex("^\\|(.+)\\|\\s*$")
internal val TableSeparatorRegex = Regex("^\\|[\\s|:-]+\\|\\s*$")

internal sealed class MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock()
    data class Paragraph(val text: String) : MarkdownBlock()
    data class ListBlock(val items: List<String>) : MarkdownBlock()
    data class CodeBlock(val code: String) : MarkdownBlock()
    object HorizontalRule : MarkdownBlock()
    data class Blockquote(val lines: List<String>) : MarkdownBlock()
    data class TaskListBlock(val items: List<Pair<Boolean, String>>) : MarkdownBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MarkdownBlock()
}

internal fun parseMarkdownBlocks(content: String): List<MarkdownBlock> {
    val lines = content.replace("\r\n", "\n").lines().stripFrontmatter()
    val blocks = mutableListOf<MarkdownBlock>()
    var index = 0

    while (index < lines.size) {
        val line = lines[index]

        if (line.isBlank()) { index++; continue }

        // コードブロック
        if (line.trimStart().startsWith("```")) {
            val codeLines = mutableListOf<String>()
            index++
            while (index < lines.size && !lines[index].trimStart().startsWith("```")) {
                codeLines.add(lines[index])
                index++
            }
            if (index < lines.size) index++
            blocks.add(MarkdownBlock.CodeBlock(codeLines.joinToString("\n")))
            continue
        }

        // テーブル
        if (TableRowRegex.matches(line)) {
            val tableLines = mutableListOf<String>()
            while (index < lines.size && TableRowRegex.matches(lines[index])) {
                tableLines.add(lines[index])
                index++
            }
            val headers = tableLines.firstOrNull()?.let(::splitTableRow) ?: emptyList()
            val rows = tableLines.drop(1)
                .filter { !TableSeparatorRegex.matches(it) }
                .map(::splitTableRow)
            blocks.add(MarkdownBlock.Table(headers, rows))
            continue
        }

        // 水平線（見出しより先にチェック）
        if (HorizontalRuleRegex.matches(line)) {
            blocks.add(MarkdownBlock.HorizontalRule)
            index++
            continue
        }

        // 見出し
        val headingMatch = HeadingRegex.matchEntire(line)
        if (headingMatch != null) {
            blocks.add(MarkdownBlock.Heading(headingMatch.groupValues[1].length, headingMatch.groupValues[2]))
            index++
            continue
        }

        // 引用ブロック
        if (BlockquoteRegex.matches(line)) {
            val quoteLines = mutableListOf<String>()
            while (index < lines.size && BlockquoteRegex.matches(lines[index])) {
                quoteLines.add(BlockquoteRegex.matchEntire(lines[index])!!.groupValues[1])
                index++
            }
            blocks.add(MarkdownBlock.Blockquote(quoteLines))
            continue
        }

        // タスクリスト（通常リストより先にチェック）
        if (TaskListRegex.matches(line)) {
            val items = mutableListOf<Pair<Boolean, String>>()
            while (index < lines.size && TaskListRegex.matches(lines[index])) {
                val m = TaskListRegex.matchEntire(lines[index])!!
                items.add((m.groupValues[1].lowercase() == "x") to m.groupValues[2])
                index++
            }
            blocks.add(MarkdownBlock.TaskListBlock(items))
            continue
        }

        // 通常リスト
        val unorderedMatch = UnorderedListRegex.matchEntire(line)
        val orderedMatch = OrderedListRegex.matchEntire(line)
        if (unorderedMatch != null || orderedMatch != null) {
            val items = mutableListOf<String>()
            while (index < lines.size) {
                val current = lines[index]
                if (TaskListRegex.matches(current)) break
                val item = UnorderedListRegex.matchEntire(current)?.groupValues?.get(1)
                    ?: OrderedListRegex.matchEntire(current)?.groupValues?.get(1)
                    ?: break
                items.add(item)
                index++
            }
            blocks.add(MarkdownBlock.ListBlock(items))
            continue
        }

        // 段落
        val paragraphLines = mutableListOf(line.trim())
        index++
        while (index < lines.size) {
            val current = lines[index]
            if (
                current.isBlank() ||
                current.trimStart().startsWith("```") ||
                TableRowRegex.matches(current) ||
                HorizontalRuleRegex.matches(current) ||
                HeadingRegex.matches(current) ||
                BlockquoteRegex.matches(current) ||
                TaskListRegex.matches(current) ||
                UnorderedListRegex.matches(current) ||
                OrderedListRegex.matches(current)
            ) break
            paragraphLines.add(current.trim())
            index++
        }
        blocks.add(MarkdownBlock.Paragraph(paragraphLines.joinToString(" ")))
    }

    return blocks
}

/**
 * テーブル行をセルに分割する。先頭・末尾の | の外側だけを捨て、
 * 中間の空セルは列位置を保つため保持する（"| a |  | c |" → ["a", "", "c"]）。
 * 以前は isNotBlank フィルタで中間の空セルまで捨てられ、列がズレていた。
 */
private fun splitTableRow(line: String): List<String> =
    line.trimEnd().split("|").drop(1).dropLast(1).map { it.trim() }

/**
 * YAML frontmatter（先頭の --- ～ --- ブロック）を描画対象から除外する。
 * 判定は NoteRepository.parseMeta と同じ（先頭行が --- で、次の --- までをメタデータとみなす）。
 * 閉じ --- が無い場合は frontmatter とみなさず全行を返す。
 */
private fun List<String>.stripFrontmatter(): List<String> {
    if (firstOrNull()?.trim() != "---") return this
    val endIndex = drop(1).indexOfFirst { it.trim() == "---" }
    return if (endIndex >= 0) drop(endIndex + 2) else this
}
