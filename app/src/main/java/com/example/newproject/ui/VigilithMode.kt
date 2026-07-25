package com.example.newproject.ui

import com.example.newproject.DistillState
import com.example.newproject.ReadingTraceCard

/** アプリ内に常駐するVigilithの3つの基本姿勢。 */
internal enum class VigilithMode {
    Idle,
    Distilling,
    Messenger
}

/**
 * Vigilithの表示判断。
 *
 * ViewModelへキャラクター専用状態を増やさず、既存の業務状態だけから導出する。
 * これによりVigilith自身が記憶や処理状態を持つ「第二の真実」になることを避ける。
 */
internal data class VigilithPresentation(
    val isVisible: Boolean,
    val mode: VigilithMode
)

internal fun resolveVigilithPresentation(
    currentRoute: String?,
    distillState: DistillState,
    readingTraceCard: ReadingTraceCard?,
    isBlockingOverlayVisible: Boolean = false
): VigilithPresentation {
    val isTabRoute = AppDestination.entries.any { it.route == currentRoute }
    if (!isTabRoute || isBlockingOverlayVisible) {
        return VigilithPresentation(isVisible = false, mode = VigilithMode.Idle)
    }

    val activelyDistilling = when (distillState) {
        is DistillState.Analyzing,
        is DistillState.Downloading,
        is DistillState.Saving -> true
        else -> false
    }
    val pointingAtCandidates =
        currentRoute == AppDestination.Ai.route && distillState is DistillState.Candidates

    val mode = when {
        activelyDistilling || pointingAtCandidates -> VigilithMode.Distilling
        currentRoute == AppDestination.Note.route &&
            readingTraceCard != null &&
            !readingTraceCard.isDismissed -> VigilithMode.Messenger
        else -> VigilithMode.Idle
    }
    return VigilithPresentation(isVisible = true, mode = mode)
}
