package com.example.newproject.domain

import com.example.newproject.model.state.SectionChatProblem
import com.example.newproject.model.state.SectionChatState

/** 部分要約の4状態。入口の表示と全画面の最小AIインジケータが共用する。 */
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
