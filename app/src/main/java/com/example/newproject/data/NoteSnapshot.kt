package com.example.newproject.data

import android.net.Uri
import com.example.newproject.model.DistillLimits
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

/** 上限で打ち切って読んだ結果。[isTruncated] が真なら、ファイルには続きがある。 */
internal class BoundedBytes(val bytes: ByteArray, val isTruncated: Boolean)

/**
 * 上限まで読んで、超えた分は**捨てる**（[readBoundedBytes] と違い例外を投げない）。
 *
 * 蒸留は「大きすぎる」ことを検知して機能を止める必要があるので例外版を使うが、
 * 表示とスニペット抽出は先頭さえ読めれば成立する。EOFまで読んでから捨てるのでは
 * I/Oを節約できないため、上限に達した時点で読むのをやめる。
 */
internal fun readAtMostBytes(input: InputStream, maximumBytes: Int): BoundedBytes {
    require(maximumBytes >= 0)
    val output = ByteArrayOutputStream(minOf(maximumBytes, DISTILL_READ_BUFFER_SIZE))
    val buffer = ByteArray(DISTILL_READ_BUFFER_SIZE)
    var total = 0
    while (total < maximumBytes) {
        val read = input.read(buffer, 0, minOf(buffer.size, maximumBytes - total))
        if (read < 0) return BoundedBytes(output.toByteArray(), isTruncated = false)
        output.write(buffer, 0, read)
        total += read
    }
    // 上限ちょうどで止まった。続きがあるかは1バイト覗いて判断する
    // （無いのに「切り詰めた」と言うと、上限と同じ長さのノートで誤表示になる）。
    return BoundedBytes(output.toByteArray(), isTruncated = input.read() >= 0)
}

/**
 * 末尾の不完全なUTF-8シーケンスを落とす。
 *
 * バイト数で切ると多バイト文字が割れる。そのまま復号すると末尾に U+FFFD が残り、
 * 表示にもスニペットにも化けた1文字が混ざる。先頭バイトから必要な長さを読み取り、
 * 足りていなければその文字ごと落とす。
 *
 * 先頭バイトの上位ビットで長さが決まる: `0xxxxxxx`=1、`110xxxxx`=2、`1110xxxx`=3、
 * `11110xxx`=4。`10xxxxxx` は継続バイト。
 */
internal fun dropIncompleteUtf8Tail(bytes: ByteArray): ByteArray {
    // 継続バイトを最大3つ遡れば、必ず先頭バイトに当たる（不正な並びなら諦めて元のまま返す）。
    var index = bytes.size - 1
    val lowestLeadIndex = maxOf(0, bytes.size - 4)
    while (index >= lowestLeadIndex) {
        val byte = bytes[index].toInt() and 0xFF
        if (byte and 0xC0 != 0x80) {
            // 先頭バイト。この文字が最後まで揃っているか数える。
            val expected = when {
                byte and 0x80 == 0x00 -> 1
                byte and 0xE0 == 0xC0 -> 2
                byte and 0xF0 == 0xE0 -> 3
                byte and 0xF8 == 0xF0 -> 4
                else -> return bytes // 不正な先頭バイト。判断できないので触らない。
            }
            val available = bytes.size - index
            return if (available >= expected) bytes else bytes.copyOf(index)
        }
        index--
    }
    return bytes
}

internal fun decodeUtf8Strict(bytes: ByteArray): String = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (error: CharacterCodingException) {
    throw InvalidNoteEncodingException(error)
}
