package com.example.newproject.model

import com.example.newproject.model.state.BookletBundle
import com.example.newproject.model.state.RelatedNotesState
import com.example.newproject.model.state.WeaveBlockedReason
import com.example.newproject.model.state.WeaveState

/**
 * 編む束の種 — **冊子へ入る直前に開いていたノート。**
 *
 * 表示中ページのノートを種にすると推薦が未計算なので Nano を新規に回すことになり、
 * 「冊子では新しいAIを始めない」（→ features/booklet_mode.md 判断3）をやり直すことになる。
 *
 * [ref] は種自身を束から外すためだけに使う。`NoteState.Success.targetUri` が空の経路では
 * 空の参照が入るが、**実在するノートの参照とは決して一致しない**ので特別扱いは要らない。
 */
data class BookletSeed(val ref: DocumentRef, val title: String)

/**
 * 済んだAI推薦から編む束を作る。**追加のNano呼び出しゼロ・追加I/Oゼロ。**
 *
 * ノートを開いた時点で `relatedNotesState` は既に答えを持っているので、ここは並べ替えるだけである。
 *
 * ## 並び
 *
 * **AI推薦を先に、決定的な関連のうち未リンクを次に、wikilink済みを最後へ。**
 * 既に繋がっているノートは「再会」になりにくいので後ろへ回す
 * （[com.example.newproject.controller.RemarkController] の候補選定と同じ並べ方）。
 *
 * ## 水増ししない
 *
 * **薄ければ薄いまま実際の枚数を出す。** 後半をランダムで埋めると
 * 「関係ないノイズ」ではなく「**関係あると思って読むノイズ**」になり、
 * 何枚目で関連が切れたのか分からなくなる。
 *
 * 重複は [DocumentRef] で畳む — 同じノートがAI経路と決定的経路の両方から来る。
 */
fun buildWeaveState(
    seed: BookletSeed?,
    related: RelatedNotesState,
    size: Int,
    bundleId: Long
): WeaveState {
    if (seed == null || seed.title.isBlank()) return WeaveState.NoSeed
    return when (related) {
        // **Idle も「探している最中」に畳む。** 冊子へ入る時点でノートが開いているなら
        // 関連ノートは必ず走っているので、Idle は取り違えではなく開始直後の一瞬である。
        RelatedNotesState.Idle, RelatedNotesState.Loading ->
            WeaveState.Blocked(seed.title, WeaveBlockedReason.Pending)
        is RelatedNotesState.Error ->
            WeaveState.Blocked(seed.title, WeaveBlockedReason.Failed)
        is RelatedNotesState.Success -> {
            val entries = weaveEntries(seed, related, size)
            if (entries.isEmpty()) WeaveState.Blocked(seed.title, WeaveBlockedReason.Empty)
            else WeaveState.Ready(seed.title, BookletBundle(entries = entries, bundleId = bundleId))
        }
    }
}

private fun weaveEntries(
    seed: BookletSeed,
    related: RelatedNotesState.Success,
    size: Int
): List<BookletEntry> =
    (
        related.aiNotes +
            related.relatedNotes.filterNot { it.isWikilinked } +
            related.relatedNotes.filter { it.isWikilinked }
        )
        .filter { it.ref != seed.ref && it.title.isNotBlank() }
        .distinctBy { it.ref }
        .take(size)
        .map { BookletEntry(ref = it.ref, title = it.title) }
