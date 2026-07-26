package com.example.newproject.ui.vigilith

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
    val haloAlpha: Float,
    val haloScale: Float,
    val titleAlpha: Float,
    val titleLiftFraction: Float
)

/**
 * 単一のタイムラインからVigilithの登場を組み立てる。
 *
 * 演出順は「ハロー → 黒曜石の全身 → 名称 → 読書画面へ溶ける」。
 * 回転・バウンド・大きな身振りを避け、寡黙な不寝番らしい静かなフォーカス動作に限定する。
 */
internal fun vigilithOpeningMotion(timeline: Float): VigilithOpeningMotion {
    val t = timeline.coerceIn(0f, 1f)
    val exit = 1f - t.fractionBetween(0.78f, 0.96f)
    val bodyReveal = t.fractionBetween(0.14f, 0.42f)
    val titleReveal = t.fractionBetween(0.34f, 0.58f)
    val haloReveal = t.fractionBetween(0.08f, 0.38f)

    return VigilithOpeningMotion(
        backdropAlpha = 1f - t.fractionBetween(0.76f, 1f),
        bodyAlpha = bodyReveal * exit,
        bodyScale = 0.94f + (0.06f * bodyReveal),
        bodyLiftFraction = 1f - bodyReveal,
        // 色側にAdaptive Icon背景と同じ実効アルファを持たせ、ここでは登退場だけを制御する。
        haloAlpha = haloReveal * exit,
        haloScale = 0.88f + (0.12f * haloReveal),
        titleAlpha = titleReveal * exit,
        titleLiftFraction = 1f - titleReveal
    )
}

// このファイル内の進捗計算専用。同パッケージへ公開すると
// VigilithMascotMotion 側で同名の別定義が生まれるため private に閉じる。
private fun Float.fractionBetween(start: Float, end: Float): Float =
    ((this - start) / (end - start)).coerceIn(0f, 1f)
