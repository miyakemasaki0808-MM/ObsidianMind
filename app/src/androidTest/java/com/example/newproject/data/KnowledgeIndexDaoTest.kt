package com.example.newproject.data

import android.database.Cursor
import android.os.CancellationSignal
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SupportSQLiteStatement
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.newproject.domain.IndexedLink
import com.example.newproject.domain.IndexedLinkKind
import com.example.newproject.domain.NoteLink
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 知識索引の置き場を**実物の Room**で通す（→ docs/dev/system/knowledge_index.md §3・§4・§10）。
 *
 * 規則そのもの（書いてよいか）は JVM の `KnowledgeIndexPlanTest` が持つ。ここが見るのは、
 * **照合と書き込みが1つのトランザクションに入っていること**と、Vault の照合で索引を作り直すこと。
 * メモリ上の DB を使うので、端末のアプリデータには触れない。
 */
@RunWith(AndroidJUnit4::class)
class KnowledgeIndexDaoTest {

    private lateinit var database: KnowledgeIndexDatabase
    private lateinit var store: RoomKnowledgeIndexStore

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, KnowledgeIndexDatabase::class.java).build()
        store = RoomKnowledgeIndexStore { database }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun 別のVaultの索引は開いたときに全部消す() = runBlocking<Unit> {
        store.openVault(VAULT_A)
        assertTrue(store.applyRead(VAULT_A, record("a.md"), listOf(link("習慣")), writeNumberAtRead = null))
        store.markFirstBuildDone(VAULT_A)

        val reopened = store.openVault(VAULT_A)
        assertEquals(setOf("a.md"), reopened.notes.keys)
        assertTrue(reopened.firstBuildDone)

        val other = store.openVault(VAULT_B)
        assertTrue(other.notes.isEmpty())
        assertFalse(other.firstBuildDone)
        assertTrue(database.dao().linksOf("a.md").isEmpty())
    }

    @Test
    fun 読んでいる途中に書き換えた控えがあれば書かず控えも残し最新の番号なら書いて外す() = runBlocking<Unit> {
        store.openVault(VAULT_A)
        assertTrue(store.applyRead(VAULT_A, record("n.md", fingerprint = "A"), listOf(link("古い")), writeNumberAtRead = null))

        store.markRewrittenNow("ref:n.md")
        val numberAtRead = store.openVault(VAULT_A).writeNumbers.getValue("ref:n.md")
        store.markRewrittenNow("ref:n.md")

        assertFalse(
            "古い番号の読み込みは書かない",
            store.applyRead(VAULT_A, record("n.md", fingerprint = "B?"), listOf(link("途中")), numberAtRead)
        )
        assertEquals(listOf("古い"), database.dao().linksOf("n.md").map { it.target })

        val latest = store.openVault(VAULT_A).writeNumbers.getValue("ref:n.md")
        assertTrue(store.applyRead(VAULT_A, record("n.md", fingerprint = "B"), listOf(link("新しい")), latest))
        assertEquals(listOf("新しい"), database.dao().linksOf("n.md").map { it.target })
        assertEquals("書いただけでは控えを外さない", setOf("ref:n.md"), store.openVault(VAULT_A).writeNumbers.keys)
        store.clearMark(VAULT_A, "ref:n.md", latest)
        assertTrue("番号が同じなら外す", store.openVault(VAULT_A).writeNumbers.isEmpty())
        assertFalse(
            "新しい読み込みが控えを外した後に、古い番号の読み込みが書こうとしても B から戻らない",
            store.applyRead(VAULT_A, record("n.md", fingerprint = "A"), listOf(link("古い")), numberAtRead)
        )
        assertEquals(listOf("新しい"), database.dao().linksOf("n.md").map { it.target })
    }

    /** 控えは参照の単位。ノートの行を消しても控えは残り（名前の変更で新しいパスが読み直す）、外すのは番号が同じときだけ。 */
    @Test
    fun ノートを消しても控えは残り番号が進んでいれば外さない() = runBlocking<Unit> {
        store.openVault(VAULT_A)
        store.applyRead(VAULT_A, record("old.md"), listOf(link("習慣")), writeNumberAtRead = null)
        store.markRewrittenNow("ref:old.md")
        val number = store.openVault(VAULT_A).writeNumbers.getValue("ref:old.md")

        store.deleteNotes(VAULT_A, listOf("old.md"))
        assertEquals(setOf("ref:old.md"), store.openVault(VAULT_A).writeNumbers.keys)

        store.markRewrittenNow("ref:old.md")
        store.clearMark(VAULT_A, "ref:old.md", number)
        assertEquals("途中で進んだ番号の控えは残る", setOf("ref:old.md"), store.openVault(VAULT_A).writeNumbers.keys)
    }

    @Test
    fun 名前の鍵で書かれたままと復号した後のどちらかが合うリンクを引き別のVaultの索引ならnullを返す() = runBlocking<Unit> {
        store.openVault(VAULT_A)
        store.applyRead(VAULT_A, record("a.md"), listOf(link("習慣").copy(keys = listOf("my%20note", "my note"))), writeNumberAtRead = null)
        store.applyRead(VAULT_A, record("b.md"), listOf(link("別").copy(keys = listOf("別"))), writeNumberAtRead = null)

        assertEquals(listOf("a.md"), store.backlinkLinks(VAULT_A, "my note", "x.md")!!.candidates.map { it.sourcePath })
        assertEquals(listOf("a.md"), store.backlinkLinks(VAULT_A, "my%20note", "x.md")!!.candidates.map { it.sourcePath })
        assertEquals(null, store.backlinkLinks(VAULT_B, "my note", "x.md"))
    }

    @Test
    fun 一文の中のリンクの範囲と名前の長さを書いたまま戻し無ければnullで戻す() = runBlocking<Unit> {
        store.openVault(VAULT_A)
        store.applyRead(VAULT_A, record("a.md"), listOf(link("習慣")), writeNumberAtRead = null)
        store.applyRead(VAULT_A, record("b.md"), listOf(link("習慣").copy(sentenceLink = null, sentenceNameLength = null)), writeNumberAtRead = null)

        val bySource = store.backlinkLinks(VAULT_A, "習慣", "x.md")!!.candidates.associate { it.sourcePath to (it.sentenceLink to it.sentenceNameLength) }
        assertEquals(mapOf("a.md" to (0..1 to 2), "b.md" to (null to null)), bySource)
    }

    @Test
    fun 入るリンクと一組でノートから出るリンクを本文の順に引き_読んでいないノートと別のVaultではnullを返す() = runBlocking<Unit> {
        store.openVault(VAULT_A)
        val links = listOf(
            link("習慣").copy(ordinal = 1),
            link("日記.md").copy(ordinal = 0, kind = IndexedLinkKind.PATH, heading = "まとめ")
        )
        store.applyRead(VAULT_A, record("a.md"), links, writeNumberAtRead = null)

        assertEquals(
            listOf(NoteLink.ByPath("日記.md", "まとめ"), NoteLink.ByName("習慣", null)),
            store.backlinkLinks(VAULT_A, "習慣", "a.md")?.outgoing
        )
        assertEquals("読んでいないノートは空と分けて null", null, store.backlinkLinks(VAULT_A, "習慣", "まだ読んでいない.md")?.outgoing)
        store.applyRead(VAULT_A, record("リンクなし.md"), emptyList(), writeNumberAtRead = null)
        assertEquals(emptyList<NoteLink>(), store.backlinkLinks(VAULT_A, "習慣", "リンクなし.md")?.outgoing)
        assertEquals(null, store.backlinkLinks(VAULT_B, "習慣", "a.md"))
    }

    /**
     * 入るリンクと出るリンクを引く照会は、**実物の Room の1つのトランザクション**で一組になる（→ features/backlinks.md 判断5）。
     * 照会を2本目の手前で止め、同じファイルを開いた別の Room から、今のノートの出るリンクを消す書き込みを始める。
     * 照会は書く前の組（入るリンクも出るリンクも旧い）を返し、書いた後に引き直すと新しい組になる。
     * トランザクションが外れると、止めた間の書き込みが2本目に見え、入るリンクは旧く出るリンクは新しい組になる。
     */
    @Test
    fun 入るリンクと出るリンクの照会の途中に別の接続から書いても_書く前の組を返す() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(TX_FILE)
        val path = context.getDatabasePath(TX_FILE).path
        val pausing = PausingFactory()
        val reader = Room.databaseBuilder(context, KnowledgeIndexDatabase::class.java, path).openHelperFactory(pausing).build()
        val writer = Room.databaseBuilder(context, KnowledgeIndexDatabase::class.java, path).build()
        try {
            val readerStore = RoomKnowledgeIndexStore { reader }
            val writerStore = RoomKnowledgeIndexStore { writer }
            writerStore.openVault(VAULT_A)
            writerStore.applyRead(VAULT_A, record("a.md"), listOf(link("b")), writeNumberAtRead = null)
            writerStore.applyRead(VAULT_A, record("b.md"), listOf(link("a")), writeNumberAtRead = null)
            readerStore.openVault(VAULT_A)

            pausing.pauseBefore = "FROM indexed_note WHERE relativePath"
            val read = async(Dispatchers.IO) { readerStore.backlinkLinks(VAULT_A, "a", "a.md") }
            assertTrue("照会が2本目の手前で止まった", pausing.paused.await(10, TimeUnit.SECONDS))
            val write = async(Dispatchers.IO) {
                writerStore.applyRead(VAULT_A, record("a.md", fingerprint = "g"), emptyList(), writeNumberAtRead = null)
            }
            // 書き手は照会が終わるまで待たされうる。待たせたまま照会を止め続けると互いに待つので、時間を区切って照会を進める。
            withTimeoutOrNull(2_000) { write.await() }
            pausing.resume.countDown()
            val links = checkNotNull(read.await())
            assertTrue(write.await())

            assertEquals(listOf("b.md"), links.candidates.map { it.sourcePath })
            assertEquals("書く前の組", listOf(NoteLink.ByName("b", null)), links.outgoing)
            assertEquals("書いた後は新しい組", emptyList<NoteLink>(), readerStore.backlinkLinks(VAULT_A, "a", "a.md")?.outgoing)
        } finally {
            pausing.resume.countDown()
            reader.close()
            writer.close()
            context.deleteDatabase(TX_FILE)
        }
    }

    @Test
    fun 別のVaultに替えた後は旧いVaultの書き込みを受けない() = runBlocking<Unit> {
        store.openVault(VAULT_A)
        store.openVault(VAULT_B)

        assertFalse(store.applyRead(VAULT_A, record("a.md"), listOf(link("習慣")), writeNumberAtRead = null))
        assertTrue(store.openVault(VAULT_B).notes.isEmpty())
    }

    @Test
    fun 索引に無い参照も控え初めて書く読み込みを照合で止める() = runBlocking<Unit> {
        store.openVault(VAULT_A)
        store.markRewrittenNow("ref:新しい.md")
        assertEquals(setOf("ref:新しい.md"), store.openVault(VAULT_A).writeNumbers.keys)

        assertFalse(
            "控える前に読み始めた初めての読み込みは書かない",
            store.applyRead(VAULT_A, record("新しい.md"), listOf(link("古い")), writeNumberAtRead = null)
        )
        val number = store.openVault(VAULT_A).writeNumbers.getValue("ref:新しい.md")
        assertTrue(store.applyRead(VAULT_A, record("新しい.md"), listOf(link("新しい")), number))
        store.clearMark(VAULT_A, "ref:新しい.md", number)
        assertTrue(store.openVault(VAULT_A).writeNumbers.isEmpty())

        store.markRewrittenNow("ref:消える.md")
        store.forgetMarks(VAULT_A, listOf("ref:消える.md"))
        assertTrue(store.openVault(VAULT_A).writeNumbers.isEmpty())
    }

    @Test
    fun 変数の上限を超える数のノートもまとめて消せる() = runBlocking<Unit> {
        store.openVault(VAULT_A)
        val paths = (0 until 1_200).map { "n$it.md" }
        paths.forEach { store.applyRead(VAULT_A, record(it), listOf(link("習慣")), writeNumberAtRead = null) }

        store.deleteNotes(VAULT_A, paths)

        assertTrue(store.openVault(VAULT_A).notes.isEmpty())
    }

    /**
     * 照会の文を見張り、[pauseBefore] を含む文の手前で一度だけ止める開き方（テスト専用）。本番の DAO に検査の口を足さずに、
     * 1つの照会の中の2本の文の間へ別の接続の書き込みを挟むために使う。
     */
    private class PausingFactory(private val delegate: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory()) :
        SupportSQLiteOpenHelper.Factory {
        @Volatile
        var pauseBefore: String? = null
        val paused = CountDownLatch(1)
        val resume = CountDownLatch(1)

        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            val helper = delegate.create(configuration)
            return object : SupportSQLiteOpenHelper by helper {
                override val writableDatabase: SupportSQLiteDatabase get() = Watched(helper.writableDatabase)
                override val readableDatabase: SupportSQLiteDatabase get() = Watched(helper.readableDatabase)
            }
        }

        private inner class Watched(private val db: SupportSQLiteDatabase) : SupportSQLiteDatabase by db {
            private fun watch(sql: String) {
                val key = pauseBefore ?: return
                if (key !in sql) return
                pauseBefore = null
                paused.countDown()
                resume.await(10, TimeUnit.SECONDS)
            }

            override fun compileStatement(sql: String): SupportSQLiteStatement = db.compileStatement(sql).also { watch(sql) }
            override fun query(query: String): Cursor = watch(query).let { db.query(query) }
            override fun query(query: String, bindArgs: Array<out Any?>): Cursor = watch(query).let { db.query(query, bindArgs) }
            override fun query(query: SupportSQLiteQuery): Cursor = watch(query.sql).let { db.query(query) }
            override fun query(query: SupportSQLiteQuery, cancellationSignal: CancellationSignal?): Cursor =
                watch(query.sql).let { db.query(query, cancellationSignal) }
        }
    }

    private fun record(path: String, fingerprint: String = "f") =
        IndexedNoteRecord(relativePath = path, documentRef = "ref:$path", name = path, lastModified = 1L, fingerprint = fingerprint)

    private fun link(target: String) = IndexedLink(
        ordinal = 0,
        kind = IndexedLinkKind.NAME,
        target = target,
        heading = null,
        sectionHeading = null,
        sectionHeadingOccurrence = 0,
        sentence = "$target を読む。",
        sentenceLink = target.indices,
        sentenceNameLength = target.length,
        keys = listOf(target)
    )

    private companion object {
        const val VAULT_A = "content://vault/a"
        const val VAULT_B = "content://vault/b"
        const val TX_FILE = "knowledge_index_tx_test.db"
    }
}
