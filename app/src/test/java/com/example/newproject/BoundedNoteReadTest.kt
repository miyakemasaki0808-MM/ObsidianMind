package com.example.newproject

import com.example.newproject.data.dropIncompleteUtf8Tail
import com.example.newproject.data.readAtMostBytes
import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用途別の読込予算のうち、Uri に依存しない部分（打ち切りとUTF-8境界）を固定する。
 *
 * バイト数で切ると多バイト文字が割れる。日本語ノートでは3バイト文字が並ぶので、
 * 上限がどこに来ても末尾が割れる可能性がある。
 */
class BoundedNoteReadTest {

    // ── dropIncompleteUtf8Tail ────────────────────────────────────────────

    @Test
    fun `日本語の途中で切っても化けた文字が残らない`() {
        val full = "こんにちは".toByteArray(Charsets.UTF_8)
        // 「こんに」＝9バイト。そこから1バイトだけ余分に取ると「ち」が割れる。
        val cut = full.copyOf(10)

        val repaired = dropIncompleteUtf8Tail(cut)

        assertEquals("こんに", String(repaired, Charsets.UTF_8))
        // 落とさずに復号すると置換文字が混ざることを、対比として示す。
        assertTrue(String(cut, Charsets.UTF_8).contains('�'))
    }

    @Test
    fun `文字境界ちょうどなら1バイトも落とさない`() {
        val full = "こんにちは".toByteArray(Charsets.UTF_8)
        val cut = full.copyOf(9)

        assertArrayEquals(cut, dropIncompleteUtf8Tail(cut))
    }

    @Test
    fun `ASCIIだけなら常にそのまま`() {
        val bytes = "hello world".toByteArray(Charsets.UTF_8)

        assertArrayEquals(bytes, dropIncompleteUtf8Tail(bytes))
    }

    @Test
    fun `絵文字が割れても直前の文字までは残る`() {
        // 😀 は4バイト。3バイトで切れば落ちる。
        val full = "ab😀".toByteArray(Charsets.UTF_8)
        val cut = full.copyOf(full.size - 1)

        assertEquals("ab", String(dropIncompleteUtf8Tail(cut), Charsets.UTF_8))
    }

    @Test
    fun `空でも落ちない`() {
        assertArrayEquals(ByteArray(0), dropIncompleteUtf8Tail(ByteArray(0)))
    }

    // ── readAtMostBytes ───────────────────────────────────────────────────

    @Test
    fun `上限を超える入力は打ち切られる`() {
        val source = ByteArray(100) { 'a'.code.toByte() }

        val bounded = readAtMostBytes(ByteArrayInputStream(source), maximumBytes = 30)

        assertEquals(30, bounded.bytes.size)
        assertTrue(bounded.isTruncated)
    }

    @Test
    fun `上限に満たない入力は全部読める`() {
        val source = ByteArray(10) { 'a'.code.toByte() }

        val bounded = readAtMostBytes(ByteArrayInputStream(source), maximumBytes = 30)

        assertEquals(10, bounded.bytes.size)
        assertFalse(bounded.isTruncated)
    }

    // 上限と同じ長さのノートで「切り詰めた」と誤表示しないこと。
    @Test
    fun `上限ちょうどの入力は切り詰め扱いにしない`() {
        val source = ByteArray(30) { 'a'.code.toByte() }

        val bounded = readAtMostBytes(ByteArrayInputStream(source), maximumBytes = 30)

        assertEquals(30, bounded.bytes.size)
        assertFalse(bounded.isTruncated)
    }

    // 上限を超えたら、そこから先は読まない（EOFまで読んでから捨てるとI/Oを節約できない）。
    @Test
    fun `上限に達したら残りを読まない`() {
        val source = ByteArray(1_000) { 'a'.code.toByte() }
        val stream = object : ByteArrayInputStream(source) {
            var bytesRead = 0
                private set

            override fun read(b: ByteArray, off: Int, len: Int): Int =
                super.read(b, off, len).also { if (it > 0) bytesRead += it }
        }

        readAtMostBytes(stream, maximumBytes = 30)

        // 上限30バイト＋続きの有無を見る1バイトまで。
        assertTrue("読んだのは ${stream.bytesRead} バイト", stream.bytesRead <= 31)
    }
}
