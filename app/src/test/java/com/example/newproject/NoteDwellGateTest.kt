package com.example.newproject

import com.example.newproject.controller.NoteDwellGate
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
 * 自動生成の門番（→ `docs/dev/system/background_ai_ux.md` §7）を固定する。
 *
 * **見ているのは「いつ開くか」と「何が門を持ち越さないか」。** 門番を誰に配るかは
 * `NoteSessionCoordinatorTest` が、本番と同じ入口から見る。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteDwellGateTest {

    private val dwell = NoteDwellGate.DWELL_MILLIS

    @Test
    fun `続けて表示されるまで門は開かない`() = runTest {
        val gate = NoteDwellGate(backgroundScope)
        gate.start()
        val waiter = waitOn(gate)

        advance(dwell - 1)
        assertFalse("待ち時間の手前で開いている", waiter.opened)

        advance(1)
        assertTrue("待ち時間を過ぎても開かない", waiter.opened)
    }

    /** **始まる前から待っていた生成は、本文が出てから数える**（門を作り直して置き去りにしない）。 */
    @Test
    fun `本文が出る前から待っている生成は本文が出てから数える`() = runTest {
        val gate = NoteDwellGate(backgroundScope)
        val waiter = waitOn(gate)

        advance(dwell * 10)
        assertFalse("本文が出ていないのに開いている", waiter.opened)

        gate.start()
        advance(dwell)
        assertTrue("本文が出た後も開かない（門が置き去りになっている）", waiter.opened)
    }

    /** **本文が出る前の再開では数え始めない。** 背面からの復帰は起動直後、ノートを読み込む前にも届く。 */
    @Test
    fun `本文が出る前の再開では数え始めない`() = runTest {
        val gate = NoteDwellGate(backgroundScope)
        val waiter = waitOn(gate)
        gate.resume(ReadingPauseReason.AppBackground)
        advance(dwell * 10)

        gate.start()
        advance(dwell - 1)

        assertFalse("本文が出る前から数えている", waiter.opened)
    }

    /** **次のノートを読み込んでいる間の再開でも、本文が出るまで数えない。** */
    @Test
    fun `ノートを離れた後の再開では次の本文が出るまで数えない`() = runTest {
        val gate = NoteDwellGate(backgroundScope)
        gate.start()
        advance(dwell)
        gate.stop()

        val next = waitOn(gate)
        gate.resume(ReadingPauseReason.AppBackground)
        advance(dwell * 10)
        gate.start()
        advance(dwell - 1)

        assertFalse("次のノートの本文が出る前から数えている", next.opened)
    }

    /** **留まる前に離れる。** 待っていた生成は取り消され、次のノートは最初から数える。 */
    @Test
    fun `留まる前に離れると待っていた生成は取り消され次のノートは最初から数える`() = runTest {
        val gate = NoteDwellGate(backgroundScope)
        gate.start()
        val old = waitOn(gate)
        advance(dwell - 1)

        gate.stop()
        runCurrent()
        assertTrue("旧ノートの生成が門で待ったまま残っている", old.isCancelled)

        gate.start()
        val next = waitOn(gate)
        advance(1)
        assertFalse("旧ノートで数えた時間を持ち越している", next.opened)
        advance(dwell - 1)
        assertTrue(next.opened)
    }

    /** **留まった後に離れる。** 開いた門を次のノートへ持ち越さない（走行フラグを残さない）。 */
    @Test
    fun `留まった後に離れても次のノートは開いた門を引き継がない`() = runTest {
        val gate = NoteDwellGate(backgroundScope)
        gate.start()
        advance(dwell)

        gate.stop()
        gate.start()
        val next = waitOn(gate)
        runCurrent()

        assertFalse("前のノートの開いた門を引き継いでいる", next.opened)
    }

    /**
     * **冊子を見ている間は数えず、戻ったら最初から数える。**
     * 数えかけの時間を足していくと、冊子をめくって戻った直後に生成が始まる。
     */
    @Test
    fun `冊子を見ている間は数えず戻ったら最初から数える`() = runTest {
        val gate = NoteDwellGate(backgroundScope)
        gate.start()
        val waiter = waitOn(gate)
        advance(dwell - 1)

        gate.pause(ReadingPauseReason.Booklet)
        advance(dwell * 10)
        assertFalse("冊子を見ている間に開いている", waiter.opened)

        gate.resume(ReadingPauseReason.Booklet)
        advance(dwell - 1)
        assertFalse("冊子へ行く前の時間を足している", waiter.opened)
        advance(1)
        assertTrue(waiter.opened)
    }

    @Test
    fun `停止理由が2つあるときは両方解けるまで数えない`() = runTest {
        val gate = NoteDwellGate(backgroundScope)
        gate.pause(ReadingPauseReason.Booklet)
        gate.pause(ReadingPauseReason.AppBackground)
        gate.start()
        val waiter = waitOn(gate)

        gate.resume(ReadingPauseReason.AppBackground)
        advance(dwell * 10)
        assertFalse("冊子が前面のまま開いている", waiter.opened)

        gate.resume(ReadingPauseReason.Booklet)
        advance(dwell)
        assertTrue(waiter.opened)
    }

    /**
     * **止めていないときの再開で、2本目のタイマーを立てない。**
     * 背面からの復帰は起動直後にも届く。2本立つと、冊子で止めたのは片方だけになり、
     * もう片方が冊子を見ている間に門を開ける。
     */
    @Test
    fun `止めていないときの再開は数えている時間に影響しない`() = runTest {
        val gate = NoteDwellGate(backgroundScope)
        gate.start()
        val waiter = waitOn(gate)
        gate.resume(ReadingPauseReason.AppBackground)
        runCurrent()

        gate.pause(ReadingPauseReason.Booklet)
        advance(dwell * 10)

        assertFalse("冊子を見ている間に、止め損ねたタイマーが門を開けている", waiter.opened)
    }

    /** **一度開いたら、背面へ回っても閉じない。** 走り始めた生成を止めるのはノート切替だけ。 */
    @Test
    fun `開いた後に背面へ回っても門は閉じない`() = runTest {
        val gate = NoteDwellGate(backgroundScope)
        gate.start()
        advance(dwell)

        gate.pause(ReadingPauseReason.AppBackground)
        val waiter = waitOn(gate)
        runCurrent()

        assertTrue("開いた門が背面化で閉じている", waiter.opened)
    }

    @Test
    fun `同じノートで2回始めても待ち時間は延びない`() = runTest {
        val gate = NoteDwellGate(backgroundScope)
        gate.start()
        val waiter = waitOn(gate)
        advance(dwell - 1)

        gate.start()
        advance(1)

        assertTrue("2回目の開始で数え直している", waiter.opened)
    }

    /**
     * 門を待つ生成の代わり。**開いたことは `opened` で見る** —
     * `Job.isCompleted` は取り消されたときも真になるので、開いたことの証拠にならない。
     */
    private class Waiter {
        var opened = false
        lateinit var job: Job
        val isCancelled get() = job.isCancelled
    }

    private fun TestScope.waitOn(gate: NoteDwellGate): Waiter {
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
