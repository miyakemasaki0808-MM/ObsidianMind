package com.example.newproject.model

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
