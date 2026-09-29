package com.example.newproject.ui.vigilith

import com.example.newproject.ui.AppDestination
import com.example.newproject.ui.screen.NoteReaderTab
import com.example.newproject.model.state.DistillState
import com.example.newproject.model.state.ReadingTraceCard
import com.example.newproject.model.state.SectionChatProblem
import com.example.newproject.model.state.SectionChatState
import com.example.newproject.model.state.SummaryState

/**
 * AI操作の4状態。Vigilith本体の状態バッジと全画面の最小AIインジケータで共用する。
 *
 * 以前は同じ4状態が `NoteReaderTab.SectionFabStatus`（Idle/Loading/Ready/Error）としても
 * 定義され、導出ロジックごと2箇所に重複していた。型と導出の両方をここへ集約する。
 */
internal enum class VigilithActionStatus {
    Idle,
    Working,
    Ready,
    Error
}

/**
 * 部分要約の状態から [VigilithActionStatus] を導出する。
 *
 * 判定順に意味がある。エラーを最優先で拾い、次に生成中、最後に完了。
 * チャットが存在するのに要約も理由も無い場合は「これから要約が始まる」= Working とする。
 * **理由があるなら待っていない**ので Idle にする。
 */
internal fun sectionChatStatus(chat: SectionChatState?): VigilithActionStatus = when {
    chat == null -> VigilithActionStatus.Idle
    // **状態の説明は失敗として数えない。** 端末AIが使えないだけならインジケータは光らせない。
    chat.summaryProblem is SectionChatProblem.GenerationFailed -> VigilithActionStatus.Error
    chat.isSummaryLoading -> VigilithActionStatus.Working
    chat.summary != null -> VigilithActionStatus.Ready
    // **走っていないのに Working にしない。** 端末AIが使えず要約も無いときに
    // 「AI生成中」のスピナーが回り続けていた（上のコメントと実装が食い違っていた）。
    chat.summaryProblem != null -> VigilithActionStatus.Idle
    else -> VigilithActionStatus.Working
}

/** アプリ内に常駐するVigilithの表示状態。 */
internal enum class VigilithMode {
    Idle,
    Summarizing,
    Distilling,
    Messenger
}

/** 蒸留の「集める・選び取る・確定する」を混ぜずに描き分ける工程。 */
internal enum class VigilithDistillPhase {
    FindingCandidates,
    HoldingCandidate,
    Underlining
}

/**
 * Vigilithの表示判断。
 *
 * ViewModelへキャラクター専用状態を増やさず、既存の業務状態だけから導出する。
 * これによりVigilith自身が記憶や処理状態を持つ「第二の真実」になることを避ける。
 */
internal data class VigilithPresentation(
    val isVisible: Boolean,
    val mode: VigilithMode,
    val distillPhase: VigilithDistillPhase? = null
)

internal fun resolveVigilithPresentation(
    currentRoute: String?,
    distillState: DistillState,
    readingTraceCard: ReadingTraceCard?,
    summaryState: SummaryState = SummaryState.Idle,
    isSectionSummaryLoading: Boolean = false,
    isBlockingOverlayVisible: Boolean = false
): VigilithPresentation {
    val isTabRoute = AppDestination.entries.any { it.route == currentRoute }
    // **蒸留の調整シートも遮るオーバーレイである。** 第二の状態を足さず、
    // 候補状態が持つシート情報から導出する（放置すると `HoldingCandidate` の姿勢のまま
    // モーダルシートと重なる）。
    val isDistillRangeSheetVisible =
        (distillState as? DistillState.Candidates)?.rangeSheetCandidateId != null
    if (!isTabRoute || isBlockingOverlayVisible || isDistillRangeSheetVisible) {
        return VigilithPresentation(isVisible = false, mode = VigilithMode.Idle)
    }

    val distillPhase = when {
        distillState is DistillState.Analyzing ->
            VigilithDistillPhase.FindingCandidates
        currentRoute == AppDestination.Ai.route && distillState is DistillState.Candidates ->
            VigilithDistillPhase.HoldingCandidate
        distillState is DistillState.Saving ->
            VigilithDistillPhase.Underlining
        else -> null
    }
    val backgroundSummaryLoading =
        summaryState is SummaryState.Loading || summaryState is SummaryState.Downloading
    val hasVisibleTrace =
        currentRoute == AppDestination.Note.route &&
            readingTraceCard != null &&
            !readingTraceCard.isDismissed

    val mode = when {
        distillPhase != null -> VigilithMode.Distilling
        isSectionSummaryLoading -> VigilithMode.Summarizing
        hasVisibleTrace -> VigilithMode.Messenger
        backgroundSummaryLoading -> VigilithMode.Summarizing
        else -> VigilithMode.Idle
    }
    return VigilithPresentation(
        isVisible = true,
        mode = mode,
        distillPhase = distillPhase
    )
}
