package com.example.newproject.ui.screen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

// 机の画面で、本文と補助の面のどちらかを横の払いで1画面にする（→ features/margin_pane.md §5.1）。
// 縦の払いはつまみで操作の帯を呼び、横の払いは片側を広げる、と役目を分ける。

/** 机の画面の広げ方。 */
internal enum class DeskSpread {
    /** 本文と補助の面を同じ大きさで並べる。 */
    Both,

    /** 本文だけを1画面に。補助の面は組んだまま右へ隠れる。 */
    MainOnly,

    /** 補助の面だけを1画面に。本文は組んだまま左へ隠れる。 */
    SupportingOnly
}

/** 机の画面の2つの面（→ system/fold_experience.md §3）。 */
internal enum class DeskFace { Main, Supporting }

internal fun DeskSpread.shows(face: DeskFace): Boolean = when (this) {
    DeskSpread.Both -> true
    DeskSpread.MainOnly -> face == DeskFace.Main
    DeskSpread.SupportingOnly -> face == DeskFace.Supporting
}

/**
 * [face] の中身へ送る依頼（見出しの印・要約の入口・見出し名で本文へ飛ぶ）が来たときの広げ方。
 * **隠れていれば2面へ戻す** — 隠れた面へ送っても、利用者には何も起きないように見える。
 */
internal fun deskSpreadShowing(current: DeskSpread, face: DeskFace): DeskSpread =
    if (current.shows(face)) current else DeskSpread.Both

/**
 * 端末AIが使えない理由を見せるとき、机の画面のペインを出ている面として数えるか（→ `opensFaceForNotice`）。
 * 本文だけにしている間はペインが隠れているので数えず、理由が届けばペインを戻して見せる。
 * **ただし並べ読みを隠しているときは数える** — 戻してもペインは並べ読みのままで、理由の行が無い。並べ読みを閉じてまで見せず、
 * 余白へ戻ったときか、要約の入口でもう一度見に行ったときに見せる。
 */
internal fun deskPaneCountsAsFace(spread: DeskSpread, reading: Boolean): Boolean = spread.shows(DeskFace.Supporting) || reading

/** 横に払って離したときの扱い。 */
internal enum class DeskSpreadGesture { Left, Right, None }

/**
 * 横に払って離したときの扱いを決める（横かどうかは動いたと言えた時点で [isSpreadSwipe] が決めた後）。
 * [dragDp] は触れた位置から離した位置までの横の距離（右が正）、[velocityDpPerSec] は離したときの横の速さ。
 * **距離か速さのどちらかで決める** — 短く素早く払う（フリック）は距離が足りなくても広げる。向きは距離の符号で決め、速さが逆向きなら数えない。
 */
internal fun deskSpreadGestureFor(dragDp: Float, velocityDpPerSec: Float): DeskSpreadGesture = when {
    dragDp < 0f && (dragDp <= -DESK_SPREAD_SWIPE_DP || velocityDpPerSec <= -DESK_SPREAD_FLING_DP_PER_SEC) -> DeskSpreadGesture.Left
    dragDp > 0f && (dragDp >= DESK_SPREAD_SWIPE_DP || velocityDpPerSec >= DESK_SPREAD_FLING_DP_PER_SEC) -> DeskSpreadGesture.Right
    else -> DeskSpreadGesture.None
}

/**
 * 指が動いたと言えた時点の移動（[dx], [dy]）から、片側を広げる横の払いかを決める。**横の移動が縦の2倍を超えたときだけ**横とする
 * （androidx の ViewPager が横の払いを取る比と同じ）。縦より大きいだけで横にすると、真斜め45度に読み進める指で片側が広がる —
 * 本文と余白の面のどこからでも受けるので、つまみ（[isVerticalSwipe]）より強く横を求める。どちらでもない斜めは、本文のスクロールへ渡す。
 */
internal fun isSpreadSwipe(dx: Float, dy: Float): Boolean = abs(dx) > DESK_SPREAD_HORIZONTAL_RATIO * abs(dy)

/** 片側を広げる横の払いとみなす、横と縦の移動の比。 */
internal const val DESK_SPREAD_HORIZONTAL_RATIO = 2f

/**
 * 1回の払いで、仕切りを払った向きへ1段だけ動かす。左へ払うと補助の面が、右へ払うと本文が広がる。
 * 1画面から逆へ払えば2面へ戻り、もう1段は進まない（片側から反対の片側へ一度に跳ばない）。
 */
internal fun deskSpreadAfter(current: DeskSpread, gesture: DeskSpreadGesture): DeskSpread = when (gesture) {
    DeskSpreadGesture.Left -> if (current == DeskSpread.MainOnly) DeskSpread.Both else DeskSpread.SupportingOnly
    DeskSpreadGesture.Right -> if (current == DeskSpread.SupportingOnly) DeskSpread.Both else DeskSpread.MainOnly
    DeskSpreadGesture.None -> current
}

/**
 * 読み上げ（TalkBack）から広げ方を替える操作。名前と行き先の組。読み上げ中は画面をなぞる操作が読み上げの移動に使われて払えない。
 * 補助の面だけのときは、本文の上のつまみが隠れているので、戻る操作で2面へ戻す。
 */
internal fun deskSpreadActionsFor(current: DeskSpread): List<Pair<String, DeskSpread>> = when (current) {
    DeskSpread.Both -> listOf("余白を1画面に" to DeskSpread.SupportingOnly, "本文を1画面に" to DeskSpread.MainOnly)
    DeskSpread.MainOnly, DeskSpread.SupportingOnly -> listOf("2面に戻す" to DeskSpread.Both)
}

/** 広げ方の位置。補助の面だけ＝-1、2面＝0、本文だけ＝1。この値を動かして仕切りを滑らせる。 */
internal val DeskSpread.position: Float
    get() = when (this) {
        DeskSpread.SupportingOnly -> -1f
        DeskSpread.Both -> 0f
        DeskSpread.MainOnly -> 1f
    }

/** 本文と補助の面の左端と幅。単位は呼び出し側に合わせる。 */
internal data class DeskFacePlacement(val mainX: Float, val mainWidth: Float, val supportingX: Float, val supportingWidth: Float)

/**
 * 広げ方の位置 [position]（-1〜1）から、本文と補助の面の左端と幅を決める。[total] は机の画面の幅、
 * [main] は2面のときの本文の幅、[gutter] は溝の幅。
 *
 * **広がる面だけが幅を変え、隠れる面は2面のときの幅のまま外へ滑る。** 隠れる面まで縮めると、
 * 滑っている間ずっと中身が折り返し直され、崩れながら消えるように見える。
 */
internal fun deskFacePlacement(position: Float, total: Float, main: Float, gutter: Float): DeskFacePlacement {
    val supporting = total - main - gutter
    // 仕切り（本文の右端）。0 で2面の位置、1 で窓の右端、-1 で補助の面の左端が窓の左端に来る位置。
    val split = if (position >= 0f) main + position * (total - main) else main + position * (main + gutter)
    val mainWidth = maxOf(main, split)
    val supportingX = split + gutter
    return DeskFacePlacement(
        mainX = split - mainWidth,
        mainWidth = mainWidth,
        supportingX = supportingX,
        supportingWidth = maxOf(supporting, total - supportingX)
    )
}

/**
 * 速さが足りないときに、片側を広げるのに払う距離。Material3 のシートが位置で開閉を決める距離（56dp）にそろえる。
 * 縦のつまみ（24dp）より長いのは、本文の上でも受けるため — 読んでいる途中の斜めの指で広がらないように。
 */
internal const val DESK_SPREAD_SWIPE_DP = 56f

/** 距離が足りなくても広げる速さ。Material3 の横から出るドロワーが払いで開閉する速さ（400dp/秒）にそろえる。 */
internal const val DESK_SPREAD_FLING_DP_PER_SEC = 400f

/**
 * 本文と補助の面を広げ方 [spread] に従って並べ、仕切りを滑らせる。[mainWidth] と [gutter] は2面のときの本文と溝の幅。
 *
 * **隠れた面は組んだまま窓の外へ置く。** 組むのをやめると、補助の面のスクロール位置や並べ読みの読みかけが戻したときに失われる。
 * 滑り終えたら読み上げから外す（窓の外の面へ読み上げが移らないように）。指は窓の外へ届かないので、触れることもない。
 */
@Composable
internal fun DeskFaces(
    spread: DeskSpread,
    mainWidth: Dp,
    gutter: Dp,
    modifier: Modifier = Modifier,
    main: @Composable () -> Unit,
    supporting: @Composable () -> Unit
) {
    val target = spread.position
    val position = remember { Animatable(target) }
    LaunchedEffect(target) { position.animateTo(target, tween(DESK_MOTION_MILLIS, easing = FastOutSlowInEasing)) }
    // 位置は毎フレーム変わるので、組み直しは隠れたかどうかが変わったときだけにする（位置は並べるときに読む）。
    val mainHidden by remember(spread) { derivedStateOf { position.value == target && !spread.shows(DeskFace.Main) } }
    val supportingHidden by remember(spread) { derivedStateOf { position.value == target && !spread.shows(DeskFace.Supporting) } }
    Layout(
        contents = listOf(
            { Box(modifier = if (mainHidden) Modifier.clearAndSetSemantics {} else Modifier) { main() } },
            { Box(modifier = if (supportingHidden) Modifier.clearAndSetSemantics {} else Modifier) { supporting() } }
        ),
        // 外へ滑った面を描かない。
        modifier = modifier.clipToBounds()
    ) { (mainMeasurables, supportingMeasurables), constraints ->
        val placement = deskFacePlacement(position.value, constraints.maxWidth.toFloat(), mainWidth.toPx(), gutter.toPx())
        val height = constraints.maxHeight
        val mains = mainMeasurables.map { it.measure(Constraints.fixed(placement.mainWidth.roundToInt(), height)) }
        val supportings = supportingMeasurables.map { it.measure(Constraints.fixed(placement.supportingWidth.roundToInt(), height)) }
        layout(constraints.maxWidth, height) {
            mains.forEach { it.place(placement.mainX.roundToInt(), 0) }
            supportings.forEach { it.place(placement.supportingX.roundToInt(), 0) }
        }
    }
}

/**
 * 補助の面を1画面に広げて戻すときの、補助の面のスクロール位置の覚え（→ features/margin_pane.md §5.1）。
 *
 * **画素で位置を持つ面（余白の面）は、1画面に広げると位置を失う。** 幅が広がると折り返しが減って中身が短くなり、
 * 位置が新しい上限まで縮められ、2面へ戻しても元に戻らない。段落で位置を持つ面（本文・並べ読み）はずれない。
 * 広げる前の位置を覚え、戻したら戻す。**1画面の間に面が動いたら**（利用者のスクロールか、面の中の送り）、戻さずにそちらを残す。
 */
internal class SupportingScrollMemory {
    private var saved: Int? = null
    private var moved = false

    /**
     * 広げ方を替える直前に呼ぶ。補助の面だけへ広げるときに、**まだ2面の幅の位置**を覚える。
     * 戻し終える前にもう一度広げたら覚え直さない — そのときの位置は戻す途中の値で、広げる前の位置ではない。
     */
    fun beforeSpread(current: DeskSpread, next: DeskSpread, scrollValue: Int) {
        if (next == DeskSpread.SupportingOnly && current != DeskSpread.SupportingOnly && saved == null) {
            saved = scrollValue
            moved = false
        }
    }

    /** 補助の面だけの間に、面が動いた。覚える前の動きは、覚えるときに数え直すので残らない。 */
    fun movedWhileWide() {
        moved = true
    }

    /** 補助の面だけでなくなったときに戻す位置。覚えていないか、1画面の間に動いたなら null（今の位置を残す）。 */
    fun restoreTarget(): Int? = saved.takeIf { !moved }

    /** 戻し終えたか、戻さないと決めた。 */
    fun forget() {
        saved = null
        moved = false
    }
}

/**
 * 補助の面を1画面に広げて戻したとき、補助の面の [scrollState] を広げる前の位置へ戻す（→ [SupportingScrollMemory]）。
 * 広げる前の位置は、広げ方を替える操作が [SupportingScrollMemory.beforeSpread] で覚えておく。
 */
@Composable
internal fun RestoreSupportingScroll(spread: DeskSpread, scrollState: ScrollState, memory: SupportingScrollMemory) {
    val wide = spread == DeskSpread.SupportingOnly
    LaunchedEffect(wide, scrollState, memory) {
        if (wide) {
            // 広がるときに上限で縮められても動いた扱いにしない（それは利用者の操作ではない）。
            snapshotFlow { scrollState.isScrollInProgress }.first { it }
            memory.movedWhileWide()
        } else {
            val target = memory.restoreTarget()
            if (target != null) {
                // 戻る途中は幅が狭まって中身が伸びていく。伸び切る前に置くと、その時点の上限で切り詰められる。
                withTimeoutOrNull(DESK_MOTION_MILLIS * 4L) { snapshotFlow { scrollState.maxValue }.first { it >= target } }
                scrollState.scrollTo(target)
            }
            memory.forget()
        }
    }
}

/**
 * 机の画面の面で、横の払いを受けて [deskSpreadGestureFor] の扱いを [onGesture] へ渡す。**本文と補助の面のどこからでも受ける。**
 *
 * - **子より先に指を見て（Initial の段）、横と決まったら使い切る。** 子の本文のスクロール・ボタン・入力欄は、使われた指を見て手放す。
 *   横でないと決まったら手放し、本文のスクロールやつまみへ渡す。横かどうかは動いたと言えた時点の移動で決める（[isSpreadSwipe]）
 * - **長押しが成り立つまでに横と決まらなければ手放す。** 長押しの後の動きは本文の文字の選択が使う
 * - 画面の端から払い始めた指はシステムの戻る操作が先に取るので、ここへは届かない（取り返さない）
 * - **取り消された指では広げない。** 途中でシステムなどへ渡って取り消されると、Compose は最後と同じ位置の、
 *   使用済みの指離しを渡してくる。普通の指離しと同じに数えると、渡したはずの払いをこちらでも確定してしまう
 */
internal suspend fun PointerInputScope.detectDeskSpread(onGesture: (DeskSpreadGesture) -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val velocity = VelocityTracker().apply { addPointerInputChange(down) }
        var last = down
        val horizontal = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            var decided: Boolean? = null
            while (decided == null) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                if (change == null || !change.pressed) {
                    decided = false
                } else {
                    velocity.addPointerInputChange(change)
                    last = change
                    val offset = change.position - down.position
                    if (offset.getDistance() > viewConfiguration.touchSlop) decided = isSpreadSwipe(offset.x, offset.y)
                }
            }
            decided == true
        } == true
        if (!horizontal) return@awaitEachGesture
        last.consume()
        while (last.pressed) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
            // 自分で使う前から使用済みなら、取り消されたか、ほかが取った指。
            if (change == null || change.isConsumed) return@awaitEachGesture
            velocity.addPointerInputChange(change)
            change.consume()
            last = change
        }
        val dragDp = (last.position.x - down.position.x).toDp().value
        val velocityDpPerSec = velocity.calculateVelocity().x.toDp().value
        onGesture(deskSpreadGestureFor(dragDp, velocityDpPerSec))
    }
}
