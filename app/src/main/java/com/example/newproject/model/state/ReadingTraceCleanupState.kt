package com.example.newproject.model.state

import com.example.newproject.model.OrphanBlockReason
import com.example.newproject.model.OrphanCandidate
import com.example.newproject.model.WithheldOrphans

/**
 * 読書痕跡の整理画面の状態。**Vault単位**（ノート切替では消えない）。
 *
 * 候補と、遮断器が保留した一群の両方を持つ。**保留は隠さない** —
 * 「この判定は信用できるか」を運用で見るための観測対象そのものだから
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
        val withheld: List<WithheldOrphans>,
        /**
         * 直近の削除で失敗した件数。**失敗した候補は一覧に残す** —
         * 消えると再試行できなくなる（SAFプロバイダは削除に失敗し得る）。
         */
        val deleteFailureCount: Int = 0
    ) : ReadingTraceCleanupState

    /**
     * 判定そのものを見送った。**「孤児は無かった」ではない。**
     * ここを [Success] の空リストで代用すると、判定の可否と結果が混ざる。
     */
    data class Blocked(val reason: OrphanBlockReason, val candidateCount: Int) :
        ReadingTraceCleanupState

    data class Error(val message: String) : ReadingTraceCleanupState
}
