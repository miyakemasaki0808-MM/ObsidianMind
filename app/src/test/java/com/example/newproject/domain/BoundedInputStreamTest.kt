package com.example.newproject.domain

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 上限つきストリームの**境界**を固定する。
 *
 * **守っている一点は「上限ちょうどは超過ではない」。** 呼び出し側は
 * `size > MAX_INPUT_BYTES` だけを拒否するので上限ちょうどは許可であり、
 * ここで打ち切り扱いにすると**正常な画像が `TooLarge` として弾かれる**。
 *
 * 上限到達だけで `truncated` を立てていた実装は、読み方（単一バイト・配列・skip）や
 * デコーダの終端確認の有無で結果が変わっていた。**-1 / ちょうど / +1 の3点を、
 * 読み方を変えても同じ結果になる形で固定する。**
 */
class BoundedInputStreamTest {

    private val max = 8L

    // --- 境界（-1 / ちょうど / +1） -------------------------------------------

    @Test
    fun `上限より1バイト少ない入力は打ち切りにならない`() {
        val stream = bounded(bytes(max.toInt() - 1))

        assertArrayEquals(bytes(max.toInt() - 1), stream.readBytes())
        assertFalse(stream.truncated)
    }

    @Test
    fun `上限ちょうどの入力は打ち切りにならない`() {
        val stream = bounded(bytes(max.toInt()))

        assertArrayEquals(bytes(max.toInt()), stream.readBytes())
        // ここが本題。到達しただけで打ち切りにすると TooLarge へ化ける。
        assertFalse(stream.truncated)
    }

    @Test
    fun `上限を1バイト超える入力だけが打ち切りになる`() {
        val stream = bounded(bytes(max.toInt() + 1))

        assertArrayEquals(bytes(max.toInt()), stream.readBytes())
        assertTrue(stream.truncated)
    }

    // --- 読み方を変えても同じ結果になる ---------------------------------------

    @Test
    fun `単一バイトreadだけでも境界の判定は変わらない`() {
        assertFalse(readOneByOne(bounded(bytes(max.toInt()))).truncated)
        assertTrue(readOneByOne(bounded(bytes(max.toInt() + 1))).truncated)
    }

    @Test
    fun `配列readと単一readを混ぜても境界の判定は変わらない`() {
        val exact = bounded(bytes(max.toInt()))
        exact.read()
        exact.read(ByteArray(3), 0, 3)
        exact.readBytes()
        assertFalse(exact.truncated)

        val over = bounded(bytes(max.toInt() + 1))
        over.read()
        over.read(ByteArray(3), 0, 3)
        over.readBytes()
        assertTrue(over.truncated)
    }

    @Test
    fun `skipで進めても上限を超えず、境界の判定も変わらない`() {
        val exact = bounded(bytes(max.toInt()))
        assertEquals(max, exact.skip(100))
        assertEquals(-1, exact.read())
        assertFalse(exact.truncated)

        val over = bounded(bytes(max.toInt() + 1))
        assertEquals(max, over.skip(100))
        assertEquals(-1, over.read())
        assertTrue(over.truncated)
    }

    // --- InputStream の契約 ---------------------------------------------------

    @Test
    fun `長さ0の配列readは終端でも0を返す`() {
        val stream = bounded(bytes(max.toInt()))
        assertEquals(0, stream.read(ByteArray(4), 0, 0))

        stream.readBytes()
        // 上限到達後も同じ。-1 を返すと、長さ0の問い合わせだけで打ち切り扱いになる。
        assertEquals(0, stream.read(ByteArray(4), 0, 0))
        assertFalse(stream.truncated)
    }

    @Test
    fun `availableは上限内の残量へ丸められる`() {
        val stream = bounded(bytes(100))
        assertEquals(max.toInt(), stream.available())

        stream.read(ByteArray(5), 0, 5)
        assertEquals(3, stream.available())

        stream.readBytes()
        assertEquals(0, stream.available())
    }

    // --- 先読みの副作用 -------------------------------------------------------

    @Test
    fun `超過判定の先読みは1回だけで、元ストリームを余分に消費しない`() {
        val counting = CountingStream(bytes(max.toInt() + 5))
        val stream = BoundedInputStream(counting, max)

        stream.readBytes()
        val afterFirst = counting.consumed
        repeat(3) {
            stream.read()
            stream.read(ByteArray(4), 0, 4)
        }

        assertTrue(stream.truncated)
        // 上限8バイト＋先読み1バイトまで。読み直すたびに1バイトずつ減らさない。
        assertEquals(max + 1, afterFirst)
        assertEquals(afterFirst, counting.consumed)
    }

    /**
     * 正常な終端を打ち切りと取り違えない。
     *
     * ここが逆転すると、壊れた画像（`Broken`）が「大きすぎる」（`TooLarge`）として
     * 表示され、**ユーザーは縮小すれば直ると誤解する**。
     */
    @Test
    fun `上限に届かないまま終わった入力は打ち切りにならない`() {
        val stream = bounded(bytes(3))

        assertArrayEquals(bytes(3), stream.readBytes())
        assertEquals(-1, stream.read())
        assertFalse(stream.truncated)
    }

    // --- 補助 -----------------------------------------------------------------

    private fun bounded(source: ByteArray) = BoundedInputStream(ByteArrayInputStream(source), max)

    private fun bytes(size: Int) = ByteArray(size) { (it + 1).toByte() }

    private fun readOneByOne(stream: BoundedInputStream): BoundedInputStream {
        while (stream.read() != -1) Unit
        return stream
    }

    /** 元ストリームから実際に消費されたバイト数を数える。 */
    private class CountingStream(source: ByteArray) : InputStream() {
        private val delegate = ByteArrayInputStream(source)
        var consumed = 0L
            private set

        override fun read(): Int = delegate.read().also { if (it >= 0) consumed++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            delegate.read(b, off, len).also { if (it > 0) consumed += it }

        override fun available(): Int = delegate.available()
    }
}
