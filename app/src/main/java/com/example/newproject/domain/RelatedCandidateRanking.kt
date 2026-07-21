package com.example.newproject.domain

// 関連ノートAI候補の汎用ランキング基盤（純ロジック・Uri非依存）。
// 「どう採点するか」を呼び出し側の scoreOf に委ね、「除外→並べ替え→上限」の規約だけを
// ここへ集約する。Phase 3a（タイトル類似）と将来の Phase 3b（本文シグナル）で、
// 並び順・タイブレーク・上限の挙動を重複なく共有するための土台。

// 採点結果。score は主シグナル、tieBreak は同点時の従シグナル（ともに大きいほど優先）。
internal data class CandidateScore(val score: Double, val tieBreak: Double)

private data class RankedCandidate<T>(
    val value: T,
    val inputIndex: Int,
    val score: Double,
    val tieBreak: Double
)

/**
 * 候補を採点して上位を返す汎用ランキング。
 *
 * - [isExcluded] が真の候補は**上限適用の前に**落とす（正規化・判定は呼び出し側の責務）。
 * - 並び順は score 降順 → tieBreak 降順 → 元の入力順で、常に決定的。
 * - [limit] が 0 以下なら空を返す。
 *
 * scoreOf を差し替えるだけで別シグナル（本文・タグ等）のランキングにも使い回せる。
 */
internal fun <T> rankByScore(
    candidates: List<T>,
    isExcluded: (T) -> Boolean,
    limit: Int,
    scoreOf: (T) -> CandidateScore
): List<T> {
    if (limit <= 0) return emptyList()

    return candidates
        .mapIndexedNotNull { index, candidate ->
            if (isExcluded(candidate)) return@mapIndexedNotNull null
            val (score, tieBreak) = scoreOf(candidate)
            RankedCandidate(candidate, index, score, tieBreak)
        }
        .sortedWith(
            compareByDescending<RankedCandidate<T>> { it.score }
                .thenByDescending { it.tieBreak }
                .thenBy { it.inputIndex }
        )
        .take(limit)
        .map { it.value }
}
