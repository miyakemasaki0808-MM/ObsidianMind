package com.example.newproject.domain

import com.example.newproject.model.RelatedNote

// 反証の一文の候補の順位付け（→ features/rebuttal_sentence.md §5）。
// Nano は先頭の候補を選びやすいので、**今のノートと話題の近い文を先頭へ寄せる**（関連ノートの再ランクと同じ狙い）。

/** 1つの関連ノートから候補に残す文の上限。1ノートの文で候補が埋まらないようにする。 */
internal const val REBUTTAL_CLAIMS_PER_NOTE = 3

/** Nano に渡す候補の総数。IDは `X01..X12`。 */
internal const val REBUTTAL_CANDIDATE_LIMIT = 12

/** 候補1件。表示する文と、移る先の関連ノートを組で持つ。 */
internal data class RebuttalCandidate(val source: RelatedNote, val text: String)

private data class ScoredCandidate(val candidate: RebuttalCandidate, val score: Double)

/**
 * 候補を並べて上限で切る。
 *
 * - 採点は今のノートの抜粋と候補文の文字 bigram の Dice 係数。**[excerptText] はAIへ渡す抜粋と同じ文字列にする** —
 *   順位付けとAIが別の本文を見ると「近いから先頭に置いた」が成り立たない
 * - ノートごとに上位 [perNote] 件、全体で上位 [total] 件。同点は [claimsBySource] の並び（関連ノートの並び）、次に原文順
 * - **score が 0 でも捨てない。** 日本語の短文では bigram が重ならないことが普通にあり、閾値は測ってから決める
 */
internal fun rankRebuttalCandidates(
    excerptText: String,
    claimsBySource: List<Pair<RelatedNote, List<String>>>,
    perNote: Int = REBUTTAL_CLAIMS_PER_NOTE,
    total: Int = REBUTTAL_CANDIDATE_LIMIT
): List<RebuttalCandidate> {
    val current = textBigrams(excerptText)
    val perSource = claimsBySource.flatMap { (source, claims) ->
        val scored = claims.map { claim ->
            ScoredCandidate(RebuttalCandidate(source, claim), diceCoefficient(current, textBigrams(claim)))
        }
        rankByScore(scored, isExcluded = { false }, limit = perNote) { CandidateScore(it.score, 0.0) }
    }
    return rankByScore(perSource, isExcluded = { false }, limit = total) { CandidateScore(it.score, 0.0) }
        .map { it.candidate }
}
