package com.example.newproject.model.state

import com.example.newproject.model.OrphanBlockReason
import com.example.newproject.model.OrphanCandidate
import com.example.newproject.model.WithheldOrphans

/**
 * 読書痕跡の整理画面の状態。**Vault単位**（ノート切替では消えない）。
 *
 * 段階3 の時点では**削除しない**。候補と、遮断器が保留した一群を並べて見せるだけの
 * シャドーモードで、「この判定は信用できるか」を実運用で観測するのが目的
 * （→ reflect_reading_trace §14）。
 */
sealed interface ReadingTraceCleanupState {
    object Idle : ReadingTraceCleanupState

    object Loading : ReadingTraceCleanupState

    /**
     * 判定できた。
     *
     * [orphans] が空でも [withheld] が空とは限らない — 「候補ゼロ」と
     * 「遮断器が全部止めた」は違う状態なので、画面では区別して見せる。
     */
    data class Success(
        val orphans: List<OrphanCandidate>,
        val withheld: List<WithheldOrphans>
    ) : ReadingTraceCleanupState

    /**
     * 判定そのものを見送った。**「孤児は無かった」ではない。**
     * ここを [Success] の空リストで代用すると、判定の可否と結果が混ざる。
     */
    data class Blocked(val reason: OrphanBlockReason, val candidateCount: Int) :
        ReadingTraceCleanupState

    data class Error(val message: String) : ReadingTraceCleanupState
}
