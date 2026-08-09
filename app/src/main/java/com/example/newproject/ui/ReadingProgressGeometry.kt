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

/**
 * 最終可視ブロックの進捗を報告してよいか。
 *
 * **寸法未確定の画像より後ろは報告しない。** 画像は寸法が取れるまで画面1枚ぶんの
 * プレースホルダで確保するが、**これは元画像の高さの上限ではない。**
 * 縦長画像なら実際は画面2〜3枚ぶんになり得るので、確保が足りない間は
 * **まだ読んでいない後続ブロックが「可視」に見える。**
 *
 * 最深到達点は後から下がらないため、この誤りは**サイドカーへ永続化される** —
 * 見た目が一瞬ずれるのとは重さが違う。したがって
 * **「測れていないものの向こう側は、見えたと言えない」**として報告そのものを止める。
 *
 * 測定が終われば [firstUnsettledImageIndex] が後ろへ動くか null になるので、報告は再開する。
 *
 * @param lastVisibleBlockIndex 最終可視ブロックの index
 * @param firstUnsettledImageIndex 寸法未確定の画像のうち最も手前の index。無ければ null
 */
internal fun shouldReportReadingProgress(
    lastVisibleBlockIndex: Int,
    firstUnsettledImageIndex: Int?
): Boolean = firstUnsettledImageIndex == null || lastVisibleBlockIndex < firstUnsettledImageIndex
