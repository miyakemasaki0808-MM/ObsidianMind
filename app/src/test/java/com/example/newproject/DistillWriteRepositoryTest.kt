package com.example.newproject

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DistillWriteRepositoryTest {
    private val root = Files.createTempDirectory("distill-write-test").toFile()
    private val cache = File(root, "cache").apply { mkdirs() }
    private val noBackup = File(root, "no-backup").apply { mkdirs() }
    private val recoveryStore = DistillRecoveryStore(noBackup) { 10L }

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `successful write verifies output and removes temporary state`() {
        val original = "元本文です。".toByteArray()
        val output = "**元本文です。**".toByteArray()
        val gateway = FakeGateway(original)
        val repository = repository(gateway)

        val result = repository.write(request(original, output))

        assertTrue(result is DistillWriteResult.Success)
        assertArrayEquals(output, gateway.content)
        assertEquals(DistillRecoveryReadResult.None, recoveryStore.read())
        assertTrue(cache.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `external edit before transaction is a conflict without recovery record`() {
        val baseline = "元本文です。".toByteArray()
        val external = "外部編集です。".toByteArray()
        val gateway = FakeGateway(external)

        val result = repository(gateway).write(request(baseline, "出力".toByteArray()))

        assertTrue(result is DistillWriteResult.Conflict)
        assertArrayEquals(external, gateway.content)
        assertEquals(DistillRecoveryReadResult.None, recoveryStore.read())
    }

    @Test
    fun `edit between checks aborts before write and removes prepared recovery`() {
        val original = "元本文です。".toByteArray()
        val external = "保存直前の編集です。".toByteArray()
        val gateway = FakeGateway(original).apply {
            readOverride = { count, current -> if (count == 2) external else current }
        }

        val result = repository(gateway).write(request(original, "**元本文です。**".toByteArray()))

        assertTrue(result is DistillWriteResult.Conflict)
        assertEquals(0, gateway.writeCount)
        assertEquals(DistillRecoveryReadResult.None, recoveryStore.read())
    }

    @Test
    fun `partial SAF write failure keeps original in recovery record`() {
        val original = "元本文です。".toByteArray()
        val gateway = FakeGateway(original).apply {
            writeOverride = { _, self ->
                self.content = "部分".toByteArray()
                throw IOException("provider failure")
            }
        }

        val result = repository(gateway).write(request(original, "**元本文です。**".toByteArray()))

        assertTrue(result is DistillWriteResult.Failure && result.recoveryRequired)
        val record = (recoveryStore.read() as DistillRecoveryReadResult.Valid).record
        assertArrayEquals(original, record.originalBytes)
        assertTrue(repository(gateway).assessPendingRecovery() is DistillRecoveryAssessment.Diverged)
    }

    @Test
    fun `post write hash mismatch is not reported as success`() {
        val original = "元本文です。".toByteArray()
        val gateway = FakeGateway(original).apply {
            writeOverride = { _, self -> self.content = "壊れた出力".toByteArray() }
        }

        val result = repository(gateway).write(request(original, "**元本文です。**".toByteArray()))

        assertTrue(result is DistillWriteResult.Failure)
        result as DistillWriteResult.Failure
        assertEquals(DistillWriteFailureStage.VERIFY, result.stage)
        assertTrue(result.recoveryRequired)
        assertTrue(recoveryStore.read() is DistillRecoveryReadResult.Valid)
    }

    @Test
    fun `unresolved recovery blocks another note write`() {
        val original = "元本文です。".toByteArray()
        recoveryStore.create(
            recoveryStore.newRecord("content://old", sha256Hex(original), sha256Hex(original), original)
        )
        val gateway = FakeGateway(original)

        val result = repository(gateway).write(request(original, original, "content://new"))

        assertTrue(result is DistillWriteResult.PendingRecovery)
        assertEquals(0, gateway.readCount)
        assertEquals(0, gateway.writeCount)
    }

    @Test
    fun `crash after document write leaves assessable expected output record`() {
        val original = "元本文です。".toByteArray()
        val output = "**元本文です。**".toByteArray()
        val gateway = FakeGateway(original)
        val crashingRepository = DistillWriteRepository(
            gateway,
            recoveryStore,
            cache,
            DistillWriteFaultInjector { checkpoint ->
                if (checkpoint == DistillWriteCheckpoint.AFTER_DOCUMENT_WRITE) throw SimulatedCrash()
            }
        )

        try {
            crashingRepository.write(request(original, output))
            throw AssertionError("crash was not injected")
        } catch (_: SimulatedCrash) {
            // process death相当: Exceptionでは捕捉されず復旧レコードが残る
        }

        val assessment = repository(gateway).assessPendingRecovery()
        assertTrue(assessment is DistillRecoveryAssessment.ExpectedOutputPresent)
        assertTrue(repository(gateway).discardResolvedRecovery(assessment))
        assertEquals(DistillRecoveryReadResult.None, recoveryStore.read())
    }

    @Test
    fun `prepared recovery with unchanged document is original still present`() {
        val original = "元本文です。".toByteArray()
        val output = "**元本文です。**".toByteArray()
        recoveryStore.create(
            recoveryStore.newRecord(
                "content://vault/note",
                sha256Hex(original),
                sha256Hex(output),
                original
            )
        )

        val assessment = repository(FakeGateway(original)).assessPendingRecovery()

        assertTrue(assessment is DistillRecoveryAssessment.OriginalStillPresent)
    }

    @Test
    fun `inaccessible recovery target is reported without deleting original record`() {
        val original = "元本文です。".toByteArray()
        recoveryStore.create(
            recoveryStore.newRecord(
                "content://vault/note",
                sha256Hex(original),
                sha256Hex("output".toByteArray()),
                original
            )
        )
        val gateway = FakeGateway(original).apply {
            readOverride = { _, _ -> throw IOException("permission lost") }
        }

        val assessment = repository(gateway).assessPendingRecovery()

        assertTrue(assessment is DistillRecoveryAssessment.Inaccessible)
        assertTrue(recoveryStore.read() is DistillRecoveryReadResult.Valid)
    }

    @Test
    fun `corrupt recovery record is surfaced by assessment`() {
        val original = "元本文です。".toByteArray()
        recoveryStore.create(
            recoveryStore.newRecord(
                "content://vault/note",
                sha256Hex(original),
                sha256Hex(original),
                original
            )
        )
        val recordFile = noBackup.listFiles().orEmpty().single { it.name.endsWith(".bin") }
        val bytes = recordFile.readBytes()
        bytes[10] = (bytes[10].toInt() xor 1).toByte()
        recordFile.writeBytes(bytes)

        val assessment = repository(FakeGateway(original)).assessPendingRecovery()

        assertTrue(assessment is DistillRecoveryAssessment.Corrupt)
    }

    @Test
    fun `zero or unavailable usable space aborts before staging`() {
        val original = "元本文です。".toByteArray()
        val zeroGateway = FakeGateway(original)
        val zeroResult = DistillWriteRepository(
            zeroGateway,
            recoveryStore,
            cache,
            availableSpaceProvider = { 0L }
        ).write(request(original, "output".toByteArray()))

        assertTrue(zeroResult is DistillWriteResult.StorageUnavailable)
        assertEquals(0, zeroGateway.writeCount)
        assertEquals(DistillRecoveryReadResult.None, recoveryStore.read())

        val errorGateway = FakeGateway(original)
        val errorResult = DistillWriteRepository(
            errorGateway,
            recoveryStore,
            cache,
            availableSpaceProvider = { throw IOException("stat failed") }
        ).write(request(original, "output".toByteArray()))

        assertTrue(errorResult is DistillWriteResult.StorageUnavailable)
        assertEquals(0, errorGateway.writeCount)
    }

    @Test
    fun `insufficient storage reports conservative peak requirement`() {
        val original = ByteArray(1024) { 'a'.code.toByte() }
        val output = original + "****".toByteArray()
        val gateway = FakeGateway(original)
        val result = DistillWriteRepository(
            gateway,
            recoveryStore,
            cache,
            availableSpaceProvider = { 1L }
        ).write(request(original, output))

        assertTrue(result is DistillWriteResult.InsufficientStorage)
        result as DistillWriteResult.InsufficientStorage
        assertTrue(result.requiredBytes > original.size * 2L)
        assertEquals(requiredDistillStorageBytes(original.size, output.size), result.requiredBytes)
        assertEquals(0, gateway.writeCount)
    }

    @Test
    fun `explicit recovery restores original and removes record after verification`() {
        val original = "保存前の本文".toByteArray()
        val changed = "部分書き込み".toByteArray()
        recoveryStore.create(
            recoveryStore.newRecord(
                "content://vault/note",
                sha256Hex(original),
                sha256Hex("期待出力".toByteArray()),
                original
            )
        )
        val gateway = FakeGateway(changed)
        val repository = repository(gateway)

        val result = repository.restoreOriginal()

        assertTrue(result is DistillRecoveryResolutionResult.Restored)
        assertArrayEquals(original, gateway.content)
        assertEquals(DistillRecoveryReadResult.None, recoveryStore.read())
    }

    @Test
    fun `failed explicit restore keeps recovery and exportable original`() {
        val original = "保存前の本文".toByteArray()
        recoveryStore.create(
            recoveryStore.newRecord(
                "content://vault/note",
                sha256Hex(original),
                sha256Hex("期待出力".toByteArray()),
                original
            )
        )
        val gateway = FakeGateway("現在".toByteArray()).apply {
            writeOverride = { _, _ -> throw IOException("write failed") }
        }
        val repository = repository(gateway)

        val result = repository.restoreOriginal()

        assertTrue(result is DistillRecoveryResolutionResult.Failure)
        assertTrue(recoveryStore.read() is DistillRecoveryReadResult.Valid)
        assertArrayEquals(original, repository.pendingOriginal()!!.bytes)
    }

    @Test
    fun `startup cleanup removes only distill cache files`() {
        File(cache, "distill-output-old.tmp").writeText("old")
        val unrelated = File(cache, "keep.tmp").apply { writeText("keep") }

        repository(FakeGateway(byteArrayOf())).cleanupStaleCacheFiles()

        assertFalse(File(cache, "distill-output-old.tmp").exists())
        assertTrue(unrelated.exists())
    }

    private fun repository(gateway: FakeGateway) =
        DistillWriteRepository(gateway, recoveryStore, cache)

    private fun request(
        original: ByteArray,
        output: ByteArray,
        uri: String = "content://vault/note"
    ) = DistillWriteRequest(uri, sha256Hex(original), output)

    private class SimulatedCrash : Error()

    private class FakeGateway(initial: ByteArray) : DistillDocumentGateway {
        var content: ByteArray = initial.copyOf()
        var readCount = 0
        var writeCount = 0
        var readOverride: ((Int, ByteArray) -> ByteArray)? = null
        var writeOverride: ((File, FakeGateway) -> Unit)? = null

        override fun read(targetUri: String, maximumBytes: Int): ByteArray {
            readCount++
            val result = readOverride?.invoke(readCount, content) ?: content
            if (result.size > maximumBytes) throw NoteFileTooLargeException(result.size, maximumBytes)
            return result.copyOf()
        }

        override fun writeFromFile(targetUri: String, source: File) {
            writeCount++
            writeOverride?.invoke(source, this) ?: run { content = source.readBytes() }
        }
    }
}
