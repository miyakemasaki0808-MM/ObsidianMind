package com.example.newproject.model.state

import com.example.newproject.model.RelatedNote

/**
 * 並べ読み（→ features/margin_pane.md §5.9）。ペインの右で眺めている関連ノート。
 *
 * **眺めるだけで、今のノートにはしない。** 本文の解析結果は `domain` の型なので、ここには持たない
 * （`SideReadingController.blocks`）。状態がここにあるのは、ノート切替の一括リセット（`withNoteScopedReset()`）に載せるため。
 */
sealed interface SideReadingState {
    data object Idle : SideReadingState

    data class Loading(val note: RelatedNote) : SideReadingState

    /** 読めた。本文は `SideReadingController.blocks` にある。 */
    data class Ready(val note: RelatedNote) : SideReadingState

    /** 読めなかった。右側だけに出して、そこで再試行できる。 */
    data class Failed(val note: RelatedNote, val message: String) : SideReadingState
}

/** 右で眺めているノート。並べ読みをしていなければ null。 */
val SideReadingState.note: RelatedNote?
    get() = when (this) {
        SideReadingState.Idle -> null
        is SideReadingState.Loading -> note
        is SideReadingState.Ready -> note
        is SideReadingState.Failed -> note
    }
