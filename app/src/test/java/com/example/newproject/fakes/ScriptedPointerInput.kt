package com.example.newproject.fakes

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.IntSize
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.runBlocking

/**
 * 指の動きを台本どおりに渡す [PointerInputScope]。密度1（px＝dp）・タッチスロップ18・長押し500ms。
 *
 * **再現しないもの:** 当たり判定、親と子への配り分け（どの段でも同じ台本を渡す）、長押しの時間切れ
 * （`withTimeoutOrNull` は待たずに中身を走らせる）。払いの検出が1本の指をどう数えるかだけを確かめる。
 */
internal class ScriptedPointerInput(private val events: List<PointerEvent>) : PointerInputScope, AwaitPointerEventScope {
    private var cursor = 0

    override val density = 1f
    override val fontScale = 1f
    override val size = IntSize(1_000, 1_000)
    override val extendedTouchPadding = Size.Zero
    override var currentEvent = PointerEvent(emptyList())
    override val viewConfiguration = object : ViewConfiguration {
        override val longPressTimeoutMillis = 500L
        override val doubleTapTimeoutMillis = 300L
        override val doubleTapMinTimeMillis = 40L
        override val touchSlop = 18f
    }

    override suspend fun awaitPointerEvent(pass: PointerEventPass): PointerEvent {
        if (cursor == events.size) throw ScriptEnded()
        return events[cursor++].also { currentEvent = it }
    }

    override suspend fun <R> awaitPointerEventScope(block: suspend AwaitPointerEventScope.() -> R): R =
        suspendCoroutine { continuation ->
            block.startCoroutine(this, object : Continuation<R> {
                override val context = EmptyCoroutineContext
                override fun resumeWith(result: Result<R>) = continuation.resumeWith(result)
            })
        }

    /** 台本を渡し終えた。`awaitEachGesture` は終わらないので、これで抜ける。 */
    class ScriptEnded : RuntimeException()

    companion object {
        /**
         * 台本どおりに [detect] を走らせ、渡された扱いを順に返す。
         * [detect] には、検出関数と、扱いを受け取る口を渡す。
         */
        fun <T> run(events: List<PointerEvent>, detect: suspend PointerInputScope.((T) -> Unit) -> Unit): List<T> {
            val received = mutableListOf<T>()
            runBlocking {
                // 受け手の型を PointerInputScope に絞る。AwaitPointerEventScope のままだと、その中でしか呼べない関数の扱いになる。
                val scope: PointerInputScope = ScriptedPointerInput(events)
                try {
                    scope.detect { received += it }
                } catch (_: ScriptEnded) {
                }
            }
            return received
        }

        /**
         * 1本の指を、触れた位置から [dx]・[dy] だけ [durationMillis] かけて [steps] 回に分けて動かし、離す台本。
         * **[cancelled] なら、離す代わりに Compose の取り消しで終える** — 最後と同じ位置・時刻の、使用済みの指離し
         * （Compose UI 1.7.3 の `SuspendingPointerInputFilter.onCancelPointerInput` が合成する形）。
         * 取り消さなければ、同じ位置で 8ms 後に普通に離す。
         */
        fun swipe(dx: Float, dy: Float, durationMillis: Long, cancelled: Boolean = false, steps: Int = 12): List<PointerEvent> {
            val start = Offset(500f, 500f)
            fun at(step: Int) = start + Offset(dx * step / steps, dy * step / steps)
            fun time(step: Int) = 100L + durationMillis * step / steps
            val moves = (0..steps).map { step ->
                change(
                    position = at(step),
                    uptimeMillis = time(step),
                    pressed = true,
                    previousPosition = at(maxOf(step - 1, 0)),
                    previousUptimeMillis = time(maxOf(step - 1, 0)),
                    previousPressed = step != 0
                )
            }
            val end = if (cancelled) {
                PointerInputChange(
                    id = POINTER,
                    uptimeMillis = time(steps),
                    position = at(steps),
                    pressed = false,
                    previousUptimeMillis = time(steps),
                    previousPosition = at(steps),
                    previousPressed = true,
                    isInitiallyConsumed = true
                )
            } else {
                change(
                    position = at(steps),
                    uptimeMillis = time(steps) + 8,
                    pressed = false,
                    previousPosition = at(steps),
                    previousUptimeMillis = time(steps),
                    previousPressed = true
                )
            }
            return (moves + end).map { PointerEvent(listOf(it)) }
        }

        private val POINTER = PointerId(1)

        private fun change(
            position: Offset,
            uptimeMillis: Long,
            pressed: Boolean,
            previousPosition: Offset,
            previousUptimeMillis: Long,
            previousPressed: Boolean
        ) = PointerInputChange(
            id = POINTER,
            uptimeMillis = uptimeMillis,
            position = position,
            pressed = pressed,
            previousUptimeMillis = previousUptimeMillis,
            previousPosition = previousPosition,
            previousPressed = previousPressed,
            isInitiallyConsumed = false
        ).also { it.setOriginalEventPosition(position) }

        /**
         * 速さの計測は、実機では Android の入力から写される元の位置を読む。公開のコンストラクタでは原点のままなので、
         * 同じ値をここで入れる（入れないと、速さがどの払いでも同じ値になる）。
         */
        private fun PointerInputChange.setOriginalEventPosition(position: Offset) {
            PointerInputChange::class.java.getDeclaredField("originalEventPosition").apply {
                isAccessible = true
                setLong(this@setOriginalEventPosition, (position.x.toRawBits().toLong() shl 32) or (position.y.toRawBits().toLong() and 0xffffffffL))
            }
        }
    }
}
