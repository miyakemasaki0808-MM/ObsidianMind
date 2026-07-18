package com.example.newproject.ui.markdown

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import com.example.newproject.ui.theme.CodePanel
import com.example.newproject.ui.theme.LinkBlue

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
            val headers = tableLines.firstOrNull()
                ?.split("|")?.filter { it.isNotBlank() } ?: emptyList()
            val rows = tableLines.drop(1)
                .filter { !TableSeparatorRegex.matches(it) }
                .map { row -> row.split("|").filter { it.isNotBlank() } }
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
 * YAML frontmatter（先頭の --- ～ --- ブロック）を描画対象から除外する。
 * 判定は NoteRepository.parseMeta と同じ（先頭行が --- で、次の --- までをメタデータとみなす）。
 * 閉じ --- が無い場合は frontmatter とみなさず全行を返す。
 */
private fun List<String>.stripFrontmatter(): List<String> {
    if (firstOrNull()?.trim() != "---") return this
    val endIndex = drop(1).indexOfFirst { it.trim() == "---" }
    return if (endIndex >= 0) drop(endIndex + 2) else this
}

internal fun inlineMarkdown(text: String) = buildAnnotatedString {
    var index = 0

    while (index < text.length) {
        when {
            // 太字イタリック ***text*** (** より先にチェック)
            text.startsWith("***", index) -> {
                val end = text.indexOf("***", startIndex = index + 3)
                if (end != -1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)) {
                        append(text.substring(index + 3, end))
                    }
                    index = end + 3
                } else { append(text[index]); index++ }
            }
            // 太字 **text**
            text.startsWith("**", index) -> {
                val end = text.indexOf("**", startIndex = index + 2)
                if (end != -1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(index + 2, end))
                    }
                    index = end + 2
                } else { append(text[index]); index++ }
            }
            // イタリック *text*
            text[index] == '*' -> {
                val end = text.indexOf('*', startIndex = index + 1)
                if (end != -1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text.substring(index + 1, end))
                    }
                    index = end + 1
                } else { append(text[index]); index++ }
            }
            // 打ち消し線 ~~text~~
            text.startsWith("~~", index) -> {
                val end = text.indexOf("~~", startIndex = index + 2)
                if (end != -1) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = Color(0xFF888888))) {
                        append(text.substring(index + 2, end))
                    }
                    index = end + 2
                } else { append(text[index]); index++ }
            }
            // インラインコード `code`
            text[index] == '`' -> {
                val end = text.indexOf('`', startIndex = index + 1)
                if (end != -1) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = CodePanel)) {
                        append(text.substring(index + 1, end))
                    }
                    index = end + 1
                } else { append(text[index]); index++ }
            }
            // Obsidianリンク [[note]]
            text.startsWith("[[", index) -> {
                val end = text.indexOf("]]", startIndex = index + 2)
                if (end != -1) {
                    val linkText = text.substring(index + 2, end).split("|").last()
                    withStyle(SpanStyle(color = LinkBlue, textDecoration = TextDecoration.Underline)) {
                        append(linkText)
                    }
                    index = end + 2
                } else { append(text[index]); index++ }
            }
            // 通常リンク [label](url)
            text[index] == '[' -> {
                val closeLabel = text.indexOf("](", startIndex = index)
                val closeUrl = if (closeLabel != -1) text.indexOf(')', startIndex = closeLabel + 2) else -1
                if (closeLabel != -1 && closeUrl != -1) {
                    withStyle(SpanStyle(color = LinkBlue, textDecoration = TextDecoration.Underline)) {
                        append(text.substring(index + 1, closeLabel))
                    }
                    index = closeUrl + 1
                } else { append(text[index]); index++ }
            }
            else -> { append(text[index]); index++ }
        }
    }
}
