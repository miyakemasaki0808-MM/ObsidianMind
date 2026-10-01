package com.example.newproject.model

import com.example.newproject.model.state.MarginMemoDraft

/**
 * 余白メモの書きかけの置き場（→ features/margin_pane.md §6.1）。**ノートごとに1組を持ち、Vault を替えると捨てる。**
 *
 * `NoteUiState` に置かないのは、入力中の文字を StateFlow 経由で描くと日本語の変換中に入力が崩れやすいため。
 * 実体は ViewModel 側が Compose の状態で持ち、Controller はこの口だけを通して読み書きする。
 * **書くのは Main からだけ**（入力・保存の結果・読み込みの照合のどれも Main で届く）。
 */
interface MarginMemoDraftStore {
    /** [noteKey] の書きかけ。無ければ空。 */
    fun draft(noteKey: String): MarginMemoDraft

    fun update(noteKey: String, transform: (MarginMemoDraft) -> MarginMemoDraft)

    /** 全ノート分を捨てる。**Vault 切替でだけ呼ぶ** — ノートを替えても書きかけは預かる。 */
    fun clear()
}
