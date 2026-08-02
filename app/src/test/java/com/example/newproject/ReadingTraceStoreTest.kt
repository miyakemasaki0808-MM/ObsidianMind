package com.example.newproject

import com.example.newproject.data.NoteFileTooLargeException
import com.example.newproject.data.ReadingTraceDocumentGateway
import com.example.newproject.data.ReadingTraceFolderStatus
import com.example.newproject.data.ReadingTraceJson
import com.example.newproject.data.ReadingTraceKeyListing
import com.example.newproject.data.ReadingTraceReadResult
import com.example.newproject.data.ReadingTraceSaveResult
import com.example.newproject.data.ReadingTraceStore
import com.example.newproject.model.ReadingTrace
import com.example.newproject.model.ReadingVisit
import com.example.newproject.model.withVisit
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

    // --- 列挙API（段階1）-------------------------------------------------------
    //
    // 孤児判定はキーの集合差で行う（キー = sha256(相対パス) なのでファイルを1つも
    // 読まずに済む）。したがって「列挙できなかった」を「1件も無い」に畳むと、
    // 全痕跡が孤児に見える。ここはその一点を守るためのテスト群。

    @Test
    fun `listKeys returns the key of every saved trace`() {
        val store = ReadingTraceStore(FakeGateway())
        store.save(trace(path = "ideas/habit.md"), VAULT)
        store.save(trace(path = "journal/2026.md"), VAULT)

        val listing = store.listKeys(VAULT)

        assertEquals(
            setOf(
                ReadingTraceStore.keyFor("ideas/habit.md"),
                ReadingTraceStore.keyFor("journal/2026.md")
            ),
            (listing as ReadingTraceKeyListing.Available).keys
        )
    }

    @Test
    fun `listKeys reports unavailable instead of an empty set when listing fails`() {
        val gateway = FakeGateway()
        val store = ReadingTraceStore(gateway)
        store.save(trace(), VAULT)
        gateway.listingUnreadable = true

        // ここが空集合になると、保存済みの痕跡がすべて孤児として削除候補になる。
        assertTrue(store.listKeys(VAULT) is ReadingTraceKeyListing.Unavailable)
    }

    @Test
    fun `listKeys returns an empty set when nothing has been saved`() {
        val store = ReadingTraceStore(FakeGateway())

        // 「本当に0件」は Available(empty)。Unavailable と混ぜない。
        assertEquals(
            emptySet<String>(),
            (store.listKeys(VAULT) as ReadingTraceKeyListing.Available).keys
        )
    }

    @Test
    fun `listKeys is unavailable for a different vault`() {
        val gateway = FakeGateway()
        val store = ReadingTraceStore(gateway)
        store.save(trace(), VAULT)
        gateway.currentVaultKey = "content://new-vault"

        assertTrue(store.listKeys(VAULT) is ReadingTraceKeyListing.Unavailable)
    }

    @Test
    fun `listKeys surfaces gateway exceptions as unavailable`() {
        val store = ReadingTraceStore(ThrowingListGateway())

        assertTrue(store.listKeys(VAULT) is ReadingTraceKeyListing.Unavailable)
    }

    // --- キー指定の読み出し（孤児はパスが分からないため必要）---------------------

    @Test
    fun `loadByKey reads a trace when only the key is known`() {
        val store = ReadingTraceStore(FakeGateway())
        store.save(trace(path = "ideas/habit.md"), VAULT)

        val result = store.loadByKey(ReadingTraceStore.keyFor("ideas/habit.md"), VAULT)

        assertEquals(
            "ideas/habit.md",
            (result as ReadingTraceReadResult.Valid).trace.vaultRelativePath
        )
    }

    @Test
    fun `loadByKey returns none for an unknown key`() {
        val store = ReadingTraceStore(FakeGateway())

        assertEquals(
            ReadingTraceReadResult.None,
            store.loadByKey(ReadingTraceStore.keyFor("missing.md"), VAULT)
        )
    }

    // --- 索引の陳腐化（外部同期で後から増えたファイル）-----------------------------
    //
    // Gateway は置き場の子一覧を1回だけ読んで索引に持つ。別端末が作った痕跡が
    // 後から同期されても自力では気づけないので、**不在だった時にだけ**索引を捨てて
    // 作り直す。作り直しには頻度の上限があり、未読ノートを開くたびに全走査しない。

    @Test
    fun `a trace that arrived by external sync becomes readable after the index is rebuilt`() {
        val clock = ElapsedClock()
        val gateway = FakeGateway()
        val store = store(gateway, clock)
        // 索引を作る（この時点では空）
        store.load("ideas/habit.md", VAULT)
        // 別端末が作った痕跡が、索引を作った後に同期で着地する
        val synced = trace(path = "ideas/habit.md")
        gateway.syncFromOutside(ReadingTraceStore.keyFor("ideas/habit.md"), ReadingTraceJson.encode(synced))
        clock.advance(REFRESH_INTERVAL)

        assertEquals(ReadingTraceReadResult.Valid(synced), store.load("ideas/habit.md", VAULT))
    }

    // 索引に無いまま保存すると、SAF実装は createDocument して同じキーのファイルを2つ作る。
    // Controller は必ず load → save の順で書くので、load 側で作り直せばここも塞がる。
    @Test
    fun `appending to an externally synced trace does not create a second file`() {
        val clock = ElapsedClock()
        val gateway = FakeGateway()
        val store = store(gateway, clock)
        store.load("ideas/habit.md", VAULT)
        gateway.syncFromOutside(
            ReadingTraceStore.keyFor("ideas/habit.md"),
            ReadingTraceJson.encode(trace(path = "ideas/habit.md"))
        )
        clock.advance(REFRESH_INTERVAL)

        val existing = store.load("ideas/habit.md", VAULT) as ReadingTraceReadResult.Valid
        store.save(existing.trace.withVisit(ReadingVisit(2_000L, "まとめ", 100)), VAULT)

        assertEquals(0, gateway.createCount)
        assertEquals(1, gateway.files.size)
    }

    // 見つかっている間は索引を疑わない。ここが崩れると、キャッシュを置いた意味が消える。
    @Test
    fun `a hit never rebuilds the index`() {
        val clock = ElapsedClock()
        val gateway = FakeGateway()
        val store = store(gateway, clock)
        store.save(trace(), VAULT)

        clock.advance(REFRESH_INTERVAL * 10)
        store.load("ideas/habit.md", VAULT)

        assertEquals(0, gateway.invalidateCount)
    }

    // 未読ノートを続けて開くと毎回ミスする。ここで毎回作り直すと全走査が復活する。
    @Test
    fun `repeated misses within the interval rebuild the index only once`() {
        val clock = ElapsedClock()
        val gateway = FakeGateway()
        val store = store(gateway, clock)

        store.load("never/read.md", VAULT)
        clock.advance(REFRESH_INTERVAL - 1)
        store.load("never/read.md", VAULT)

        assertEquals(1, gateway.invalidateCount)
    }

    @Test
    fun `a miss after the interval rebuilds the index again`() {
        val clock = ElapsedClock()
        val gateway = FakeGateway()
        val store = store(gateway, clock)

        store.load("never/read.md", VAULT)
        clock.advance(REFRESH_INTERVAL)
        store.load("never/read.md", VAULT)

        assertEquals(2, gateway.invalidateCount)
    }

    // 壊れたファイルは「見えている」ので、索引が古いことの証拠にならない。
    @Test
    fun `a corrupt file does not rebuild the index`() {
        val clock = ElapsedClock()
        val gateway = FakeGateway()
        gateway.files[ReadingTraceStore.keyFor("ideas/habit.md")] = "これはJSONではない".toByteArray()
        val store = store(gateway, clock)

        assertTrue(store.load("ideas/habit.md", VAULT) is ReadingTraceReadResult.Corrupt)
        assertEquals(0, gateway.invalidateCount)
    }

    // 作り直しは1回だけ。本当に無いものを探して読み出しが繰り返されない。
    @Test
    fun `a trace that is really absent reports none after a single retry`() {
        val clock = ElapsedClock()
        val gateway = FakeGateway()
        val store = store(gateway, clock)

        assertEquals(ReadingTraceReadResult.None, store.load("never/read.md", VAULT))
        assertEquals(2, gateway.readCount)
    }

    // Gateway は読込に失敗すると**自力で索引を捨ててから** null を返す。
    // 「実際に捨てたか」を作り直しの条件にすると、この直後だけ読み直しが行われず、
    // 作り直せば読めるはずの痕跡をその回だけ取りこぼす。
    @Test
    fun `a read that already dropped the index still gets its rebuild`() {
        val clock = ElapsedClock()
        val gateway = FakeGateway()
        val store = store(gateway, clock)
        val existing = trace(path = "ideas/habit.md")
        gateway.syncFromOutside(ReadingTraceStore.keyFor("ideas/habit.md"), ReadingTraceJson.encode(existing))
        gateway.failNextReadAndDropIndex = true

        assertEquals(ReadingTraceReadResult.Valid(existing), store.load("ideas/habit.md", VAULT))
    }

    // 旧Vaultの索引を抱えたまま切り替わり、新Vaultへ一度も触れないうちに
    // 遅れた旧Vault向けの読み出しが届く順序。索引が旧Vaultのものかどうかで判定すると
    // ここで枠を食い、直後の正当なミスが60秒作り直せなくなる。
    @Test
    fun `a stale vault load does not spend the budget when the cached index is also stale`() {
        val clock = ElapsedClock()
        val gateway = FakeGateway()
        val store = store(gateway, clock)
        // Vault A の索引を作る（1回目の作り直しがここで起きる）
        store.load("ideas/habit.md", VAULT)
        clock.advance(REFRESH_INTERVAL)
        // Vault B へ切替。Bへは一度も触れていないので、索引はAのまま残っている。
        gateway.currentVaultKey = "content://b"

        // 切替前に出た A 向けの読み出しが遅れて届く
        store.load("ideas/habit.md", VAULT)
        assertEquals(1, gateway.invalidateCount)

        // 枠が残っているので、B の正当なミスはちゃんと作り直せる
        store.load("ideas/habit.md", "content://b")
        assertEquals(2, gateway.invalidateCount)
    }

    // 旧Vault向けの遅れた読み出しが、現在のVaultの正常な索引を捨ててはいけない。
    // 枠も消費させない（消費されると、直後の正当なミスが作り直せなくなる）。
    @Test
    fun `a load for a stale vault neither drops the current index nor spends the budget`() {
        val clock = ElapsedClock()
        val gateway = FakeGateway().apply { currentVaultKey = "content://b" }
        val store = store(gateway, clock)
        // Vault B の索引を作る（1回目の作り直しがここで起きる）
        store.load("ideas/habit.md", "content://b")
        clock.advance(REFRESH_INTERVAL)

        // 切替前の Vault A へ向けた読み出しが遅れて届く
        store.load("ideas/habit.md", "content://a")
        assertEquals(1, gateway.invalidateCount)

        // 枠が残っているので、直後の B のミスはちゃんと作り直せる
        store.load("ideas/habit.md", "content://b")
        assertEquals(2, gateway.invalidateCount)
    }

    @Test
    fun `loadByKey rejects a file whose contents do not match its key`() {
        val gateway = FakeGateway()
        val store = ReadingTraceStore(gateway)
        // 中身は habit.md のまま、別キーのファイルとして置く（改名・取り違えの再現）。
        // 索引を作る前に置くのは、**この検査が索引の作り直しに依存しないようにする**ため。
        gateway.files[ReadingTraceStore.keyFor("journal/other.md")] =
            ReadingTraceJson.encode(trace(path = "ideas/habit.md"))

        val result = store.loadByKey(ReadingTraceStore.keyFor("journal/other.md"), VAULT)

        assertTrue(result is ReadingTraceReadResult.Corrupt)
    }
}

private class ThrowingListGateway : ReadingTraceDocumentGateway {
    override fun ensureFolder(): Boolean = true
    override fun read(key: String, maximumBytes: Int, vaultKey: String): ByteArray? = null
    override fun write(key: String, bytes: ByteArray, vaultKey: String) = Unit
    override fun listKeys(vaultKey: String): Set<String>? = throw RuntimeException("boom")
    override fun delete(key: String, vaultKey: String): Boolean = throw RuntimeException("boom")
    override fun prepareIndexRebuild(vaultKey: String): Boolean = false
}

private const val VAULT = "content://vault"

private val REFRESH_INTERVAL = ReadingTraceStore.DEFAULT_INDEX_REFRESH_INTERVAL_MILLIS

/**
 * 経過時間の偽装。**0 から始める** — 本番の測定源が 0 付近を返す状況を含めて、
 * 初回の作り直しが抑止されないことを確かめたい。
 */
private class ElapsedClock {
    private var current = 0L

    fun now(): Long = current

    fun advance(by: Long) {
        current += by
    }
}

private fun store(gateway: FakeGateway, clock: ElapsedClock) =
    ReadingTraceStore(gateway, clock::now, REFRESH_INTERVAL)

/**
 * SAF実装の構造を写したFake。**`files`（ディスク）と `indexedKeys`（索引）を分ける**のが要点で、
 * 分けないと「外部同期でディスクにだけ増えたファイル」という状態を作れず、
 * 索引の陳腐化を再現できない。
 *
 * 読み書きは索引経由。索引が null なら次の読み書きが `files` から作り直す
 * （SAF実装の `folderIndexOf` と同じく遅延再構築）。
 */
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

    /**
     * 索引に載っていないキーへ書いた回数＝**SAF実装が `createDocument` する回数**。
     * 物理的な `hash (1).json` は再現しないが、「新規作成という判断が起きたか」は数えられる。
     */
    var createCount = 0
        private set
    var invalidateCount = 0
        private set

    /** SAF実装が「列挙できなかった」を返す状態。空フォルダとは区別する。 */
    var listingUnreadable = false
    var listKeysCount = 0
        private set

    /** null なら索引未構築。次の読み書きが `files` から作り直す。 */
    private var indexedKeys: MutableSet<String>? = null

    /** 索引がどのVaultのものか。SAF実装の `FolderIndex.vault` にあたる。 */
    private var indexedVaultKey: String? = null

    /**
     * 次の [read] で、キャッシュ済みUriの読込に失敗したことにする。
     * SAF実装はこの時**自力で索引を捨ててから** null を返す。
     */
    var failNextReadAndDropIndex = false

    /** 索引を経由せずディスクへ置く。外部同期で後からファイルが現れた状態を作る。 */
    fun syncFromOutside(key: String, bytes: ByteArray) {
        files[key] = bytes
    }

    /** SAF実装の `folderIndexOf` と同じく、別Vaultの索引は使わず作り直す。 */
    private fun index(): MutableSet<String> {
        indexedKeys?.let { if (indexedVaultKey == currentVaultKey) return it }
        return files.keys.toMutableSet().also {
            indexedKeys = it
            indexedVaultKey = currentVaultKey
        }
    }

    override fun ensureFolder(): Boolean = folderAvailable

    /**
     * SAF実装と同じく、**捨てるだけで作り直さない**（列挙は次の読み書きが行う）。
     * 判定は「現在のVaultか」だけ。索引が既に無くても、旧Vaultの索引を抱えていても、
     * 現在のVaultなら作り直す価値がある。
     */
    override fun prepareIndexRebuild(vaultKey: String): Boolean {
        if (vaultKey != currentVaultKey) return false
        invalidateCount++
        indexedKeys = null
        indexedVaultKey = null
        return true
    }

    override fun listKeys(vaultKey: String): Set<String>? {
        listKeysCount++
        if (vaultKey != currentVaultKey) return null
        if (listingUnreadable) return null
        return files.keys.toSet()
    }

    /** SAF実装と同じく、Vault不一致・削除失敗は false。 */
    var deleteSucceeds = true
    var deletedKeys = mutableListOf<String>()

    override fun delete(key: String, vaultKey: String): Boolean {
        if (vaultKey != currentVaultKey) return false
        if (!deleteSucceeds) return false
        deletedKeys += key
        indexedKeys?.remove(key)
        return files.remove(key) != null
    }

    override fun read(key: String, maximumBytes: Int, vaultKey: String): ByteArray? {
        readCount++
        readVaultKeys += vaultKey
        // SAF実装は openInputStream の失敗時、索引を捨ててから null を返す。
        if (failNextReadAndDropIndex) {
            failNextReadAndDropIndex = false
            indexedKeys = null
            indexedVaultKey = null
            return null
        }
        if (vaultKey != currentVaultKey) return null
        if (!folderAvailable) return null
        // 索引に載っていないものは、ディスクにあっても見えない（SAF実装と同じ）。
        if (key !in index()) return null
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
        // 索引に無ければ新規作成。SAF実装ではここで createDocument が走り、
        // 実体が既にディスクにあれば同じキーのファイルが2つできる。
        if (index().add(key)) createCount++
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
