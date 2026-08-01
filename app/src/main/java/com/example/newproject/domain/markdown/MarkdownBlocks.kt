package com.example.newproject.domain.markdown

// ---------------------------------------------------------------------------
// Markdownのブロック解析。Composeに依存しない純粋ロジックなので、UIではなく
// domain 側に置く。ViewModel・Controller は本文をセクションへ切るためにこれを使う
// （以前は ui.markdown にあり、ロジック層からUIパッケージを参照していた）。
// 装飾（AnnotatedString生成）は ui/markdown/InlineMarkdown.kt が持つ。
// ---------------------------------------------------------------------------

internal val HeadingRegex = Regex("^(#{1,6})\\s+(.+)$")
internal val UnorderedListRegex = Regex("^(\\s*)([-*+])\\s+(.+)$")
internal val OrderedListRegex = Regex("^(\\s*)(\\d+)([.)])\\s+(.+)$")
internal val HorizontalRuleRegex = Regex("^\\s*([-*_])\\s*(\\1\\s*){2,}$")
internal val BlockquoteRegex = Regex("^>\\s?(.*)")
internal val TaskListRegex = Regex("^\\s*[-*+]\\s+\\[([ xX])\\]\\s+(.+)$")
internal val TableRowRegex = Regex("^\\|(.+)\\|\\s*$")
internal val TableSeparatorRegex = Regex("^\\|[\\s|:-]+\\|\\s*$")

/**
 * リスト項目の行頭マーカー。
 *
 * **番号付きだけ原文を保持し、箇条書きは正規化する**（非対称は意図的）。
 * `-` / `*` / `+` の違いは意味を持たず、描画は常に `•`、書き戻しは `-` で足りる。
 * むしろ `*` を復元すると強調記号と紛らわしい。一方で番号は意味を持つため、
 * `1.` と `1)`、`01.` の先頭ゼロ、桁数を落とさずに持つ。
 * [number] を [Int] にすると区切り記号と先頭ゼロを失い、桁溢れもあり得るので [String] で持つ。
 */
internal sealed interface ListMarker {
    object Bullet : ListMarker
    data class Ordered(val number: String, val delimiter: Char) : ListMarker
}

/** [depth] は0起点の入れ子段数。算出規則は [ListDepthTracker]。 */
internal data class ListItem(
    val depth: Int,
    val marker: ListMarker,
    val text: String
)

internal sealed class MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock()
    data class Paragraph(val text: String) : MarkdownBlock()
    data class ListBlock(val items: List<ListItem>) : MarkdownBlock()
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
        if (UnorderedListRegex.matches(line) || OrderedListRegex.matches(line)) {
            val items = mutableListOf<ListItem>()
            // 段数はブロック単位で追跡する。別のリストへ跨いで幅を覚えない。
            val depths = ListDepthTracker()
            while (index < lines.size) {
                val current = lines[index]
                if (TaskListRegex.matches(current)) break
                val item = parseListItem(current, depths) ?: break
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

private fun parseListItem(line: String, depths: ListDepthTracker): ListItem? {
    OrderedListRegex.matchEntire(line)?.let { m ->
        return ListItem(
            depth = depths.depthFor(indentWidth(m.groupValues[1])),
            marker = ListMarker.Ordered(m.groupValues[2], m.groupValues[3][0]),
            text = m.groupValues[4]
        )
    }
    val unordered = UnorderedListRegex.matchEntire(line) ?: return null
    return ListItem(
        depth = depths.depthFor(indentWidth(unordered.groupValues[1])),
        marker = ListMarker.Bullet,
        text = unordered.groupValues[3]
    )
}

/**
 * 行頭インデントの列幅。**タブは4文字置換ではなく次の4列境界まで展開する**
 * （`"  \t"` は 2 → 4 であって 2+4=6 ではない）。
 */
private fun indentWidth(indent: String): Int {
    var width = 0
    for (ch in indent) {
        width = if (ch == '\t') (width / TAB_STOP + 1) * TAB_STOP else width + 1
    }
    return width
}

/**
 * インデント列幅から入れ子段数を決める。**CommonMark にも Obsidian にも準拠しない、
 * 意図的に寛容な規則**である（規格は親のマーカー直後の列位置で入れ子を定義するが、
 * ここでは幅の絶対値を見ず相対的な深浅だけで判定する）。理由は
 * [markdown_rendering](../../../../../../../../docs/design/markdown_rendering.md) にある。
 *
 * 規則は5つ。
 * 1. 同じ幅は同じ段数
 * 2. 直前より深ければ、幅の差に関係なく段数 +1
 * 3. 既知の祖先幅へ戻れば、その段数
 * 4. 未知の浅い幅は「直近の浅い祖先 +1」へ正規化する
 * 5. ある枝を抜けたら、その枝の幅は忘れる（別の枝で同じ幅が違う段数になり得る）
 */
private class ListDepthTracker {
    /** index が段数、値がその段のインデント列幅。単調増加を保つ。 */
    private val widths = mutableListOf<Int>()

    fun depthFor(width: Int): Int {
        // 規則5: 現在幅より深い枝は忘れる
        while (widths.isNotEmpty() && widths.last() > width) {
            widths.removeAt(widths.lastIndex)
        }
        // 規則3で祖先幅と一致した場合はここで確定する
        if (widths.isNotEmpty() && widths.last() == width) return widths.lastIndex
        // 規則2（深くなった）と規則4（未知の浅い幅）は、どちらも「今の親 +1」に落ちる
        widths.add(width)
        return widths.lastIndex
    }
}

private const val TAB_STOP = 4

/**
 * テーブル行をセルに分割する。先頭・末尾の | の外側だけを捨て、
 * 中間の空セルは列位置を保つため保持する（"| a |  | c |" → ["a", "", "c"]）。
 * 以前は isNotBlank フィルタで中間の空セルまで捨てられ、列がズレていた。
 */
private fun splitTableRow(line: String): List<String> =
    line.trimEnd().split("|").drop(1).dropLast(1).map { it.trim() }

/**
 * YAML frontmatter（先頭の --- ～ --- ブロック）を描画対象から除外する。
 * 判定は `NoteRepository.parseMeta` と同じ（先頭行が --- で、次の --- までをメタデータとみなす）。
 * 閉じ --- が無い場合は frontmatter とみなさず全行を返す。
 */
private fun List<String>.stripFrontmatter(): List<String> {
    if (firstOrNull()?.trim() != "---") return this
    val endIndex = drop(1).indexOfFirst { it.trim() == "---" }
    return if (endIndex >= 0) drop(endIndex + 2) else this
}
