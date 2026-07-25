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

        assertEquals(ReadingTraceSaveResult.Success, store.save(trace, VAULT))
        assertEquals(ReadingTraceReadResult.Valid(trace), store.load(trace.vaultRelativePath, VAULT))
    }

    @Test
    fun `unknown note reports none`() {
        val store = ReadingTraceStore(FakeGateway())

        assertEquals(ReadingTraceReadResult.None, store.load("never/read.md", VAULT))
    }

    @Test
    fun `corrupt bytes report corrupt without touching the note`() {
        val gateway = FakeGateway()
        gateway.files[ReadingTraceStore.keyFor("ideas/habit.md")] = "これはJSONではない".toByteArray()
        val store = ReadingTraceStore(gateway)

        assertTrue(store.load("ideas/habit.md", VAULT) is ReadingTraceReadResult.Corrupt)
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

        assertTrue(store.load("ideas/habit.md", VAULT) is ReadingTraceReadResult.Corrupt)
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

        val result = ReadingTraceStore(gateway).save(trace(), VAULT)

        assertTrue(result is ReadingTraceSaveResult.Failure)
    }

    @Test
    fun `write failure is reported without throwing`() {
        val gateway = FakeGateway().apply { writeError = IOException("書き込めませんでした") }

        val result = ReadingTraceStore(gateway).save(trace(), VAULT)

        assertEquals("書き込めませんでした", (result as ReadingTraceSaveResult.Failure).message)
    }

    @Test
    fun `invalid trace is rejected before any write`() {
        val gateway = FakeGateway()

        val result = ReadingTraceStore(gateway).save(trace(visits = emptyList()), VAULT)

        assertTrue(result is ReadingTraceSaveResult.Failure)
        assertEquals(0, gateway.writeCount)
    }

    @Test
    fun `saving twice overwrites the same file`() {
        val gateway = FakeGateway()
        val store = ReadingTraceStore(gateway)
        val first = trace()
        val second = first.withVisit(ReadingVisit(2_000L, "まとめ", 100))

        store.save(first, VAULT)
        store.save(second, VAULT)

        assertEquals(1, gateway.files.size)
        assertEquals(ReadingTraceReadResult.Valid(second), store.load(second.vaultRelativePath, VAULT))
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

    // ── Vault識別子の受け渡し ──────────────────────────────────────────────────

    // 「どのVaultへの要求か」はStoreが判断せず、そのままGatewayへ運ぶ。
    // 実際の照合はGateway（＝書き込み直前）で行うことで、切替との競合を閉じる。
    @Test
    fun `vault key is forwarded to the gateway`() {
        val gateway = FakeGateway().apply { currentVaultKey = "content://old-vault" }
        val store = ReadingTraceStore(gateway)

        store.save(trace(), "content://old-vault")
        store.load("ideas/habit.md", "content://old-vault")

        assertEquals(listOf("content://old-vault"), gateway.writtenVaultKeys)
        assertEquals(listOf("content://old-vault"), gateway.readVaultKeys)
    }

    // 切替後のVaultへ旧Vault向けの要求が届いても、書かずに失敗として返る。
    @Test
    fun `save for a stale vault is rejected`() {
        val gateway = FakeGateway().apply { currentVaultKey = "content://new-vault" }
        val store = ReadingTraceStore(gateway)

        val result = store.save(trace(), "content://old-vault")

        assertTrue(result is ReadingTraceSaveResult.Failure)
        assertTrue(gateway.files.isEmpty())
    }

    @Test
    fun `load for a stale vault reports none`() {
        val gateway = FakeGateway()
        val store = ReadingTraceStore(gateway)
        store.save(trace(), VAULT)
        gateway.currentVaultKey = "content://new-vault"

        assertEquals(ReadingTraceReadResult.None, store.load("ideas/habit.md", VAULT))
    }
}

private const val VAULT = "content://vault"

private class FakeGateway : ReadingTraceDocumentGateway {
    val files = mutableMapOf<String, ByteArray>()
    val readVaultKeys = mutableListOf<String>()
    val writtenVaultKeys = mutableListOf<String>()
    var folderAvailable = true
    var writeError: Exception? = null
    /** SAF実装と同じく、要求のVaultキーが現在のVaultと違えば拒む。 */
    var currentVaultKey = VAULT
    var readCount = 0
        private set
    var writeCount = 0
        private set

    override fun ensureFolder(): Boolean = folderAvailable

    override fun read(key: String, maximumBytes: Int, vaultKey: String): ByteArray? {
        readCount++
        readVaultKeys += vaultKey
        if (vaultKey != currentVaultKey) return null
        if (!folderAvailable) return null
        val bytes = files[key] ?: return null
        if (bytes.size > maximumBytes) throw NoteFileTooLargeException(bytes.size, maximumBytes)
        return bytes.copyOf()
    }

    override fun write(key: String, bytes: ByteArray, vaultKey: String) {
        writeCount++
        writtenVaultKeys += vaultKey
        if (vaultKey != currentVaultKey) {
            throw IOException("Vaultが切り替わったため痕跡を保存しませんでした。")
        }
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
