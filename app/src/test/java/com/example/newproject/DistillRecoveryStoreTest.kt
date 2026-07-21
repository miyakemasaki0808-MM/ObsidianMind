package com.example.newproject

import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DistillRecoveryStoreTest {
    private val root = Files.createTempDirectory("distill-recovery-test").toFile()
    private val store = DistillRecoveryStore(root) { 1234L }

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `record round trips and phase update stays valid`() {
        val original = "元本文です。".toByteArray()
        val record = store.newRecord(
            "content://vault/note",
            sha256Hex(original),
            sha256Hex("**元本文です。**".toByteArray()),
            original
        )

        store.create(record)
        val prepared = (store.read() as DistillRecoveryReadResult.Valid).record
        assertEquals(DistillWritePhase.PREPARED, prepared.phase)
        assertEquals(1234L, prepared.createdAtEpochMillis)
        assertArrayEquals(original, prepared.originalBytes)

        store.updatePhase(prepared, DistillWritePhase.WRITING)
        val writing = (store.read() as DistillRecoveryReadResult.Valid).record
        assertEquals(DistillWritePhase.WRITING, writing.phase)
        assertTrue(store.delete())
        assertEquals(DistillRecoveryReadResult.None, store.read())
    }

    @Test(expected = PendingDistillRecoveryException::class)
    fun `second unresolved record is rejected`() {
        val bytes = "one".toByteArray()
        val record = store.newRecord("content://one", sha256Hex(bytes), sha256Hex(bytes), bytes)

        store.create(record)
        store.create(record.copy(targetUri = "content://two"))
    }

    @Test
    fun `tampered record is reported as corrupt and is not silently deleted`() {
        val bytes = "original".toByteArray()
        store.create(store.newRecord("content://one", sha256Hex(bytes), sha256Hex(bytes), bytes))
        val file = root.listFiles().orEmpty().single { it.name.endsWith(".bin") }
        val tampered = file.readBytes()
        tampered[12] = (tampered[12].toInt() xor 1).toByte()
        file.writeBytes(tampered)

        assertTrue(store.read() is DistillRecoveryReadResult.Corrupt)
        assertTrue(file.exists())
        assertTrue(store.delete())
    }
}
