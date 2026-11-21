package com.example.newproject.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.newproject.domain.IndexedLink
import com.example.newproject.domain.IndexedLinkKind
import com.example.newproject.domain.NoteLink
import kotlinx.coroutines.runBlocking
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

        assertEquals(listOf("a.md"), store.backlinkCandidates(VAULT_A, "my note")!!.map { it.sourcePath })
        assertEquals(listOf("a.md"), store.backlinkCandidates(VAULT_A, "my%20note")!!.map { it.sourcePath })
        assertEquals(null, store.backlinkCandidates(VAULT_B, "my note"))
    }

    @Test
    fun 一文の中のリンクの範囲を書いたまま戻し無ければnullで戻す() = runBlocking<Unit> {
        store.openVault(VAULT_A)
        store.applyRead(VAULT_A, record("a.md"), listOf(link("習慣")), writeNumberAtRead = null)
        store.applyRead(VAULT_A, record("b.md"), listOf(link("習慣").copy(sentenceLink = null)), writeNumberAtRead = null)

        val bySource = store.backlinkCandidates(VAULT_A, "習慣")!!.associate { it.sourcePath to it.sentenceLink }
        assertEquals(mapOf("a.md" to 0..1, "b.md" to null), bySource)
    }

    @Test
    fun ノートから出るリンクを本文の順に引き別のVaultの索引ならnullを返す() = runBlocking<Unit> {
        store.openVault(VAULT_A)
        val links = listOf(
            link("習慣").copy(ordinal = 1),
            link("日記.md").copy(ordinal = 0, kind = IndexedLinkKind.PATH, heading = "まとめ")
        )
        store.applyRead(VAULT_A, record("a.md"), links, writeNumberAtRead = null)

        assertEquals(
            listOf(NoteLink.ByPath("日記.md", "まとめ"), NoteLink.ByName("習慣", null)),
            store.linksFrom(VAULT_A, "a.md")
        )
        assertEquals(emptyList<NoteLink>(), store.linksFrom(VAULT_A, "まだ読んでいない.md"))
        assertEquals(null, store.linksFrom(VAULT_B, "a.md"))
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
        keys = listOf(target)
    )

    private companion object {
        const val VAULT_A = "content://vault/a"
        const val VAULT_B = "content://vault/b"
    }
}
