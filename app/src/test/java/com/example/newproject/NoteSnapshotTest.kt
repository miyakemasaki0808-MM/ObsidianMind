package com.example.newproject

import com.example.newproject.data.InvalidNoteEncodingException
import com.example.newproject.data.NoteFileTooLargeException
import com.example.newproject.data.decodeNoteText
import com.example.newproject.data.decodeNoteTextStrict
import com.example.newproject.data.decodeUtf8Strict
import com.example.newproject.data.readBoundedBytes
import com.example.newproject.data.restoreLeadingByteOrderMark
import com.example.newproject.data.sha256Hex
import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.newproject.model.DistillLimits

class NoteSnapshotTest {

    @Test
    fun `strict UTF8 keeps BOM CRLF and emoji`() {
        val source = "\uFEFF見出し\r\n絵文字😀\r\n"
        val bytes = source.toByteArray(Charsets.UTF_8)

        assertEquals(source, decodeUtf8Strict(bytes))
        assertEquals(64, sha256Hex(bytes).length)
    }

    @Test(expected = InvalidNoteEncodingException::class)
    fun `invalid UTF8 is rejected instead of replaced`() {
        decodeUtf8Strict(byteArrayOf(0x61, 0xC3.toByte(), 0x28))
    }

    @Test(expected = NoteFileTooLargeException::class)
    fun `bounded read stops after limit`() {
        readBoundedBytes(ByteArrayInputStream(ByteArray(11)), maximumBytes = 10)
    }

    @Test
    fun `bounded read accepts exact limit`() {
        val result = readBoundedBytes(ByteArrayInputStream(ByteArray(10) { it.toByte() }), 10)

        assertEquals(10, result.size)
        assertTrue(result.indices.all { result[it] == it.toByte() })
    }

    @Test
    fun `distill maximum file size is accepted exactly`() {
        val bytes = ByteArray(DistillLimits.MAX_FILE_BYTES) { 'a'.code.toByte() }

        val result = readBoundedBytes(ByteArrayInputStream(bytes))

        assertEquals(DistillLimits.MAX_FILE_BYTES, result.size)
    }

    @Test
    fun `note text drops exactly one leading BOM and keeps CRLF and emoji`() {
        val bytes = "\uFEFF# 見出し\r\n絵文字😀\r\n".toByteArray(Charsets.UTF_8)

        assertEquals("# 見出し\r\n絵文字😀\r\n", decodeNoteTextStrict(bytes))
        assertEquals("# 見出し\r\n絵文字😀\r\n", decodeNoteText(bytes))
    }

    @Test
    fun `a second BOM and a BOM inside the body stay in the note text`() {
        val doubled = "\uFEFF\uFEFF# 見出し".toByteArray(Charsets.UTF_8)
        val inside = "本文\uFEFFの途中".toByteArray(Charsets.UTF_8)

        assertEquals("\uFEFF# 見出し", decodeNoteTextStrict(doubled))
        assertEquals("\uFEFF# 見出し", decodeNoteText(doubled))
        assertEquals("本文\uFEFFの途中", decodeNoteTextStrict(inside))
    }

    @Test
    fun `lenient note text still drops a broken tail after the BOM is removed`() {
        val full = "\uFEFF日本語".toByteArray(Charsets.UTF_8)
        val cut = full.copyOf(full.size - 1)

        assertEquals("日本", decodeNoteText(cut))
    }

    @Test(expected = InvalidNoteEncodingException::class)
    fun `strict note text still rejects invalid UTF8 behind a BOM`() {
        decodeNoteTextStrict(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 0x61, 0xC3.toByte(), 0x28))
    }

    @Test
    fun `restoring the BOM gives back the original bytes for an unchanged body`() {
        val originals = listOf(
            "\uFEFF# 見出し\r\n本文😀\r\n",
            "\uFEFF\uFEFF二重の BOM",
            "BOM なし\n",
            "\uFEFF",
            ""
        ).map { it.toByteArray(Charsets.UTF_8) }

        originals.forEach { original ->
            val body = decodeNoteTextStrict(original).toByteArray(Charsets.UTF_8)
            assertArrayEquals(original, restoreLeadingByteOrderMark(original, body))
        }
    }

    @Test
    fun `restoring never adds a BOM the original file did not have`() {
        val original = "BOM なし".toByteArray(Charsets.UTF_8)
        val output = "**BOM なし**".toByteArray(Charsets.UTF_8)

        assertArrayEquals(output, restoreLeadingByteOrderMark(original, output))
    }
}
