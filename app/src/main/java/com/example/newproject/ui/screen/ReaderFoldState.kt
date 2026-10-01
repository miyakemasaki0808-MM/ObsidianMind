package com.example.newproject.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * 窓に報告された折り目。[isKnown] が偽のあいだは、まだ窓の情報が届いていない。
 *
 * **届く前を「折り目が無い」と区別する。** 画面の作り直しの直後は1フレームだけ情報が無く、
 * それを「折り目が無い窓」と読むと、ペインを出せない窓へ替わったと誤って判断してしまう。
 */
internal data class ReaderFoldInfo(val isKnown: Boolean, val fold: ReaderFold?)

@Composable
internal fun rememberReaderFold(): ReaderFoldInfo {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val density = LocalDensity.current
    val flow = remember(activity, density) {
        // Activity が無い組み立て（プレビュー・部品のテスト）は、折り目の無い窓として扱う。
        activity?.let { host ->
            WindowInfoTracker.getOrCreate(host).windowLayoutInfo(host).map { it.toFoldInfo(density) }
        } ?: flowOf(ReaderFoldInfo(isKnown = true, fold = null))
    }
    val info by flow.collectAsStateWithLifecycle(initialValue = ReaderFoldInfo(isKnown = false, fold = null))
    return info
}

/** 境界は窓の座標の px で届く。本文領域の座標へ直すのは判定側（→ readerLayoutFor）。 */
private fun WindowLayoutInfo.toFoldInfo(density: Density): ReaderFoldInfo {
    val feature = displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
        ?: return ReaderFoldInfo(isKnown = true, fold = null)
    return with(density) {
        ReaderFoldInfo(
            isKnown = true,
            fold = ReaderFold(
                isVertical = feature.orientation == FoldingFeature.Orientation.VERTICAL,
                startDp = feature.bounds.left.toDp().value,
                endDp = feature.bounds.right.toDp().value
            )
        )
    }
}
