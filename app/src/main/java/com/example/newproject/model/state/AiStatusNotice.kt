package com.example.newproject.model.state

/**
 * AI状態をユーザーへ説明する1件ぶんの内容。**UIはこれだけを見て描く。**
 *
 * `ui` は `ai` を import できない（`PackageDependencyTest` が固定）ので、
 * 表示用の型は葉の `model` に置き、`AiAvailability` からの変換は `domain` が持つ。
 */
data class AiStatusNotice(
    val message: String,
    val action: AiNoticeAction,
    /**
     * **あとで状況が変わりうるか。** false は恒久非対応（AICoreが無い）だけ。
     *
     * **[action] と混同しない。** あちらは「この説明に添えるボタン」で、こちらは
     * 「機能の入口そのものを閉じてよいか」である。DL実行中は**説明にCTAを出さない**
     * （押しても始まるものが無い）が、**入口は開けておく必要がある** — 閉じると
     * DL完了後に押し直せず、同じノートでその機能が二度と使えなくなる。
     * 実際に両者を `action == None` の1つで判定して、その不具合を作った。
     */
    val canTryAgainLater: Boolean
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
