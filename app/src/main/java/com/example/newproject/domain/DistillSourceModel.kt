package com.example.newproject.domain

import com.example.newproject.model.DistillChunk
import com.example.newproject.model.DistillLimits
import com.example.newproject.model.DistillSentence
import com.example.newproject.model.DistillSourceModel
import com.example.newproject.model.DistillTextRange

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
    val contextRange: DistillTextRange,
    val heading: String?,
    val headingRegion: Int,
    val regionStart: Int,
    val paragraphIndex: Int,
    val isLinkOnly: Boolean,
    val isTerm: Boolean = false
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
 * リンク列の隙間に現れる助詞・接続詞。**閉じた一覧にする。**
 *
 * 一般の語を足すと `[[A]]は核。` のような短いが意味のある主張まで落ちるので、
 * **「リンクとリンクの間に置かれるもの」だけ**に絞る。長いものから剥がす（→ [stripLinkGlue]）。
 *
 * **一覧を絞るだけでは足りない。** 適用位置も両側がリンクの断片へ限らないと、
 * 語中の文字を剥がして別種の誤判定を作る（→ [hasSubstance]）。
 */
private val LINK_GLUE_WORDS = listOf(
    "ならびに", "および", "そして", "また", "など", "から", "まで", "かつ", "and", "or", "vs",
    "は", "が", "を", "に", "へ", "と", "や", "の", "も", "で"
).sortedByDescending(String::length)

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
            ).forEach { sentenceRange ->
                if ((syntax.codeSpans + syntax.linkSpans).any { span ->
                        span.contains(sentenceRange.start) || span.contains(sentenceRange.endExclusive)
                    }
                ) return@forEach
                if (content.substring(sentenceRange.start, sentenceRange.endExclusive).isBlank()) {
                    return@forEach
                }
                // 句へ割っても親文の範囲は保つ。候補カードの文脈表示に使う。
                splitSentenceIntoClauses(content, sentenceRange, syntax).forEach { range ->
                    val text = content.substring(range.start, range.endExclusive)
                    if (text.isBlank()) return@forEach
                    drafts += SentenceDraft(
                        text = text,
                        range = range,
                        contextRange = sentenceRange,
                        heading = currentHeading,
                        headingRegion = headingRegion,
                        regionStart = regionStart,
                        paragraphIndex = thisParagraph,
                        // リンク判定は句ごとに掛け直す。親文が通常文でも断片はリンクだけになり得る。
                        isLinkOnly = isLinkOnlyRange(content, range, syntax.linkSpans)
                    )
                    // 語句候補は親の内側に重なる。文脈は親文のままにする。
                    bracketedTermRanges(content, range, syntax).forEach { termRange ->
                        drafts += SentenceDraft(
                            text = content.substring(termRange.start, termRange.endExclusive),
                            range = termRange,
                            contextRange = sentenceRange,
                            heading = currentHeading,
                            headingRegion = headingRegion,
                            regionStart = regionStart,
                            paragraphIndex = thisParagraph,
                            isLinkOnly = isLinkOnlyRange(content, termRange, syntax.linkSpans),
                            isTerm = true
                        )
                    }
                }
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
    // 位置の重みは本文の線形構造に対して数える。語句候補は親の内側に重なる存在なので、
    // 段落先頭やノート末尾を横取りしないよう、この計算から外す。
    val structural = drafts.indices.filterNot { drafts[it].isTerm }
    val firstByParagraph = structural.groupBy { drafts[it].paragraphIndex }.mapValues { it.value.first() }
    val firstByRegion = structural.groupBy { drafts[it].headingRegion }.mapValues { it.value.first() }
    val lastByChunk = structural.groupBy { chunkIndices[it] }.mapValues { it.value.last() }
    val lastStructural = structural.lastOrNull()

    val sentences = drafts.mapIndexed { index, draft ->
        DistillSentence(
            sourceIndex = index,
            text = draft.text,
            range = draft.range,
            contextRange = draft.contextRange,
            heading = draft.heading,
            chunkIndex = chunkIndices[index],
            isParagraphFirst = firstByParagraph[draft.paragraphIndex] == index,
            isHeadingAdjacent = draft.heading != null && firstByRegion[draft.headingRegion] == index,
            isChunkLast = lastByChunk[chunkIndices[index]] == index,
            isNoteLast = index == lastStructural,
            isLinkOnly = draft.isLinkOnly,
            isTerm = draft.isTerm
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

/**
 * リンクを除くと実質的な文字が残らない範囲かを判定する。
 *
 * **`DistillSentence` ではなく範囲とリンク範囲だけを受ける。** 候補の単位が文から句へ変わっても
 * 同じ判定を候補ごとへ再適用できるようにするため（`詳細は[[A]]、実装は[[B]]。` は文としては
 * 通常文だが、読点で割ると両断片ともリンクだけになる）。
 *
 * **代理判定を2度作ったので、いまは「何を」と「どこで」を分けて決める**
 * → [蒸留の候補除外契約](../../../../../../../../docs/dev/features/reflect_distill.md)。
 * 1度目は長さを意味の代理にして、区切り記号を実質文字として数えたため
 * `[[A]]、[[B]]、[[C]]、[[D]]。`（4文字）が残り `[[A]]は核。`（3文字）が落ちた。
 * 2度目は接続語一覧を入れたが適用位置を捨てたため、`[[A]]のもの。` の語中まで剥がして落とした。
 * いまは**記号を全断片から除き、接続語はリンクに挟まれた断片でだけ除く**（→ [hasSubstance]）。
 *
 * **リンクを含まない範囲は対象にしない。** リンクの無い短文の扱いは従来どおりで、
 * 短さは [selectDistillCandidates] 側の減点が引き受ける。
 */
internal fun isLinkOnlyRange(
    content: String,
    range: DistillTextRange,
    linkSpans: List<DistillTextRange>
): Boolean {
    val links = linkSpans.filter { it.overlaps(range) }.sortedBy { it.start }
    if (links.isEmpty()) return false

    var cursor = range.start
    links.forEachIndexed { index, link ->
        val segmentEnd = link.start.coerceIn(range.start, range.endExclusive)
        // 先頭断片（index == 0）はリンクに挟まれていないので、接続語の除去を許さない。
        if (cursor < segmentEnd && hasSubstance(content, cursor, segmentEnd, betweenLinks = index > 0)) {
            return false
        }
        cursor = maxOf(cursor, link.endExclusive.coerceAtMost(range.endExclusive))
    }
    // 末尾断片も片側しかリンクが無いので、接続語の除去を許さない。
    return !(cursor < range.endExclusive &&
        hasSubstance(content, cursor, range.endExclusive, betweenLinks = false))
}

/**
 * 非リンク断片に実質的な文字が残るか。
 *
 * **接続語を剥がすのは両側がリンクの断片だけ。** 日本語は助詞と普通名詞の語境界が空白で分かれないため、
 * 位置を問わず剥がすと語中の文字まで落ちる（`[[A]]のもの。` の `のもの` が `の`・`も` の除去で空になった）。
 * **正規化の対象と、意味を捨ててよい位置は別々に決める。**
 */
private fun hasSubstance(
    content: String,
    start: Int,
    endExclusive: Int,
    betweenLinks: Boolean
): Boolean {
    val letters = buildString {
        for (offset in start until endExclusive) {
            // 空白・句読点・記号はここで落ちる。残るのは文字と数字だけ。
            if (content[offset].isLetterOrDigit()) append(content[offset])
        }
    }
    if (letters.isEmpty()) return false
    return !betweenLinks || stripLinkGlue(letters.lowercase()).isNotEmpty()
}

/**
 * 接続語を長いものから繰り返し剥がす。**剥がすたびに必ず短くなるので停止する。**
 *
 * 繰り返すのは、除去で新しい隣接ができるため（`のは` → `の` を剥がすと `は` が残る）。
 * **呼ぶのはリンクに挟まれた断片だけ**（→ [hasSubstance]）。
 */
private fun stripLinkGlue(text: String): String {
    var result = text
    while (true) {
        val reduced = LINK_GLUE_WORDS.fold(result) { accumulated, glue ->
            accumulated.replace(glue, "")
        }
        if (reduced == result) return result
        result = reduced
    }
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
    trimmedRange(content, rawStart, rawEnd)?.let { output += it }
}

/**
 * 鉤括弧の中身を語句候補として取り出す。
 *
 * **入口を括弧に限るのは、アプリが原文範囲を決定的に取れるから。**
 * 任意の名詞句を対象にすると形態素解析が要り、依存追加と候補爆発を招く。
 * `〜とは` の定義対象は語の開始位置を決めるのに閉じた一覧が要るため、今回は採らない。
 *
 * コロン前・箇条書き先頭のラベルも入口の候補だったが、`- **ラベル**: 説明` の形で
 * **既に手で太字にされていることが多く、既存強調として編集対象から外れる**ので採らない。
 */
private fun bracketedTermRanges(
    content: String,
    range: DistillTextRange,
    syntax: InlineSyntax
): List<DistillTextRange> {
    val result = mutableListOf<DistillTextRange>()
    var i = range.start
    while (i < range.endExclusive) {
        val close = when (content[i]) {
            '「' -> '」'
            '『' -> '』'
            else -> null
        }
        if (close == null) {
            i++
            continue
        }
        val end = content.indexOf(close, i + 1).takeIf { it in (i + 1) until range.endExclusive }
        if (end == null) {
            i++
            continue
        }
        val inner = trimmedRange(content, i + 1, end)
        val protectedInner = inner != null &&
            syntax.protectedForSentenceBreaks.any { it.overlaps(inner) }
        if (inner != null && !protectedInner &&
            inner.length in DistillLimits.MIN_TERM_CHARACTERS..DistillLimits.MAX_TERM_CHARACTERS
        ) {
            result += inner
        }
        i = end + 1
    }
    return result
}

/** 前後の空白を落とした範囲。空になるなら null。 */
private fun trimmedRange(content: String, rawStart: Int, rawEnd: Int): DistillTextRange? {
    var start = rawStart
    var end = rawEnd
    while (start < end && content[start].isWhitespace()) start++
    while (end > start && content[end - 1].isWhitespace()) end--
    return if (start < end) DistillTextRange(start, end) else null
}

/**
 * 長い文だけを読点で句へ割る。短い文と、割っても下限に届かない文はそのまま1つ返す。
 *
 * **読点そのものは句に含めない。** 含めると `**AAA、**BBB。` のように区切り記号まで太字になる。
 *
 * **前から貪欲に積み、下限へ届いた時点で閉じる。** 末尾に残った下限未満の余りは直前の句へ吸収する
 * （下限未満の句を作らない）。下限だけ決めても結合の向きが決まらないため、ここで向きを固定する。
 *
 * **句の境界がコードスパンや既存強調をまたぐ心配は無い。** 境界は保護範囲の外にある読点だけで、
 * 親文が既にまたぎ判定を通過しているため。**落ちるテストを書けないガードは置かない。**
 */
private fun splitSentenceIntoClauses(
    content: String,
    range: DistillTextRange,
    syntax: InlineSyntax
): List<DistillTextRange> {
    if (range.length <= DistillLimits.CLAUSE_SPLIT_THRESHOLD) return listOf(range)

    val clauses = mutableListOf<DistillTextRange>()
    var clauseStart = range.start
    for (offset in range.start until range.endExclusive) {
        if (content[offset] != '、' && content[offset] != ',') continue
        if (syntax.protectedForSentenceBreaks.any { offset >= it.start && offset < it.endExclusive }) continue
        val clause = trimmedRange(content, clauseStart, offset) ?: continue
        if (clause.length >= DistillLimits.MIN_CLAUSE_CHARACTERS) {
            clauses += clause
            clauseStart = offset + 1
        }
    }
    if (clauses.isEmpty()) return listOf(range)

    val tail = trimmedRange(content, clauseStart, range.endExclusive)
    if (tail != null) {
        if (tail.length >= DistillLimits.MIN_CLAUSE_CHARACTERS) {
            clauses += tail
        } else {
            // 下限未満の余りは新しい句にせず、直前の句を末尾まで伸ばす。
            val last = clauses.removeAt(clauses.lastIndex)
            clauses += DistillTextRange(last.start, tail.endExclusive)
        }
    }
    return if (clauses.size <= 1) listOf(range) else clauses
}
