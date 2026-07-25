package com.example.newproject.ui

import com.example.newproject.DistillState
import com.example.newproject.ReadingTraceCard
import com.example.newproject.SummaryState

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
    if (!isTabRoute || isBlockingOverlayVisible) {
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
