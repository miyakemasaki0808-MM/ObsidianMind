package com.example.newproject.ui

/**
 * Vigilith起動OPの1フレーム分の状態。
 *
 * ComposeやAndroid型を含めない純粋な値にして、区間境界と前後関係をJVMテストできるようにする。
 */
internal data class VigilithOpeningMotion(
    val backdropAlpha: Float,
    val bodyAlpha: Float,
    val bodyScale: Float,
    val bodyLiftFraction: Float,
    val eyeAlpha: Float,
    val eyeFocusScale: Float,
    val eyePulse: Float,
    val haloAlpha: Float,
    val haloScale: Float,
    val titleAlpha: Float,
    val titleLiftFraction: Float
)

/**
 * 単一のタイムラインからVigilithの登場を組み立てる。
 *
 * 演出順は「読書レンズ点灯 → 黒曜石の輪郭 → ハローと名称 → 読書画面へ溶ける」。
 * 回転・バウンド・大きな身振りを避け、寡黙な不寝番らしい静かなフォーカス動作に限定する。
 */
internal fun vigilithOpeningMotion(timeline: Float): VigilithOpeningMotion {
    val t = timeline.coerceIn(0f, 1f)
    val exit = 1f - t.fractionBetween(0.78f, 0.96f)
    val eyeReveal = t.fractionBetween(0.04f, 0.22f)
    val bodyReveal = t.fractionBetween(0.14f, 0.42f)
    val titleReveal = t.fractionBetween(0.34f, 0.58f)
    val haloReveal = t.fractionBetween(0.08f, 0.38f)

    return VigilithOpeningMotion(
        backdropAlpha = 1f - t.fractionBetween(0.76f, 1f),
        bodyAlpha = bodyReveal * exit,
        bodyScale = 0.94f + (0.06f * bodyReveal),
        bodyLiftFraction = 1f - bodyReveal,
        eyeAlpha = eyeReveal * exit,
        // 外へ開いた光が静かに絞られ、読書レンズとして焦点を結ぶ。
        eyeFocusScale = 1.28f - (0.28f * t.fractionBetween(0.04f, 0.30f)),
        // 焦点が合う瞬間だけ一度明るくなる。常時点滅させず、催促感を出さない。
        eyePulse = trianglePulse(t, start = 0.24f, peak = 0.38f, end = 0.56f),
        // 色側にAdaptive Icon背景と同じ実効アルファを持たせ、ここでは登退場だけを制御する。
        haloAlpha = haloReveal * exit,
        haloScale = 0.88f + (0.12f * haloReveal),
        titleAlpha = titleReveal * exit,
        titleLiftFraction = 1f - titleReveal
    )
}

private fun trianglePulse(value: Float, start: Float, peak: Float, end: Float): Float = when {
    value <= start || value >= end -> 0f
    value <= peak -> value.fractionBetween(start, peak)
    else -> 1f - value.fractionBetween(peak, end)
}

internal fun Float.fractionBetween(start: Float, end: Float): Float =
    ((this - start) / (end - start)).coerceIn(0f, 1f)
