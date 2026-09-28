package com.example.newproject

import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 走査キャッシュは、**走査の間にVaultが切り替わったら結果を公開しない**。
 *
 * 結晶の一覧から始めた走査が Vault 切替で止まらず、旧Vaultの結果がキャッシュ・開けるパス・分野の索引へ
 * 戻った（実装レビューの指摘）。呼び出し側の Job の取り消しに頼らず、公開の直前で世代を照合する。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoteScanCacheTest {

    private var generation = 0L
    private val published = mutableListOf<List<String>>()
    private val cache = NoteScanCache(ttlMillis = 60_000L, vaultGeneration = { generation }, clock = { 0L })

    @Test
    fun `Aの走査を止めたままBへ切り替え、Bの後にAが返っても、キャッシュはBのまま`() = runTest {
        val scanA = CompletableDeferred<List<NoteFile>>()
        val pendingA = async { runCatching { cache.get(scan = { scanA.await() }, onPublished = ::record) } }
        runCurrent()

        switchVault()
        cache.get(scan = { listOf(note("B.md", "B-ref"), note("both.md", "B-both")) }, onPublished = ::record)
        scanA.complete(listOf(note("A.md", "A-ref"), note("both.md", "A-both")))
        runCurrent()

        assertTrue("旧Vaultの走査が止まっていない", pendingA.await().exceptionOrNull() is CancellationException)
        assertEquals(listOf("B.md", "both.md"), cache.notes.map { it.vaultRelativePath })
        assertEquals(setOf("B.md", "both.md"), cache.knownPaths.value)
        assertEquals("分野の索引へ載せたのはBだけ", listOf(listOf("B.md", "both.md")), published)
        // 結晶の根拠を押したときに引く参照（→ NoteViewModel.openCrystalSource）
        assertEquals(null, cache.notes.firstOrNull { it.vaultRelativePath == "A.md" })
        assertEquals(DocumentRef("B-both"), cache.notes.first { it.vaultRelativePath == "both.md" }.ref)
    }

    @Test
    fun `A→B→Aでも、最初のAの遅い走査が今の走査を上書きしない`() = runTest {
        val firstA = CompletableDeferred<List<NoteFile>>()
        val pending = async { runCatching { cache.get(scan = { firstA.await() }, onPublished = ::record) } }
        runCurrent()

        switchVault()
        switchVault()
        cache.get(scan = { listOf(note("A-new.md", "A2-ref")) }, onPublished = ::record)
        firstA.complete(listOf(note("A-old.md", "A1-ref")))
        runCurrent()

        assertTrue(pending.await().exceptionOrNull() is CancellationException)
        assertEquals(listOf("A-new.md"), cache.notes.map { it.vaultRelativePath })
        assertEquals(listOf(listOf("A-new.md")), published)
    }

    @Test
    fun `切り替えなければ走査の結果を公開し、TTL内は走査し直さない`() = runTest {
        var scans = 0
        repeat(2) {
            cache.get(scan = { scans++; listOf(note("a.md", "a-ref")) }, onPublished = ::record)
        }
        assertEquals(1, scans)
        assertEquals(setOf("a.md"), cache.knownPaths.value)
        assertEquals(1, published.size)
    }

    private fun switchVault() {
        generation++
        cache.clear()
    }

    private fun record(notes: List<NoteFile>) {
        published += notes.map { it.vaultRelativePath }
    }

    private fun note(path: String, ref: String) = NoteFile(name = path, ref = DocumentRef(ref), vaultRelativePath = path)
}
