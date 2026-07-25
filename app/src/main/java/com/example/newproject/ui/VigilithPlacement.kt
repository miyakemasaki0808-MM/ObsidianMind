package com.example.newproject.ui

/**
 * Vigilith の位置を、配置可能領域に対する 0..1 の割合で保持する。
 *
 * px の移動量そのものを保存すると Fold の開閉や回転で位置関係が崩れるため、
 * 左上を 0、右下を 1 とする相対位置にして画面変更後も同じ場所へ再配置する。
 */
internal data class VigilithPlacement(
    val horizontalFraction: Float = 1f,
    val verticalFraction: Float = 1f
)

internal data class VigilithPlacementBounds(
    val minX: Float,
    val maxX: Float,
    val minY: Float,
    val maxY: Float
)

internal data class VigilithPlacementOffset(
    val x: Float,
    val y: Float
)

/**
 * 画面下端から確保すべき高さを統合する。
 *
 * 通常はシステム下端＋Navigation UI、Snackbar表示中はさらにその高さ、
 * IME表示中はキーボード上端を優先する。SnackbarとIMEを加算しないため、
 * 同時表示でも必要以上に上へ押し上げない。
 */
internal fun calculateVigilithBottomReserved(
    safeBottomPx: Float,
    navigationClearancePx: Float,
    isSnackbarVisible: Boolean,
    snackbarClearancePx: Float,
    imeBottomPx: Float,
    imeMarginPx: Float
): Float {
    val navigationBottom = safeBottomPx + navigationClearancePx
    val snackbarBottom = navigationBottom +
        if (isSnackbarVisible) snackbarClearancePx else 0f
    val imeBottom = if (imeBottomPx > safeBottomPx) imeBottomPx + imeMarginPx else 0f
    return maxOf(snackbarBottom, imeBottom)
}

/**
 * Vigilith 全体（状態ラベルを含む）の左上座標が取り得る範囲を返す。
 *
 * [startReservedPx] は safe drawing inset と NavigationRail の大きい方、
 * [bottomReservedPx] は safe drawing / 下部ナビ / Snackbar / IME を統合した値。
 */
internal fun calculateVigilithPlacementBounds(
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    contentWidthPx: Float,
    contentHeightPx: Float,
    startReservedPx: Float,
    topReservedPx: Float,
    endReservedPx: Float,
    bottomReservedPx: Float,
    edgeMarginPx: Float
): VigilithPlacementBounds {
    val minX = (startReservedPx + edgeMarginPx).coerceAtLeast(0f)
    val minY = (topReservedPx + edgeMarginPx).coerceAtLeast(0f)
    val maxX = (
        viewportWidthPx - endReservedPx - edgeMarginPx - contentWidthPx
        ).coerceAtLeast(minX)
    val maxY = (
        viewportHeightPx - bottomReservedPx - edgeMarginPx - contentHeightPx
        ).coerceAtLeast(minY)

    return VigilithPlacementBounds(
        minX = minX,
        maxX = maxX,
        minY = minY,
        maxY = maxY
    )
}

internal fun resolveVigilithPlacement(
    placement: VigilithPlacement,
    bounds: VigilithPlacementBounds
): VigilithPlacementOffset = VigilithPlacementOffset(
    x = bounds.minX + bounds.width * placement.horizontalFraction.coerceIn(0f, 1f),
    y = bounds.minY + bounds.height * placement.verticalFraction.coerceIn(0f, 1f)
)

/**
 * ドラッグを適用し、画面内へ clamp した相対位置を返す。
 */
internal fun moveVigilithPlacement(
    placement: VigilithPlacement,
    deltaX: Float,
    deltaY: Float,
    bounds: VigilithPlacementBounds
): VigilithPlacement {
    val current = resolveVigilithPlacement(placement, bounds)
    return VigilithPlacement(
        horizontalFraction = fractionInRange(
            value = current.x + deltaX,
            minimum = bounds.minX,
            maximum = bounds.maxX
        ),
        verticalFraction = fractionInRange(
            value = current.y + deltaY,
            minimum = bounds.minY,
            maximum = bounds.maxY
        )
    )
}

private val VigilithPlacementBounds.width: Float
    get() = (maxX - minX).coerceAtLeast(0f)

private val VigilithPlacementBounds.height: Float
    get() = (maxY - minY).coerceAtLeast(0f)

private fun fractionInRange(value: Float, minimum: Float, maximum: Float): Float {
    val size = maximum - minimum
    if (size <= 0f) return 0f
    return ((value - minimum) / size).coerceIn(0f, 1f)
}
