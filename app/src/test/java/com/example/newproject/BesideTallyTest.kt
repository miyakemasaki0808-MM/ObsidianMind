package com.example.newproject

import com.example.newproject.controller.BesideTally
import com.example.newproject.model.BesideNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 並べた相手ごとに見えていた時間を足す（→ features/desk_memory.md §5・判断4・判断5）。
 * 線は30秒。見えている間・止めていない間だけを数え、いちばん長い相手（同じなら後のもの）を返す。
 */
class BesideTallyTest {

    private var now = 10_000L
    private val tally = BesideTally(elapsedMillis = { now })

    @Test
    fun `見えていた時間が30秒に届いた相手だけを返す`() {
        tally.show(A)
        now += 29_999
        assertNull(tally.leader())

        now += 1
        assertEquals(A, tally.leader())
    }

    /** **見えていない間は数えない。** 見えなくなってから時間が過ぎても足さない（走行中の印を残さない）。 */
    @Test
    fun `見えなくなった後の時間は足さない`() {
        tally.show(A)
        now += 20_000
        tally.show(null)
        now += 60_000

        assertNull(tally.leader())
    }

    @Test
    fun `止めている間は数えず、再開すると続きから数える`() {
        tally.show(A)
        now += 20_000
        tally.pause()
        now += 60_000
        assertNull(tally.leader())

        tally.resume()
        now += 10_000
        assertEquals(A, tally.leader())
    }

    /** 止めている間に右が替わっても、再開するまでは数えない。 */
    @Test
    fun `止めている間に見え始めた相手は再開まで数えない`() {
        tally.pause()
        tally.show(A)
        now += 60_000
        assertNull(tally.leader())

        tally.resume()
        now += 30_000
        assertEquals(A, tally.leader())
    }

    @Test
    fun `行き来した相手は足し合わせ、いちばん長いものを返す`() {
        tally.show(A)
        now += 20_000
        tally.show(B)
        now += 35_000
        tally.show(A)
        now += 20_000

        assertEquals("A は 20+20=40秒、B は 35秒", A, tally.leader())
    }

    @Test
    fun `同じ長さなら後から並べた相手を返す`() {
        tally.show(A)
        now += 30_000
        tally.show(B)
        now += 30_000
        tally.show(null)

        assertEquals(B, tally.leader())
    }

    /** 同じ相手を続けて知らされても、二重に数えない。タイトルが違っても相対パスが同じなら同じ相手。 */
    @Test
    fun `同じ相手を続けて知らされても二重に数えない`() {
        tally.show(A)
        now += 15_000
        tally.show(A.copy(title = "入口で違う題名"))
        now += 14_999

        assertNull(tally.leader())
        now += 1
        assertEquals("入口で違う題名", tally.leader()?.title)
    }

    /** 再開を重ねて呼んでも、数えている区間を始め直さない（始め直すと、それまでの時間を落とす）。 */
    @Test
    fun `止めていないときの再開は何もしない`() {
        tally.show(A)
        now += 20_000
        tally.resume()
        now += 10_000

        assertEquals(A, tally.leader())
    }

    private companion object {
        val A = BesideNote("refs/a.md", "A")
        val B = BesideNote("refs/b.md", "B")
    }
}
