package com.example.newproject

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.newproject.domain.DistillLimits

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
}
