package com.example.newproject.ui.screen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.newproject.model.ExploreQueryStore

/**
 * Explore の打った言葉を Compose の状態で持つ置き場（→ [ExploreQueryStore]）。**ViewModel が1つ持つ。**
 *
 * 入力欄はここを直接描くので、打った文字が同じフレームのうちに読める。画面の `remember` に置くと、
 * タブを離れたときに言葉だけが消えて、残った範囲と結果の上に空の入力欄が出る。
 */
internal class ComposeExploreQuery : ExploreQueryStore {
    override var text: String by mutableStateOf("")
        private set

    override fun set(text: String) {
        this.text = text
    }

    override fun clear() {
        text = ""
    }
}
