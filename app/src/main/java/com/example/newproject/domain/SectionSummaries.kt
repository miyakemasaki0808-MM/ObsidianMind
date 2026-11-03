package com.example.newproject.domain

import com.example.newproject.model.SectionRef
import com.example.newproject.model.state.SectionChatProblem
import com.example.newproject.model.state.SectionChatState
import com.example.newproject.model.state.SectionSummary

/** 全節の要約が1回に作る節の数（根拠の実測 → system/background_ai_ux.md §6）。超えた節は押し直すと作る。 */
internal const val SECTION_BATCH_LIMIT = 15

/** AICore に断られてから同じ節をやり直すまで。実測で足りた待ち時間であって、時間窓の長さではない。 */
internal const val SECTION_BATCH_REFUSAL_WAIT_MS = 10_000L

/** 同じ節で続けて断られたら全節を止める回数。 */
internal const val SECTION_BATCH_REFUSAL_LIMIT = 3

/** 続けて失敗したら全節を止める節の数。節によらない失敗（タイムアウトなど）を、節の数だけ繰り返さない。 */
internal const val SECTION_BATCH_FAILURE_LIMIT = 3

/** 全節の要約が走っているか。 */
internal val SectionChatState.isBatchRunning: Boolean get() = batch?.isRunning == true

/** 全節の生成中で、[section] の番がまだ来ていないか。面はボタンの代わりに「次に作ります」を出す。 */
internal fun SectionChatState.isQueued(section: SectionRef?): Boolean =
    section != null && isBatchRunning && summaryOf(section) == null && batch?.targets?.contains(section) == true

/** [section] を全節の列で次に作ることにした。**出せなかった理由を下げ、対象でなかった節は対象へ足す。** */
internal fun SectionChatState.withQueued(section: SectionRef): SectionChatState =
    without(section).let { chat ->
        val running = chat.batch ?: return@let chat
        if (section in running.targets) chat else chat.copy(batch = running.copy(targets = running.targets + section))
    }

/** 全節の生成係が止まった。 */
internal fun SectionChatState.withBatchStopped(): SectionChatState =
    copy(batch = batch?.copy(isRunning = false))

/** 全節の要約で作り直す節か。持っていない節と、出せなかった節。**生成中の節は最後まで作る。** */
internal fun SectionSummary?.needsBatchGeneration(): Boolean =
    this == null || (summary == null && !isSummaryLoading)

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
 * 面を出さずに頼んだ要求に、端末AIが使えない理由が届いていて、まだ見せていないか（→ features/margin_pane.md §5.4）。
 * 理由は失敗として数えず記号に出ないので、要約の行で見せるまで印を残す。
 */
internal fun SectionSummary.hasUnshownNotice(): Boolean =
    noticePending && summaryProblem is SectionChatProblem.AiStatus

/**
 * [started] を最も新しい要約として足す。
 *
 * - 同じ節の要約は置き換える（作り直し）
 * - **生成中の別の節は取り除く。** 生成は一度に1本で、取り消された節はボタンに戻る
 */
internal fun SectionChatState.withStarted(started: SectionSummary): SectionChatState =
    copy(summaries = listOf(started) + summaries.filterNot { it.section == started.section || it.isSummaryLoading })

/** [requestId] の要約だけを書き換える。**取り消された要求の結果は、どこにも書かない。** */
internal fun SectionChatState.updated(
    requestId: Long,
    transform: (SectionSummary) -> SectionSummary
): SectionChatState =
    copy(summaries = summaries.map { if (it.requestId == requestId) transform(it) else it })

/** [section] の要約を取り除く。 */
internal fun SectionChatState.without(section: SectionRef): SectionChatState =
    copy(summaries = summaries.filterNot { it.section == section })
