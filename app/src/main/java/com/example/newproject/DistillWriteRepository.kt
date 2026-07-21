package com.example.newproject

import android.content.ContentResolver
import android.net.Uri
import com.example.newproject.domain.DistillLimits
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

internal interface DistillDocumentGateway {
    fun read(targetUri: String, maximumBytes: Int): ByteArray
    fun writeFromFile(targetUri: String, source: File)
}

internal class SafDistillDocumentGateway(
    private val contentResolver: ContentResolver
) : DistillDocumentGateway {
    override fun read(targetUri: String, maximumBytes: Int): ByteArray {
        val uri = Uri.parse(targetUri)
        return contentResolver.openInputStream(uri)?.use { readBoundedBytes(it, maximumBytes) }
            ?: throw IOException("対象ノートを開けませんでした。")
    }

    override fun writeFromFile(targetUri: String, source: File) {
        val uri = Uri.parse(targetUri)
        contentResolver.openOutputStream(uri, "wt")?.use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
            output.flush()
        } ?: throw IOException("対象ノートへ書き込めませんでした。")
    }
}

internal data class DistillWriteRequest(
    val targetUri: String,
    val baselineHash: String,
    val outputBytes: ByteArray
)

internal enum class DistillWriteFailureStage {
    VALIDATION,
    STAGING,
    RECOVERY_RECORD,
    PRE_WRITE_CHECK,
    WRITE,
    VERIFY,
    CLEANUP
}

internal sealed interface DistillWriteResult {
    data class Success(val outputHash: String, val outputByteCount: Int) : DistillWriteResult
    data class Conflict(val actualHash: String?, val message: String) : DistillWriteResult
    data class InsufficientStorage(val requiredBytes: Long, val availableBytes: Long) : DistillWriteResult
    data class StorageUnavailable(val message: String) : DistillWriteResult
    data class PendingRecovery(val state: DistillRecoveryReadResult) : DistillWriteResult
    data class Failure(
        val stage: DistillWriteFailureStage,
        val message: String,
        val recoveryRequired: Boolean
    ) : DistillWriteResult
}

internal sealed interface DistillRecoveryAssessment {
    data object None : DistillRecoveryAssessment
    data class Corrupt(val reason: String) : DistillRecoveryAssessment
    data class OriginalStillPresent(val record: DistillRecoveryRecord) : DistillRecoveryAssessment
    data class ExpectedOutputPresent(val record: DistillRecoveryRecord) : DistillRecoveryAssessment
    data class Diverged(val record: DistillRecoveryRecord, val currentHash: String) : DistillRecoveryAssessment
    data class Inaccessible(val record: DistillRecoveryRecord, val message: String) : DistillRecoveryAssessment
}

internal enum class DistillWriteCheckpoint {
    AFTER_STAGING,
    AFTER_RECOVERY_CREATED,
    AFTER_PHASE_WRITING,
    AFTER_DOCUMENT_WRITE
}

internal fun interface DistillWriteFaultInjector {
    fun at(checkpoint: DistillWriteCheckpoint)
}

internal data class PendingDistillOriginal(
    val targetUri: String,
    val bytes: ByteArray
)

internal sealed interface DistillRecoveryResolutionResult {
    data class Restored(val targetUri: String, val originalHash: String) : DistillRecoveryResolutionResult
    data class Failure(val message: String) : DistillRecoveryResolutionResult
    data object NoValidRecord : DistillRecoveryResolutionResult
}

internal interface DistillPersistence {
    fun write(request: DistillWriteRequest): DistillWriteResult
    fun assessPendingRecovery(): DistillRecoveryAssessment
    fun discardResolvedRecovery(assessment: DistillRecoveryAssessment): Boolean
    fun discardPendingRecovery(): Boolean
    fun pendingOriginal(): PendingDistillOriginal?
    fun restoreOriginal(): DistillRecoveryResolutionResult
}

/**
 * SAFがrenameを保証しない前提で、復旧可能性を確保してから一気書きし、再読込ハッシュで確定する。
 */
internal class DistillWriteRepository(
    private val gateway: DistillDocumentGateway,
    private val recoveryStore: DistillRecoveryStore,
    private val cacheDirectory: File,
    private val faultInjector: DistillWriteFaultInjector = DistillWriteFaultInjector { },
    private val availableSpaceProvider: (() -> Long)? = null
) : DistillPersistence {
    @Synchronized
    override fun write(request: DistillWriteRequest): DistillWriteResult {
        cleanupStaleCacheFiles()
        val pending = recoveryStore.read()
        if (pending !is DistillRecoveryReadResult.None) {
            return DistillWriteResult.PendingRecovery(pending)
        }
        if (!HASH_PATTERN.matches(request.baselineHash)) {
            return DistillWriteResult.Failure(
                DistillWriteFailureStage.VALIDATION,
                "基準ハッシュが不正です。",
                recoveryRequired = false
            )
        }
        if (request.targetUri.isBlank()) {
            return DistillWriteResult.Failure(
                DistillWriteFailureStage.VALIDATION,
                "対象URIが空です。",
                recoveryRequired = false
            )
        }
        if (request.outputBytes.size > MAX_OUTPUT_BYTES) {
            return DistillWriteResult.Failure(
                DistillWriteFailureStage.VALIDATION,
                "書き込み後のノートがサイズ上限を超えています。",
                recoveryRequired = false
            )
        }
        try {
            decodeUtf8Strict(request.outputBytes)
        } catch (error: InvalidNoteEncodingException) {
            return DistillWriteResult.Failure(
                DistillWriteFailureStage.VALIDATION,
                error.message.orEmpty(),
                recoveryRequired = false
            )
        }

        val initialBytes = try {
            gateway.read(request.targetUri, DistillLimits.MAX_FILE_BYTES)
        } catch (error: Exception) {
            return DistillWriteResult.Failure(
                DistillWriteFailureStage.PRE_WRITE_CHECK,
                error.message ?: "保存前のノートを読み取れませんでした。",
                recoveryRequired = false
            )
        }
        val initialHash = sha256Hex(initialBytes)
        if (initialHash != request.baselineHash) {
            return DistillWriteResult.Conflict(initialHash, "ノートが外部で変更されています。再解析してください。")
        }

        val requiredBytes = requiredDistillStorageBytes(initialBytes.size, request.outputBytes.size)
        val availableBytes = try {
            availableSpaceProvider?.invoke() ?: run {
                val cacheSpace = (cacheDirectory.takeIf { it.exists() } ?: cacheDirectory.parentFile)
                    ?.usableSpace ?: 0L
                minOf(cacheSpace, recoveryStore.usableSpace())
            }
        } catch (error: Exception) {
            return DistillWriteResult.StorageUnavailable(
                error.message ?: "内部ストレージの空き容量を確認できません。"
            )
        }
        if (availableBytes <= 0L) {
            return DistillWriteResult.StorageUnavailable("内部ストレージの空き容量を確認できません。")
        }
        if (availableBytes < requiredBytes) {
            return DistillWriteResult.InsufficientStorage(requiredBytes, availableBytes)
        }

        if (!cacheDirectory.exists() && !cacheDirectory.mkdirs()) {
            return DistillWriteResult.Failure(
                DistillWriteFailureStage.STAGING,
                "蒸留キャッシュ用ディレクトリを作成できません。",
                recoveryRequired = false
            )
        }
        val stageFile = File(cacheDirectory, "$CACHE_PREFIX${UUID.randomUUID()}.tmp")
        var recordCreated = false
        var documentWriteStarted = false
        var failureStage = DistillWriteFailureStage.STAGING
        try {
            writeStageFile(stageFile, request.outputBytes)
            val expectedHash = sha256Hex(request.outputBytes)
            if (sha256Hex(stageFile.readBytes()) != expectedHash) {
                return DistillWriteResult.Failure(
                    DistillWriteFailureStage.STAGING,
                    "蒸留キャッシュの検証に失敗しました。",
                    recoveryRequired = false
                )
            }
            faultInjector.at(DistillWriteCheckpoint.AFTER_STAGING)

            failureStage = DistillWriteFailureStage.RECOVERY_RECORD
            var recoveryRecord = recoveryStore.newRecord(
                targetUri = request.targetUri,
                originalHash = initialHash,
                expectedHash = expectedHash,
                originalBytes = initialBytes
            )
            recoveryStore.create(recoveryRecord)
            recordCreated = true
            faultInjector.at(DistillWriteCheckpoint.AFTER_RECOVERY_CREATED)

            failureStage = DistillWriteFailureStage.PRE_WRITE_CHECK
            val finalBytes = gateway.read(request.targetUri, DistillLimits.MAX_FILE_BYTES)
            val finalHash = sha256Hex(finalBytes)
            if (finalHash != request.baselineHash) {
                if (!recoveryStore.delete()) {
                    return DistillWriteResult.Failure(
                        DistillWriteFailureStage.CLEANUP,
                        "競合を検知しましたが、準備済み復旧レコードを削除できませんでした。",
                        recoveryRequired = true
                    )
                }
                recordCreated = false
                return DistillWriteResult.Conflict(finalHash, "保存直前にノートが変更されました。再解析してください。")
            }

            failureStage = DistillWriteFailureStage.RECOVERY_RECORD
            recoveryRecord = recoveryStore.updatePhase(recoveryRecord, DistillWritePhase.WRITING)
            check(recoveryRecord.phase == DistillWritePhase.WRITING)
            faultInjector.at(DistillWriteCheckpoint.AFTER_PHASE_WRITING)

            failureStage = DistillWriteFailureStage.WRITE
            documentWriteStarted = true
            gateway.writeFromFile(request.targetUri, stageFile)
            faultInjector.at(DistillWriteCheckpoint.AFTER_DOCUMENT_WRITE)

            failureStage = DistillWriteFailureStage.VERIFY
            val writtenBytes = gateway.read(request.targetUri, MAX_OUTPUT_BYTES)
            val writtenHash = sha256Hex(writtenBytes)
            if (writtenHash != expectedHash) {
                return DistillWriteResult.Failure(
                    DistillWriteFailureStage.VERIFY,
                    "書き込み後の内容を検証できませんでした。復旧が必要です。",
                    recoveryRequired = true
                )
            }

            failureStage = DistillWriteFailureStage.CLEANUP
            if (!recoveryStore.delete()) {
                return DistillWriteResult.Failure(
                    DistillWriteFailureStage.CLEANUP,
                    "書き込みは完了しましたが、復旧レコードを削除できませんでした。",
                    recoveryRequired = true
                )
            }
            recordCreated = false
            return DistillWriteResult.Success(expectedHash, writtenBytes.size)
        } catch (error: Exception) {
            val recoveryRequired = documentWriteStarted || (recordCreated && !recoveryStore.delete())
            return DistillWriteResult.Failure(
                stage = failureStage,
                message = error.message ?: "蒸留の保存に失敗しました。",
                recoveryRequired = recoveryRequired
            )
        } finally {
            if (stageFile.exists()) stageFile.delete()
        }
    }

    override fun assessPendingRecovery(): DistillRecoveryAssessment = when (val state = recoveryStore.read()) {
        DistillRecoveryReadResult.None -> DistillRecoveryAssessment.None
        is DistillRecoveryReadResult.Corrupt -> DistillRecoveryAssessment.Corrupt(state.reason)
        is DistillRecoveryReadResult.Valid -> {
            val record = state.record
            try {
                val current = gateway.read(record.targetUri, MAX_OUTPUT_BYTES)
                val currentHash = sha256Hex(current)
                when (currentHash) {
                    record.originalHash -> DistillRecoveryAssessment.OriginalStillPresent(record)
                    record.expectedHash -> DistillRecoveryAssessment.ExpectedOutputPresent(record)
                    else -> DistillRecoveryAssessment.Diverged(record, currentHash)
                }
            } catch (error: Exception) {
                DistillRecoveryAssessment.Inaccessible(
                    record,
                    error.message ?: "対象ノートへアクセスできません。"
                )
            }
        }
    }

    /** 元ハッシュ/期待ハッシュ一致を確認済みのレコードだけを安全に破棄する。 */
    override fun discardResolvedRecovery(assessment: DistillRecoveryAssessment): Boolean = when (assessment) {
        is DistillRecoveryAssessment.OriginalStillPresent,
        is DistillRecoveryAssessment.ExpectedOutputPresent -> recoveryStore.delete()
        else -> false
    }

    override fun discardPendingRecovery(): Boolean = recoveryStore.delete()

    override fun pendingOriginal(): PendingDistillOriginal? =
        (recoveryStore.read() as? DistillRecoveryReadResult.Valid)?.record?.let { record ->
            PendingDistillOriginal(record.targetUri, record.originalBytes.copyOf())
        }

    @Synchronized
    override fun restoreOriginal(): DistillRecoveryResolutionResult {
        val record = (recoveryStore.read() as? DistillRecoveryReadResult.Valid)?.record
            ?: return DistillRecoveryResolutionResult.NoValidRecord
        if (!cacheDirectory.exists() && !cacheDirectory.mkdirs()) {
            return DistillRecoveryResolutionResult.Failure("復元用キャッシュを作成できません。")
        }
        val stageFile = File(cacheDirectory, "$CACHE_PREFIX${UUID.randomUUID()}-restore.tmp")
        return try {
            writeStageFile(stageFile, record.originalBytes)
            if (sha256Hex(stageFile.readBytes()) != record.originalHash) {
                DistillRecoveryResolutionResult.Failure("復元データの検証に失敗しました。")
            } else {
                gateway.writeFromFile(record.targetUri, stageFile)
                val restored = gateway.read(record.targetUri, DistillLimits.MAX_FILE_BYTES)
                if (sha256Hex(restored) != record.originalHash) {
                    DistillRecoveryResolutionResult.Failure("復元後のノートを検証できませんでした。")
                } else if (!recoveryStore.delete()) {
                    DistillRecoveryResolutionResult.Failure("復元は完了しましたが、復旧レコードを削除できませんでした。")
                } else {
                    DistillRecoveryResolutionResult.Restored(record.targetUri, record.originalHash)
                }
            }
        } catch (error: Exception) {
            DistillRecoveryResolutionResult.Failure(error.message ?: "元本文へ復元できませんでした。")
        } finally {
            if (stageFile.exists()) stageFile.delete()
        }
    }

    fun cleanupStaleCacheFiles() {
        cacheDirectory.listFiles { file -> file.isFile && file.name.startsWith(CACHE_PREFIX) }
            ?.forEach { it.delete() }
    }

    private fun writeStageFile(file: File, bytes: ByteArray) {
        FileOutputStream(file).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
    }

    companion object {
        private const val CACHE_PREFIX = "distill-output-"
        private const val MAX_OUTPUT_BYTES =
            DistillLimits.MAX_FILE_BYTES + (DistillLimits.FINAL_SELECTION_LIMIT * 4)
        private val HASH_PATTERN = Regex("[0-9a-f]{64}")
    }
}

private const val DISTILL_STORAGE_OVERHEAD_BYTES = 64L * 1024L

internal fun requiredDistillStorageBytes(originalByteCount: Int, outputByteCount: Int): Long {
    require(originalByteCount >= 0)
    require(outputByteCount >= 0)
    return outputByteCount.toLong() + originalByteCount.toLong() * 2L + DISTILL_STORAGE_OVERHEAD_BYTES
}
