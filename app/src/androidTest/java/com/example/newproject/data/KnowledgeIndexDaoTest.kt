package com.example.newproject.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.newproject.domain.IndexedLink
import com.example.newproject.domain.IndexedLinkKind
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

        store.markRewritten(VAULT_A, "n.md")
        val numberAtRead = store.openVault(VAULT_A).writeNumbers.getValue("n.md")
        store.markRewritten(VAULT_A, "n.md")

        assertFalse(
            "古い番号の読み込みは書かない",
            store.applyRead(VAULT_A, record("n.md", fingerprint = "B?"), listOf(link("途中")), numberAtRead)
        )
        assertEquals(listOf("古い"), database.dao().linksOf("n.md").map { it.target })

        val latest = store.openVault(VAULT_A).writeNumbers.getValue("n.md")
        assertTrue(store.applyRead(VAULT_A, record("n.md", fingerprint = "B"), listOf(link("新しい")), latest))
        assertEquals(listOf("新しい"), database.dao().linksOf("n.md").map { it.target })
        assertTrue("書いたら控えを外す", store.openVault(VAULT_A).writeNumbers.isEmpty())
        assertFalse(
            "新しい読み込みが控えを外した後に、古い番号の読み込みが書こうとしても B から戻らない",
            store.applyRead(VAULT_A, record("n.md", fingerprint = "A"), listOf(link("古い")), numberAtRead)
        )
        assertEquals(listOf("新しい"), database.dao().linksOf("n.md").map { it.target })
    }

    @Test
    fun 別のVaultに替えた後は旧いVaultの書き込みを受けない() = runBlocking<Unit> {
        store.openVault(VAULT_A)
        store.openVault(VAULT_B)

        assertFalse(store.applyRead(VAULT_A, record("a.md"), listOf(link("習慣")), writeNumberAtRead = null))
        store.markRewritten(VAULT_A, "a.md")
        assertTrue(store.openVault(VAULT_B).notes.isEmpty())
        assertTrue(store.openVault(VAULT_B).writeNumbers.isEmpty())
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
        keys = listOf(target)
    )

    private companion object {
        const val VAULT_A = "content://vault/a"
        const val VAULT_B = "content://vault/b"
    }
}
