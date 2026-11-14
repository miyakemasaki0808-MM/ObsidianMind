package com.example.newproject

import com.example.newproject.controller.KnowledgeIndexController
import com.example.newproject.data.IndexedNoteRecord
import com.example.newproject.data.KnowledgeIndexSnapshot
import com.example.newproject.data.KnowledgeIndexStore
import com.example.newproject.domain.IndexedLink
import com.example.newproject.domain.IndexedNoteState
import com.example.newproject.domain.mayApplyIndexedRead
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteFile
import com.example.newproject.model.NoteScanKind
import com.example.newproject.model.PublishedNoteScan
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 知識索引を作る側（→ docs/dev/system/knowledge_index.md §3・§4・§5・§10）。
 *
 * **突き合わせは1本ずつ走り、途中で届いた公開は終わってからもう1回走らせる。** 書き換えの番号が最新のときだけ書くので、
 * 読んでいる途中にアプリが書き換えても、古い本文で索引を上書きせず、次の回で新しい本文を書く。
 * 置き場の偽物は、本番の DAO と同じ規則（[mayApplyIndexedRead]・Vault の照合）で書き込みを受ける。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KnowledgeIndexControllerTest {

    private class Env(scope: TestScope) {
        var generation = 0L
        var key: String? = VAULT_A
        val handle = FakeVaultHandle()
        val browser = FakeVaultBrowser(handle)
        val store = FakeKnowledgeIndexStore()
        var scanRequests = 0
        var publishOnRequest: (() -> Unit)? = null
        private var publishes = 0L

        val controller = KnowledgeIndexController(
            scope = scope,
            store = store,
            vault = browser,
            vaultGeneration = { generation },
            vaultKey = { key },
            requestScan = {
                scanRequests++
                publishOnRequest?.invoke()
            },
            parseDispatcher = UnconfinedTestDispatcher(scope.testScheduler)
        )

        fun publish(notes: List<NoteFile>, kind: NoteScanKind = NoteScanKind.COMPLETE) {
            publishes++
            controller.onScanPublished(PublishedNoteScan(notes, kind, publishes, generation))
        }

        fun body(path: String, text: String) {
            val previous = handle.bodies
            handle.bodies = { ref -> if (ref == refOf(path)) text else previous(ref) }
        }

        fun reads(): List<String> = handle.readBodyRefs.map { it.value.removePrefix("ref:") }
    }

    @Test
    fun `最初の公開で全ノートを読み、リンクを入れて最初の作成を終えたと控える`() = runTest {
        val env = Env(this)
        env.body("a.md", "[[b]] を読む。")
        env.body("b.md", "リンクの無いノート。")

        env.publish(listOf(note("a.md", 1L), note("b.md", 1L)))
        advanceUntilIdle()

        assertEquals(setOf("a.md", "b.md"), env.store.notes.keys)
        assertEquals(listOf("b"), env.store.links.getValue("a.md").map { it.target })
        assertTrue(env.store.firstBuildDone)
        assertEquals(1L, env.controller.indexGeneration)
    }

    @Test
    fun `更新日時が同じノートは読み直さず、変わったノートだけ読む`() = runTest {
        val env = Env(this)
        env.body("a.md", "[[b]]")
        env.body("b.md", "本文")
        env.publish(listOf(note("a.md", 1L), note("b.md", 1L)))
        advanceUntilIdle()
        env.handle.readBodyRefs.clear()

        env.body("a.md", "[[c]]")
        env.publish(listOf(note("a.md", 2L), note("b.md", 1L)))
        advanceUntilIdle()

        assertEquals(listOf("a.md"), env.reads())
        assertEquals(listOf("c"), env.store.links.getValue("a.md").map { it.target })
    }

    @Test
    fun `日時が両方空のノートは毎回読み、リンクの変化を反映し、指紋が同じなら書き換えない`() = runTest {
        val env = Env(this)
        env.body("n.md", "[[古い]]")
        env.publish(listOf(note("n.md", null)))
        advanceUntilIdle()
        val writes = env.store.applyCount

        env.publish(listOf(note("n.md", null)))
        advanceUntilIdle()
        assertEquals("指紋が同じなら書かない", writes, env.store.applyCount)

        env.body("n.md", "[[新しい]]")
        env.publish(listOf(note("n.md", null)))
        advanceUntilIdle()
        assertEquals(listOf("新しい"), env.store.links.getValue("n.md").map { it.target })
        assertEquals("3回とも読む", 3, env.reads().size)
    }

    @Test
    fun `読み切れた走査に無いノートは消し、一部を読めない走査では消さない`() = runTest {
        val env = Env(this)
        env.body("a.md", "本文")
        env.body("b.md", "本文")
        env.publish(listOf(note("a.md", 1L), note("b.md", 1L)))
        advanceUntilIdle()

        env.publish(listOf(note("a.md", 1L)), NoteScanKind.PARTIAL)
        advanceUntilIdle()
        assertEquals(setOf("a.md", "b.md"), env.store.notes.keys)

        env.publish(listOf(note("a.md", 1L)), NoteScanKind.COMPLETE)
        advanceUntilIdle()
        assertEquals(setOf("a.md"), env.store.notes.keys)
    }

    @Test
    fun `読めずに空の公開では何もせず、読み切った空のVaultでは全部消す`() = runTest {
        val env = Env(this)
        env.body("a.md", "[[b]]")
        env.publish(listOf(note("a.md", 1L)))
        advanceUntilIdle()
        val generation = env.controller.indexGeneration

        env.publish(emptyList(), NoteScanKind.UNREADABLE_EMPTY)
        advanceUntilIdle()
        assertEquals(setOf("a.md"), env.store.notes.keys)
        assertEquals("準備完了と見なさないので索引の世代も進めない", generation, env.controller.indexGeneration)

        env.publish(emptyList(), NoteScanKind.COMPLETE)
        advanceUntilIdle()
        assertTrue(env.store.notes.isEmpty())
    }

    /** 読めずに空の走査で残すのは同じ Vault の行だけ。別の Vault へ替えたら、読めなくても前の Vault の本文の断片を残さない（→ knowledge_index.md §4）。 */
    @Test
    fun `切り替えた先のVaultが読めずに空でも前のVaultの索引を残さず、同じVaultなら残し、戻したら作り直す`() = runTest {
        val env = Env(this)
        env.body("a.md", "[[b]]")
        env.publish(listOf(note("a.md", 1L)))
        advanceUntilIdle()

        env.publish(emptyList(), NoteScanKind.UNREADABLE_EMPTY)
        advanceUntilIdle()
        assertEquals("同じ Vault の読めない走査では残す", setOf("a.md"), env.store.notes.keys)

        switchVault(env, VAULT_B)
        env.publish(emptyList(), NoteScanKind.UNREADABLE_EMPTY)
        advanceUntilIdle()
        assertTrue("前の Vault の行は残さない", env.store.notes.isEmpty())
        assertTrue(env.store.marks.isEmpty())

        switchVault(env, VAULT_A)
        env.publish(listOf(note("a.md", 1L)))
        advanceUntilIdle()
        assertEquals(setOf("a.md"), env.store.notes.keys)
        assertTrue("A を作り直す", env.store.firstBuildDone)
        assertEquals(2, env.reads().count { it == "a.md" })
    }

    /** 開いたときの照合を通らずに、別の Vault の読めない公開だけが届いても、突き合わせが先に開いて前の行を消す。 */
    @Test
    fun `別のVaultの読めずに空の公開だけが届いても、前のVaultの索引を残さない`() = runTest {
        val env = Env(this)
        env.body("a.md", "[[b]]")
        env.publish(listOf(note("a.md", 1L)))
        advanceUntilIdle()

        env.generation++
        env.key = VAULT_B
        env.controller.onVaultChanged()
        env.publish(emptyList(), NoteScanKind.UNREADABLE_EMPTY)
        advanceUntilIdle()

        assertTrue(env.store.notes.isEmpty())
        assertEquals(VAULT_B, env.store.vaultKey)
    }

    /** 替えた先の走査が例外で終わると公開が来ない。開いたときの照合が無いと、前の Vault の行が残り続ける。 */
    @Test
    fun `切り替えた先の走査が失敗して公開が来なくても、開いたときに前のVaultの索引を消す`() = runTest {
        val env = Env(this)
        env.body("a.md", "[[b]]")
        env.publish(listOf(note("a.md", 1L)))
        advanceUntilIdle()

        env.publishOnRequest = { throw java.io.IOException("Vault を読めない") }
        switchVault(env, VAULT_B)
        advanceUntilIdle()

        assertEquals(1, env.scanRequests)
        assertTrue(env.store.notes.isEmpty())
        assertEquals(VAULT_B, env.store.vaultKey)
    }

    private fun switchVault(env: Env, key: String) {
        env.generation++
        env.key = key
        env.controller.onVaultChanged()
        env.controller.onVaultOpened()
    }

    @Test
    fun `突き合わせの途中に届いた公開は終わってからもう1回走らせ、2本は重ならない`() = runTest {
        val env = Env(this)
        env.handle.bodies = { "[[x]]" }
        val held = CompletableDeferred<Unit>()
        env.store.beforeApply = { path -> if (path == "a.md") held.await() }

        env.publish(listOf(note("a.md", 1L)))
        runCurrent()
        assertEquals(1, env.store.openCount)

        env.publish(listOf(note("a.md", 1L), note("b.md", 1L)))
        env.publish(listOf(note("a.md", 1L), note("b.md", 1L), note("c.md", 1L)))
        runCurrent()
        assertEquals("1本目が終わるまで2本目は始まらない", 1, env.store.openCount)

        env.store.beforeApply = {}
        held.complete(Unit)
        advanceUntilIdle()

        assertEquals("途中の2つの公開は1回にまとめる", 2, env.store.openCount)
        assertEquals(setOf("a.md", "b.md", "c.md"), env.store.notes.keys)
    }

    /**
     * 3回目のレビューで示された順序（→ knowledge_index.md §10）。日時は空でない同じ値のまま。
     * X が本文Aを読んで書く前に止まる → アプリが B へ書き換えて公開が届く → X が終わる。X は書かずに控えを残し、次の回が B を書く。
     */
    @Test
    fun `読んでいる途中にBへ書き換えると、Aを書かずに控えを残し、次の回でBを書く`() = runTest {
        val env = Env(this)
        env.body("n.md", "[[最初]]")
        env.publish(listOf(note("n.md", 5L)))
        advanceUntilIdle()

        // アプリが A を書いた。控えて走査を頼み、公開が届く。
        env.body("n.md", "[[A]]")
        env.publishOnRequest = { env.publish(listOf(note("n.md", 5L))) }
        val readingA = CompletableDeferred<Unit>()
        env.store.beforeApply = { path -> if (path == "n.md" && env.store.links.getValue("n.md").single().target == "最初") readingA.await() }
        env.controller.onNoteBodyWritten("n.md")
        runCurrent()

        // X が A を読み、書く手前で止まっている間に、アプリが B を書く。
        env.body("n.md", "[[B]]")
        env.controller.onNoteBodyWritten("n.md")
        runCurrent()
        readingA.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("B"), env.store.links.getValue("n.md").map { it.target })
        assertTrue("書き終えたら控えは外れる", env.store.marks.isEmpty())
        assertTrue("A の読み込みは書かなかった", env.store.rejectedApplies >= 1)
    }

    @Test
    fun `本文を書き換えたら控えて走査を頼み、公開が無ければもう一度だけ頼む`() = runTest {
        val env = Env(this)
        env.body("n.md", "本文")
        env.publish(listOf(note("n.md", 1L)))
        advanceUntilIdle()

        env.controller.onNoteBodyWritten("n.md")
        advanceUntilIdle()

        assertEquals(setOf("n.md"), env.store.marks.keys)
        assertEquals("公開されなかったので2回まで", 2, env.scanRequests)

        env.scanRequests = 0
        env.publishOnRequest = { env.publish(listOf(note("n.md", 1L))) }
        env.controller.onNoteBodyWritten("n.md")
        advanceUntilIdle()
        assertEquals("公開されたら頼み直さない", 1, env.scanRequests)
        assertTrue("日時が同じでも読み直して控えを外す", env.store.marks.isEmpty())
    }

    @Test
    fun `Vaultを開いたら走査を頼む`() = runTest {
        val env = Env(this)

        env.controller.onVaultOpened()
        advanceUntilIdle()

        assertEquals(1, env.scanRequests)
    }

    @Test
    fun `Vaultを替えたら、旧いVaultの本文の読み込みが後から終わっても書かない`() = runTest {
        val env = Env(this)
        val held = CompletableDeferred<Unit>()
        env.store.beforeApply = { held.await() }
        env.body("a.md", "[[b]]")
        env.publish(listOf(note("a.md", 1L)))
        runCurrent()

        env.generation++
        env.key = VAULT_B
        env.controller.onVaultChanged()
        held.complete(Unit)
        advanceUntilIdle()

        assertTrue(env.store.notes.isEmpty())
        assertFalse(env.store.firstBuildDone)
    }

    @Test
    fun `旧いVaultの世代の公開は受けない`() = runTest {
        val env = Env(this)
        env.body("a.md", "本文")
        env.controller.onScanPublished(PublishedNoteScan(listOf(note("a.md", 1L)), NoteScanKind.COMPLETE, 1L, vaultGeneration = -1L))
        advanceUntilIdle()

        assertEquals(0, env.store.openCount)
    }

    /** 本番の DAO と同じ規則で書き込みを受ける偽物。[beforeApply] で書く手前に止められる。 */
    private class FakeKnowledgeIndexStore : KnowledgeIndexStore {
        var vaultKey: String? = null
        var firstBuildDone = false
        private var lastWriteNumber = 0L
        val notes = linkedMapOf<String, IndexedNoteRecord>()
        val links = mutableMapOf<String, List<IndexedLink>>()
        val marks = mutableMapOf<String, Long>()
        var openCount = 0
        var applyCount = 0
        var rejectedApplies = 0
        var beforeApply: suspend (String) -> Unit = {}

        override suspend fun openVault(vaultKey: String): KnowledgeIndexSnapshot {
            openCount++
            if (this.vaultKey != vaultKey) {
                notes.clear(); links.clear(); marks.clear()
                firstBuildDone = false
                this.vaultKey = vaultKey
            }
            return KnowledgeIndexSnapshot(
                notes = notes.mapValues { IndexedNoteState(it.value.lastModified, it.value.fingerprint) },
                writeNumbers = marks.toMap(),
                firstBuildDone = firstBuildDone
            )
        }

        override suspend fun markRewritten(vaultKey: String, relativePath: String) {
            if (this.vaultKey != vaultKey) return
            marks[relativePath] = ++lastWriteNumber
        }

        override suspend fun applyRead(
            vaultKey: String,
            note: IndexedNoteRecord,
            links: List<IndexedLink>,
            writeNumberAtRead: Long?
        ): Boolean {
            beforeApply(note.relativePath)
            if (this.vaultKey != vaultKey) return false
            if (!mayApplyIndexedRead(writeNumberAtRead, marks[note.relativePath])) {
                rejectedApplies++
                return false
            }
            applyCount++
            notes[note.relativePath] = note
            this.links[note.relativePath] = links
            if (writeNumberAtRead != null) marks.remove(note.relativePath)
            return true
        }

        override suspend fun deleteNotes(vaultKey: String, relativePaths: List<String>) {
            if (this.vaultKey != vaultKey) return
            relativePaths.forEach { notes.remove(it); links.remove(it); marks.remove(it) }
        }

        override suspend fun markFirstBuildDone(vaultKey: String) {
            if (this.vaultKey == vaultKey) firstBuildDone = true
        }
    }

    private companion object {
        const val VAULT_A = "content://vault/a"
        const val VAULT_B = "content://vault/b"

        fun refOf(path: String) = DocumentRef("ref:$path")

        fun note(path: String, lastModified: Long?) =
            NoteFile(name = path, ref = refOf(path), lastModified = lastModified, vaultRelativePath = path)
    }
}
