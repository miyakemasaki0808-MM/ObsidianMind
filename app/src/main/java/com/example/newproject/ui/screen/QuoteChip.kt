package com.example.newproject.ui.screen

import androidx.compose.foundation.border
import com.example.newproject.ui.markdown.QuoteOffers
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.MutableState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.ui.markdown.QuoteOffer
import com.example.newproject.ui.markdown.QuoteSelection
import com.example.newproject.ui.theme.AccentText
import com.example.newproject.ui.theme.OnSurface
import com.example.newproject.ui.theme.PanelChip
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// 机の札（→ features/margin_quote.md §5.1・§5.7・判断12）。選んだ範囲の終わりの近くに出し、つまんで余白の面へ運ぶと引用が付く。
// 押すだけでも同じ。札は開いた Fold の机で、2面が並んでいるときだけ出す。
//
// **札は机の画面のいちばん上の層に置き、指を自分で受ける。** 机の横の払い（`detectDeskSpread`）と本文のスクロールは面の中の修飾子なので、
// 面の上に重なった兄弟の札から始まった指は面に届かない。札の無い場所の指は、この層を素通りして面に届く。
// ---------------------------------------------------------------------------

/** 札が今どうしているか。 */
internal sealed interface QuoteChip {
    /** 出していない。 */
    data object Hidden : QuoteChip

    /**
     * 出していない。**その選択では出し直さない**（本文のスクロール・窓や机の形の変化で消した）。選択メニューは動いた選択の位置で出し直されるので、
     * 出し直しのたびに札を出すと、選択が画面の外へ出ても札だけが本文の面に残る。本文に触れれば（新しい選択の始まり）[Hidden] へ戻る。
     */
    data object Withdrawn : QuoteChip

    /** 選択メニューと一緒に出している。押すか運び始めると、選んだ一節を受け取る（受け取ると選択は解ける）。 */
    data class Offered(val offer: QuoteOffer) : QuoteChip

    /** 一節と引用元を受け取って持っている。選択は解けている。[home] は戻る位置（窓の座標の左上）。 */
    data class Holding(val selection: QuoteSelection, val home: Offset) : QuoteChip
}

/** 札に起きること。 */
internal sealed interface QuoteChipEvent {
    /** 文字を選んで選択メニューが出た。**持っていた一節は捨てる**（別の文字を選んだ）。 */
    data class MenuShown(val offer: QuoteOffer) : QuoteChipEvent

    /** 選択メニューが閉じた。受け取る前なら札も消す。受け取った後は、受け取ったことで閉じたので持ったまま。 */
    data object MenuHidden : QuoteChipEvent

    /** 札を押した・運び始めた。[selection] は受け取った一節で、選択がもう無ければ null。[home] は札が出ていた位置。 */
    data class Taken(val selection: QuoteSelection?, val home: Offset) : QuoteChipEvent

    /** 余白へ落とした・押した。引用が付いたので消える。 */
    data object Attached : QuoteChipEvent

    /** 余白の外で離した。**持ったまま元の位置へ戻る**（→ 判断12）。 */
    data object ReleasedOutside : QuoteChipEvent

    /** 本文のほかの場所に触れた。札を消し、次に出た選択メニューでは出す（新しい選択は本文に触れて始まる）。 */
    data object Touched : QuoteChipEvent

    /** 本文がスクロールした・窓や机の形が変わった。札を消し、**その選択では出し直さない**（→ [QuoteChip.Withdrawn]）。 */
    data object Dismissed : QuoteChipEvent
}

/** 札の次の様子（→ features/margin_quote.md §5.1）。 */
internal fun quoteChipAfter(chip: QuoteChip, event: QuoteChipEvent): QuoteChip = when (event) {
    is QuoteChipEvent.MenuShown -> if (chip is QuoteChip.Withdrawn) chip else QuoteChip.Offered(event.offer)
    QuoteChipEvent.MenuHidden -> if (chip is QuoteChip.Offered) QuoteChip.Hidden else chip
    is QuoteChipEvent.Taken -> event.selection?.let { QuoteChip.Holding(it, event.home) } ?: QuoteChip.Hidden
    QuoteChipEvent.ReleasedOutside -> chip
    QuoteChipEvent.Attached, QuoteChipEvent.Touched -> QuoteChip.Hidden
    QuoteChipEvent.Dismissed -> QuoteChip.Withdrawn
}

/**
 * 札の様子を、本文の選択メニュー・本文のスクロール・机の形の変化から決める（→ features/margin_quote.md §5.1）。[shape] は机の形を決める値で、
 * 替わったら消す。本文に触れたときは本文の面に [quoteChipTouch] を付けて知らせる。
 */
@Composable
internal fun QuoteChipEffects(chip: MutableState<QuoteChip>, offers: QuoteOffers, listState: LazyListState, vararg shape: Any?) {
    LaunchedEffect(*shape) { chip.value = quoteChipAfter(chip.value, QuoteChipEvent.Dismissed) }
    LaunchedEffect(offers) {
        snapshotFlow { offers.current }.collect { offer ->
            chip.value = quoteChipAfter(chip.value, if (offer != null) QuoteChipEvent.MenuShown(offer) else QuoteChipEvent.MenuHidden)
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling) chip.value = quoteChipAfter(chip.value, QuoteChipEvent.Dismissed)
        }
    }
}

/** 本文のほかの場所に触れたら札を消し、次の選択で出せるようにする。指は使わずに見るだけ（選択と払いへそのまま渡す）。 */
internal fun Modifier.quoteChipTouch(chip: MutableState<QuoteChip>): Modifier = pointerInput(chip) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        chip.value = quoteChipAfter(chip.value, QuoteChipEvent.Touched)
    }
}

/**
 * 札を置く位置（窓の座標の左上）。**選んだ範囲の終わりの近く** — 範囲の矩形の下に右寄せで置き、下に入らなければ上に置く。
 * **本文の面 [main] の内側に収め、[gap] だけ縁から離す** — 本文の面の右の縁は溝（折り目）に接するので、折り目には置かない
 * （→ system/fold_experience.md §6）。選択メニューが渡す矩形の下は、選択の端のつまみの分だけ広げてある。
 */
internal fun quoteChipPosition(selection: Rect, main: Rect, chip: Size, gap: Float): Offset {
    val x = (selection.right - chip.width).coerceIn(main.left + gap, maxOf(main.left + gap, main.right - chip.width - gap))
    val below = selection.bottom + gap
    val y = if (below + chip.height <= main.bottom - gap) below else selection.top - chip.height - gap
    return Offset(x, y.coerceIn(main.top + gap, maxOf(main.top + gap, main.bottom - chip.height - gap)))
}

/**
 * 札を落とせる場所か。**余白の面 [supporting] のうち、折り目の側の帯 [creaseBand] を除いた範囲**（→ system/fold_experience.md §6
 * 「折り目に操作を置かない」）。折り目のすぐ横で離したものは落とさない。余白の面は折り目の向こう側（主が左）にある。
 */
internal fun quoteDropTargetFor(pointer: Offset, supporting: Rect, creaseBand: Float): Boolean =
    pointer.x >= supporting.left + creaseBand && pointer.x < supporting.right &&
        pointer.y >= supporting.top && pointer.y < supporting.bottom

/**
 * 離したときに付けるか。**離した指の位置 [finger] で決め**、描いた時点の判定（縁を強める表示）は使わない — 描く前に離すと、前の位置で決まってしまう。
 * **運び始めてから面の位置か大きさが変わっていれば付けない**（キーボードで高さだけが変わったときなど。札もそのとき消える）。
 */
internal fun quoteChipDropped(
    finger: Offset,
    mainAtStart: Rect?,
    supportingAtStart: Rect?,
    mainNow: Rect?,
    supportingNow: Rect?,
    creaseBand: Float
): Boolean =
    supportingNow != null && mainNow == mainAtStart && supportingNow == supportingAtStart &&
        quoteDropTargetFor(finger, supportingNow, creaseBand)

/** 札の大きさ。押せる範囲を広く取る。 */
internal val QUOTE_CHIP_WIDTH = 88.dp
internal val QUOTE_CHIP_HEIGHT = 40.dp

/** 札と縁・選んだ範囲の間。 */
internal val QUOTE_CHIP_GAP = 8.dp

/** 余白の面のうち、折り目の側で落とさない帯の幅。 */
internal val QUOTE_DROP_CREASE_BAND = 24.dp

/** 札の読み上げの名前。メニューの「引用して書く」と同じことをする（運ぶ操作は読み上げで扱わない）。 */
internal const val QUOTE_CHIP_DESCRIPTION = "選んだ一節を引用して書く"

/** 余白の面に入ったときの知らせ。行き先が今のノートの余白だと示す。 */
internal const val QUOTE_DROP_DESTINATION = "今のノートの余白へ"

/**
 * 札の層。机の画面の面の上に重ね、札が出ているときだけ札を描く。[chip] と [onEvent] は持ち主（机の画面）が持つ。
 * [main]・[supporting] は本文の面と余白の面の位置（窓の座標）を返す。測れていなければ null で、札を出さない。
 * **指の処理からも読むので、値ではなく読み口で受け取る** — 面を測り直した直後の指にも、描き直しを待たずに今の位置を見せる。
 * [onAttach] は引用を付ける（メニューの「引用して書く」と同じ口）。
 *
 * **面の位置か大きさが変わったら札を消す**（→ features/margin_quote.md §5.7）。並べ方の値が同じまま高さだけが変わる
 * （右の入力欄でキーボードを出す）ときも消す — 持った札の元の位置は、変わる前の面の中で決めたもの。
 */
@Composable
internal fun QuoteChipLayer(
    chip: QuoteChip,
    onEvent: (QuoteChipEvent) -> Unit,
    main: () -> Rect?,
    supporting: () -> Rect?,
    onAttach: (QuoteSelection) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val chipSize = with(density) { Size(QUOTE_CHIP_WIDTH.toPx(), QUOTE_CHIP_HEIGHT.toPx()) }
    val gap = with(density) { QUOTE_CHIP_GAP.toPx() }
    val creaseBand = with(density) { QUOTE_DROP_CREASE_BAND.toPx() }
    // 層の左上（窓の座標）。札の位置は窓の座標で決め、ここで層の中へ直す。
    var origin by remember { mutableStateOf(Offset.Zero) }
    // 運んでいる間の、札の動いた分と指の位置（窓の座標）。描くためのもので、付けるかは指の処理の中の位置で決める。離したら消す。
    var dragged by remember { mutableStateOf(Offset.Zero) }
    var pointer by remember { mutableStateOf<Offset?>(null) }
    val mainNow = main()
    val supportingNow = supporting()
    val home: Offset? = when {
        mainNow == null -> null
        chip is QuoteChip.Holding -> chip.home
        chip is QuoteChip.Offered -> quoteChipPosition(chip.offer.rect, mainNow, chipSize, gap)
        else -> null
    }
    val hovering = pointer?.let { at -> supportingNow != null && quoteDropTargetFor(at, supportingNow, creaseBand) } == true

    // 指の処理は札が長く持つので、最新の様子と口を読む（→ lessons L34）。
    val currentChip by rememberUpdatedState(chip)
    val currentHome by rememberUpdatedState(home)
    val currentOnEvent by rememberUpdatedState(onEvent)
    val currentOnAttach by rememberUpdatedState(onAttach)
    val currentMain by rememberUpdatedState(main)
    val currentSupporting by rememberUpdatedState(supporting)

    // 面の位置か大きさが変わったら消す。測れていない間と、最初に測れたときは変わったと数えない。
    val geometry = if (mainNow != null && supportingNow != null) mainNow to supportingNow else null
    val seenGeometry = remember { arrayOfNulls<Pair<Rect, Rect>>(1) }
    LaunchedEffect(geometry) {
        if (geometry == null) return@LaunchedEffect
        val before = seenGeometry[0]
        seenGeometry[0] = geometry
        if (before != null && before != geometry) currentOnEvent(QuoteChipEvent.Dismissed)
    }

    /** 持っている一節を返す。まだ受け取っていなければ受け取る（受け取ると選択は解ける）。 */
    fun held(): QuoteSelection? = when (val now = currentChip) {
        is QuoteChip.Holding -> now.selection
        is QuoteChip.Offered -> now.offer.take().also { taken -> currentOnEvent(QuoteChipEvent.Taken(taken, currentHome ?: Offset.Zero)) }
        QuoteChip.Hidden, QuoteChip.Withdrawn -> null
    }

    fun attach(selection: QuoteSelection) {
        currentOnEvent(QuoteChipEvent.Attached)
        currentOnAttach(selection)
    }

    Box(modifier = modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        if (hovering && supportingNow != null) {
            // 余白の面に入ったら縁を強め、行き先を出す（表示だけ。付けるかは離した指の位置で決める）。
            Box(
                modifier = Modifier
                    .offset { (supportingNow.topLeft - origin).round() }
                    .size(with(density) { supportingNow.width.toDp() }, with(density) { supportingNow.height.toDp() })
                    .border(2.dp, AccentText, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.TopCenter
            ) {
                Text(QUOTE_DROP_DESTINATION, color = AccentText, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
            }
        }
        if (home != null) {
            Surface(
                color = PanelChip,
                shape = RoundedCornerShape(20.dp),
                shadowElevation = if (pointer != null) 6.dp else 2.dp,
                modifier = Modifier
                    .offset { (home - origin + dragged).round() }
                    .size(QUOTE_CHIP_WIDTH, QUOTE_CHIP_HEIGHT)
                    .semantics {
                        role = Role.Button
                        contentDescription = QUOTE_CHIP_DESCRIPTION
                        onClick { held()?.let(::attach); true }
                    }
                    .pointerInput(Unit) { detectTapGestures(onTap = { held()?.let(::attach) }) }
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val from = currentHome ?: return@awaitEachGesture
                            val start = awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() } ?: return@awaitEachGesture
                            val selection = held() ?: return@awaitEachGesture
                            // **指の位置は窓の座標で1つだけ持つ。** 運び始めるまで札は動かないので、始まりは札の元の位置と、しきい値を越えたときの位置で決まる。
                            // その後は動いた量を足す — 札の局所座標の位置は札と一緒に動くので使わず、動いた量（札が動いても変わらない）だけを使う。
                            var finger = from + start.position
                            val mainAtStart = currentMain()
                            val supportingAtStart = currentSupporting()
                            // **描くための動いた分と指の位置は、どう終わっても片付ける** — 札が外れて処理が取り消されたときも。
                            // 残すと、次に選んだ一節の札がずれて出る（この2つは札より長く生きる）。
                            val dropped = try {
                                dragged = start.position - down.position
                                pointer = finger
                                val released = drag(start.id) { change ->
                                    // **動いた量は使用済みにする前に読む** — 使用済みの動きは 0 を返す。
                                    val moved = change.positionChange()
                                    change.consume()
                                    finger += moved
                                    dragged += moved
                                    pointer = finger
                                }
                                // **離した指の位置で決める**（描いた時点の縁の判定は使わない）。面が変わっていれば付けない。
                                released && quoteChipDropped(finger, mainAtStart, supportingAtStart, currentMain(), currentSupporting(), creaseBand)
                            } finally {
                                dragged = Offset.Zero
                                pointer = null
                            }
                            if (dropped) attach(selection) else currentOnEvent(QuoteChipEvent.ReleasedOutside)
                        }
                    }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text("❝ 引用", color = OnSurface, fontSize = 14.sp)
                }
            }
        }
    }
}

private fun Offset.round(): IntOffset = IntOffset(x.roundToInt(), y.roundToInt())
