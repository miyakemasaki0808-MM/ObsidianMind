package com.example.newproject.ui.screen

import androidx.compose.runtime.mutableStateMapOf
import com.example.newproject.model.MarginMemoDraftStore
import com.example.newproject.model.state.MarginMemoDraft

/**
 * 書きかけを Compose の状態で持つ置き場（→ [MarginMemoDraftStore]）。**ViewModel が1つ持つ。**
 *
 * 入力欄はここを直接描くので、打った文字が同じフレームのうちに読める。StateFlow を挟むと
 * 届くまでの間に古い値で描き直され、日本語の変換中に入力が崩れやすい。
 * 空になった組は外す — 開いたノートの数だけ溜めない。
 */
internal class ComposeMarginMemoDrafts : MarginMemoDraftStore {
    private val drafts = mutableStateMapOf<String, MarginMemoDraft>()

    override fun draft(noteKey: String): MarginMemoDraft = drafts[noteKey] ?: MarginMemoDraft()

    override fun update(noteKey: String, transform: (MarginMemoDraft) -> MarginMemoDraft) {
        val next = transform(draft(noteKey))
        if (next.isEmpty) drafts.remove(noteKey) else drafts[noteKey] = next
    }

    override fun clear() {
        drafts.clear()
    }
}
