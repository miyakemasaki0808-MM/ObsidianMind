package com.example.newproject.domain

internal data class DistillCandidate(
    val id: String,
    val sentence: DistillSentence,
    val score: Double,
    val structuralWeight: Double
)

internal object DistillLimits {
    const val MAX_FILE_BYTES = 256 * 1024
    const val MAX_SENTENCES_FOR_SCORING = 400
    const val MAX_AI_CANDIDATES = 24
    const val AI_CANDIDATE_CHAR_BUDGET = 1_500
    const val FINAL_SELECTION_LIMIT = 6
    const val MAX_SENTENCE_CHARACTERS = 160
    const val CHUNK_CHARACTER_LIMIT = 1_200
    const val MAX_BOLD_RATIO = 0.30
}

private const val TITLE_WEIGHT = 1.0
private const val HEADING_WEIGHT = 0.7
private const val PARAGRAPH_FIRST_BONUS = 0.3
private const val CHUNK_LAST_BONUS = 0.4
private const val HEADING_ADJACENT_BONUS = 0.2
private const val SHORT_SENTENCE_PENALTY = 0.25

/** 全文の各所を残しつつ、AIへ渡す上位候補を決定的に選ぶ。 */
internal fun selectDistillCandidates(
    model: DistillSourceModel,
    noteTitle: String,
    limit: Int = DistillLimits.MAX_AI_CANDIDATES
): List<DistillCandidate> {
    if (limit <= 0) return emptyList()
    val eligible = model.sentences.filter {
        it.text.isNotBlank() && it.text.length <= DistillLimits.MAX_SENTENCE_CHARACTERS
    }
    if (eligible.isEmpty()) return emptyList()
    val bounded = boundedSentencesPreservingChunks(eligible, DistillLimits.MAX_SENTENCES_FOR_SCORING)
    val titleSignal = textBigrams(noteTitle)

    data class Scored(val sentence: DistillSentence, val score: Double, val structural: Double)
    val scored = bounded.map { sentence ->
        val structural = structuralWeight(sentence)
        val score = TITLE_WEIGHT * diceCoefficient(textBigrams(sentence.text), titleSignal) +
            HEADING_WEIGHT * diceCoefficient(
                textBigrams(sentence.text),
                textBigrams(sentence.heading.orEmpty())
            ) + structural +
            if (sentence.text.length < 15) -SHORT_SENTENCE_PENALTY else 0.0
        Scored(sentence, score, structural)
    }
    val ordering = compareByDescending<Scored> { it.score }
        .thenByDescending { it.structural }
        .thenBy { it.sentence.sourceIndex }

    val leaders = scored.groupBy { it.sentence.chunkIndex }.values.map { it.sortedWith(ordering).first() }
    val selected = LinkedHashMap<Int, Scored>()
    if (leaders.size > limit) {
        leaders.sortedWith(
            compareByDescending<Scored> { it.structural }
                .thenByDescending { it.score }
                .thenBy { it.sentence.sourceIndex }
        ).take(limit).forEach { selected[it.sentence.sourceIndex] = it }
    } else {
        leaders.forEach { selected[it.sentence.sourceIndex] = it }
        scored.sortedWith(ordering).forEach { candidate ->
            if (selected.size < limit) selected.putIfAbsent(candidate.sentence.sourceIndex, candidate)
        }
    }

    return selected.values.sortedBy { it.sentence.sourceIndex }.mapIndexed { index, item ->
        DistillCandidate(
            id = distillCandidateId(index),
            sentence = item.sentence,
            score = item.score,
            structuralWeight = item.structural
        )
    }
}

internal fun structuralWeight(sentence: DistillSentence): Double =
    (if (sentence.isParagraphFirst) PARAGRAPH_FIRST_BONUS else 0.0) +
        (if (sentence.isChunkLast || sentence.isNoteLast) CHUNK_LAST_BONUS else 0.0) +
        (if (sentence.isHeadingAdjacent) HEADING_ADJACENT_BONUS else 0.0)

private fun evenlyBoundedSentences(
    input: List<DistillSentence>,
    limit: Int
): List<DistillSentence> {
    if (input.size <= limit) return input
    if (limit <= 1) return listOf(input.last())
    val indices = (0 until limit).map { slot ->
        ((slot.toLong() * (input.lastIndex)) / (limit - 1)).toInt()
    }.distinct()
    return indices.map(input::get)
}

/**
 * 計算量を制限しても、小さなチャンクが全体の均等間引きで消えないよう代表文を先に確保する。
 * チャンク自体が上限を超える場合だけ、構造重みと実用的な文長を優先して二次間引きする。
 */
private fun boundedSentencesPreservingChunks(
    input: List<DistillSentence>,
    limit: Int
): List<DistillSentence> {
    if (input.size <= limit) return input
    if (limit <= 0) return emptyList()

    val representativeOrdering = compareByDescending<DistillSentence> { structuralWeight(it) }
        .thenByDescending { it.text.length >= 15 }
        .thenByDescending { it.text.length.coerceAtMost(80) }
        .thenBy { it.sourceIndex }
    val representatives = input.groupBy { it.chunkIndex }.values
        .map { chunk -> chunk.sortedWith(representativeOrdering).first() }

    if (representatives.size >= limit) {
        return representatives.sortedWith(representativeOrdering)
            .take(limit)
            .sortedBy { it.sourceIndex }
    }

    val representativeIndices = representatives.mapTo(mutableSetOf()) { it.sourceIndex }
    val remaining = input.filterNot { it.sourceIndex in representativeIndices }
    val additional = evenlyBoundedSentences(remaining, limit - representatives.size)
    return (representatives + additional).sortedBy { it.sourceIndex }
}

internal fun distillCandidateId(index: Int): String {
    require(index in 0..998)
    return "S" + (index + 1).toString().padStart(3, '0')
}
