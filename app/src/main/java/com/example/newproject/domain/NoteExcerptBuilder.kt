package com.example.newproject.domain

import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.domain.markdown.blocksToMarkdown
import com.example.newproject.domain.markdown.parseMarkdownBlocks
import com.example.newproject.model.NoteExcerpt
import com.example.newproject.model.NoteExcerptLimits

private const val OUTLINE_LABEL = "## Note outline"
private const val BEGINNING_LABEL = "## Beginning excerpt"
private const val ENDING_LABEL = "## Ending excerpt"
private const val OMITTED_MARKER = "(omitted)"
private const val ELLIPSIS = "…"
private const val BLOCK_SEPARATOR = "\n\n"
private const val CODE_PREFIX = "```\n"
private const val CODE_SUFFIX = "\n```"
private const val MIN_OUTLINE_HEADING_CHARS = 16

/**
 * AI入力用の本文を用途別予算へ収める。
 *
 * 予算内ならパースせず原文をそのまま返す。超過時だけMarkdownブロックへ正規化し、
 * level 3までの見出し骨格・冒頭・末尾を明示ラベル付きで構成する。骨格と本文側に
 * 同じ見出しが現れる重複は意図的に許容する。骨格は全体の地図、本文は実際の文脈であり、
 * 地図から見出しを欠落させる方が害が大きいためである。
 *
 * 超過時は既存Markdownパーサの仕様により、frontmatter除去、ordered listの箇条書き化、
 * コードフェンス言語名の除去、段落内改行の空白化が起きる。短いノートとの非対称は、
 * 超過した入力だけを圧縮する仕様として受け入れる。
 *
 * 戻り値は `text.length + ABRIDGED_NOTICE.length <= budget` を満たす。注意書き自体を
 * 収められない予算で長文を切ることはできないため、その場合は引数を拒否する。
 */
fun buildNoteExcerpt(content: String, budget: Int): NoteExcerpt {
    require(budget >= 0) { "budget must be non-negative" }
    if (content.length <= budget) return NoteExcerpt(content, isAbridged = false)
    require(budget >= NoteExcerptLimits.ABRIDGED_NOTICE.length) {
        "budget must fit the abridged notice"
    }

    val textBudget = budget - NoteExcerptLimits.ABRIDGED_NOTICE.length
    val blocks = parseMarkdownBlocks(content)
    if (blocks.isEmpty() || textBudget == 0) {
        return NoteExcerpt(
            text = truncatePlain(OMITTED_MARKER, textBudget, fromStart = true),
            isAbridged = true
        )
    }

    val headingLines = blocks.mapNotNull { block ->
        (block as? MarkdownBlock.Heading)
            ?.takeIf { it.level <= 3 }
            ?.let { "#".repeat(it.level) + " " + it.text }
    }
    val canIncludeOutline = headingLines.isNotEmpty() &&
        fixedLayoutCost(includeOutline = true) <= textBudget
    val fixedCost = fixedLayoutCost(includeOutline = canIncludeOutline)

    // 固定ラベルすら全て置けない極小枠では、末尾を示す最小マーカーだけを返す。
    if (fixedCost > textBudget) {
        return NoteExcerpt(
            text = truncatePlain(OMITTED_MARKER, textBudget, fromStart = true),
            isAbridged = true
        )
    }

    val contentBudget = textBudget - fixedCost
    val outlineCap = if (canIncludeOutline) contentBudget / 4 else 0
    val outline = if (canIncludeOutline) buildOutline(headingLines, outlineCap) else ""
    // 骨格が上限未満で済んだ分は、冒頭・末尾へ返す。
    val bodyBudget = contentBudget - outline.length
    val beginningCap = bodyBudget * 60 / 100
    val endingCap = bodyBudget - beginningCap

    val beginning = selectBeginning(blocks, beginningCap)
    val ending = selectEnding(blocks, endingCap, beginning)
    val allBlocksCovered = beginning.partialIndex == null &&
        ending.partialIndex == null &&
        (beginning.wholeIndices + ending.wholeIndices).size == blocks.size

    val text = if (allBlocksCovered) {
        renderContinuousLayout(
            outline = outline.takeIf { it.isNotEmpty() },
            body = blocksToMarkdown(blocks)
        )
    } else {
        renderAbridgedLayout(
            outline = outline.takeIf { it.isNotEmpty() },
            beginning = beginning.text.takeIf { it.isNotEmpty() },
            ending = ending.text.takeIf { it.isNotEmpty() }
        )
    }
    check(text.length <= textBudget) {
        "excerpt layout exceeded its reserved text budget: ${text.length} > $textBudget"
    }
    return NoteExcerpt(text, isAbridged = true)
}

/** ラベル間の改行も含め、可変本文を1文字ずつ置いたときの固定コストを求める。 */
private fun fixedLayoutCost(includeOutline: Boolean): Int {
    val placeholder = "x"
    val rendered = renderAbridgedLayout(
        outline = placeholder.takeIf { includeOutline },
        beginning = placeholder,
        ending = placeholder
    )
    val variableCharacters = if (includeOutline) 3 else 2
    return rendered.length - variableCharacters
}

private fun buildOutline(lines: List<String>, budget: Int): String {
    if (budget <= 0 || lines.isEmpty()) return ""
    val all = lines.joinToString("\n")
    if (all.length <= budget) return all
    if (lines.size == 1) return truncateLine(lines.single(), budget)

    val selectedCount = (lines.size downTo 2).firstOrNull { count ->
        val indices = evenlySpacedIndices(lines.size, count)
        minimumOutlineLength(lines, indices) <= budget
    } ?: return truncateLine(ELLIPSIS, budget)

    val indices = evenlySpacedIndices(lines.size, selectedCount)
    val components = mutableListOf<OutlineComponent>()
    indices.forEachIndexed { position, index ->
        if (position > 0 && index > indices[position - 1] + 1) {
            components += OutlineComponent(ELLIPSIS, isHeading = false)
        }
        components += OutlineComponent(lines[index], isHeading = true)
    }

    val separatorCost = (components.size - 1).coerceAtLeast(0)
    val gapCost = components.filterNot { it.isHeading }.sumOf { it.text.length }
    var headingBudget = (budget - separatorCost - gapCost).coerceAtLeast(0)
    var headingsRemaining = selectedCount
    val rendered = components.map { component ->
        if (!component.isHeading) {
            component.text
        } else {
            val share = if (headingsRemaining == 0) 0 else headingBudget / headingsRemaining
            val line = truncateLine(component.text, share)
            headingBudget -= line.length
            headingsRemaining--
            line
        }
    }
    val result = rendered.joinToString("\n")
    check(result.length <= budget)
    return result
}

private fun evenlySpacedIndices(size: Int, count: Int): List<Int> {
    require(size >= 1)
    require(count in 1..size)
    if (count == 1) return listOf(size - 1)
    return (0 until count).map { slot ->
        ((slot.toLong() * (size - 1)) / (count - 1)).toInt()
    }.distinct()
}

private fun minimumOutlineLength(lines: List<String>, indices: List<Int>): Int {
    var componentCount = indices.size
    var gapCount = 0
    for (index in 1 until indices.size) {
        if (indices[index] > indices[index - 1] + 1) {
            componentCount++
            gapCount++
        }
    }
    val headingCharacters = indices.sumOf { minOf(lines[it].length, MIN_OUTLINE_HEADING_CHARS) }
    val gapCharacters = gapCount * ELLIPSIS.length
    val newlines = (componentCount - 1).coerceAtLeast(0)
    return headingCharacters + gapCharacters + newlines
}

private data class OutlineComponent(val text: String, val isHeading: Boolean)

private data class IndexedBlockPart(
    val index: Int,
    val text: String,
    val isWhole: Boolean
)

private data class BodySelection(
    val parts: List<IndexedBlockPart>,
    val partialIndex: Int?
) {
    val text: String = parts.joinToString(BLOCK_SEPARATOR) { it.text }
    val wholeIndices: Set<Int> = parts.filter { it.isWhole }.mapTo(mutableSetOf()) { it.index }
}

private fun selectBeginning(blocks: List<MarkdownBlock>, budget: Int): BodySelection {
    if (budget <= 0) return BodySelection(emptyList(), partialIndex = null)
    val parts = mutableListOf<IndexedBlockPart>()
    var used = 0

    for (index in blocks.indices) {
        val separator = if (parts.isEmpty()) 0 else BLOCK_SEPARATOR.length
        val available = budget - used - separator
        if (available <= 0) break
        val rendered = blocksToMarkdown(listOf(blocks[index]))
        if (rendered.length <= available) {
            parts += IndexedBlockPart(index, rendered, isWhole = true)
            used += separator + rendered.length
        } else {
            val fragment = truncateBlock(blocks[index], available, fromStart = true)
            if (fragment.isNotEmpty()) {
                parts += IndexedBlockPart(index, fragment, isWhole = false)
            }
            return BodySelection(parts, partialIndex = index)
        }
    }
    return BodySelection(parts, partialIndex = null)
}

private fun selectEnding(
    blocks: List<MarkdownBlock>,
    budget: Int,
    beginning: BodySelection
): BodySelection {
    if (budget <= 0) return BodySelection(emptyList(), partialIndex = null)
    val partsReversed = mutableListOf<IndexedBlockPart>()
    val firstUnconsumed = beginning.partialIndex
        ?: ((beginning.wholeIndices.maxOrNull() ?: -1) + 1)
    var used = 0

    for (index in blocks.lastIndex downTo firstUnconsumed) {
        val separator = if (partsReversed.isEmpty()) 0 else BLOCK_SEPARATOR.length
        val available = budget - used - separator
        if (available <= 0) break
        val block = blocks[index]
        val rendered = blocksToMarkdown(listOf(block))
        val overlapsBeginningFragment = index == beginning.partialIndex
        if (rendered.length <= available && !overlapsBeginningFragment) {
            partsReversed += IndexedBlockPart(index, rendered, isWhole = true)
            used += separator + rendered.length
        } else {
            val beginningLength = beginning.parts.firstOrNull { it.index == index }?.text?.length ?: 0
            val nonOverlappingCap = if (overlapsBeginningFragment) {
                (rendered.length - beginningLength).coerceAtLeast(0)
            } else {
                available
            }
            val fragment = truncateBlock(
                block = block,
                budget = minOf(available, nonOverlappingCap),
                fromStart = false
            )
            if (fragment.isNotEmpty()) {
                partsReversed += IndexedBlockPart(index, fragment, isWhole = false)
            }
            return BodySelection(partsReversed.asReversed(), partialIndex = index)
        }
    }
    return BodySelection(partsReversed.asReversed(), partialIndex = null)
}

private fun truncateBlock(block: MarkdownBlock, budget: Int, fromStart: Boolean): String {
    if (budget <= 0) return ""
    if (block !is MarkdownBlock.CodeBlock) {
        return truncatePlain(blocksToMarkdown(listOf(block)), budget, fromStart)
    }

    val fenceCost = CODE_PREFIX.length + CODE_SUFFIX.length
    if (budget <= fenceCost) return ""
    val codeBudget = budget - fenceCost
    val code = if (block.code.length <= codeBudget) {
        block.code
    } else {
        truncatePlain(block.code, codeBudget, fromStart)
    }
    return CODE_PREFIX + code + CODE_SUFFIX
}

private fun truncateLine(line: String, budget: Int): String =
    truncatePlain(line, budget, fromStart = true)

private fun truncatePlain(value: String, budget: Int, fromStart: Boolean): String {
    if (budget <= 0) return ""
    if (value.length <= budget) return value
    if (budget == ELLIPSIS.length) return ELLIPSIS
    val contentBudget = budget - ELLIPSIS.length
    return if (fromStart) {
        safeTake(value, contentBudget).trimEnd() + ELLIPSIS
    } else {
        ELLIPSIS + safeTakeLast(value, contentBudget).trimStart()
    }
}

private fun safeTake(value: String, count: Int): String {
    var end = minOf(count, value.length)
    if (end in 1 until value.length &&
        Character.isHighSurrogate(value[end - 1]) &&
        Character.isLowSurrogate(value[end])
    ) {
        end--
    }
    return value.substring(0, end)
}

private fun safeTakeLast(value: String, count: Int): String {
    var start = (value.length - count).coerceAtLeast(0)
    if (start in 1 until value.length &&
        Character.isLowSurrogate(value[start]) &&
        Character.isHighSurrogate(value[start - 1])
    ) {
        start++
    }
    return value.substring(start)
}

private fun renderContinuousLayout(outline: String?, body: String): String =
    buildList {
        outline?.let { add("$OUTLINE_LABEL\n$it") }
        if (body.isNotEmpty()) add("$BEGINNING_LABEL\n$body")
    }.joinToString(BLOCK_SEPARATOR)

private fun renderAbridgedLayout(
    outline: String?,
    beginning: String?,
    ending: String?
): String = buildList {
    outline?.let { add("$OUTLINE_LABEL\n$it") }
    beginning?.let { add("$BEGINNING_LABEL\n$it") }
    add(OMITTED_MARKER)
    ending?.let { add("$ENDING_LABEL\n$it") }
}.joinToString(BLOCK_SEPARATOR)
