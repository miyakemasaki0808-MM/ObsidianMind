package com.example.newproject.model

/**
 * 冊子の1ページ。**本文は持たない。**
 *
 * 束は10枚あるが、本文は最大1MBまで許容しているので10枚ぶんを抱えられない。
 * 保持するのは参照・タイトルと、**抽出後の1行だけ**（→ features/booklet_mode.md 判断4・判断7）。
 */
data class BookletEntry(
    val ref: DocumentRef,
    val title: String,
    val cover: BookletCover = BookletCover.Loading
)

/**
 * 扉（代表文1行）の状態。
 *
 * **「読み込み中」と「失敗」を分ける。** 束を作った後にノートが削除・改名されることがあり、
 * そのときは**そのページだけ**を失敗として見せる（束は作り直さない → §10 の境界条件）。
 * 分けないと、消えたノートのページが永久に読み込み中のまま残る。
 */
sealed interface BookletCover {
    data object Loading : BookletCover

    /** [line] は必ず非空。本文から選べなければタイトルが入る（→ [com.example.newproject.domain.selectCoverLine]）。 */
    data class Ready(val line: String) : BookletCover

    data object Failed : BookletCover
}
