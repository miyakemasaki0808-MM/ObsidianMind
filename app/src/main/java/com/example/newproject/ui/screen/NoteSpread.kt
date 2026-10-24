package com.example.newproject.ui.screen

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.newproject.domain.NoteLink
import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.domain.markdown.NoteSectionModel
import com.example.newproject.ui.READING_FRACTION_STEPS
import com.example.newproject.ui.markdown.HeadingAccessoryWidth
import com.example.newproject.ui.markdown.InlineMarkdownColors
import com.example.newproject.ui.markdown.LocalNoteLinkTaps
import com.example.newproject.ui.markdown.MarkdownBodyStyle
import com.example.newproject.ui.markdown.MeasuredSpreadBlock
import com.example.newproject.ui.markdown.NoteImageBlockRef
import com.example.newproject.ui.markdown.NoteImageLoader
import com.example.newproject.ui.markdown.NoteImageMeasurement
import com.example.newproject.ui.markdown.NoteImageMeasurements
import com.example.newproject.ui.markdown.NoteLinkTaps
import com.example.newproject.ui.markdown.SpreadMeasureInputs
import com.example.newproject.ui.markdown.SpreadSliceView
import com.example.newproject.ui.markdown.firstUnmeasuredImageIndex
import com.example.newproject.ui.markdown.imageBlockRefs
import com.example.newproject.ui.markdown.measureSpreadBlock
import com.example.newproject.ui.markdown.reservedImageHeightDp
import com.example.newproject.ui.markdown.watchLinkPresses
import com.example.newproject.ui.quantizeReadingFraction
import com.example.newproject.ui.shouldReportReadingProgress
import com.example.newproject.ui.theme.AccentText
import com.example.newproject.ui.theme.CodePanel
import com.example.newproject.ui.theme.LinkText
import com.example.newproject.ui.theme.OnSurface
import com.example.newproject.ui.theme.OnSurfaceFaint
import com.example.newproject.ui.theme.OnSurfaceMuted
import com.example.newproject.ui.theme.ReadingSurfaceShape
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

// 本文の見開き（→ features/note_spread.md）。測って、ページへ詰めて、左右2ページで出し、横の払いでめくる。

/**
 * 本文を左右2ページの見開きで出す。[pageWidth] は1ページの幅（左右同じ）、[gutter] は折り目をまたぐ溝。
 * [leftPageTop] は左ページの上に置くもの（再会カード）で、出ている間は左ページが短くなる。
 *
 * - **読む位置は [position] の一覧の状態と往復させる**（→ 同 §5「読む位置」「位置の書き込み」）。めくりが決まったら書き戻し、
 *   外からの送りは要求の番号で見分けて読む位置を替える。組み直しでは読む位置を変えない
 * - 測るのは Main で、ブロックの間で約4msごとに次のフレームへ譲る（→ 同 §9）。測り終えるまでは、組めた見開きだけを出す
 */
@Composable
internal fun NoteSpread(
    noteKey: String,
    title: String,
    sectionModel: NoteSectionModel,
    position: BodyPosition,
    paperColor: Color,
    imageLoader: NoteImageLoader?,
    imageMeasurements: NoteImageMeasurements?,
    headingAccessory: (@Composable (blockIndex: Int) -> Unit)?,
    onLink: ((NoteLink) -> Unit)?,
    onReadingProgress: (blockIndex: Int, blockFraction: Float, totalBlocks: Int, sectionTitle: String?) -> Unit,
    pageWidth: Dp,
    gutter: Dp,
    leftPageTop: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val blocks = sectionModel.blocks
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val baseStyle = LocalTextStyle.current
    val colors = InlineMarkdownColors(strikethrough = OnSurfaceFaint, codeBackground = CodePanel, link = LinkText)
    val currentOnLink by rememberUpdatedState(onLink)
    val linkTaps = remember(onLink != null) { if (onLink != null) NoteLinkTaps { currentOnLink?.invoke(it) } else null }
    val textWidthPx = with(density) { (pageWidth - PagePadding * 2).roundToPx() }
    val accessoryPx = if (headingAccessory != null) with(density) { HeadingAccessoryWidth.roundToPx() } else 0
    val pendingImageHeightDp = LocalConfiguration.current.screenHeightDp
    // 画像の寸法が届くたびに組み直す（読む位置は保つ → 判断11）。寸法は共有の入れ物の観測できる状態から読む。
    val imageSizes = blocks.filterIsInstance<MarkdownBlock.Image>().map { imageMeasurements?.measurementOf(it) }

    // 本文の画像は、組む前に寸法を測る（描く前に高さが要る）。測り終えれば入れ物が替わり、上の鍵で組み直す。
    LaunchedEffect(blocks, imageLoader, imageMeasurements) {
        if (imageLoader == null || imageMeasurements == null) return@LaunchedEffect
        blocks.filterIsInstance<MarkdownBlock.Image>().filter { imageMeasurements.measurementOf(it) == null }.forEach { block ->
            imageMeasurements.record(block, imageLoader.measure(block))
        }
    }

    var measured by remember(noteKey, blocks, textWidthPx, density, baseStyle, colors, linkTaps, accessoryPx, imageSizes) {
        mutableStateOf(SpreadMeasured(emptyList(), complete = blocks.isEmpty()))
    }
    LaunchedEffect(noteKey, blocks, textWidthPx, density, baseStyle, colors, linkTaps, accessoryPx, imageSizes) {
        val widthDp = with(density) { textWidthPx.toDp().value.toInt() }
        val inputs = SpreadMeasureInputs(
            measurer = measurer,
            baseStyle = baseStyle,
            density = density,
            textWidthPx = textWidthPx,
            headingAccessoryPx = accessoryPx,
            colors = colors,
            linkTaps = linkTaps,
            imageHeightPx = { block ->
                val size = imageMeasurements?.measurementOf(block) as? NoteImageMeasurement.Measured
                with(density) { reservedImageHeightDp(widthDp, size?.width ?: 0, size?.height ?: 0, pendingImageHeightDp).dp.toPx() }
            }
        )
        val out = ArrayList<MeasuredSpreadBlock>(blocks.size)
        var sliceStart = System.nanoTime()
        blocks.forEach { block ->
            out += measureSpreadBlock(block, inputs)
            if (System.nanoTime() - sliceStart > MEASURE_SLICE_NANOS) {
                measured = SpreadMeasured(out.toList(), complete = false)
                withFrameNanos { }
                sliceStart = System.nanoTime()
            }
        }
        measured = SpreadMeasured(out.toList(), complete = true)
    }

    // 読む位置。ノートの画面の保存値に置き、組むたびに今のノートと本文の位置の口へ合わせる。
    // **保存値から戻した直後も同じ照合を通す** — 留守の間の送りや、別のノートを開いたことを見落とさない。
    var saved by rememberSaveable(stateSaver = ReadingSaver) { mutableStateOf<SpreadReading?>(null) }
    val parse = remember(blocks) { blocks.hashCode() }
    // **組み始めて保存値を戻すときは、ブロックを一覧から取る**（→ restoredAnchor）。保存値は見開きを組むまで消費されずに残るので、
    // その間にふだんの画面で読み進めた位置（口を通らない指のスクロール）や、適用済みの送り・解析し直しの後の位置を見落とさない。
    var restoring by remember { mutableStateOf(true) }
    val listBlock = { position.listState.firstVisibleItemIndex }
    val base = if (restoring) restoreSpreadReading(saved, noteKey, parse, blocks.size, position.port, position.lastRequest, listBlock) else saved
    val reading = reconcileSpreadReading(base, noteKey, parse, blocks.size, position.port, position.lastRequest, listBlock)
    SideEffect {
        restoring = false
        if (saved != reading) saved = reading
        // **読む位置のブロックが一覧と食い違えば書き戻す**（めくりと同じ自分の書き戻し）。解析し直しで範囲へ収めたときも書かないと、
        // 離れて直接戻ったときに一覧のブロックが古く、戻すときの規則が古いほうを採る（→ features/note_spread.md §5「読む位置」）。
        if (reading.anchor.blockIndex != position.listState.firstVisibleItemIndex) {
            position.moveTo(reading.anchor.blockIndex, source = BodyPositionSource.SpreadTurn)
        }
    }
    val anchor = reading.anchor

    BoxWithConstraints(modifier = modifier) {
        val pageContentHeight = with(density) { (maxHeight - PagePadding * 2).toPx() }
        var cardHeightPx by remember(noteKey) { mutableIntStateOf(0) }
        // 再会カードを測ったか。**測る前の高さ（前に測った値か0）で組んだ見開きは、読書の記録へ送らない。** カードが出入りするたびに測り直す。
        var cardMeasured by remember(noteKey, leftPageTop != null) { mutableStateOf(false) }
        val leftHeight = pageContentHeight - if (leftPageTop != null) cardHeightPx + with(density) { CardGap.toPx() } else 0f
        val titleHeight = remember(title, textWidthPx, density, baseStyle) {
            val layout = measurer.measure(AnnotatedString(title), baseStyle.merge(TitleStyle), constraints = Constraints(maxWidth = textWidthPx), density = density)
            layout.size.height + with(density) { TitleGap.toPx() }
        }
        val spacingPx = with(density) { MarkdownBodyStyle.BlockSpacing.toPx() }
        val fitHeight = minOf(leftHeight, pageContentHeight, leftHeight - titleHeight)
        val pages = remember(measured, leftHeight, pageContentHeight, titleHeight, spacingPx) {
            paginateSpread(
                blocks = measured.blocks.map { it.spread },
                leftPageHeight = leftHeight,
                rightPageHeight = pageContentHeight,
                firstPageHeight = leftHeight - titleHeight,
                blockSpacing = spacingPx
            )
        }
        // 測り終えるまでは最後のページがまだ伸びるので、両方のページが組み上がった見開きだけを出す。
        val available = if (measured.complete) maxOf(spreadCount(pages), 1) else (pages.size - 1).coerceAtLeast(0) / 2
        val target = spreadIndexOf(pages, anchor)
        val ready = measured.complete || target < available

        if (!ready) {
            SpreadComposing(paperColor, pageWidth, gutter)
            return@BoxWithConstraints
        }

        val pagerState = rememberPagerState(initialPage = target.coerceIn(0, available - 1)) { available }
        val currentPages by rememberUpdatedState(pages)
        val currentReading by rememberUpdatedState(reading)
        val currentReport by rememberUpdatedState(onReadingProgress)
        val imageRefs = remember(blocks) { imageBlockRefs(blocks) }
        val shown = remember(pages, measured, target, leftPageTop == null || cardMeasured, sectionModel, imageRefs) {
            ShownSpread(pages, measured.blocks.map { it.spread }, target, leftPageTop == null || cardMeasured, sectionModel, imageRefs)
        }
        val currentShown by rememberUpdatedState(shown)

        // 組み直しや外からの送りで、読む位置を含む見開きが替わったら、そこを出す。めくりとは数えない（読む位置を替えない）。
        LaunchedEffect(target, available) {
            val page = target.coerceIn(0, available - 1)
            if (pagerState.currentPage != page) pagerState.scrollToPage(page)
        }
        // めくりが決まって止まったら、読む位置をその見開きの左ページの先頭にして書き戻す。
        LaunchedEffect(pagerState, noteKey) {
            snapshotFlow { pagerState.settledPage to pagerState.isScrollInProgress }
                .filter { (_, moving) -> !moving }
                .collect { (settled, _) ->
                    val pagesNow = currentPages
                    if (settled != spreadIndexOf(pagesNow, currentReading.anchor)) {
                        anchorAfterTurn(pagesNow, settled)?.let { turned ->
                            position.moveTo(turned.blockIndex, source = BodyPositionSource.SpreadTurn)
                            saved = currentReading.copy(anchor = turned, seenRequest = position.lastRequest.request)
                        }
                    }
                }
        }
        // 出ている見開きの読んだ位置を記録へ送る。**送るのは、止まった見開きが今の組版で読む位置の見開きで、再会カードを測り終えたときだけ。**
        // 組み直しのたびに照らし直すので、ページの番号が変わらない組み直しでも、出ている中身で送り直す。
        // 記録の最深到達点は下がらないので、確定する前の組版で先まで送ると取り消せない。
        LaunchedEffect(pagerState, noteKey) {
            snapshotFlow { Triple(pagerState.settledPage, pagerState.isScrollInProgress, currentShown) }
                .filter { (settled, moving, now) -> !moving && now.cardMeasured && settled == now.spread }
                .map { (settled, _, now) ->
                    val progress = spreadProgress(now.pages, settled, now.blocks) ?: return@map null
                    val unmeasured = imageMeasurements?.let { firstUnmeasuredImageIndex(now.imageRefs, it.measuredReferences()) }
                    if (!shouldReportReadingProgress(progress.blockIndex, unmeasured)) return@map null
                    ReadingReport(progress.blockIndex, quantizeReadingFraction(progress.fraction), now.model)
                }
                .distinctUntilChanged()
                .filterNotNull()
                .collect { report ->
                    currentReport(
                        report.blockIndex,
                        report.step.toFloat() / READING_FRACTION_STEPS,
                        report.model.blocks.size,
                        report.model.sectionForBlockIndex(report.blockIndex)?.title
                    )
                }
        }

        val scope = rememberCoroutineScope()
        val turnTargets = spreadTurnTargets(pagerState.currentPage, available)
        val gutterPx = with(density) { gutter.toPx() }
        val liftRoomPx = with(density) { SPREAD_TURN_LIFT_ROOM_DP.dp.toPx() }
        // 指を離した後の寄せ。倍率0のときだけ、行き先の規則は同じまま途中を省く寄せに替える（→ SpreadSettleFling）。
        val smoothSettle = PagerDefaults.flingBehavior(state = pagerState)
        val instantSettle = PagerDefaults.flingBehavior(state = pagerState, snapAnimationSpec = SpreadInstantSettleSpec)
        val settle = remember(smoothSettle, instantSettle) { SpreadSettleFling(smoothSettle, instantSettle) }
        HorizontalPager(
            state = pagerState,
            flingBehavior = settle,
            modifier = Modifier
                .fillMaxSize()
                // 払えない利用者のめくり。送りは時間で進むので、OS のアニメーションの設定に従う（→ features/note_spread.md 判断7）。
                .semantics {
                    stateDescription = spreadPositionDescription(pagerState.currentPage)
                    customActions = listOfNotNull(
                        turnTargets.next?.let { next ->
                            CustomAccessibilityAction("次の見開きへ") {
                                scope.launch { pagerState.animateScrollToPage(next) }
                                true
                            }
                        },
                        turnTargets.previous?.let { previous ->
                            CustomAccessibilityAction("前の見開きへ") {
                                scope.launch { pagerState.animateScrollToPage(previous) }
                                true
                            }
                        }
                    )
                },
            key = { it }
        ) { spread ->
            // 今の見開きからの距離（→ spreadTurnLayers）。描く段で読むので、めくっている間に組み直さない。
            val offset = { (pagerState.currentPage - spread) + pagerState.currentPageOffsetFraction }
            // めくる1枚を描いている見開きを上に重ねる。ページャは番号順に置くので、放っておくとめくる紙が下に隠れる。
            // 入れ替わるのは90度を越えるときだけなので、そのときだけ組み直す。
            val onTop by remember(spread) { derivedStateOf { spreadTurnLayers(offset()).onTop } }
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(if (onTop) 1f else 0f)
                    // 見開きは動かさない。ページャが送るために与えた変位を打ち消して、定位置へ置き直す。
                    .graphicsLayer { translationX = size.width * spreadTurnLayers(offset()).slotShift }
            ) {
                Column(modifier = Modifier.width(pageWidth).fillMaxHeight().spreadTurnFace(rightPage = false, offset, gutterPx, liftRoomPx)) {
                    if (leftPageTop != null) {
                        Box(
                            modifier = Modifier.onSizeChanged {
                                cardHeightPx = it.height
                                cardMeasured = true
                            }
                        ) { leftPageTop() }
                        Spacer(modifier = Modifier.height(CardGap))
                    }
                    SpreadPageView(
                        page = pages.getOrNull(spread * 2),
                        showTitle = spread == 0,
                        title = title,
                        measured = measured.blocks,
                        fitHeightPx = fitHeight,
                        paperColor = paperColor,
                        linkTaps = linkTaps,
                        imageLoader = imageLoader,
                        imageMeasurements = imageMeasurements,
                        headingAccessory = headingAccessory,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.width(gutter))
                SpreadPageView(
                    page = pages.getOrNull(spread * 2 + 1),
                    showTitle = false,
                    title = title,
                    measured = measured.blocks,
                    fitHeightPx = fitHeight,
                    paperColor = paperColor,
                    linkTaps = linkTaps,
                    imageLoader = imageLoader,
                    imageMeasurements = imageMeasurements,
                    headingAccessory = headingAccessory,
                    modifier = Modifier.width(pageWidth).fillMaxHeight().spreadTurnFace(rightPage = true, offset, gutterPx, liftRoomPx)
                )
            }
        }
    }
}

/**
 * 見開きの1ページを、めくる1枚の表か裏なら綴じ目を軸に回し、描かない側なら描かない（→ [spreadTurnLayers]）。
 * 紙の色も透明度も途中の値を取らない — 描くか描かないかだけ。持ち上がりは遠近と影で言う。
 */
private fun Modifier.spreadTurnFace(rightPage: Boolean, offset: () -> Float, gutterPx: Float, liftRoomPx: Float): Modifier =
    graphicsLayer {
        val layers = spreadTurnLayers(offset())
        val face = if (rightPage) layers.right else layers.left
        alpha = if (face.shown) 1f else 0f
        rotationY = face.rotationY
        transformOrigin = TransformOrigin(spreadSpinePivot(size.width, gutterPx, rightPage), 0.5f)
        cameraDistance = spreadTurnCameraDistance(size.width, size.height, liftRoomPx)
        shadowElevation = if (face.shown && face.turning) spreadTurnShadowDp(face.rotationY).dp.toPx() else 0f
        shape = ReadingSurfaceShape
    }

/** 1ページ。読む面（紙の色は年代、形は読む面）に、切れ端を本文の順で並べる。[page] が null なら空のページ（最後の見開きの右）。 */
@Composable
private fun SpreadPageView(
    page: SpreadPage?,
    showTitle: Boolean,
    title: String,
    measured: List<MeasuredSpreadBlock>,
    fitHeightPx: Float,
    paperColor: Color,
    linkTaps: NoteLinkTaps?,
    imageLoader: NoteImageLoader?,
    imageMeasurements: NoteImageMeasurements?,
    headingAccessory: (@Composable (blockIndex: Int) -> Unit)?,
    modifier: Modifier
) {
    Surface(color = paperColor, shape = ReadingSurfaceShape, modifier = modifier) {
        SelectionContainer {
            CompositionLocalProvider(LocalNoteLinkTaps provides linkTaps) {
                // 長押しの見張りは選択の容器の内側に置く（→ MarkdownNoteContent と同じ理由）。
                Box(modifier = (linkTaps?.let { Modifier.watchLinkPresses(it) } ?: Modifier).fillMaxSize()) {
                    Column(modifier = Modifier.padding(PagePadding)) {
                        if (showTitle) {
                            Text(text = title, color = OnSurface, style = LocalTextStyle.current.merge(TitleStyle))
                            Spacer(modifier = Modifier.height(TitleGap))
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(MarkdownBodyStyle.BlockSpacing)) {
                            page?.slices?.forEach { slice ->
                                measured.getOrNull(slice.blockIndex)?.let { block ->
                                    SpreadSliceView(block, slice, fitHeightPx, imageLoader, imageMeasurements, headingAccessory)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 読む位置を含む見開きが組み上がる前。その場所に印を出して、めくりを止める（→ features/note_spread.md §5「組み上がる前」）。 */
@Composable
private fun SpreadComposing(paperColor: Color, pageWidth: Dp, gutter: Dp) {
    Row(modifier = Modifier.fillMaxSize()) {
        Surface(color = paperColor, shape = ReadingSurfaceShape, modifier = Modifier.width(pageWidth).fillMaxHeight()) {
            Box(contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = AccentText, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(text = "組んでいます", color = OnSurfaceMuted, fontSize = 14.sp)
                }
            }
        }
        Spacer(modifier = Modifier.width(gutter))
        Surface(color = paperColor, shape = ReadingSurfaceShape, modifier = Modifier.width(pageWidth).fillMaxHeight()) {}
    }
}

/**
 * 出ている組版。[spread] は読む位置を含む見開き、[cardMeasured] は再会カードの高さを測ってから組んだか（カードが無ければ真）。
 * 読書の記録はこれから送る — 組版と、その組版を組んだときの解析結果を一緒に持つので、古い組版の中身を新しい本文へ当てない。
 */
private class ShownSpread(
    val pages: List<SpreadPage>,
    val blocks: List<SpreadBlock>,
    val spread: Int,
    val cardMeasured: Boolean,
    val model: NoteSectionModel,
    val imageRefs: List<NoteImageBlockRef>
)

/**
 * 指を離した後の寄せ（→ features/note_spread.md 判断7）。**OS のアニメーションの倍率が0なら [instant]、それ以外は [smooth]** で寄せる。
 * どちらもページャ既定の寄せで、行き先の規則は同じ。ページャ既定の寄せは時間の倍率を1に固定した中で動くので、
 * 外から倍率を渡しても途中は省かれない — 途中を省くには寄せそのものを替えるしかない。
 * 倍率は寄せを始めるときの実行コンテキストから読む（設定を切り替えると次の寄せから効く）。
 */
internal class SpreadSettleFling(private val smooth: TargetedFlingBehavior, private val instant: TargetedFlingBehavior) : TargetedFlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float, onRemainingDistanceUpdated: (Float) -> Unit): Float {
        val chosen = if (spreadSettlesInstantly(coroutineContext[MotionDurationScale]?.scaleFactor)) instant else smooth
        return with(chosen) { performFling(initialVelocity, onRemainingDistanceUpdated) }
    }
}

/** 寄せの途中を省くか。倍率が0のときだけ。実行コンテキストに倍率が無ければ、既定の1とみなして省かない。 */
internal fun spreadSettlesInstantly(scaleFactor: Float?): Boolean = scaleFactor == 0f

/** 途中を省く寄せ。時間が0なので、最初のフレームで行き先に着く。 */
internal val SpreadInstantSettleSpec: AnimationSpec<Float> = snap()

/** 読み上げの操作で送れる見開き（→ features/note_spread.md §5「めくり」）。組み上がった見開きの中だけで、端では送らない。 */
internal data class SpreadTurnTargets(val next: Int?, val previous: Int?)

internal fun spreadTurnTargets(current: Int, available: Int): SpreadTurnTargets = SpreadTurnTargets(
    next = (current + 1).takeIf { it < available },
    previous = (current - 1).takeIf { it >= 0 }
)

/**
 * 読み上げにだけ伝える今の見開きの位置。画面には出さない。**総数は言わない** — 全体を組み終えるまで決まらない（→ features/note_spread.md §11）。
 * 送った後に位置を言えないと、払えない利用者は送れたのかが分からない。
 */
internal fun spreadPositionDescription(spread: Int): String = "${spread + 1}番目の見開き"

/** 読書の記録へ送る1回分。同じ値は送り直さない。 */
private data class ReadingReport(val blockIndex: Int, val step: Int, val model: NoteSectionModel)

/** 測った分のブロックと、本文の最後まで測り終えたか。 */
private data class SpreadMeasured(val blocks: List<MeasuredSpreadBlock>, val complete: Boolean)

private val ReadingSaver = Saver<SpreadReading?, ArrayList<Any>>(
    save = { reading -> reading?.run { arrayListOf(noteKey, anchor.blockIndex, anchor.charOffset, parse, port, seenRequest) } },
    restore = { SpreadReading(it[0] as String, SpreadAnchor(it[1] as Int, it[2] as Int), it[3] as Int, it[4] as Long, it[5] as Long) }
)

/** ページの内側の余白（→ [SPREAD_PAGE_PADDING_DP]）。 */
private val PagePadding = SPREAD_PAGE_PADDING_DP.dp

/** 最初のページの題名。縦の流しの本文の面と同じ書式と、本文との間。 */
private val TitleStyle = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold)
private val TitleGap = 12.dp

/** 再会カードと左ページの間。 */
private val CardGap = 8.dp

/** 計測を1回のフレームで続ける時間（→ features/note_spread.md §9）。 */
private const val MEASURE_SLICE_NANOS = 4_000_000L
