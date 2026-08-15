package com.example.newproject.model.state

import com.example.newproject.model.RelatedNote

/**
 * 関連ノートタブの状態。
 *
 * **AIが使えたかどうかを持たない。** 関連ノートは**ノートを開くと自動で走る**機能なので、
 * 使えないときは黙って決定的チャンネル（wikilink・同採番グループ）だけを出す。
 * 押していない機能が理由を語り出すと、ノートを開くたび騒がしくなる。
 */
sealed class RelatedNotesState {
    object Idle : RelatedNotesState()
    object Loading : RelatedNotesState()
    data class Success(
        val relatedNotes: List<RelatedNote>,
        val aiNotes: List<RelatedNote>
    ) : RelatedNotesState()
    data class Error(val message: String) : RelatedNotesState()
}
