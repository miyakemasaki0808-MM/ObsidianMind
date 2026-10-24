package com.example.newproject.ui

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.snapFlingBehavior
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.MotionDurationScale
import com.example.newproject.ui.screen.SpreadInstantSettleSpec
import com.example.newproject.ui.screen.SpreadSettleFling
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

// 見開きで指を離した後の寄せ（→ features/note_spread.md 判断7）。OS のアニメーションを無効にしたら、寄せの途中の回転を省く。
// 寄せの実体はページャ既定の寄せ（snapFlingBehavior）なので、ここではライブラリの実体を倍率0の中で走らせて、出てくるフレームを数える。
class SpreadSettleTest {

    /** **倍率0のときだけ、途中を省く寄せを選ぶ。** 倍率1・0.5・実行コンテキストに倍率が無いときは、ページャ既定の寄せのまま。 */
    @Test
    fun `倍率0のときだけ途中を省く寄せを選ぶ`() {
        assertEquals("instant", chosenUnder(scale(0f)))
        assertEquals("smooth", chosenUnder(scale(1f)))
        assertEquals("smooth", chosenUnder(scale(0.5f)))
        assertEquals("smooth", chosenUnder(EmptyCoroutineContext))
    }

    /**
     * **途中を省く寄せは、倍率0の中で最初のフレームに行き先へ着き、途中の位置を出さない。** 行き先の規則は同じなので、着く位置は既定の寄せと同じ。
     * 対照のページャ既定の寄せ（同じばね）は、倍率0の中でも何フレームもかけて途中の位置を出す — ライブラリが寄せの倍率を1に固定するため。
     * **対照が1フレームで終わるようになったら、ライブラリが倍率に従うようになった** — `SpreadSettleFling` は要らないかもしれない。
     */
    @Test
    fun `途中を省く寄せは倍率0で最初のフレームに着き、既定の寄せは途中を出す`() {
        val instant = settleFrames(SpreadInstantSettleSpec)
        val smooth = settleFrames(spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = 1f))
        assertEquals("途中を省く寄せが最初のフレームで着かない（$instant）", listOf(TARGET), instant)
        assertTrue("対照の既定の寄せが途中を出さない（$smooth）。ライブラリの寄せが倍率に従うようになったか確かめる", smooth.size > 1)
        assertEquals("行き先が違う", TARGET, smooth.last(), 0.5f)
    }

    /** [context] の中で寄せを始めたとき、どちらの寄せが選ばれたか。 */
    private fun chosenUnder(context: CoroutineContext): String = runBlocking(context) {
        var chosen = ""
        val fling = SpreadSettleFling(smooth = recording("smooth") { chosen = it }, instant = recording("instant") { chosen = it })
        with(fling) { with(FreeScroll()) { performFling(0f) {} } }
        chosen
    }

    private fun recording(name: String, onCall: (String) -> Unit) = object : TargetedFlingBehavior {
        override suspend fun ScrollScope.performFling(initialVelocity: Float, onRemainingDistanceUpdated: (Float) -> Unit): Float {
            onCall(name)
            return 0f
        }
    }

    /** ページャ既定の寄せと同じ形（近づけずに、行き先までを [spec] で寄せる）を倍率0の中で走らせ、フレームごとの位置を返す。 */
    private fun settleFrames(spec: AnimationSpec<Float>): List<Float> = runBlocking {
        val clock = BroadcastFrameClock()
        val scroll = FreeScroll()
        val fling = snapFlingBehavior(FixedTarget, exponentialDecay(), spec)
        // Unconfined なので、起動した時点で最初のフレーム待ちに入っている。
        val job = launch(Dispatchers.Unconfined + clock + scale(0f)) {
            with(fling) { with(scroll) { performFling(0f) {} } }
        }
        val positions = mutableListOf<Float>()
        var frame = 0L
        while (job.isActive && frame < MAX_FRAMES) {
            clock.sendFrame(frame * FRAME_NANOS)
            positions += scroll.moved
            frame++
        }
        job.cancel()
        positions
    }

    /** 動かした量をそのまま受け取る。 */
    private class FreeScroll : ScrollScope {
        var moved = 0f
        override fun scrollBy(pixels: Float): Float {
            moved += pixels
            return pixels
        }
    }

    /** 近づける段を持たず（ページャの寄せは1見開きまで）、行き先が [TARGET] の寄せ。 */
    private object FixedTarget : SnapLayoutInfoProvider {
        override fun calculateApproachOffset(velocity: Float, decayOffset: Float): Float = 0f
        override fun calculateSnapOffset(velocity: Float): Float = TARGET
    }

    private fun scale(factor: Float) = object : MotionDurationScale {
        override val scaleFactor: Float = factor
    }

    private companion object {
        const val TARGET = 300f
        const val MAX_FRAMES = 200L
        const val FRAME_NANOS = 16_000_000L
    }
}
