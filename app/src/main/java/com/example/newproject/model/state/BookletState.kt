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

    /** **[entries] が空なら「Vaultにノートが無い」。** 別のvariantを作らない。 */
    data class Open(val entries: List<BookletEntry>) : BookletState

    /** 束そのものが作れなかった（走査の失敗）。ページ単位の失敗は [com.example.newproject.model.BookletCover.Failed]。 */
    data class Failed(val message: String) : BookletState
}
