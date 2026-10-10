package com.example.newproject.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.newproject.ui.theme.AccentText
import com.example.newproject.ui.theme.OnGradientHeaderSubtitle
import com.example.newproject.ui.theme.Panel
import com.example.newproject.ui.theme.ReadingGradient
import kotlin.math.abs

// 開いた Fold で ✎ を押した後の「机の画面」の操作（→ features/margin_pane.md §5.1）。
// 机の画面では本文とペインを同じ大きさで並べ、見出しと操作の行は出さない。
// 左の面の上のつまみを下へ払うと、操作の帯が本文の上に重なって出る（指で押しても開かない）。帯の出し入れで本文とペインの大きさは変えない。

/** つまみに触れて離したときの扱い。 */
internal enum class DeskBarGesture {
    /** 動かさずに離した。押した扱い。 */
    Tap,
    Show,
    Hide,
    None
}

/**
 * つまみに触れて離したときの扱いを決める。[moved] は指が触れた位置から動いたと言える距離（タッチスロップ）を越えたか、
 * [vertical] は越えた時点で縦に払っていたか（→ [isVerticalSwipe]）、[dragDp] は触れた位置から離した位置までの縦の距離
 * （下向きが正）、[velocityDpPerSec] は離したときの縦の速さ。
 *
 * **横に払ったときは、縦に少しずれていても帯の操作にしない。** 縦の成分だけを見ると、横へ払うつもりの指で帯が開閉する
 * （縦は操作を呼び、横は片側を広げる、と分けているため）。
 * **縦なら、距離か速さのどちらかで決める。** 短く素早く払う（フリック）は距離が足りなくても開閉する — 距離だけで決めると、
 * 短く素早く払っても何も起きない。向きは距離の符号で決め、速さが逆向きなら数えない。
 */
internal fun deskBarGestureFor(moved: Boolean, vertical: Boolean, dragDp: Float, velocityDpPerSec: Float): DeskBarGesture = when {
    !moved -> DeskBarGesture.Tap
    !vertical -> DeskBarGesture.None
    dragDp > 0f && (dragDp >= DESK_BAR_SWIPE_DP || velocityDpPerSec >= DESK_BAR_FLING_DP_PER_SEC) -> DeskBarGesture.Show
    dragDp < 0f && (dragDp <= -DESK_BAR_SWIPE_DP || velocityDpPerSec <= -DESK_BAR_FLING_DP_PER_SEC) -> DeskBarGesture.Hide
    else -> DeskBarGesture.None
}

/**
 * 指が動いたと言えた時点の移動（[dx], [dy]）から、縦に払っているかを決める。**縦が横より大きいときだけ縦**で、斜め45度は縦にしない。
 * 向きは動き始めで決め、払っている途中で縦横を入れ替えない（スクロールと同じ決め方）。
 */
internal fun isVerticalSwipe(dx: Float, dy: Float): Boolean = abs(dy) > abs(dx)

/** 速さが足りないときに、帯を出し入れするのに払う距離。 */
internal const val DESK_BAR_SWIPE_DP = 24f

/** 距離が足りなくても出し入れする速さ。Material3 のシートが払いで開閉する速さ（125dp/秒）にそろえる。 */
internal const val DESK_BAR_FLING_DP_PER_SEC = 125f

/**
 * 帯が降りて出る時間と、仕切りが滑る時間。机の画面で動くものはこの速さにそろえ、終わりで減速する。
 */
internal const val DESK_MOTION_MILLIS = 250

/** 帯が巻き上がってしまう時間。出るときより短い — しまうのは読むことへ戻る操作なので、待たせない。 */
internal const val DESK_BAR_HIDE_MILLIS = 200

/**
 * 机の画面で、本文とペインの上に置く余白の高さ。**両方に同じだけ置く** — 片方にだけ置くと上端がずれ、同じ大きさに見えない。
 * 左ではここがつまみになり、払い始められる高さを兼ねる（触れる部品の下限 48dp）。
 */
internal val DeskStripHeight = 48.dp

/**
 * 左の面の上のつまみ。**下へ払うと帯を出す。指で押しても開かない**（→ features/margin_pane.md §5.1）。
 * 本文の縦のスクロールと取り合わないよう、縦の払いはここでだけ受ける（横の払いは面のどこからでも受ける → [detectDeskSpread]）。
 * **読み上げ（TalkBack）からは「操作を出す」の操作で開く。** 読み上げ中は画面をなぞる操作が読み上げの移動に使われて払えず、
 * これを外すと帯の ✎ にも届かず、机の画面から抜けられない。この操作は読み上げ機能だけが呼び、指で押しても呼ばれない。
 * 読み込み中は端にくるくるを出す — 帯がしまわれている間も、次のノートを読んでいることが分かるように。
 */
@Composable
internal fun DeskGripStrip(
    loading: Boolean,
    onShow: () -> Unit,
    spread: DeskSpread,
    onSpread: (DeskSpread) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentOnShow by rememberUpdatedState(onShow)
    val currentOnSpread by rememberUpdatedState(onSpread)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(DeskStripHeight)
            .pointerInput(Unit) {
                detectDeskGrip { gesture -> if (gesture == DeskBarGesture.Show) currentOnShow() }
            }
            .semantics {
                role = Role.Button
                contentDescription = "操作を出す"
                onClick(label = "操作を出す") {
                    currentOnShow()
                    true
                }
                // 片側を1画面にする横の払いも、読み上げ中は払えないので操作として置く（→ deskSpreadActionsFor）。
                customActions = deskSpreadActionsFor(spread).map { (label, next) ->
                    CustomAccessibilityAction(label) {
                        currentOnSpread(next)
                        true
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        GripPill()
        if (loading) {
            Surface(color = Panel, shape = CircleShape, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)) {
                CircularProgressIndicator(color = AccentText, strokeWidth = 2.dp, modifier = Modifier.padding(6.dp).size(16.dp))
            }
        }
    }
}

/**
 * つまみの上の余白も、払い始める場所に含める。**画面の上の縁から引き下ろしても帯が出る**ようにするため。
 * つまみの上には切り欠きを避ける余白と画面の余白があり、上のほうから引き下ろすと指がそこに置かれる。
 * ここを受けないと、上のほうから引き下ろしても帯が出ない。
 *
 * 本文領域の左上に置き、[reachAbove] だけ上へはみ出させる。余白ペインの中身はシートの器（切り取りをする面）の内側にあるので、
 * **器の外に置かないと、はみ出した分が指を受け取らない。** 帯が出ている間は置かない — 帯の上の操作を横取りする。
 */
@Composable
internal fun DeskGripReach(reachAbove: Dp, width: Dp, onShow: () -> Unit, onSpread: (DeskSpreadGesture) -> Unit) {
    val currentOnShow by rememberUpdatedState(onShow)
    val currentOnSpread by rememberUpdatedState(onSpread)
    Box(
        modifier = Modifier
            .offset(y = -reachAbove)
            .width(width)
            .height(reachAbove + DeskStripHeight)
            // 面の上に重ねて置くので、ここで払い始めた指は面へ届かない。横の払いもここで受ける。
            .pointerInput(Unit) { detectDeskSpread { gesture -> currentOnSpread(gesture) } }
            .pointerInput(Unit) {
                detectDeskGrip { gesture -> if (gesture == DeskBarGesture.Show) currentOnShow() }
            }
    )
}

/**
 * 本文の上に重なって出る操作の帯。中身は今の見出しの行と同じ並びで、**ボタンの位置を探し直さない。**
 * **帯のどこからでも上へ払うとしまう。** 下端のつまみは押してもしまう。
 * ボタンの上から払い始めても、そのボタンは押さない — 帯が子より先に指を見て、動いたと言えた時点で使うので、
 * ボタンは押下を取り消す。
 *
 * 背景は画面と同じ地にし、**はみ出しを切る** — 見出しの霞は左右の余白へ広げて描くので、切らないと溝とペインの上へかかる。
 */
@Composable
internal fun DeskBar(onHide: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val currentOnHide by rememberUpdatedState(onHide)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds()
            .background(ReadingGradient)
            .pointerInput(Unit) {
                detectDeskGrip(PointerEventPass.Initial) { gesture -> if (gesture == DeskBarGesture.Hide) currentOnHide() }
            }
    ) {
        content()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                // 払ってしまうのは帯が受ける。ここは押してしまう分だけ。
                .pointerInput(Unit) {
                    detectDeskGrip { gesture -> if (gesture == DeskBarGesture.Tap) currentOnHide() }
                }
                .semantics {
                    role = Role.Button
                    contentDescription = "操作をしまう"
                    onClick(label = "操作をしまう") {
                        currentOnHide()
                        true
                    }
                },
            contentAlignment = Alignment.Center
        ) { GripPill() }
    }
}

/**
 * 帯が出ている間、本文への最初の一触れで帯をしまう。**その一触れは本文へ渡さない** — 渡すと、しまうつもりの指で本文が流れる。
 * 読み上げには載せない（しまう操作は帯のつまみと戻る操作が持つ）。
 */
@Composable
internal fun DeskTouchCatcher(onTouch: () -> Unit, modifier: Modifier = Modifier) {
    val currentOnTouch by rememberUpdatedState(onTouch)
    Box(
        modifier = modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown().consume()
                currentOnTouch()
            }
        }
    )
}

/**
 * つまみの見た目。地はグラデーションのままなので、**どの色の上でも 3:1 を割らない色**を使う（→ ui_design_principles §1、操作要素）。
 * 見出しの副題の色はライトで濃く、ダークで明るい。ライトで明るい色を使うと、水色と珊瑚色の上で 2:1 余りまで落ちる。
 */
@Composable
private fun GripPill() {
    Box(
        modifier = Modifier
            .width(36.dp)
            .height(5.dp)
            .background(OnGradientHeaderSubtitle, RoundedCornerShape(3.dp))
    )
}

/**
 * つまみや帯に触れてから離すまでを見て、[deskBarGestureFor] の扱いを [onGesture] へ渡す。
 * [pass] は指を見る段。子にボタンを持つ帯は [PointerEventPass.Initial] で子より先に見て、動いた指を使って子の押下を取り消す。
 * **押すと払うを1つの検出で受ける。** 押す部品と払う部品を重ねると、払った量を指が動き始めてからしか数えず、
 * 短いフリックが判定に届かない。ここでは触れた位置から離した位置までを数える。
 * 動いたと言えてからの指は使い、ほかへ渡さない。
 */
private suspend fun PointerInputScope.detectDeskGrip(
    pass: PointerEventPass = PointerEventPass.Main,
    onGesture: (DeskBarGesture) -> Unit
) {
    awaitEachGesture {
        // 子のボタンが触れた瞬間を使っていても見る。
        val down = awaitFirstDown(requireUnconsumed = false, pass = pass)
        val velocity = VelocityTracker().apply { addPointerInputChange(down) }
        var moved = false
        var vertical = false
        var last = down
        while (true) {
            val change = awaitPointerEvent(pass).changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            velocity.addPointerInputChange(change)
            last = change
            val offset = change.position - down.position
            if (!moved && offset.getDistance() > viewConfiguration.touchSlop) {
                moved = true
                vertical = isVerticalSwipe(offset.x, offset.y)
            }
            if (moved) change.consume()
            if (!change.pressed) break
        }
        val dragDp = (last.position.y - down.position.y).toDp().value
        val velocityDpPerSec = velocity.calculateVelocity().y.toDp().value
        onGesture(deskBarGestureFor(moved, vertical, dragDp, velocityDpPerSec))
    }
}
