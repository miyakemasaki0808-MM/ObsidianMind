package com.example.newproject.model

/**
 * Explore の打った言葉の置き場（→ features/ai_picker.md §6）。**タブを離れても残し、Vault を替えると捨てる。**
 *
 * 範囲と AI の結果はタブを離れても残るので、言葉だけが消えると、戻ったときに空の入力欄の下に前の結果が並ぶ。
 * `NoteUiState` に置かないのは、入力中の文字を StateFlow 経由で描くと日本語の変換中に入力が崩れやすいため。
 * 実体は ViewModel 側が Compose の状態で持ち、Controller はこの口だけを通す。**書くのは Main からだけ。**
 */
interface ExploreQueryStore {
    val text: String

    fun set(text: String)

    /** **Vault 切替でだけ呼ぶ** — タブを離れても言葉は預かる。 */
    fun clear()
}
