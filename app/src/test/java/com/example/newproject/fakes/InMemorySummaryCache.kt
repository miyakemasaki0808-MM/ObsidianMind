package com.example.newproject.fakes

import com.example.newproject.domain.SummaryCache

/**
 * テスト用の [SummaryCache]。鍵（完成したプロンプト）をそのまま持つ。
 *
 * 既存の要約テストもこれを通す。**保存を素通りする代役にすると、
 * 同じノートを2度開いて2回生成されることを前提にしたテストが残っても気づけない。**
 */
class InMemorySummaryCache : SummaryCache {

    val entries = linkedMapOf<String, String>()

    /** [find] が呼ばれた回数。「引かないこと」を確かめるために要る。 */
    var findCalls = 0
        private set

    override suspend fun find(prompt: String): String? {
        findCalls++
        return entries[prompt]
    }

    override suspend fun save(prompt: String, summary: String) {
        entries[prompt] = summary
    }
}
