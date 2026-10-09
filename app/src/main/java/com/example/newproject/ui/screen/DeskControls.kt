package com.example.newproject.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.newproject.ui.theme.AccentText
import com.example.newproject.ui.theme.OnGradientHeaderSubtitle
import com.example.newproject.ui.theme.Panel
import com.example.newproject.ui.theme.ReadingGradient
import kotlinx.coroutines.CoroutineScope

// 開いた Fold で ✎ を押した後の「机の画面」の操作（→ features/margin_pane.md §5.1）。
// 机の画面では本文とペインを同じ大きさで並べ、見出しと操作の行は出さない。
// 左の面の上のつまみを下へ払うか押すと、操作の帯が本文の上に重なって出る。帯の出し入れで本文とペインの大きさは変えない。

/** つまみや帯を縦に払ったときの扱い。 */
internal enum class DeskBarGesture {
    Show,
    Hide,
    None
}

/**
 * 縦に払った量 [dragDp]（下向きが正）から、帯を出すかしまうかを決める。
 * **払った向きだけで決め、どこで払い始めたかは見ない** — 受けるのはつまみと帯だけで、本文のスクロールとは取り合わない。
 * 指が少し揺れただけで出し入れしないよう、[DESK_BAR_SWIPE_DP] に届かなければ何もしない（押した扱いは別に受ける）。
 */
internal fun deskBarGestureFor(dragDp: Float): DeskBarGesture = when {
    dragDp >= DESK_BAR_SWIPE_DP -> DeskBarGesture.Show
    dragDp <= -DESK_BAR_SWIPE_DP -> DeskBarGesture.Hide
    else -> DeskBarGesture.None
}

/** 帯を出し入れするのに払う距離。 */
internal const val DESK_BAR_SWIPE_DP = 24f

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
    val swipe = rememberDeskSwipe { gesture -> if (gesture == DeskBarGesture.Show) currentOnShow() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(DeskStripHeight)
            .draggable(swipe.state, Orientation.Vertical, onDragStarted = swipe.onStarted, onDragStopped = swipe.onStopped)
            .clickable(onClickLabel = "操作を出す") { currentOnShow() }
            .semantics {
                role = Role.Button
                contentDescription = "操作を出す"
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
 * 下端のつまみを上へ払うか押すとしまう。
 *
 * 背景は画面と同じ地にし、**はみ出しを切る** — 見出しの霞は左右の余白へ広げて描くので、切らないと溝とペインの上へかかる。
 */
@Composable
internal fun DeskBar(onHide: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val currentOnHide by rememberUpdatedState(onHide)
    val swipe = rememberDeskSwipe { gesture -> if (gesture == DeskBarGesture.Hide) currentOnHide() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clipToBounds()
            .background(ReadingGradient)
    ) {
        content()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .draggable(swipe.state, Orientation.Vertical, onDragStarted = swipe.onStarted, onDragStopped = swipe.onStopped)
                .clickable(onClickLabel = "操作をしまう") { currentOnHide() }
                .semantics {
                    role = Role.Button
                    contentDescription = "操作をしまう"
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

/** 縦に払うのを受ける口。[rememberDeskSwipe] で作り、`draggable` へそのまま渡す。 */
private class DeskSwipe(
    val state: DraggableState,
    val onStarted: suspend CoroutineScope.(Offset) -> Unit,
    val onStopped: suspend CoroutineScope.(Float) -> Unit
)

/** 払い終えたとき、払った量から決めた扱い（→ [deskBarGestureFor]）を [onGesture] へ渡す。 */
@Composable
private fun rememberDeskSwipe(onGesture: (DeskBarGesture) -> Unit): DeskSwipe {
    val density = LocalDensity.current
    val currentOnGesture by rememberUpdatedState(onGesture)
    val dragged = remember { mutableFloatStateOf(0f) }
    val state = rememberDraggableState { delta -> dragged.floatValue += delta }
    return remember(state, density) {
        DeskSwipe(
            state = state,
            onStarted = { dragged.floatValue = 0f },
            onStopped = { currentOnGesture(deskBarGestureFor(with(density) { dragged.floatValue.toDp().value })) }
        )
    }
}
