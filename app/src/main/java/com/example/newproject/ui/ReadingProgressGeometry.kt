package com.example.newproject.ui

// ---------------------------------------------------------------------------
// 読書痕跡の到達率に使う、ブロック内の可視量の計算。
//
// 到達率をMarkdownブロック数だけで測ると、長大な段落・コードブロック・表が
// 1ブロックとして描画されたとき、冒頭しか見えていなくても「1/1＝100%」になり
// 「最後まで読んでいます」と誤って断定してしまう。最終可視ブロックが
// どこまで見えているかを分数として足すことで、この誤りを防ぐ。
//
// LazyList の座標（Int）だけを受け取る純関数にしてあるので、Compose を起動せず
// JVMユニットテストで検証できる。
// ---------------------------------------------------------------------------

/** 可視割合の量子化ステップ数。5%刻み（1/20）。 */
internal const val READING_FRACTION_STEPS = 20

/**
 * 最終可視ブロックのうち、viewport に入っている割合（0f〜1f）。
 *
 * @param itemOffset ブロック先頭の位置（viewport 原点基準。上へスクロールアウトすると負）
 * @param itemSize ブロックの高さ
 * @param viewportEndOffset viewport の下端（contentPadding を除いた実表示域）
 */
internal fun visibleFractionOfBlock(
    itemOffset: Int,
    itemSize: Int,
    viewportEndOffset: Int
): Float {
    // 高さが測れていないブロックは割合を出せない。到達率を不当に下げないよう全可視扱いにする。
    if (itemSize <= 0) return 1f
    val visibleBottom = (viewportEndOffset - itemOffset).coerceIn(0, itemSize)
    return visibleBottom.toFloat() / itemSize
}

/**
 * 可視割合を [READING_FRACTION_STEPS] 段階へ丸める。
 *
 * スクロール中は割合が毎フレーム変わるため、そのまま流すと報告が止まらない。
 * 粗い段階へ落としてから distinctUntilChanged に掛けることで報告数を抑える。
 * 切り捨てなので、末端まで見えたときだけ最大段階（＝1f）になる。
 */
internal fun quantizeReadingFraction(fraction: Float): Int =
    (fraction.coerceIn(0f, 1f) * READING_FRACTION_STEPS).toInt()
