package com.example.newproject

import com.example.newproject.domain.NoteLinkResolution
import com.example.newproject.domain.markdown.scanInlineSyntax
import com.example.newproject.domain.noteLinkOf
import com.example.newproject.domain.resolveNoteLink
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

    /**
     * 本文を書き換えた後（蒸留の保存・太字の復元）も、リンクは引ける。書き換えでノートの名前・パス・参照は変わらず、
     * 索引まで捨てると次の走査まで「読み込んでいます」が続く（→ features/note_links.md §6）。
     */
    @Test
    fun `本文を書き換えた後もリンクの索引は残り、一覧は次に走査し直す`() = runTest {
        var scans = 0
        val scan = { scans++; listOf(note("A.md", "A-ref"), note("B.md", "B-ref")) }
        cache.get(scan = scan, onPublished = ::record)

        cache.expireAfterBodyWrite()

        assertEquals(DocumentRef("B-ref"), (linkFromA("[[B]]") as NoteLinkResolution.Open).note.ref)
        assertEquals(DocumentRef("B-ref"), (linkFromA("[x](B.md)") as NoteLinkResolution.Open).note.ref)
        assertEquals(emptyList<NoteFile>(), cache.notes)
        cache.get(scan = scan, onPublished = ::record)
        assertEquals("書き換えの後は TTL 内でも走査し直す", 2, scans)
    }

    @Test
    fun `Vaultを切り替えたらリンクの索引も捨てる`() = runTest {
        cache.get(scan = { listOf(note("A.md", "A-ref"), note("B.md", "B-ref")) }, onPublished = ::record)

        switchVault()

        assertEquals(NoteLinkResolution.NotReady, linkFromA("[[B]]"))
    }

    private fun linkFromA(text: String): NoteLinkResolution =
        resolveNoteLink(noteLinkOf(text, scanInlineSyntax(text).spans.single())!!, DocumentRef("A-ref"), cache.linkIndex)

    private fun switchVault() {
        generation++
        cache.clear()
    }

    private fun record(notes: List<NoteFile>) {
        published += notes.map { it.vaultRelativePath }
    }

    private fun note(path: String, ref: String) = NoteFile(name = path, ref = DocumentRef(ref), vaultRelativePath = path)
}
