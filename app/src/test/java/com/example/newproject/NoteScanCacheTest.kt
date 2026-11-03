package com.example.newproject

import com.example.newproject.model.BesideNote
import com.example.newproject.domain.resolveBesideNote
import com.example.newproject.domain.NoteLinkResolution
import com.example.newproject.domain.markdown.scanInlineSyntax
import com.example.newproject.domain.noteLinkOf
import com.example.newproject.domain.resolveNoteLink
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    private var now = 0L
    private val published = mutableListOf<List<String>>()
    private val events = mutableListOf<NoteScanEvent>()
    private val scanParent = Job()
    private val cache = NoteScanCache(
        scope = CoroutineScope(Dispatchers.Unconfined + scanParent),
        ttlMillis = 60_000L,
        vaultGeneration = { generation },
        clock = { now },
        onScanEvent = { events += it }
    )

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
        assertEquals(mapOf("B.md" to DocumentRef("B-ref"), "both.md" to DocumentRef("B-both")), cache.knownNotes.value)
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
        assertEquals(mapOf("a.md" to DocumentRef("a-ref")), cache.knownNotes.value)
        assertEquals(1, published.size)
    }

    @Test
    fun `一覧の寿命は走査を終えてから数え、60秒未満は使い、60秒で切れる`() = runTest {
        val scanResult = CompletableDeferred<List<NoteFile>>()
        var scans = 0
        val scan: suspend () -> List<NoteFile> = {
            scans++
            if (scans == 1) scanResult.await() else listOf(note("a.md", "a-ref"))
        }
        val pending = async { cache.get(scan = scan, onPublished = ::record) }
        runCurrent()
        now = 15_000L
        scanResult.complete(listOf(note("a.md", "a-ref")))
        pending.await()

        now = 15_000L + 59_999L
        cache.get(scan = scan, onPublished = ::record)
        assertEquals("走査を終えてから60秒未満なのに走り直した", 1, scans)

        now = 15_000L + 60_000L
        cache.get(scan = scan, onPublished = ::record)
        assertEquals("走査を終えてから60秒で切れていない", 2, scans)
    }

    /** 実機ケースが本文の表示を起点にしない理由の1つ。持っている一覧で引いても、走査の完了からの残り時間は増えない。 */
    @Test
    fun `持っている一覧を使っても寿命は延びない`() = runTest {
        var scans = 0
        val scan: suspend () -> List<NoteFile> = { scans++; listOf(note("a.md", "a-ref")) }
        cache.get(scan = scan, onPublished = ::record)

        now = 50_000L
        cache.get(scan = scan, onPublished = ::record)
        assertEquals(1, scans)

        now = 60_000L
        cache.get(scan = scan, onPublished = ::record)
        assertEquals(2, scans)
    }

    /** 実機ケースが読む行。寿命の中と切れた後を区別でき、相乗りと捨てた走査を数え違えない。 */
    @Test
    fun `走査の記録は寿命の中では増えず、切れた後に1回増える`() = runTest {
        val scan: suspend () -> List<NoteFile> = { listOf(note("a.md", "a-ref")) }

        cache.get(scan = scan, onPublished = ::record)
        assertEquals(listOf(NoteScanEvent.STARTED, NoteScanEvent.PUBLISHED), events)

        now = 59_999L
        cache.get(scan = scan, onPublished = ::record)
        assertEquals(2, events.size)

        now = 60_000L
        cache.get(scan = scan, onPublished = ::record)
        assertEquals(
            listOf(NoteScanEvent.STARTED, NoteScanEvent.PUBLISHED, NoteScanEvent.STARTED, NoteScanEvent.PUBLISHED),
            events
        )
    }

    @Test
    fun `相乗りでは走査の記録が増えず、捨てた走査は公開の記録を出さない`() = runTest {
        val beforeWrite = CompletableDeferred<List<NoteFile>>()
        val first = async { cache.get(scan = { beforeWrite.await() }, onPublished = ::record) }
        runCurrent()
        val shared = async { cache.get(scan = { emptyList() }, onPublished = ::record) }
        runCurrent()
        assertEquals("相乗りで走査の行が増えた", listOf(NoteScanEvent.STARTED), events)

        cache.expireAfterBodyWrite()
        beforeWrite.complete(listOf(note("古い.md", "old-ref")))
        first.await()
        shared.await()

        assertEquals("捨てた走査で寿命の起点の行が出た", listOf(NoteScanEvent.STARTED), events)
    }

    @Test
    fun `60秒より長くかかった走査の結果も、次の要求で使える`() = runTest {
        val scanResult = CompletableDeferred<List<NoteFile>>()
        var scans = 0
        val scan: suspend () -> List<NoteFile> = { scans++; scanResult.await() }
        val pending = async { cache.get(scan = scan, onPublished = ::record) }
        runCurrent()
        now = 90_000L
        scanResult.complete(listOf(note("a.md", "a-ref")))
        pending.await()

        cache.get(scan = scan, onPublished = ::record)

        assertEquals(1, scans)
    }

    @Test
    fun `走査の途中に来た要求は同じ走査を待ち、走査は1本で公開も1回で済む`() = runTest {
        val scanResult = CompletableDeferred<List<NoteFile>>()
        var scans = 0
        val slowScan: suspend () -> List<NoteFile> = { scans++; scanResult.await() }

        val first = async { cache.get(scan = slowScan, onPublished = ::record) }
        runCurrent()
        val second = async { cache.get(scan = slowScan, onPublished = ::record) }
        runCurrent()
        scanResult.complete(listOf(note("a.md", "a-ref")))

        assertEquals(listOf("a.md"), first.await().map { it.vaultRelativePath })
        assertEquals(listOf("a.md"), second.await().map { it.vaultRelativePath })
        assertEquals(1, scans)
        assertEquals(1, published.size)
    }

    @Test
    fun `最初に頼んだ人が取り消されても、相乗りした人へ結果が届く`() = runTest {
        val scanResult = CompletableDeferred<List<NoteFile>>()
        val slowScan: suspend () -> List<NoteFile> = { scanResult.await() }

        val first = async { cache.get(scan = slowScan, onPublished = ::record) }
        runCurrent()
        val second = async { cache.get(scan = slowScan, onPublished = ::record) }
        runCurrent()
        first.cancel()
        runCurrent()
        scanResult.complete(listOf(note("a.md", "a-ref")))

        assertEquals(listOf("a.md"), second.await().map { it.vaultRelativePath })
        assertEquals(listOf("a.md"), cache.notes.map { it.vaultRelativePath })
    }

    @Test
    fun `相乗りした人が取り消されても、最初に頼んだ人へ結果が届く`() = runTest {
        val scanResult = CompletableDeferred<List<NoteFile>>()
        val slowScan: suspend () -> List<NoteFile> = { scanResult.await() }

        val first = async { cache.get(scan = slowScan, onPublished = ::record) }
        runCurrent()
        val second = async { cache.get(scan = slowScan, onPublished = ::record) }
        runCurrent()
        second.cancel()
        runCurrent()
        scanResult.complete(listOf(note("a.md", "a-ref")))

        assertEquals(listOf("a.md"), first.await().map { it.vaultRelativePath })
    }

    @Test
    fun `頼んだ全員が取り消されても走査は終わりまで走り、結果を次の要求で使う`() = runTest {
        val scanResult = CompletableDeferred<List<NoteFile>>()
        var scans = 0

        val only = async { cache.get(scan = { scans++; scanResult.await() }, onPublished = ::record) }
        runCurrent()
        only.cancel()
        runCurrent()
        scanResult.complete(listOf(note("a.md", "a-ref")))

        cache.get(scan = { scans++; emptyList() }, onPublished = ::record)
        assertEquals(1, scans)
        assertEquals(listOf("a.md"), cache.notes.map { it.vaultRelativePath })
    }

    @Test
    fun `走査が失敗したら待っている全員へ届き、走行中の印が残らず、親を巻き添えにしない`() = runTest {
        val scanResult = CompletableDeferred<List<NoteFile>>()
        val failingScan: suspend () -> List<NoteFile> = { scanResult.await() }

        val first = async { runCatching { cache.get(scan = failingScan, onPublished = ::record) } }
        runCurrent()
        val second = async { runCatching { cache.get(scan = failingScan, onPublished = ::record) } }
        runCurrent()
        scanResult.completeExceptionally(IllegalStateException("走査失敗"))

        assertEquals("走査失敗", first.await().exceptionOrNull()?.message)
        assertEquals("走査失敗", second.await().exceptionOrNull()?.message)
        assertTrue("走査の失敗で親が止まった", scanParent.isActive)
        val next = cache.get(scan = { listOf(note("a.md", "a-ref")) }, onPublished = ::record)
        assertEquals(listOf("a.md"), next.map { it.vaultRelativePath })
    }

    @Test
    fun `走査の途中でVaultを替えた後の要求は、旧い走査を共有しない`() = runTest {
        val scanA = CompletableDeferred<List<NoteFile>>()
        val pendingA = async { runCatching { cache.get(scan = { scanA.await() }, onPublished = ::record) } }
        runCurrent()

        switchVault()
        val fromB = cache.get(scan = { listOf(note("B.md", "B-ref")) }, onPublished = ::record)
        scanA.complete(listOf(note("A.md", "A-ref")))

        assertEquals(listOf("B.md"), fromB.map { it.vaultRelativePath })
        assertTrue(pendingA.await().exceptionOrNull() is CancellationException)
    }

    /** 書き換えより前に読んだ一覧は更新日時が古い。紙の地色は更新日時から決めるので、次の要求へ持ち越さない。 */
    @Test
    fun `走査の途中で本文を書き換えたら、次の要求は走査し直し、古い走査が一覧を上書きしない`() = runTest {
        val beforeWrite = CompletableDeferred<List<NoteFile>>()
        var scans = 0
        val pending = async { cache.get(scan = { scans++; beforeWrite.await() }, onPublished = ::record) }
        runCurrent()

        cache.expireAfterBodyWrite()
        cache.get(scan = { scans++; listOf(note("新しい.md", "new-ref")) }, onPublished = ::record)
        beforeWrite.complete(listOf(note("古い.md", "old-ref")))

        assertEquals(2, scans)
        assertEquals("書き換えより前に頼んだ人には、頼んだ走査の結果を返す", listOf("古い.md"), pending.await().map { it.vaultRelativePath })
        assertEquals(listOf("新しい.md"), cache.notes.map { it.vaultRelativePath })
        assertEquals(listOf(listOf("新しい.md")), published)
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

    /**
     * **並べた相手の相対パスは、本文を書き換えて一覧が空になった後もリンクの索引から引ける**（→ features/desk_memory.md §5）。
     * 一覧だけから引くと、書き換えの後に右で開いたノートを取りこぼす。引くのに走査しない。Vault を替えたら引けない。
     */
    @Test
    fun `本文を書き換えた後も相対パスを走査せずに引け、Vaultを替えたら引けない`() = runTest {
        var scans = 0
        cache.get(scan = { scans++; listOf(note("A.md", "A-ref"), note("refs/B.md", "B-ref"), note("refs/C.md", "C-ref")) }, onPublished = ::record)

        cache.expireAfterBodyWrite()

        assertEquals(emptyList<NoteFile>(), cache.notes)
        assertEquals("refs/B.md", cache.relativePathOf(DocumentRef("B-ref")))
        assertEquals("refs/C.md", cache.relativePathOf(DocumentRef("C-ref")))
        assertEquals("引くために走査した", 1, scans)
        assertTrue("書き換えの後に再会カードの相手を引けない", resolveBesideNote(BesideNote("refs/B.md", "B"), cache.linkIndex) is NoteLinkResolution.Open)

        switchVault()

        assertNull(cache.relativePathOf(DocumentRef("B-ref")))
        assertEquals(NoteLinkResolution.NotReady, resolveBesideNote(BesideNote("refs/B.md", "B"), cache.linkIndex))
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
