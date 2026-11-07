package com.example.newproject.model

/**
 * 関連ノートを近いと見た根拠のうち、いちばん効いたもの（→ features/related_notes_ai.md §5）。
 * **数値は持たない。** スコアが見えると、順位への納得と同じだけ不満も生む。何が近かったかだけを持つ。
 */
sealed interface RelatedReason {
    /** 今のノートと共通のタグ。候補側の書き方から先頭の `#` を除いたもの。 */
    data class SharedTag(val tag: String) : RelatedReason

    /** 本文の書き出しが近い。 */
    object SimilarOpening : RelatedReason

    /** 題名が近い。 */
    object SimilarTitle : RelatedReason
}
