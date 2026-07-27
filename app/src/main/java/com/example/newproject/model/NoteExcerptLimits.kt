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
        "The following content has been compacted and may combine a note outline with excerpts " +
            "from the beginning and ending. A (omitted) marker denotes a skipped span when present. " +
            "Do not assume the content is continuous or complete."

    /** 注意書きと本文をつなぐ改行まで含め、抜粋予算と描画で共有する。 */
    const val ABRIDGED_NOTICE_PREFIX = ABRIDGED_NOTICE + "\n"
}
