package com.example.newproject.ui.screen

/**
 * ノート画面の並べ方（→ features/rediscover.md 判断6・features/margin_pane.md §5.1）。
 *
 * **低い横長の画面だけ左右2列にする。** 縦に積むと、見出し・操作ボタン・再会カードが先に高さを取り、
 * 本文は残りだけになる。高さ443dpのカバー画面を横にすると、カードだけで本文の高さが尽きる。
 * 2列なら本文は画面の高さをまるごと使える。
 *
 * **十分に高く広い窓では、本文の右に余白ペインを置く。** 開いた折りたたみでは折り目で割る。
 * ペインを閉じていれば、縦長の画面と同じく縦に積む（見開きのタブを選んでいれば本の形で見開き）。
 */
internal sealed interface ReaderLayout {
    data object Stacked : ReaderLayout
    data object SideBySide : ReaderLayout

    /** 左に操作と本文、右に余白ペイン。本文の幅と、折り目をまたぐ溝の幅を持つ。 */
    data class MarginPane(val bodyWidthDp: Float, val gutterDp: Float) : ReaderLayout

    /**
     * 本の形でペインを閉じて読むときの、本文の見開き（→ features/note_spread.md）。
     * [pageStartDp] は左ページの左端（本文領域の座標）、[pageWidthDp] は1ページの幅（左右同じ）、[gutterDp] は折り目をまたぐ溝。
     */
    data class Spread(val pageStartDp: Float, val pageWidthDp: Float, val gutterDp: Float) : ReaderLayout
}

/** 見開きに入れるかを決める、本文の側の入力（→ [spreadAllowed]）。[maxBlockChars] は本文のブロックの文字数の最大。 */
internal data class SpreadFit(val fontScale: Float, val maxBlockChars: Int)

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
    regionStartDp: Float = 0f,
    /** 本文の側の入力。見開きのタブを選んでいないとき、ノートが無いとき、解析の前は null で、見開きにしない（→ [spreadFitFor]）。 */
    spread: SpreadFit? = null
): ReaderLayout {
    // 高さの足りない窓では、ペインより本文の高さを優先する。ペインの開閉によらない。
    if (heightDp < SIDE_BY_SIDE_MAX_HEIGHT_DP && widthDp > heightDp) return ReaderLayout.SideBySide
    if (!paneOpen) return spreadFor(widthDp, heightDp, fold, regionStartDp, spread) ?: ReaderLayout.Stacked
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

/**
 * ペインを閉じた本の形の見開き。縦の折り目が本文領域の中にあり、1行の字数・ページの行数・巨大なブロックの条件を満たすとき。
 * **入れるかは右のページで決める** — 左のページはレールを畳むまで狭く、畳むのは見開きが出てからなので、左で決めると出入りを繰り返す。
 * 2ページは狭いほうの幅にそろえ、折り目をはさんで置く（レールが畳まれると左右が同じになる）。
 */
private fun spreadFor(widthDp: Float, heightDp: Float, fold: ReaderFold?, regionStartDp: Float, spread: SpreadFit?): ReaderLayout.Spread? {
    val pages = spreadPagesFor(widthDp, heightDp, fold, regionStartDp) ?: return null
    if (spread == null || !spreadAllowed(pages.textWidthDp, pages.textHeightDp, spread.fontScale, spread.maxBlockChars)) return null
    return ReaderLayout.Spread(pageStartDp = pages.pageStartDp, pageWidthDp = pages.pageWidthDp, gutterDp = pages.gutterDp)
}

/**
 * 見開きにできない理由（→ features/note_spread.md §5「見開きにできないノート」）。**窓の側は見開きにできて、本文の側の条件だけを欠く**ときに返す。
 * 窓の側（縦の折り目が本文領域に無い）で入れないとき・ノートが無いとき（[spread] が null）・入れるときは null。
 */
internal fun spreadRefusalFor(widthDp: Float, heightDp: Float, fold: ReaderFold?, regionStartDp: Float, spread: SpreadFit?): SpreadRefusal? {
    if (spread == null) return null
    if (heightDp < SIDE_BY_SIDE_MAX_HEIGHT_DP && widthDp > heightDp) return null
    val pages = spreadPagesFor(widthDp, heightDp, fold, regionStartDp) ?: return null
    return spreadRefusal(pages.textWidthDp, pages.textHeightDp, spread.fontScale, spread.maxBlockChars)
}

/** 折り目で割った2ページの寸法。本文の幅と高さは右のページで測る（→ [spreadFor]）。折り目が本文領域に無ければ null。 */
private data class SpreadPages(val pageStartDp: Float, val pageWidthDp: Float, val gutterDp: Float, val textWidthDp: Float, val textHeightDp: Float)

private fun spreadPagesFor(widthDp: Float, heightDp: Float, fold: ReaderFold?, regionStartDp: Float): SpreadPages? {
    if (fold?.isVertical != true) return null
    val foldStart = fold.startDp - regionStartDp
    val foldEnd = fold.endDp - regionStartDp
    if (foldEnd <= 0f || foldStart >= widthDp) return null
    val gutter = maxOf(PANE_GUTTER_DP, foldEnd - foldStart)
    val center = (foldStart + foldEnd) / 2f
    val left = center - gutter / 2f
    val right = widthDp - center - gutter / 2f
    val page = minOf(left, right)
    return SpreadPages(
        pageStartDp = left - page,
        pageWidthDp = page,
        gutterDp = gutter,
        textWidthDp = right - SPREAD_PAGE_PADDING_DP * 2,
        textHeightDp = heightDp - SPREAD_STRIP_DP - SPREAD_PAGE_PADDING_DP * 2
    )
}

/** 見開きのページの内側の余白。縦の流しの本文の面と同じ。 */
internal const val SPREAD_PAGE_PADDING_DP = 18f

/** 見開きの上に置くつまみの段の高さ（机の画面と同じ `DeskStripHeight`）。 */
private const val SPREAD_STRIP_DP = 48f

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
 * ペインを出せる窓か。**ペインを開いているとみなしたとき、余白ペインになる窓**
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

/**
 * 見開きに入るかの本文の側の入力（→ [readerLayoutFor]）。**見開きのタブを選んでいなければ null** — 本の形でペインを閉じても見開きにしない
 * （→ features/note_spread.md §8 判断2）。ノートが無いときと解析の前も null。
 */
internal fun spreadFitFor(spreadSelected: Boolean, hasNote: Boolean, maxBlockChars: Int?, fontScale: Float): SpreadFit? =
    if (spreadSelected && hasNote && maxBlockChars != null) SpreadFit(fontScale, maxBlockChars) else null

/**
 * 外殻が観測した本文領域。**レールがある状態で、キーボードを除いて測る**（→ features/margin_pane.md §5.4「1つの値」）。
 * 机と見開きではレールを畳むが、畳んだ本文領域で測り直すと、畳んだせいで値が替わって出す・閉じるを繰り返しうる。
 */
internal data class ReaderRegion(val widthDp: Float, val heightDp: Float, val startDp: Float)

/**
 * 外殻が観測する窓の値（→ features/margin_pane.md §5.4・features/note_spread.md §5「見開きのタブ」）。
 * [canShowPane] は閉じる判定と描画の側（✎ の扱い・机を組むか）が同じ値を読む。[known] が偽のあいだは判定しない。
 */
internal data class ReaderWindow(val known: Boolean, val canShowPane: Boolean, val spreadTab: Boolean) {
    companion object {
        val Unknown = ReaderWindow(known = false, canShowPane = false, spreadTab = false)
    }
}

/** 窓の値。本文領域を測る前と、折り目の報告が届く前（作り直しの直後の1フレーム）は [ReaderWindow.Unknown]。 */
internal fun readerWindowFor(region: ReaderRegion?, expandedWidth: Boolean, foldInfo: ReaderFoldInfo): ReaderWindow {
    if (region == null || !foldInfo.isKnown) return ReaderWindow.Unknown
    return ReaderWindow(
        known = true,
        canShowPane = canShowMarginPane(region.widthDp, region.heightDp, expandedWidth, foldInfo.fold, region.startDp),
        spreadTab = canShowSpreadTab(region.widthDp, region.heightDp, expandedWidth, foldInfo.fold, region.startDp)
    )
}

/**
 * レールに見開きのタブを出す窓か（→ features/note_spread.md §5「見開きのタブ」）。横幅が Expanded で、縦の折り目が本文領域の中にあり、
 * 高さの足りない横長ではない窓。**ペインの幅の下限は効かない**（ペインとは別の条件）。ノートの中身では出し分けない。
 */
internal fun canShowSpreadTab(widthDp: Float, heightDp: Float, expandedWidth: Boolean, fold: ReaderFold?, regionStartDp: Float): Boolean {
    if (!expandedWidth) return false
    if (heightDp < SIDE_BY_SIDE_MAX_HEIGHT_DP && widthDp > heightDp) return false
    return spreadPagesFor(widthDp, heightDp, fold, regionStartDp) != null
}

/** 利用者が選んだ画面の形。ペインの開閉と、読む形が見開きか（→ features/fold_experience.md §6 の初期状態）。 */
internal data class ReaderChoice(val paneOpen: Boolean, val spreadSelected: Boolean)

/**
 * 窓を観測した後の選択。**出せない窓では閉じ、出せる窓へ戻っても開かない** — 開くのは利用者の操作だけ（→ features/fold_experience.md §6）。
 * 値が揃う前は替えない。閉じても並べ読みは終えない（終えるのは利用者が机をしまう操作 → features/margin_pane.md §5.4）。
 */
internal fun readerChoiceAfterWindow(choice: ReaderChoice, window: ReaderWindow): ReaderChoice =
    if (!window.known) choice else ReaderChoice(choice.paneOpen && window.canShowPane, choice.spreadSelected && window.spreadTab)

/** これより低い横長の画面で2列にする。カバー画面の横向き（約443dp）と横にしたスマホを含み、開いた折りたたみを含まない。 */
internal const val SIDE_BY_SIDE_MAX_HEIGHT_DP = 480f

/**
 * 余白ペインで本文に残す幅の下限。開いた Pixel 10 Pro Fold を平らにして測った本文の幅（約318dp。
 * 左のレールと余白で約100dpを取られる）が収まる値にしてある。上げるとこの端末で折り目に割れなくなる。
 * **ペインを出すかはレールがある状態で決まる**（出た後に外殻がレールを畳む → `showsRail`）ので、畳んだ後の幅で測り直さない。
 */
internal const val MARGIN_PANE_MIN_BODY_DP = 300f

/** 余白ペインの幅の下限。**仮置きで、実機で調整する。** */
internal const val MARGIN_PANE_MIN_PANE_DP = 280f

/** 本文とペインの間の溝。折り目はこの中に入り、文字と操作が折り目に重ならない。 */
internal const val PANE_GUTTER_DP = 16f

/** 2列のときの左列の幅。本文を広く残すため、画面の4割か360dpの小さいほう。 */
internal fun sideColumnWidthDp(widthDp: Float): Float = minOf(widthDp * 0.4f, 360f)
