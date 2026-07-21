package com.example.newproject.domain

/** 蒸留対象の原文範囲。offset は Kotlin String と同じ UTF-16 code unit 基準。 */
internal data class DistillTextRange(
    val start: Int,
    val endExclusive: Int
) {
    init {
        require(start >= 0)
        require(endExclusive >= start)
    }

    val length: Int get() = endExclusive - start

    fun overlaps(other: DistillTextRange): Boolean =
        start < other.endExclusive && other.start < endExclusive

    fun contains(offset: Int): Boolean = offset > start && offset < endExclusive
}

/** AI候補になる前の、原文位置を保持した1文。 */
internal data class DistillSentence(
    val sourceIndex: Int,
    val text: String,
    val range: DistillTextRange,
    val heading: String?,
    val chunkIndex: Int,
    val isParagraphFirst: Boolean,
    val isHeadingAdjacent: Boolean,
    val isChunkLast: Boolean,
    val isNoteLast: Boolean
)

internal data class DistillChunk(
    val index: Int,
    val heading: String?,
    val sentenceIndices: List<Int>
)

internal data class DistillSourceModel(
    val content: String,
    val sentences: List<DistillSentence>,
    val chunks: List<DistillChunk>,
    val eligibleBodyCharacterCount: Int,
    val existingBoldCharacterCount: Int
)

private data class SourceLine(
    val index: Int,
    val start: Int,
    val contentEnd: Int,
    val endWithBreak: Int,
    val text: String
)

private data class InlineSyntax(
    val codeSpans: List<DistillTextRange>,
    val strongSpans: List<DistillTextRange>,
    val linkSpans: List<DistillTextRange>
) {
    val protectedForSentenceBreaks: List<DistillTextRange>
        get() = codeSpans + linkSpans
}

private data class SentenceDraft(
    val text: String,
    val range: DistillTextRange,
    val heading: String?,
    val headingRegion: Int,
    val regionStart: Int,
    val paragraphIndex: Int
)

private val HEADING_PATTERN = Regex("^(#{1,6})\\s+(.+)$")
private val SETEXT_HEADING_PATTERN = Regex("^\\s{0,3}(=+|-+)\\s*$")
private val ORDERED_LIST_PREFIX = Regex("^\\s*\\d+[.)]\\s+")
private val UNORDERED_LIST_PREFIX = Regex("^\\s*[-+*]\\s+")
private val TASK_LIST_PREFIX = Regex("^\\s*[-+*]\\s+\\[[ xX]]\\s+")
private val THEMATIC_BREAK_PATTERN = Regex("^\\s{0,3}((\\*\\s*){3,}|(-\\s*){3,}|(_\\s*){3,})$")
private val TABLE_SEPARATOR_CELL_PATTERN = Regex(":?-{3,}:?")
private val ENGLISH_ABBREVIATIONS = setOf(
    "e.g", "i.e", "etc", "mr", "mrs", "ms", "dr", "prof", "vs", "fig", "no"
)

/**
 * 原文を変更せず、編集可能な本文文だけを UTF-16 offset 付きで抽出する。
 * 表示用 Markdown parser は空白・改行を再構成するため、意図的に共用しない。
 */
internal fun buildDistillSourceModel(
    content: String,
    chunkCharacterLimit: Int = 1_200
): DistillSourceModel {
    require(chunkCharacterLimit > 0)
    val lines = sourceLines(content)
    if (lines.isEmpty()) return DistillSourceModel(content, emptyList(), emptyList(), 0, 0)

    val frontmatterLines = frontmatterLineIndices(lines)
    val fencedCodeLines = fencedCodeLineIndices(lines)
    val tableLines = tableLineIndices(lines)
    val baseExcludedLines = frontmatterLines + fencedCodeLines + tableLines
    val setextHeadings = setextHeadingsByLine(lines, baseExcludedLines)
    val setextLines = setextHeadings.keys.flatMapTo(mutableSetOf()) { titleLine ->
        listOf(titleLine, titleLine + 1)
    }
    val thematicBreakLines = lines
        .filter { it.index !in setextLines && THEMATIC_BREAK_PATTERN.matches(it.text) }
        .mapTo(mutableSetOf()) { it.index }
    val excludedLines = baseExcludedLines + setextLines + thematicBreakLines
    val strongSpans = strongSpansAcrossLines(content, lines, excludedLines)

    val drafts = mutableListOf<SentenceDraft>()
    var currentHeading: String? = null
    var headingRegion = 0
    var regionStart = 0
    var paragraphIndex = 0
    var paragraphOpen = false
    var eligibleCharacters = 0
    var boldCharacters = 0

    for (line in lines) {
        val setextHeading = setextHeadings[line.index]
        if (setextHeading != null) {
            currentHeading = setextHeading
            headingRegion++
            regionStart = lines.getOrNull(line.index + 1)?.endWithBreak ?: line.endWithBreak
            paragraphOpen = false
            continue
        }
        val detectionText = line.text.removePrefix("\uFEFF")
        val headingMatch = HEADING_PATTERN.matchEntire(detectionText)
        if (headingMatch != null && line.index !in excludedLines) {
            currentHeading = headingMatch.groupValues[2].trim()
            headingRegion++
            regionStart = line.endWithBreak
            paragraphOpen = false
            continue
        }

        if (line.index in excludedLines || line.text.isBlank()) {
            paragraphOpen = false
            continue
        }

        val bodyStartInLine = contentStartWithinLine(line.text)
        if (bodyStartInLine >= line.text.length) {
            paragraphOpen = false
            continue
        }

        val bodyStart = line.start + bodyStartInLine
        val bodyEnd = line.contentEnd
        // 分母と同じ編集対象本文だけで既存太字を数える。見出し等の対象外領域にある
        // **strong** が本文の蒸留枠を消費しないよう、行内の内側文字だけを加算する。
        boldCharacters += strongSpans.sumOf { strong ->
            val innerStart = (strong.start + 2).coerceAtLeast(bodyStart)
            val innerEnd = (strong.endExclusive - 2).coerceAtMost(bodyEnd)
            if (innerStart >= innerEnd) 0 else {
                (innerStart until innerEnd).count { !content[it].isWhitespace() }
            }
        }
        val syntax = parseInlineSyntax(
            content = content,
            start = bodyStart,
            end = bodyEnd,
            strongSpans = strongSpans.filter { it.overlaps(DistillTextRange(bodyStart, bodyEnd)) }
        )
        eligibleCharacters += (bodyStart until bodyEnd).count { !content[it].isWhitespace() }

        if (!paragraphOpen || isListOrQuote(line.text)) paragraphIndex++
        val thisParagraph = paragraphIndex
        paragraphOpen = !isListOrQuote(line.text)

        subtractRanges(bodyStart, bodyEnd, syntax.strongSpans).forEach { editableSegment ->
            splitLineIntoSentences(
                content = content,
                start = editableSegment.start,
                end = editableSegment.endExclusive,
                syntax = syntax
            ).forEach { range ->
                if ((syntax.codeSpans + syntax.linkSpans).any { span ->
                        span.contains(range.start) || span.contains(range.endExclusive)
                    }
                ) return@forEach
                val text = content.substring(range.start, range.endExclusive)
                if (text.isBlank()) return@forEach
                drafts += SentenceDraft(
                    text = text,
                    range = range,
                    heading = currentHeading,
                    headingRegion = headingRegion,
                    regionStart = regionStart,
                    paragraphIndex = thisParagraph
                )
            }
        }
    }

    if (drafts.isEmpty()) {
        return DistillSourceModel(content, emptyList(), emptyList(), eligibleCharacters, boldCharacters)
    }

    val chunkKeys = drafts.map { draft ->
        draft.headingRegion to ((draft.range.start - draft.regionStart).coerceAtLeast(0) / chunkCharacterLimit)
    }
    val keyToChunk = LinkedHashMap<Pair<Int, Int>, Int>()
    chunkKeys.forEach { key -> keyToChunk.getOrPut(key) { keyToChunk.size } }
    val chunkIndices = chunkKeys.map { keyToChunk.getValue(it) }
    val firstByParagraph = drafts.indices.groupBy { drafts[it].paragraphIndex }.mapValues { it.value.first() }
    val firstByRegion = drafts.indices.groupBy { drafts[it].headingRegion }.mapValues { it.value.first() }
    val lastByChunk = drafts.indices.groupBy { chunkIndices[it] }.mapValues { it.value.last() }

    val sentences = drafts.mapIndexed { index, draft ->
        DistillSentence(
            sourceIndex = index,
            text = draft.text,
            range = draft.range,
            heading = draft.heading,
            chunkIndex = chunkIndices[index],
            isParagraphFirst = firstByParagraph[draft.paragraphIndex] == index,
            isHeadingAdjacent = draft.heading != null && firstByRegion[draft.headingRegion] == index,
            isChunkLast = lastByChunk[chunkIndices[index]] == index,
            isNoteLast = index == drafts.lastIndex
        )
    }
    val chunks = sentences.groupBy { it.chunkIndex }.map { (index, chunkSentences) ->
        DistillChunk(index, chunkSentences.firstOrNull()?.heading, chunkSentences.map { it.sourceIndex })
    }
    return DistillSourceModel(content, sentences, chunks, eligibleCharacters, boldCharacters)
}

private fun sourceLines(content: String): List<SourceLine> {
    if (content.isEmpty()) return emptyList()
    val result = mutableListOf<SourceLine>()
    var start = 0
    var index = 0
    while (start < content.length) {
        val newline = content.indexOf('\n', start)
        val endWithBreak = if (newline >= 0) newline + 1 else content.length
        var contentEnd = if (newline >= 0) newline else content.length
        if (contentEnd > start && content[contentEnd - 1] == '\r') contentEnd--
        result += SourceLine(index++, start, contentEnd, endWithBreak, content.substring(start, contentEnd))
        start = endWithBreak
    }
    return result
}

private fun frontmatterLineIndices(lines: List<SourceLine>): Set<Int> {
    val first = lines.firstOrNull() ?: return emptySet()
    if (first.text.removePrefix("\uFEFF").trim() != "---") return emptySet()
    // 開始記号だけの壊れたfrontmatterは本文と断定できないため、安全側で全体を対象外にする。
    val close = lines.drop(1).firstOrNull { it.text.trim() == "---" }
        ?: return lines.mapTo(mutableSetOf()) { it.index }
    return (first.index..close.index).toSet()
}

private fun fencedCodeLineIndices(lines: List<SourceLine>): Set<Int> {
    val result = mutableSetOf<Int>()
    var fenceCharacter: Char? = null
    var fenceLength = 0
    for (line in lines) {
        val trimmed = line.text.trimStart()
        val markerCharacter = trimmed.firstOrNull().takeIf { it == '`' || it == '~' }
        val markerLength = markerCharacter?.let { countRun(trimmed, 0, it, trimmed.length) } ?: 0
        if (fenceCharacter == null && markerLength >= 3) {
            fenceCharacter = markerCharacter
            fenceLength = markerLength
            result += line.index
        } else if (fenceCharacter != null) {
            result += line.index
            val closesFence = markerCharacter == fenceCharacter &&
                markerLength >= fenceLength &&
                trimmed.drop(markerLength).isBlank()
            if (closesFence) {
                fenceCharacter = null
                fenceLength = 0
            }
        }
    }
    return result
}

private fun tableLineIndices(lines: List<SourceLine>): Set<Int> {
    val result = mutableSetOf<Int>()
    for (separatorIndex in 1 until lines.size) {
        if (isTableRow(lines[separatorIndex - 1].text) && isTableSeparator(lines[separatorIndex].text)) {
            result += lines[separatorIndex - 1].index
            var j = separatorIndex
            while (j < lines.size && isTableRow(lines[j].text)) {
                result += lines[j].index
                j++
            }
        }
    }
    return result
}

private fun isTableRow(text: String): Boolean =
    text.contains('|') && splitTableCells(text).isNotEmpty()

private fun isTableSeparator(text: String): Boolean {
    val cells = splitTableCells(text)
    return cells.isNotEmpty() && cells.all(TABLE_SEPARATOR_CELL_PATTERN::matches)
}

private fun splitTableCells(text: String): List<String> {
    var normalized = text.trim()
    if (normalized.startsWith('|')) normalized = normalized.drop(1)
    if (normalized.endsWith('|')) normalized = normalized.dropLast(1)
    return normalized.split('|').map(String::trim)
}

private fun setextHeadingsByLine(
    lines: List<SourceLine>,
    excludedLines: Set<Int>
): Map<Int, String> {
    val result = mutableMapOf<Int, String>()
    for (underlineIndex in 1 until lines.size) {
        val titleLine = lines[underlineIndex - 1]
        val underlineLine = lines[underlineIndex]
        if (titleLine.index in excludedLines || underlineLine.index in excludedLines) continue
        if (titleLine.text.isBlank() || !SETEXT_HEADING_PATTERN.matches(underlineLine.text)) continue
        if (HEADING_PATTERN.matches(titleLine.text.removePrefix("\uFEFF"))) continue
        result[titleLine.index] = titleLine.text.trim()
    }
    return result
}

private fun contentStartWithinLine(line: String): Int {
    val prefixLength = when {
        TASK_LIST_PREFIX.containsMatchIn(line) -> TASK_LIST_PREFIX.find(line)!!.range.last + 1
        ORDERED_LIST_PREFIX.containsMatchIn(line) -> ORDERED_LIST_PREFIX.find(line)!!.range.last + 1
        UNORDERED_LIST_PREFIX.containsMatchIn(line) -> UNORDERED_LIST_PREFIX.find(line)!!.range.last + 1
        else -> {
            var i = 0
            while (i < line.length && line[i].isWhitespace()) i++
            while (i < line.length && line[i] == '>') {
                i++
                if (i < line.length && line[i] == ' ') i++
            }
            i
        }
    }
    return prefixLength.coerceAtMost(line.length)
}

private fun isListOrQuote(line: String): Boolean =
    TASK_LIST_PREFIX.containsMatchIn(line) ||
        ORDERED_LIST_PREFIX.containsMatchIn(line) ||
        UNORDERED_LIST_PREFIX.containsMatchIn(line) ||
        line.trimStart().startsWith('>')

private fun parseInlineSyntax(
    content: String,
    start: Int,
    end: Int,
    strongSpans: List<DistillTextRange>
): InlineSyntax {
    val code = mutableListOf<DistillTextRange>()
    val links = mutableListOf<DistillTextRange>()
    var i = start
    while (i < end) {
        when {
            content[i] == '`' -> {
                val ticks = countRun(content, i, '`', end)
                val marker = "`".repeat(ticks)
                val close = content.indexOf(marker, i + ticks).takeIf { it in (i + ticks) until end }
                if (close != null) {
                    code += DistillTextRange(i, close + ticks)
                    i = close + ticks
                } else i++
            }
            i + 1 < end && content.startsWith("[[", i) -> {
                val close = content.indexOf("]]", i + 2).takeIf { it in (i + 2) until end }
                if (close != null) {
                    links += DistillTextRange(i, close + 2)
                    i = close + 2
                } else i += 2
            }
            content[i] == '[' -> {
                val labelEnd = content.indexOf(']', i + 1).takeIf { it in (i + 1) until end }
                val urlStart = labelEnd?.plus(1)?.takeIf { it < end && content[it] == '(' }
                val urlEnd = urlStart?.let { content.indexOf(')', it + 1) }?.takeIf { it in (urlStart + 1) until end }
                if (urlEnd != null) {
                    links += DistillTextRange(i, urlEnd + 1)
                    i = urlEnd + 1
                } else i++
            }
            else -> i++
        }
    }
    return InlineSyntax(code, strongSpans, links)
}

/** 通常本文のsoft line breakをまたぐ既存 ** 強調も、1つの保護範囲として認識する。 */
private fun strongSpansAcrossLines(
    content: String,
    lines: List<SourceLine>,
    excludedLines: Set<Int>
): List<DistillTextRange> {
    val result = mutableListOf<DistillTextRange>()
    var openStart: Int? = null
    for (line in lines) {
        if (line.index in excludedLines || line.text.isBlank()) {
            openStart = null
            continue
        }
        val codeSpans = inlineCodeSpans(content, line.start, line.contentEnd)
        var i = line.start
        while (i + 1 < line.contentEnd) {
            val inCode = codeSpans.any { i >= it.start && i < it.endExclusive }
            if (!inCode && content.startsWith("**", i) && !isEscaped(content, i, line.start)) {
                val start = openStart
                if (start == null) {
                    openStart = i
                } else {
                    result += DistillTextRange(start, i + 2)
                    openStart = null
                }
                i += 2
            } else {
                i++
            }
        }
    }
    return result
}

private fun inlineCodeSpans(content: String, start: Int, end: Int): List<DistillTextRange> {
    val result = mutableListOf<DistillTextRange>()
    var i = start
    while (i < end) {
        if (content[i] != '`' || isEscaped(content, i, start)) {
            i++
            continue
        }
        val ticks = countRun(content, i, '`', end)
        val marker = "`".repeat(ticks)
        val close = content.indexOf(marker, i + ticks).takeIf { it in (i + ticks) until end }
        if (close == null) {
            i++
        } else {
            result += DistillTextRange(i, close + ticks)
            i = close + ticks
        }
    }
    return result
}

private fun isEscaped(content: String, offset: Int, lowerBound: Int): Boolean {
    var slashCount = 0
    var i = offset - 1
    while (i >= lowerBound && content[i] == '\\') {
        slashCount++
        i--
    }
    return slashCount % 2 == 1
}

private fun countRun(content: String, start: Int, char: Char, end: Int): Int {
    var i = start
    while (i < end && content[i] == char) i++
    return i - start
}

/** 既存の太字は編集不可領域として、その前後だけを候補区間に分割する。 */
private fun subtractRanges(
    start: Int,
    end: Int,
    excluded: List<DistillTextRange>
): List<DistillTextRange> {
    if (start >= end) return emptyList()
    val result = mutableListOf<DistillTextRange>()
    var cursor = start
    excluded.asSequence()
        .filter { it.overlaps(DistillTextRange(start, end)) }
        .sortedBy { it.start }
        .forEach { range ->
            val clippedStart = range.start.coerceAtLeast(start)
            val clippedEnd = range.endExclusive.coerceAtMost(end)
            if (cursor < clippedStart) result += DistillTextRange(cursor, clippedStart)
            cursor = cursor.coerceAtLeast(clippedEnd)
        }
    if (cursor < end) result += DistillTextRange(cursor, end)
    return result
}

private fun splitLineIntoSentences(
    content: String,
    start: Int,
    end: Int,
    syntax: InlineSyntax
): List<DistillTextRange> {
    val result = mutableListOf<DistillTextRange>()
    var sentenceStart = start
    var i = start
    while (i < end) {
        val protected = syntax.protectedForSentenceBreaks.any { i >= it.start && i < it.endExclusive }
        val isBoundary = !protected && when (content[i]) {
            '。', '！', '？', '!', '?' -> true
            '.' -> isEnglishPeriodBoundary(content, i, start, end)
            else -> false
        }
        if (isBoundary) {
            addTrimmedRange(content, sentenceStart, i + 1, result)
            sentenceStart = i + 1
        }
        i++
    }
    addTrimmedRange(content, sentenceStart, end, result)
    return result
}

private fun isEnglishPeriodBoundary(content: String, offset: Int, lineStart: Int, lineEnd: Int): Boolean {
    val prev = content.getOrNull(offset - 1)
    val next = content.getOrNull(offset + 1)
    if (prev?.isDigit() == true && next?.isDigit() == true) return false
    if (prev?.isLetter() == true) {
        var wordStart = offset - 1
        while (wordStart >= lineStart && (content[wordStart].isLetter() || content[wordStart] == '.')) wordStart--
        val token = content.substring(wordStart + 1, offset).lowercase().trimEnd('.')
        if (token in ENGLISH_ABBREVIATIONS || token.length <= 1) return false
    }
    if (next != null && offset + 1 < lineEnd && !next.isWhitespace()) return false
    return true
}

private fun addTrimmedRange(
    content: String,
    rawStart: Int,
    rawEnd: Int,
    output: MutableList<DistillTextRange>
) {
    var start = rawStart
    var end = rawEnd
    while (start < end && content[start].isWhitespace()) start++
    while (end > start && content[end - 1].isWhitespace()) end--
    if (start < end) output += DistillTextRange(start, end)
}
