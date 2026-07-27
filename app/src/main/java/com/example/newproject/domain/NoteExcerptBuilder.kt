package com.example.newproject.domain

import com.example.newproject.model.NoteExcerpt

/**
 * AI入力用の本文を用途別予算へ収める。
 *
 * この土台の導入時点では、既存の `String.take()` と同じ先頭切り出しを行う。
 * 意味境界を考慮した抽出は、全呼び出しをこの関数へ移した後に差し替える。
 */
fun buildNoteExcerpt(content: String, budget: Int): NoteExcerpt {
    require(budget >= 0) { "budget must be non-negative" }
    return NoteExcerpt(
        text = content.take(budget),
        isAbridged = content.length > budget
    )
}
