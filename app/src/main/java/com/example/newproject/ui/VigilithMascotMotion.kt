package com.example.newproject.ui

import kotlin.math.abs

/**
 * Vigilithの1フレーム分の見え方。
 *
 * Compose型を含めず、Animator倍率や描画環境に依存しない純粋計算としてテストする。
 */
internal data class VigilithMascotMotion(
    val bodyLiftFraction: Float,
    val lensGlowAlpha: Float,
    val coreGlowAlpha: Float,
    val summaryGuideFraction: Float,
    val wingCloseFraction: Float,
    val candidateGatherFraction: Float,
    val underlineFraction: Float,
    val messengerGlowAlpha: Float
)

internal fun vigilithMascotMotion(
    presentation: VigilithPresentation,
    loopFraction: Float,
    entranceFraction: Float
): VigilithMascotMotion {
    val loop = loopFraction.coerceIn(0f, 1f)
    val entrance = entranceFraction.coerceIn(0f, 1f)
    val pulse = 1f - abs(2f * loop - 1f)

    return when (presentation.mode) {
        VigilithMode.Idle -> VigilithMascotMotion(
            bodyLiftFraction = 0f,
            lensGlowAlpha = 0.08f + 0.12f * pulse,
            coreGlowAlpha = 0.05f + 0.08f * pulse,
            summaryGuideFraction = 0f,
            wingCloseFraction = 0f,
            candidateGatherFraction = 0f,
            underlineFraction = 0f,
            messengerGlowAlpha = 0f
        )
        VigilithMode.Summarizing -> VigilithMascotMotion(
            bodyLiftFraction = 0f,
            lensGlowAlpha = 0.18f + 0.20f * pulse,
            coreGlowAlpha = 0.06f + 0.06f * pulse,
            summaryGuideFraction = 0.55f + 0.45f * pulse,
            wingCloseFraction = 0f,
            candidateGatherFraction = 0f,
            underlineFraction = 0f,
            messengerGlowAlpha = 0f
        )
        VigilithMode.Distilling -> VigilithMascotMotion(
            bodyLiftFraction = (1f - entrance) * 0.35f,
            lensGlowAlpha = 0.24f + 0.22f * pulse,
            coreGlowAlpha = 0.20f + 0.30f * pulse,
            summaryGuideFraction = 0f,
            wingCloseFraction = when (presentation.distillPhase) {
                VigilithDistillPhase.FindingCandidates -> 0.25f + 0.20f * pulse
                VigilithDistillPhase.HoldingCandidate,
                VigilithDistillPhase.Underlining,
                null -> 1f
            },
            candidateGatherFraction = when (presentation.distillPhase) {
                VigilithDistillPhase.FindingCandidates -> pulse
                else -> 1f
            },
            underlineFraction = if (
                presentation.distillPhase == VigilithDistillPhase.Underlining
            ) {
                entrance
            } else {
                0f
            },
            messengerGlowAlpha = 0f
        )
        VigilithMode.Messenger -> VigilithMascotMotion(
            bodyLiftFraction = 1f - entrance,
            lensGlowAlpha = 0.20f + 0.10f * pulse,
            coreGlowAlpha = 0.10f,
            summaryGuideFraction = 0f,
            wingCloseFraction = 0f,
            candidateGatherFraction = 0f,
            underlineFraction = 0f,
            messengerGlowAlpha = messengerFlash(entrance)
        )
    }
}

/** Messenger登場時に一度だけ立ち上がり、静止後は0へ戻る光。 */
private fun messengerFlash(entrance: Float): Float = when {
    entrance <= 0.10f -> 0f
    entrance < 0.32f -> (entrance - 0.10f) / 0.22f
    entrance < 0.62f -> 1f - (entrance - 0.32f) / 0.30f
    else -> 0f
}.coerceIn(0f, 1f)
