package com.example.newproject.ui

import com.example.newproject.model.state.TitleSearchState

// ---------------------------------------------------------------------------
// Explore のタイトルのモードで、入力欄の下に何を出すか（→ docs/dev/features/ai_picker.md §4）。
// UIに条件を書かないため純関数に切り出す。
// ---------------------------------------------------------------------------

internal enum class TitleBody { History, Scanning, Failed, NoMatch, Matches }

/**
 * 打っていなければ今日読んだノート。**打った直後に結果が届くまでは前の表示のままにする** —
 * 最初の1文字で `Idle` のまま読み込み中を挟むと、結果の前に表示が一瞬跳ねる。
 */
internal fun titleBodyFor(query: String, state: TitleSearchState): TitleBody = when {
    query.isBlank() -> TitleBody.History
    state is TitleSearchState.Scanning -> TitleBody.Scanning
    state is TitleSearchState.Error -> TitleBody.Failed
    state is TitleSearchState.Matches ->
        if (state.matches.isEmpty()) TitleBody.NoMatch else TitleBody.Matches
    else -> TitleBody.History
}

/** 同じ名前のノートに添える場所。Vault の直下のノートは場所が空文字で来る。 */
internal fun titleMatchLocation(folderPath: String?): String? = folderPath?.ifEmpty { "ルート直下" }
