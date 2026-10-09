package com.example.newproject.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.newproject.ui.theme.AccentText
import com.example.newproject.ui.theme.OnGradientHeaderSubtitle
import com.example.newproject.ui.theme.Panel
import com.example.newproject.ui.theme.ReadingGradient

// 開いた Fold で ✎ を押した後の「机の画面」の操作（→ features/margin_pane.md §5.1）。
// 机の画面では本文とペインを同じ大きさで並べ、見出しと操作の行は出さない。
// 左の面の上のつまみを下へ払うか押すと、操作の帯が本文の上に重なって出る。帯の出し入れで本文とペインの大きさは変えない。

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
 * [dragDp] は触れた位置から離した位置までの縦の距離（下向きが正）、[velocityDpPerSec] は離したときの縦の速さ。
 *
 * **距離か速さのどちらかで決める。** 短く素早く払う（フリック）は距離が足りなくても開閉する — 距離だけで決めると、
 * 押して開閉はできるのに払っても何も起きない。向きは距離の符号で決め、速さが逆向きなら数えない。
 * 横へ払ったときは縦の距離も速さも小さいので何もしない（横の払いは片側を広げる操作に残す）。
 */
internal fun deskBarGestureFor(moved: Boolean, dragDp: Float, velocityDpPerSec: Float): DeskBarGesture = when {
    !moved -> DeskBarGesture.Tap
    dragDp > 0f && (dragDp >= DESK_BAR_SWIPE_DP || velocityDpPerSec >= DESK_BAR_FLING_DP_PER_SEC) -> DeskBarGesture.Show
    dragDp < 0f && (dragDp <= -DESK_BAR_SWIPE_DP || velocityDpPerSec <= -DESK_BAR_FLING_DP_PER_SEC) -> DeskBarGesture.Hide
    else -> DeskBarGesture.None
}

/** 速さが足りないときに、帯を出し入れするのに払う距離。 */
internal const val DESK_BAR_SWIPE_DP = 24f

/** 距離が足りなくても出し入れする速さ。Material3 のシートが払いで開閉する速さ（125dp/秒）にそろえる。 */
internal const val DESK_BAR_FLING_DP_PER_SEC = 125f

/**
 * 机の画面で、本文とペインの上に置く余白の高さ。**両方に同じだけ置く** — 片方にだけ置くと上端がずれ、同じ大きさに見えない。
 * 左ではここがつまみになり、押せる高さを兼ねる（触れる部品の下限 48dp）。
 */
internal val DeskStripHeight = 48.dp

/**
 * 左の面の上のつまみ。**下へ払うか押すと帯を出す。** 本文の縦のスクロールと取り合わないよう、払いはここでだけ受ける。
 * 読み込み中は端にくるくるを出す — 帯がしまわれている間も、次のノートを読んでいることが分かるように。
 */
@Composable
internal fun DeskGripStrip(loading: Boolean, onShow: () -> Unit, modifier: Modifier = Modifier) {
    val currentOnShow by rememberUpdatedState(onShow)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(DeskStripHeight)
            .pointerInput(Unit) {
                detectDeskGrip { gesture ->
                    if (gesture == DeskBarGesture.Tap || gesture == DeskBarGesture.Show) currentOnShow()
                }
            }
            .semantics {
                role = Role.Button
                contentDescription = "操作を出す"
                onClick(label = "操作を出す") {
                    currentOnShow()
                    true
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
        var last = down
        while (true) {
            val change = awaitPointerEvent(pass).changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            velocity.addPointerInputChange(change)
            last = change
            if (!moved && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
            if (moved) change.consume()
            if (!change.pressed) break
        }
        val dragDp = (last.position.y - down.position.y).toDp().value
        val velocityDpPerSec = velocity.calculateVelocity().y.toDp().value
        onGesture(deskBarGestureFor(moved, dragDp, velocityDpPerSec))
    }
}
