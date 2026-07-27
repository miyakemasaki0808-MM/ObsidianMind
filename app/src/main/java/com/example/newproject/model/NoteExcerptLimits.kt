package com.example.newproject.model

/**
 * 用途別のノート本文抜粋上限（UTF-16文字数）。
 *
 * プロンプト全体の入力予算やトークン上限ではない。タイトル・指示文・候補一覧などは
 * この上限に含まれず、実トークン数の調整は実機計測を伴う別判断とする。
 */
object NoteExcerptLimits {
    const val SUMMARY = 1200
    const val ANNOTATION = 1500
    const val RELATED = 600
    const val SECTION = 1500
    const val QUIZ = 1200

    const val ABRIDGED_NOTICE =
        "The following content is an abridged excerpt composed of the note outline, beginning, " +
            "and ending; omitted parts are marked with (omitted). Do not assume it is continuous or complete."
}
