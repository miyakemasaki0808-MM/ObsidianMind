package com.example.newproject

import com.example.newproject.controller.NoteReadGate
import com.example.newproject.controller.ReadingPauseReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 結晶の材料を控える線（→ `docs/dev/features/reflect_crystal.md` 判断4）を固定する。
 *
 * **見ているのは「どれだけ読んだら開くか」と「何を持ち越さないか」。** 誰に配るかは `NoteSessionCoordinatorTest` が、
 * 本番と同じ入口から見る。3秒の門番（`NoteDwellGateTest`）と違い、止めている間の後は**続きから足す**。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteReadGateTest {

    private val read = NoteReadGate.READ_MILLIS

    @Test
    fun `続けて読むと30秒で開く`() = runTest {
        val gate = gate()
        gate.start()
        val waiter = waitOn(gate)

        advance(read - 1)
        assertFalse("30秒の手前で開いている", waiter.opened)
        advance(1)
        assertTrue(waiter.opened)
    }

    /** **冊子を見ている間は数えず、戻ったら続きから足す。** 数え直すと、一瞬離れただけで30秒をやり直す。 */
    @Test
    fun `冊子を見ている間は数えず戻ったら続きから足す`() = runTest {
        val gate = gate()
        gate.start()
        val waiter = waitOn(gate)
        advance(20_000L)

        gate.pause(ReadingPauseReason.Booklet)
        advance(read * 10)
        assertFalse("冊子を見ている間を読んだ時間に入れた", waiter.opened)

        gate.resume(ReadingPauseReason.Booklet)
        advance(10_000L - 1)
        assertFalse(waiter.opened)
        advance(1)
        assertTrue("冊子へ行く前に読んだ時間を捨てている", waiter.opened)
    }

    /** 止めては戻るを繰り返しても、読んだ区間はすべて足す。 */
    @Test
    fun `止めては戻るを繰り返しても読んだ区間をすべて足す`() = runTest {
        val gate = gate()
        gate.start()
        val waiter = waitOn(gate)
        repeat(5) {
            advance(5_000L)
            gate.pause(ReadingPauseReason.AppBackground)
            advance(read)
            gate.resume(ReadingPauseReason.AppBackground)
        }
        advance(5_000L - 1)
        assertFalse(waiter.opened)
        advance(1)
        assertTrue(waiter.opened)
    }

    @Test
    fun `停止理由が2つあるときは両方解けるまで数えない`() = runTest {
        val gate = gate()
        gate.start()
        val waiter = waitOn(gate)
        gate.pause(ReadingPauseReason.Booklet)
        gate.pause(ReadingPauseReason.AppBackground)

        gate.resume(ReadingPauseReason.AppBackground)
        advance(read * 10)
        assertFalse("冊子が前面のまま数えている", waiter.opened)

        gate.resume(ReadingPauseReason.Booklet)
        advance(read)
        assertTrue(waiter.opened)
    }

    /** **本文が出る前の再開では数え始めない。** 背面からの復帰は起動直後、ノートを読み込む前にも届く。 */
    @Test
    fun `本文が出る前の再開では数え始めない`() = runTest {
        val gate = gate()
        val waiter = waitOn(gate)
        gate.resume(ReadingPauseReason.AppBackground)
        advance(read * 10)

        gate.start()
        advance(read - 1)
        assertFalse("本文が出る前から数えている", waiter.opened)
        advance(1)
        assertTrue(waiter.opened)
    }

    /** **届く前に離れる。** 待っていた試行は取り消され、次のノートは0から数える。 */
    @Test
    fun `届く前に離れると待っていた試行は取り消され次のノートは0から数える`() = runTest {
        val gate = gate()
        gate.start()
        val old = waitOn(gate)
        advance(read - 1)

        gate.stop()
        runCurrent()
        assertTrue("旧ノートの試行が線で待ったまま残っている", old.isCancelled)

        gate.start()
        val next = waitOn(gate)
        advance(read - 1)
        assertFalse("旧ノートで読んだ時間を持ち越している", next.opened)
        advance(1)
        assertTrue(next.opened)
    }

    /** **止めている間に離れても、読んだ時間を持ち越さない。** 止めた区間までの時間は、離れたら捨てる。 */
    @Test
    fun `止めている間に離れても読んだ時間を持ち越さない`() = runTest {
        val gate = gate()
        gate.start()
        advance(read - 1_000L)
        gate.pause(ReadingPauseReason.Booklet)
        gate.stop()
        gate.resume(ReadingPauseReason.Booklet)

        gate.start()
        val next = waitOn(gate)
        advance(read - 1)
        assertFalse("前のノートの止めた区間までの時間を持ち越している", next.opened)
    }

    /** **届いた後に離れる。** 開いた門を次のノートへ持ち越さない。 */
    @Test
    fun `届いた後に離れても次のノートは開いた門を引き継がない`() = runTest {
        val gate = gate()
        gate.start()
        advance(read)

        gate.stop()
        gate.start()
        val next = waitOn(gate)
        runCurrent()
        assertFalse("前のノートの開いた門を引き継いでいる", next.opened)
    }

    /**
     * **止めていないときの再開で、数えている区間を始め直さない。** 背面からの復帰は起動直後にも届く。
     * 始め直すと、それまでの区間の時間を足さずに捨てる。2本目のタイマーも立てない。
     */
    @Test
    fun `止めていないときの再開は数えている時間に影響しない`() = runTest {
        val gate = gate()
        gate.start()
        val waiter = waitOn(gate)
        advance(20_000L)
        gate.resume(ReadingPauseReason.AppBackground)
        advance(5_000L)

        gate.pause(ReadingPauseReason.Booklet)
        advance(read * 10)
        assertFalse("冊子を見ている間に、止め損ねたタイマーが門を開けている", waiter.opened)

        gate.resume(ReadingPauseReason.Booklet)
        advance(5_000L - 1)
        assertFalse(waiter.opened)
        advance(1)
        assertTrue("復帰の合図で区間を始め直し、それまでの時間を捨てている", waiter.opened)
    }

    @Test
    fun `同じノートで2回始めても待ち時間は延びない`() = runTest {
        val gate = gate()
        gate.start()
        val waiter = waitOn(gate)
        advance(read - 1)

        gate.start()
        advance(1)
        assertTrue("2回目の開始で数え直している", waiter.opened)
    }

    /** **一度開いたら、背面へ回っても閉じない。** */
    @Test
    fun `開いた後に背面へ回っても門は閉じない`() = runTest {
        val gate = gate()
        gate.start()
        advance(read)

        gate.pause(ReadingPauseReason.AppBackground)
        val waiter = waitOn(gate)
        runCurrent()
        assertTrue("開いた門が背面化で閉じている", waiter.opened)
    }

    private fun TestScope.gate() = NoteReadGate(backgroundScope, elapsedMillis = { testScheduler.currentTime })

    /** 線を待つ試行の代わり。**開いたことは `opened` で見る**（`Job.isCompleted` は取り消しでも真になる）。 */
    private class Waiter {
        var opened = false
        lateinit var job: Job
        val isCancelled get() = job.isCancelled
    }

    private fun TestScope.waitOn(gate: NoteReadGate): Waiter {
        val waiter = Waiter()
        waiter.job = backgroundScope.launch {
            gate.await()
            waiter.opened = true
        }
        return waiter
    }

    /** 指定時間ちょうどに予定された処理まで流す。 */
    private fun TestScope.advance(millis: Long) {
        advanceTimeBy(millis)
        runCurrent()
    }
}
