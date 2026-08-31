package com.example.newproject.model.state

import com.example.newproject.model.BookletEntry

/**
 * 冊子（ランダムに引いた10枚の束）の状態。**Vault単位。**
 *
 * ノート切替では消さない — 冊子から「これを読む」でノートへ渡り、**戻れば同じ10枚が残る**
 * のが冊子の目的そのものだから（→ features/booklet_mode.md 判断6）。
 * したがって `withNoteScopedReset()` には登録せず、`withVaultScopedReset()` 側に置く。
 */
sealed interface BookletState {
    /** 冊子を開いていない。 */
    data object Idle : BookletState

    /** 束を作っている（Vault走査の待ち）。 */
    data object Loading : BookletState

    /**
     * **[entries] が空なら「Vaultにノートが無い」。** 別のvariantを作らない。
     *
     * [page] を束と同じ場所に置くのは、**寿命が同じだから。**
     * 「戻れば同じ10枚が同じページ位置で残る」は束とページ位置の2つで1つの条件なのに、
     * 束をVault単位・ページ位置を画面ローカルに置いていたため、
     * **実機の `冊子 → ノート → 戻る` でページ位置だけが失われた**（2026-08-31）。
     * 引き直し・Vault切替・プロセス復元では束ごと消えるので、ページ位置も一緒に消える。
     */
    data class Open(val entries: List<BookletEntry>, val page: Int = 0) : BookletState

    /** 束そのものが作れなかった（走査の失敗）。ページ単位の失敗は [com.example.newproject.model.BookletCover.Failed]。 */
    data class Failed(val message: String) : BookletState
}
