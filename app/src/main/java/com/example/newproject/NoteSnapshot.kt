package com.example.newproject

import android.net.Uri
import com.example.newproject.domain.DistillLimits
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

internal data class NoteSnapshot(
    val uri: Uri,
    val bytes: ByteArray,
    val content: String,
    val hash: String
)

internal class NoteFileTooLargeException(
    val actualBytesAtLeast: Int,
    val maximumBytes: Int
) : IllegalArgumentException("ノートが蒸留の上限 ${maximumBytes} bytes を超えています。")

internal class InvalidNoteEncodingException(cause: CharacterCodingException) :
    IllegalArgumentException("ノートをUTF-8として厳密に読み取れません。", cause)

internal fun readBoundedBytes(
    input: InputStream,
    maximumBytes: Int = DistillLimits.MAX_FILE_BYTES
): ByteArray {
    require(maximumBytes >= 0)
    val output = ByteArrayOutputStream(minOf(maximumBytes, DISTILL_READ_BUFFER_SIZE))
    val buffer = ByteArray(DISTILL_READ_BUFFER_SIZE)
    var total = 0
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        total += read
        if (total > maximumBytes) throw NoteFileTooLargeException(total, maximumBytes)
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private const val DISTILL_READ_BUFFER_SIZE = 8 * 1024

internal fun decodeUtf8Strict(bytes: ByteArray): String = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (error: CharacterCodingException) {
    throw InvalidNoteEncodingException(error)
}
