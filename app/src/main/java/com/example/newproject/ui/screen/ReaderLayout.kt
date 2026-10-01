package com.example.newproject.ui.screen

/**
 * ノート画面の並べ方（→ features/rediscover.md 判断6・features/margin_pane.md §5.1）。
 *
 * **低い横長の画面だけ左右2列にする。** 縦に積むと、見出し・操作ボタン・再会カードが先に高さを取り、
 * 本文は残りだけになる。高さ443dpのカバー画面を横にすると、カードだけで本文の高さが尽きる。
 * 2列なら本文は画面の高さをまるごと使える。
 *
 * **十分に高く広い窓では、本文の右に余白ペインを置く。** 開いた折りたたみでは折り目で割る。
 * ペインの設定が閉じていれば、縦長の画面と同じく縦に積む。
 */
internal sealed interface ReaderLayout {
    data object Stacked : ReaderLayout
    data object SideBySide : ReaderLayout

    /** 左に操作と本文、右に余白ペイン。本文の幅と、折り目をまたぐ溝の幅を持つ。 */
    data class MarginPane(val bodyWidthDp: Float, val gutterDp: Float) : ReaderLayout
}

/**
 * 窓に報告された折り目。座標は**窓の**左端からの dp で、本文領域の座標ではない。
 * 横の折り目（卓上の形）では [startDp]・[endDp] を使わない。
 */
internal data class ReaderFold(val isVertical: Boolean, val startDp: Float, val endDp: Float)

/**
 * 並べ方を決める。**上から当て、最初に当たったものを採る**（→ features/margin_pane.md §5.1）。
 *
 * [widthDp]・[heightDp] は本文領域の大きさで、**キーボードを含めない。** 含めると、
 * キーボードの出入りで並べ方が替わり、書いている入力欄が別の場所へ組み直される。
 * [regionStartDp] は本文領域の左端の窓座標で、折り目を本文領域の座標へ直すのに使う。
 */
internal fun readerLayoutFor(
    widthDp: Float,
    heightDp: Float,
    paneOpen: Boolean = false,
    expandedWidth: Boolean = false,
    fold: ReaderFold? = null,
    regionStartDp: Float = 0f
): ReaderLayout {
    // 高さの足りない窓では、ペインより本文の高さを優先する。ペインの設定によらない。
    if (heightDp < SIDE_BY_SIDE_MAX_HEIGHT_DP && widthDp > heightDp) return ReaderLayout.SideBySide
    if (!paneOpen) return ReaderLayout.Stacked
    return when {
        // 平らでも半開きでも割る。`isSeparating` が偽でも除外しない。
        fold?.isVertical == true -> {
            val foldStart = fold.startDp - regionStartDp
            val foldEnd = fold.endDp - regionStartDp
            if (foldEnd > 0f && foldStart < widthDp) {
                // **本文領域の中の折り目で割れなければ、中央へ逃げずに縦に積む。** 中央で割ると、
                // 本文と見出しの操作が折り目をまたぐ。
                paneAt((foldStart + foldEnd) / 2f, widthDp, maxOf(PANE_GUTTER_DP, foldEnd - foldStart))
            } else {
                // 折り目がレールの中など本文領域の外にあれば、本文に重ならないので中央で割ってよい。
                paneAt(widthDp / 2f, widthDp, PANE_GUTTER_DP)
            }
        }
        fold == null && expandedWidth -> paneAt(widthDp / 2f, widthDp, PANE_GUTTER_DP)
        // 横の折り目（卓上の形）と、広くない窓は縦に積む。
        else -> null
    } ?: ReaderLayout.Stacked
}

/** [centerDp] を溝の中心にして割る。**割った後の幅が下限を割るなら割らない。** */
private fun paneAt(centerDp: Float, widthDp: Float, gutterDp: Float): ReaderLayout.MarginPane? {
    val body = centerDp - gutterDp / 2f
    val pane = widthDp - centerDp - gutterDp / 2f
    return if (body >= MARGIN_PANE_MIN_BODY_DP && pane >= MARGIN_PANE_MIN_PANE_DP) {
        ReaderLayout.MarginPane(bodyWidthDp = body, gutterDp = gutterDp)
    } else {
        null
    }
}

/**
 * ペインを出せる窓か。**ペインの設定を開いているとみなしたとき、余白ペインになる窓**
 * （→ features/margin_pane.md §5.4）。✎ がペインとシートのどちらを扱うかはこれで分ける。
 */
internal fun canShowMarginPane(
    widthDp: Float,
    heightDp: Float,
    expandedWidth: Boolean,
    fold: ReaderFold?,
    regionStartDp: Float
): Boolean = readerLayoutFor(
    widthDp = widthDp,
    heightDp = heightDp,
    paneOpen = true,
    expandedWidth = expandedWidth,
    fold = fold,
    regionStartDp = regionStartDp
) is ReaderLayout.MarginPane

/** これより低い横長の画面で2列にする。カバー画面の横向き（約443dp）と横にしたスマホを含み、開いた折りたたみを含まない。 */
internal const val SIDE_BY_SIDE_MAX_HEIGHT_DP = 480f

/**
 * 余白ペインで本文に残す幅の下限。開いた Pixel 10 Pro Fold を平らにして測った本文の幅（約318dp。
 * 左のレールと余白で約100dpを取られる）が収まる値にしてある。上げるとこの端末で折り目に割れなくなる。
 */
internal const val MARGIN_PANE_MIN_BODY_DP = 300f

/** 余白ペインの幅の下限。**仮置きで、実機で調整する。** */
internal const val MARGIN_PANE_MIN_PANE_DP = 280f

/** 本文とペインの間の溝。折り目はこの中に入り、文字と操作が折り目に重ならない。 */
internal const val PANE_GUTTER_DP = 16f

/** 2列のときの左列の幅。本文を広く残すため、画面の4割か360dpの小さいほう。 */
internal fun sideColumnWidthDp(widthDp: Float): Float = minOf(widthDp * 0.4f, 360f)
