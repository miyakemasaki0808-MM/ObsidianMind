package com.example.newproject.domain

import com.example.newproject.model.state.SectionChatProblem
import com.example.newproject.model.state.SectionChatState

/** 部分要約の4状態。見出しの要約ボタンの記号を決める。 */
internal enum class SectionSummaryStatus {
    Idle,
    Working,
    Ready,
    Error
}

/**
 * 部分要約の状態から [SectionSummaryStatus] を導出する。
 *
 * 判定順に意味がある。エラーを最優先で拾い、次に生成中、最後に完了。
 * セッションが存在するのに要約も理由も無い場合は「これから要約が始まる」= Working とする。
 * **理由があるなら待っていない**ので Idle にする — 端末AIが使えないだけで「生成中」を出し続けない。
 */
internal fun sectionSummaryStatus(chat: SectionChatState?): SectionSummaryStatus = when {
    chat == null -> SectionSummaryStatus.Idle
    // **状態の説明は失敗として数えない。** 端末AIが使えないだけならインジケータは光らせない。
    chat.summaryProblem is SectionChatProblem.GenerationFailed -> SectionSummaryStatus.Error
    chat.isSummaryLoading -> SectionSummaryStatus.Working
    chat.summary != null -> SectionSummaryStatus.Ready
    chat.summaryProblem != null -> SectionSummaryStatus.Idle
    else -> SectionSummaryStatus.Working
}

/** 見出しの要約ボタンの記号。状態はボタンの上で静かに示し、浮く通知は作らない。 */
internal fun sectionSummaryEntrySymbol(status: SectionSummaryStatus): String = when (status) {
    SectionSummaryStatus.Idle -> "💬"
    SectionSummaryStatus.Working -> "⏳"
    SectionSummaryStatus.Ready -> "✓"
    SectionSummaryStatus.Error -> "!"
}

/**
 * 見出しの要約ボタンの読み上げ名。**「この節を要約」を核にする** —
 * 自由に質問できる入口だと受け取られると、押しても答えが返らない。
 */
internal fun sectionSummaryEntryDescription(status: SectionSummaryStatus): String = when (status) {
    SectionSummaryStatus.Idle -> "この節を要約"
    SectionSummaryStatus.Working -> "この節を要約中。タップで開く"
    SectionSummaryStatus.Ready -> "この節の要約あり。タップで開く"
    SectionSummaryStatus.Error -> "この節の要約でエラー。タップで確認"
}
