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
    /**
     * 候補を理解するために表示する親文の範囲。**割っていない文では [range] と同じ。**
     *
     * 句だけを候補カードへ出すと「何について述べた断片か」が読めないため、親文を引けるようにする。
     * 太字にするのはあくまで [range] であって、この範囲ではない。
     */
    val contextRange: DistillTextRange,
    val heading: String?,
    val chunkIndex: Int,
    val isParagraphFirst: Boolean,
    val isHeadingAdjacent: Boolean,
    val isChunkLast: Boolean,
    val isNoteLast: Boolean,
    /**
     * 括弧から取り出した語句候補。**文・句と違い、親の内側に重なって存在する。**
     *
     * 位置の重み付け（段落先頭・チャンク末尾など）には参加しない。本文の線形構造ではなく、
     * その上に重ねる候補だから。
     */
    val isTerm: Boolean,
    /** リンクを除くと実質的な文字が残らない文。太字にしても読み返しの手がかりにならない。 */
    val isLinkOnly: Boolean
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

    /**
     * この文字数を超える文だけを句へ割る。**短い文はまるごと選ぶほうが読み返しやすい。**
     *
     * 全文を割ると、いま良く効いている短文の選定まで細切れになる。
     */
    const val CLAUSE_SPLIT_THRESHOLD = 60

    /**
     * 句の下限。これ未満の断片は隣接断片と結合し、単語だけの候補を作らない。
     *
     * **これは「長さを意味の代理にする」に当たらない。** リンクだけかどうかという意味の問いへ
     * 長さを使って2度失敗したが（→ `isLinkOnlyRange`）、こちらは
     * 「太字として読める最小の塊か」という長さそのものの問いである。
     */
    const val MIN_CLAUSE_CHARACTERS = 15

    /**
     * 1文から候補へ入れる句の上限。
     *
     * **1つに絞らないのは、非AI段が外したときAIに選び直す余地が無くなるため。**
     */
    const val MAX_CLAUSES_PER_SENTENCE = 2

    /**
     * 候補へ入れる語句の上限。
     *
     * **最終選択がすべて語句になるのを防ぐ。** 語句は短く、重なったとき親より優先されるので、
     * 歯止めが無いと本文の主張がまるごと候補から消える。
     */
    const val MAX_TERM_CANDIDATES = 2

    /** 語句候補の最小長。これ未満は括弧の中でも候補にしない。 */
    const val MIN_TERM_CHARACTERS = 2

    /** 語句候補の最大長。これを超えるものは語句ではなく文の一部として扱う。 */
    const val MAX_TERM_CHARACTERS = 20
}
