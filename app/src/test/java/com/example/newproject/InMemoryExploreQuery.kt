package com.example.newproject

import com.example.newproject.model.ExploreQueryStore

/** Explore の打った言葉の置き場のテスト用。本番は Compose の状態で持つ（`ComposeExploreQuery`）。 */
internal class InMemoryExploreQuery(override var text: String = "") : ExploreQueryStore {
    override fun set(text: String) {
        this.text = text
    }

    override fun clear() {
        text = ""
    }
}
