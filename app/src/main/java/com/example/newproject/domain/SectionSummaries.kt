package com.example.newproject.domain

import com.example.newproject.model.SectionRef
import com.example.newproject.model.state.SectionChatProblem
import com.example.newproject.model.state.SectionChatState
import com.example.newproject.model.state.SectionSummary

/** 部分要約を持つ節の数（→ features/margin_pane.md §5.8）。4節目を作ると最も古いものから消える。 */
internal const val SECTION_SUMMARY_LIMIT = 3

/** [section] の要約。持っていなければ null。 */
internal fun SectionChatState.summaryOf(section: SectionRef?): SectionSummary? =
    section?.let { target -> summaries.firstOrNull { it.section == target } }

/**
 * 頼み直されたとき、持っている要約をそのまま見せるか（→ features/margin_pane.md §5.8）。見せないなら作り直す。
 *
 * **後で使えるようになる説明（モデルの準備待ち・一時的に使えない）だけは確かめ直す。** 準備待ちの説明には
 * 再試行のボタンが無いので、見せ続けると、モデルが揃った後も同じ節を要約できない。
 * 生成中・完成・生成の失敗（再試行のボタンがある）・非対応（確かめ直しても変わらない）は、そのまま見せる。
 */
internal fun SectionSummary.isShownAsIsOnRequest(): Boolean {
    val notice = (summaryProblem as? SectionChatProblem.AiStatus)?.notice ?: return true
    return isSummaryLoading || summary != null || !notice.canTryAgainLater
}

/**
 * [started] を最も新しい要約として足す。
 *
 * - 同じ節の要約は置き換える（作り直し）
 * - **生成中の別の節は取り除く。** 生成は一度に1本で、取り消された節はボタンに戻る
 * - 最近作った [SECTION_SUMMARY_LIMIT] 節分を超えたら、最も古いものから落とす
 */
internal fun SectionChatState.withStarted(started: SectionSummary): SectionChatState =
    SectionChatState(
        (listOf(started) + summaries.filterNot { it.section == started.section || it.isSummaryLoading })
            .take(SECTION_SUMMARY_LIMIT)
    )

/** [requestId] の要約だけを書き換える。**取り消された要求の結果は、どこにも書かない。** */
internal fun SectionChatState.updated(
    requestId: Long,
    transform: (SectionSummary) -> SectionSummary
): SectionChatState =
    copy(summaries = summaries.map { if (it.requestId == requestId) transform(it) else it })

/** [section] の要約を取り除く。 */
internal fun SectionChatState.without(section: SectionRef): SectionChatState =
    copy(summaries = summaries.filterNot { it.section == section })
