package com.example.newproject

import com.example.newproject.domain.DistillLimits
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

internal enum class DistillWritePhase { PREPARED, WRITING }

internal data class DistillRecoveryRecord(
    val targetUri: String,
    val originalHash: String,
    val expectedHash: String,
    val originalBytes: ByteArray,
    val phase: DistillWritePhase,
    val createdAtEpochMillis: Long
)

internal sealed interface DistillRecoveryReadResult {
    data object None : DistillRecoveryReadResult
    data class Valid(val record: DistillRecoveryRecord) : DistillRecoveryReadResult
    data class Corrupt(val reason: String) : DistillRecoveryReadResult
}

internal class PendingDistillRecoveryException : IllegalStateException(
    "未解決の蒸留復旧レコードがあるため、新しい保存を開始できません。"
)

/** noBackupFilesDir 内に未解決1件だけを原子的に保持する。 */
internal class DistillRecoveryStore(
    private val directory: File,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val recordFile = File(directory, RECORD_FILE_NAME)

    fun newRecord(
        targetUri: String,
        originalHash: String,
        expectedHash: String,
        originalBytes: ByteArray
    ): DistillRecoveryRecord = DistillRecoveryRecord(
        targetUri = targetUri,
        originalHash = originalHash,
        expectedHash = expectedHash,
        originalBytes = originalBytes.copyOf(),
        phase = DistillWritePhase.PREPARED,
        createdAtEpochMillis = clock()
    )

    fun create(record: DistillRecoveryRecord) {
        directory.mkdirsOrThrow()
        if (recordFile.exists()) throw PendingDistillRecoveryException()
        persist(record, replaceExisting = false)
    }

    fun updatePhase(record: DistillRecoveryRecord, phase: DistillWritePhase): DistillRecoveryRecord {
        if (!recordFile.exists()) throw IllegalStateException("復旧レコードがありません。")
        val updated = record.copy(phase = phase)
        persist(updated, replaceExisting = true)
        return updated
    }

    fun read(): DistillRecoveryReadResult {
        if (!recordFile.exists()) return DistillRecoveryReadResult.None
        return try {
            val bytes = recordFile.inputStream().use { readBoundedBytes(it, MAX_RECORD_BYTES) }
            DistillRecoveryReadResult.Valid(decodeRecord(bytes))
        } catch (error: Exception) {
            DistillRecoveryReadResult.Corrupt(error.message ?: error::class.java.simpleName)
        }
    }

    fun delete(): Boolean = !recordFile.exists() || recordFile.delete()

    fun usableSpace(): Long = (directory.takeIf { it.exists() } ?: directory.parentFile)?.usableSpace ?: 0L

    private fun persist(record: DistillRecoveryRecord, replaceExisting: Boolean) {
        validateRecord(record)
        val payload = encodePayload(record)
        val checksum = MessageDigest.getInstance("SHA-256").digest(payload)
        val temp = File(directory, "$RECORD_FILE_NAME.tmp-${UUID.randomUUID()}")
        try {
            FileOutputStream(temp).use { output ->
                output.write(payload)
                output.write(checksum)
                output.flush()
                output.fd.sync()
            }
            try {
                moveRecord(temp, replaceExisting, atomic = true)
            } catch (_: AtomicMoveNotSupportedException) {
                moveRecord(temp, replaceExisting, atomic = false)
            }
        } finally {
            if (temp.exists()) temp.delete()
        }
    }

    private fun moveRecord(temp: File, replaceExisting: Boolean, atomic: Boolean) {
        val options = buildList {
            if (atomic) add(StandardCopyOption.ATOMIC_MOVE)
            if (replaceExisting) add(StandardCopyOption.REPLACE_EXISTING)
        }.toTypedArray()
        Files.move(temp.toPath(), recordFile.toPath(), *options)
    }

    private fun decodeRecord(fileBytes: ByteArray): DistillRecoveryRecord {
        require(fileBytes.size in (CHECKSUM_BYTES + 1)..MAX_RECORD_BYTES) { "復旧レコードのサイズが不正です。" }
        val payloadEnd = fileBytes.size - CHECKSUM_BYTES
        val payload = fileBytes.copyOfRange(0, payloadEnd)
        val storedChecksum = fileBytes.copyOfRange(payloadEnd, fileBytes.size)
        val actualChecksum = MessageDigest.getInstance("SHA-256").digest(payload)
        require(constantTimeEquals(storedChecksum, actualChecksum)) { "復旧レコードの整合性を確認できません。" }

        val record = DataInputStream(ByteArrayInputStream(payload)).use { input ->
            require(input.readInt() == MAGIC) { "復旧レコードの形式が不正です。" }
            require(input.readInt() == FORMAT_VERSION) { "未対応の復旧レコードです。" }
            val targetUri = input.readSizedString(MAX_URI_BYTES)
            val originalHash = input.readSizedString(MAX_HASH_BYTES)
            val expectedHash = input.readSizedString(MAX_HASH_BYTES)
            val phase = DistillWritePhase.entries.getOrNull(input.readInt())
                ?: error("書き込みフェーズが不正です。")
            val createdAt = input.readLong()
            val originalBytes = input.readSizedBytes(DistillLimits.MAX_FILE_BYTES)
            require(input.available() == 0) { "復旧レコードに未知のデータがあります。" }
            DistillRecoveryRecord(
                targetUri,
                originalHash,
                expectedHash,
                originalBytes,
                phase,
                createdAt
            )
        }
        validateRecord(record)
        return record
    }

    private fun encodePayload(record: DistillRecoveryRecord): ByteArray {
        val output = ByteArrayOutputStream(record.originalBytes.size + RECORD_OVERHEAD_BYTES)
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC)
            data.writeInt(FORMAT_VERSION)
            data.writeSizedString(record.targetUri)
            data.writeSizedString(record.originalHash)
            data.writeSizedString(record.expectedHash)
            data.writeInt(record.phase.ordinal)
            data.writeLong(record.createdAtEpochMillis)
            data.writeSizedBytes(record.originalBytes)
        }
        return output.toByteArray()
    }

    private fun validateRecord(record: DistillRecoveryRecord) {
        require(record.targetUri.isNotBlank()) { "対象URIが空です。" }
        require(record.targetUri.toByteArray(Charsets.UTF_8).size <= MAX_URI_BYTES) { "対象URIが長すぎます。" }
        require(HASH_PATTERN.matches(record.originalHash)) { "元本文ハッシュが不正です。" }
        require(HASH_PATTERN.matches(record.expectedHash)) { "期待ハッシュが不正です。" }
        require(record.originalBytes.size <= DistillLimits.MAX_FILE_BYTES) { "元本文が大きすぎます。" }
        require(sha256Hex(record.originalBytes) == record.originalHash) { "元本文とハッシュが一致しません。" }
        require(record.createdAtEpochMillis >= 0) { "作成日時が不正です。" }
    }

    private fun File.mkdirsOrThrow() {
        if (!exists() && !mkdirs()) error("復旧レコード用ディレクトリを作成できません。")
        require(isDirectory) { "復旧レコードの保存先がディレクトリではありません。" }
    }

    private fun DataOutputStream.writeSizedString(value: String) =
        writeSizedBytes(value.toByteArray(Charsets.UTF_8))

    private fun DataOutputStream.writeSizedBytes(value: ByteArray) {
        writeInt(value.size)
        write(value)
    }

    private fun DataInputStream.readSizedString(maximumBytes: Int): String =
        decodeUtf8Strict(readSizedBytes(maximumBytes))

    private fun DataInputStream.readSizedBytes(maximumBytes: Int): ByteArray {
        val size = readInt()
        require(size in 0..maximumBytes) { "復旧レコード内のデータ長が不正です。" }
        return ByteArray(size).also(::readFully)
    }

    companion object {
        private const val RECORD_FILE_NAME = "distill-recovery-v1.bin"
        private const val MAGIC = 0x4453544C // DSTL
        private const val FORMAT_VERSION = 1
        private const val CHECKSUM_BYTES = 32
        private const val RECORD_OVERHEAD_BYTES = 512
        private const val MAX_URI_BYTES = 16 * 1024
        private const val MAX_HASH_BYTES = 64
        private const val MAX_RECORD_BYTES = DistillLimits.MAX_FILE_BYTES + MAX_URI_BYTES + RECORD_OVERHEAD_BYTES
        private val HASH_PATTERN = Regex("[0-9a-f]{64}")
    }
}
