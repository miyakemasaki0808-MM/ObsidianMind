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

    data class Loading(val note: RelatedNote, val face: SideFace = SideFace.Reference) : SideReadingState

    /** 読めた。本文は `SideReadingController.blocks` にある。 */
    data class Ready(val note: RelatedNote, val face: SideFace = SideFace.Reference) : SideReadingState

    /** 読めなかった。右側だけに出して、そこで再試行できる。 */
    data class Failed(val note: RelatedNote, val message: String, val face: SideFace = SideFace.Reference) : SideReadingState
}

/**
 * 並べ読みの間に右の面が出すもの（→ features/margin_quote.md §5.6）。**引用の有無で決めず、明示の状態で持つ** —
 * 引用だけを外す ✕ が右のノートへの切替を兼ねると、書き続ける入力面が隠れる。並べ読みと同じ寿命で、終われば一緒に消える。
 */
enum class SideFace {
    /** 資料 — 右のノート。 */
    Reference,

    /** 書きかけ — 今のノートの余白の面（引用と書いた言葉）。右のノートは隠して位置ごと保つ。 */
    Draft
}

/** 右の面が出すもの。並べ読みをしていなければ資料（右の面は余白）。 */
val SideReadingState.face: SideFace
    get() = when (this) {
        SideReadingState.Idle -> SideFace.Reference
        is SideReadingState.Loading -> face
        is SideReadingState.Ready -> face
        is SideReadingState.Failed -> face
    }

/** 右の面の出し分けだけを替える。並べ読みをしていなければそのまま。 */
fun SideReadingState.withFace(face: SideFace): SideReadingState = when (this) {
    SideReadingState.Idle -> this
    is SideReadingState.Loading -> copy(face = face)
    is SideReadingState.Ready -> copy(face = face)
    is SideReadingState.Failed -> copy(face = face)
}

/** 右で眺めているノート。並べ読みをしていなければ null。 */
val SideReadingState.note: RelatedNote?
    get() = when (this) {
        SideReadingState.Idle -> null
        is SideReadingState.Loading -> note
        is SideReadingState.Ready -> note
        is SideReadingState.Failed -> note
    }
