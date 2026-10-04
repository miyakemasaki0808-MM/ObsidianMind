package com.example.newproject.model.state

import com.example.newproject.model.SectionRef

/**
 * 部分要約で「いま出せない」ことの説明。
 *
 * **失敗と状態を型で分ける。** 前者は実際に落ちたもの（赤で出す）、後者は端末AIの状態で
 * まだ何も失敗していない（通常色で出す）。文字列1本で兼ねると、色も導線も選べない。
 */
sealed class SectionChatProblem {
    /** 生成そのものが失敗した（タイムアウト・出力打ち切り・例外）。 */
    data class GenerationFailed(val message: String) : SectionChatProblem()

    /** 端末AIが使えない。文言と導線は [AiStatusNotice] が持つ。 */
    data class AiStatus(val notice: AiStatusNotice) : SectionChatProblem()
}

/**
 * 1節分の部分要約（→ features/margin_pane.md §5.8）。
 *
 * [section] は今の解析の中でだけ意味を持つ。**本文を解析し直すかノートを替えると全部捨てる**ので、
 * [requestId] が一致すれば、ノート・本文の版・節の組も一致する。
 */
data class SectionSummary(
    val section: SectionRef,
    /** 生成の要求ごとに振る番号。結果はこの番号が一致する要約にだけ書く。 */
    val requestId: Long,
    /** プロンプトへ渡す見出し名。見出しより前と見出しの無いノートではノートの題名。 */
    val sectionTitle: String,
    val sectionContext: String,     // LLM に渡す本文（表示はしない）
    val summary: String? = null,
    val isSummaryLoading: Boolean = false,
    /** 要約が出せなかった理由。要約の欄へ出す。 */
    val summaryProblem: SectionChatProblem? = null
)

/** ノートを開いている間の部分要約。**最近作った順**に持つ（上限は `SECTION_SUMMARY_LIMIT`）。 */
data class SectionChatState(
    val summaries: List<SectionSummary> = emptyList()
)
