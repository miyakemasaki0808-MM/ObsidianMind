package com.example.newproject.domain

import com.example.newproject.model.ReunionKind
import com.example.newproject.model.state.ReadingTraceCard
import com.example.newproject.model.state.SummaryState

/** 再会カードの枠に出すもの（→ features/reunion_card.md 判断6「枠の中身は1つの純関数が決める」）。 */
internal sealed interface ReunionSlot {
    /** 枠を出さない。カードは見出しの1文だけになる。 */
    data object Hidden : ReunionSlot

    /** 枠の中身をまだ作っている。 */
    data object Waiting : ReunionSlot

    data class Shown(val text: String, val kind: ReunionKind) : ReunionSlot
}

/**
 * 枠の中身を決める。カードの状態と、ノートの要約の状態の2つから決まる。
 *
 * **途中まで読んだノートはノートの要約へ倒さない。** 知りたいのは読み進めたところの前後で、
 * 前後の要約が出せないときは枠ごと出さない。**最後まで読んだノートだけ**が要約の先頭1文を既定にする。
 */
internal fun reunionSlot(card: ReadingTraceCard, summary: SummaryState): ReunionSlot {
    val item = card.aiSummary?.takeIf { it.isNotBlank() }
    val kind = card.aiSummaryKind
    return when {
        item != null && kind != null -> ReunionSlot.Shown(item, kind)
        // **選別や前後の要約が決まるまで、ノートの要約を先に出さない。**
        // 先に出して後から差し替えると、読み始めた枠の中身が入れ替わる。
        card.isSummaryLoading -> ReunionSlot.Waiting
        card.lastProgressPercent < 100 -> ReunionSlot.Hidden
        else -> noteSummarySlot(summary)
    }
}

private fun noteSummarySlot(summary: SummaryState): ReunionSlot = when (summary) {
    is SummaryState.Success ->
        firstSentenceOf(summary.summary)
            ?.let { ReunionSlot.Shown(it, ReunionKind.Overview) }
            ?: ReunionSlot.Hidden
    // 要約はノートを開いた契機で必ず走るので、始まる前（Idle）も待ちとして扱う。
    SummaryState.Idle, SummaryState.Loading, is SummaryState.Downloading -> ReunionSlot.Waiting
    SummaryState.AiUnavailable, is SummaryState.Error -> ReunionSlot.Hidden
}

/**
 * 要約の先頭1文。**全文をカードへ載せない** — カードは1文で伝える役目で、全文は✨タブにある
 * （→ features/reunion_card.md 判断1・判断6）。括弧の内側では切らない（[splitIntoSentences]）。
 */
internal fun firstSentenceOf(text: String): String? =
    splitIntoSentences(text.trim()).firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
