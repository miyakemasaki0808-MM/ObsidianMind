package com.example.newproject

import com.example.newproject.model.MarginMemoDraftStore
import com.example.newproject.model.state.MarginMemoDraft

/**
 * 書きかけの置き場のテスト用。**本番の Compose 版と同じく、空になった組は外す。**
 * 外し方が違うと、空の組が残る・残らないで照合の結果が変わって見える。
 */
internal class InMemoryMarginMemoDrafts : MarginMemoDraftStore {
    val drafts = mutableMapOf<String, MarginMemoDraft>()

    override fun draft(noteKey: String): MarginMemoDraft = drafts[noteKey] ?: MarginMemoDraft()

    override fun update(noteKey: String, transform: (MarginMemoDraft) -> MarginMemoDraft) {
        val next = transform(draft(noteKey))
        if (next.isEmpty) drafts.remove(noteKey) else drafts[noteKey] = next
    }

    override fun clear() {
        drafts.clear()
    }
}
