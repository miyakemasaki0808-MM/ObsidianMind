package com.example.newproject.model.state

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

// セクション単位の部分要約。null のときシートは閉じている。
data class SectionChatState(
    val sectionTitle: String,
    val sectionContext: String,     // LLM に渡す本文（表示はしない）
    val summary: String? = null,
    val isSummaryLoading: Boolean = false,
    /** 要約が出せなかった理由。要約エリアへ出す。 */
    val summaryProblem: SectionChatProblem? = null
)
