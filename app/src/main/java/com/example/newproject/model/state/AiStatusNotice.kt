package com.example.newproject.model.state

/**
 * AI状態をユーザーへ説明する1件ぶんの内容。**UIはこれだけを見て描く。**
 *
 * `ui` は `ai` を import できない（`PackageDependencyTest` が固定）ので、
 * 表示用の型は葉の `model` に置き、`AiAvailability` からの変換は `domain` が持つ。
 */
data class AiStatusNotice(
    val message: String,
    val action: AiNoticeAction
)

/**
 * 説明に添える導線。
 *
 * **`offersRetry` / `offersDownload` の Boolean 2つにしない。** 次の行動は3つあり、
 * Boolean 2つだと4通りのうち「両方true」が意味を持たない値として残る。
 */
sealed class AiNoticeAction {
    /** 閉じるだけ。何度試しても同じ答えが返るので、押させる先が無い。 */
    object None : AiNoticeAction()
    /** 再試行に意味がある（次は状態を取れるかもしれない）。 */
    object Retry : AiNoticeAction()
    /** モデルDLを促す。 */
    object Download : AiNoticeAction()
}
