package com.example.newproject

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingTraceStoreTest {

    @Test
    fun `saves then loads a trace`() {
        val gateway = FakeGateway()
        val store = ReadingTraceStore(gateway)
        val trace = trace()

        assertEquals(ReadingTraceSaveResult.Success, store.save(trace))
        assertEquals(ReadingTraceReadResult.Valid(trace), store.load(trace.vaultRelativePath))
    }

    @Test
    fun `unknown note reports none`() {
        val store = ReadingTraceStore(FakeGateway())

        assertEquals(ReadingTraceReadResult.None, store.load("never/read.md"))
    }

    @Test
    fun `corrupt bytes report corrupt without touching the note`() {
        val gateway = FakeGateway()
        gateway.files[ReadingTraceStore.keyFor("ideas/habit.md")] = "これはJSONではない".toByteArray()
        val store = ReadingTraceStore(gateway)

        assertTrue(store.load("ideas/habit.md") is ReadingTraceReadResult.Corrupt)
        assertEquals(0, gateway.writeCount)
    }

    // ファイル名は相対パスのハッシュなので、中身のパスが食い違うのは
    // ハッシュ衝突か手による改変。信用せず孤立扱いにする。
    @Test
    fun `mismatched path inside the file is corrupt`() {
        val gateway = FakeGateway()
        // "other/note.md" の痕跡を "ideas/habit.md" のキーの位置へ置く
        gateway.files[ReadingTraceStore.keyFor("ideas/habit.md")] =
            ReadingTraceJson.encode(trace(path = "other/note.md"))
        val store = ReadingTraceStore(gateway)

        assertTrue(store.load("ideas/habit.md") is ReadingTraceReadResult.Corrupt)
    }

    @Test
    fun `folder status reports ready when the sidecar folder exists`() {
        assertEquals(ReadingTraceFolderStatus.Ready, ReadingTraceStore(FakeGateway()).folderStatus())
    }

    @Test
    fun `folder status reports unavailable when the folder cannot be made`() {
        val gateway = FakeGateway().apply { folderAvailable = false }

        val status = ReadingTraceStore(gateway).folderStatus()

        assertTrue(status is ReadingTraceFolderStatus.Unavailable)
    }

    @Test
    fun `save fails when the folder cannot be made`() {
        val gateway = FakeGateway().apply { folderAvailable = false }

        val result = ReadingTraceStore(gateway).save(trace())

        assertTrue(result is ReadingTraceSaveResult.Failure)
    }

    @Test
    fun `write failure is reported without throwing`() {
        val gateway = FakeGateway().apply { writeError = IOException("書き込めませんでした") }

        val result = ReadingTraceStore(gateway).save(trace())

        assertEquals("書き込めませんでした", (result as ReadingTraceSaveResult.Failure).message)
    }

    @Test
    fun `invalid trace is rejected before any write`() {
        val gateway = FakeGateway()

        val result = ReadingTraceStore(gateway).save(trace(visits = emptyList()))

        assertTrue(result is ReadingTraceSaveResult.Failure)
        assertEquals(0, gateway.writeCount)
    }

    @Test
    fun `saving twice overwrites the same file`() {
        val gateway = FakeGateway()
        val store = ReadingTraceStore(gateway)
        val first = trace()
        val second = first.withVisit(ReadingVisit(2_000L, "まとめ", 100))

        store.save(first)
        store.save(second)

        assertEquals(1, gateway.files.size)
        assertEquals(ReadingTraceReadResult.Valid(second), store.load(second.vaultRelativePath))
    }

    @Test
    fun `key is stable per path and differs across paths`() {
        assertEquals(
            ReadingTraceStore.keyFor("ideas/habit.md"),
            ReadingTraceStore.keyFor("ideas/habit.md")
        )
        assertTrue(
            ReadingTraceStore.keyFor("ideas/habit.md") != ReadingTraceStore.keyFor("ideas/other.md")
        )
    }

    // ファイル名に使えない文字（"/" 等）が残らないこと
    @Test
    fun `key is filename safe hex`() {
        val key = ReadingTraceStore.keyFor("ideas/2026/日本語 のノート.md")

        assertTrue("16進64桁でない: $key", Regex("[0-9a-f]{64}").matches(key))
    }
}

private class FakeGateway : ReadingTraceDocumentGateway {
    val files = mutableMapOf<String, ByteArray>()
    var folderAvailable = true
    var writeError: Exception? = null
    var readCount = 0
        private set
    var writeCount = 0
        private set

    override fun ensureFolder(): Boolean = folderAvailable

    override fun read(key: String, maximumBytes: Int): ByteArray? {
        readCount++
        if (!folderAvailable) return null
        val bytes = files[key] ?: return null
        if (bytes.size > maximumBytes) throw NoteFileTooLargeException(bytes.size, maximumBytes)
        return bytes.copyOf()
    }

    override fun write(key: String, bytes: ByteArray) {
        writeCount++
        writeError?.let { throw it }
        if (!folderAvailable) throw IOException("痕跡の保存先を用意できませんでした。")
        files[key] = bytes.copyOf()
    }
}

private fun trace(
    path: String = "ideas/habit.md",
    visits: List<ReadingVisit> = listOf(ReadingVisit(1_000L, "導入", 40))
) = ReadingTrace(
    vaultRelativePath = path,
    noteTitle = "習慣について",
    documentId = "doc-1",
    visits = visits
)
