package com.example.newproject.ui

import androidx.compose.ui.geometry.Offset
import com.example.newproject.model.state.DistillRangeEdge
import com.example.newproject.ui.screen.distillDragHitPoint
import com.example.newproject.ui.screen.grabbedDistillEdge
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ドラッグで掴む端の決め方を固定する。**引いている途中で選び直さないこと**が要点。
 *
 * 実際の描画位置は `TextLayoutResult` が要るので `DistillRangeAdjustUiTest` が見る。
 * ここは「どちらを掴むか」という判断だけを、実機を待たずに観測する。
 */
class DistillRangeHandleTest {

    private val start = Offset(20f, 60f)
    private val end = Offset(200f, 60f)

    @Test
    fun `近いほうのつまみを掴む`() {
        assertEquals(DistillRangeEdge.Start, grabbedDistillEdge(Offset(40f, 62f), start, end))
        assertEquals(DistillRangeEdge.End, grabbedDistillEdge(Offset(180f, 62f), start, end))
    }

    @Test
    fun `行が違えば横位置が近くても掴まない`() {
        // 折り返した親文で、終点が次の行にある形。横だけで測ると取り違える。
        val wrapped = Offset(30f, 140f)

        assertEquals(DistillRangeEdge.End, grabbedDistillEdge(Offset(34f, 138f), start, wrapped))
    }

    @Test
    fun `同じ距離なら始点を採る`() {
        assertEquals(DistillRangeEdge.Start, grabbedDistillEdge(Offset(110f, 60f), start, end))
    }

    @Test
    fun `横へ引くあいだ当て先の高さは文字の上から動かない`() {
        // つまみの見た目は行の下辺（y=72）だが、当て先は文字の縦中央（y=60）から始める。
        // 行の下辺は次の行の上辺でもあるので、そのまま当てると次行の文字になる。
        val reference = Offset(200f, 60f)
        val grabbedAt = Offset(200f, 72f)

        val moved = distillDragHitPoint(reference, grabbedAt, Offset(120f, 72f))

        assertEquals(60f, moved.y, 0f)
        assertEquals(120f, moved.x, 0f)
    }

    @Test
    fun `掴んだ位置がつまみの中心からずれていても当て先は文字から始まる`() {
        val reference = Offset(200f, 60f)
        val grabbedAt = Offset(210f, 78f)

        val held = distillDragHitPoint(reference, grabbedAt, grabbedAt)

        assertEquals(reference, held)
    }

    @Test
    fun `行の高さぶん縦へ引けば当て先も次の行へ移る`() {
        val reference = Offset(200f, 60f)
        val grabbedAt = Offset(200f, 72f)
        val lineHeight = 26f

        val moved = distillDragHitPoint(reference, grabbedAt, Offset(200f, 72f + lineHeight))

        assertEquals(60f + lineHeight, moved.y, 0f)
    }
}

