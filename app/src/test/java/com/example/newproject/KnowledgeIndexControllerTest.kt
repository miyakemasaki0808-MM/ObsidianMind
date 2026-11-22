package com.example.newproject

import com.example.newproject.controller.KnowledgeIndexController
import com.example.newproject.data.BacklinkLinks
import com.example.newproject.data.IndexedNoteRecord
import com.example.newproject.data.KnowledgeIndexSnapshot
import com.example.newproject.data.KnowledgeIndexStore
import com.example.newproject.domain.IndexedLink
import com.example.newproject.domain.IndexedNoteState
import com.example.newproject.domain.mayApplyIndexedRead
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.KnowledgeIndexProgressWriter
import com.example.newproject.model.NoteFile
import com.example.newproject.model.NoteScanKind
import com.example.newproject.model.PublishedNoteScan
import com.example.newproject.model.state.KnowledgeIndexProgress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    private class Env(scope: TestScope, val store: FakeKnowledgeIndexStore = FakeKnowledgeIndexStore()) {
        var generation = 0L
        var key: String? = VAULT_A
        val handle = FakeVaultHandle()
        val browser = FakeVaultBrowser(handle)
        var scanRequests = 0
        var publishOnRequest: (() -> Unit)? = null

        /** 走査を頼むと投げる例外。null なら頼めて、[publishOnRequest] に従う。 */
        var scanFailure: Exception? = null
        private var publishes = 0L

        /** 公開を受けた・索引を直し終えた知らせの回数。 */
        var changes = 0

        /** 書かれた進み具合の順。 */
        val progress = mutableListOf<KnowledgeIndexProgress>()

        val controller = KnowledgeIndexController(
            scope = scope,
            store = store,
            vault = browser,
            vaultGeneration = { generation },
            vaultKey = { key },
            requestScan = {
                scanRequests++
                scanFailure?.let { throw it }
                publishOnRequest?.invoke()
            },
            progress = object : KnowledgeIndexProgressWriter {
                override fun set(progress: KnowledgeIndexProgress) {
                    this@Env.progress += progress
                }
            },
            onChanged = { changes++ },
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

        /** 蒸留が本文を書き終えたときの本番の順: 書いたスレッドで控えを永続し、Main で走査を頼む。 */
        fun written(path: String) {
            controller.markRewrittenNow(refOf(path))
            controller.onNoteBodyWritten()
        }
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
        assertEquals("公開を受けたときと、直し終えたときに知らせる", 2, env.changes)
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
        val changes = env.changes

        env.publish(emptyList(), NoteScanKind.UNREADABLE_EMPTY)
        advanceUntilIdle()
        assertEquals(setOf("a.md"), env.store.notes.keys)
        assertEquals("公開を受けた知らせだけで、直し終えた知らせは出さない", changes + 1, env.changes)
        assertEquals(NoteScanKind.UNREADABLE_EMPTY, env.controller.latestScan?.kind)

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
        env.written("n.md")
        runCurrent()

        // X が A を読み、書く手前で止まっている間に、アプリが B を書く。
        env.body("n.md", "[[B]]")
        env.written("n.md")
        runCurrent()
        readingA.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("B"), env.store.links.getValue("n.md").map { it.target })
        assertTrue("書き終えたら控えは外れる", env.store.marks.isEmpty())
        assertTrue("A の読み込みは書かなかった", env.store.rejectedApplies >= 1)
    }

    /**
     * 索引に無いノートは、参照から索引の行を引けない。控えを参照そのもので持たないと、初めて読んでいる途中の書き換えで
     * 古い読み込みが番号の照合（どちらも空）を通り、日時が同じなら以後読み直さない。
     */
    @Test
    fun `索引に無いノートを初めて読んでいる途中に書き換えても、古い本文を残さず新しい本文を書く`() = runTest {
        val env = Env(this)
        env.body("n.md", "[[old]]")
        val held = CompletableDeferred<Unit>()
        env.store.beforeApply = { path -> if (path == "n.md") held.await() }
        env.publish(listOf(note("n.md", 5L)))
        runCurrent()

        env.body("n.md", "[[new]]")
        env.publishOnRequest = { env.publish(listOf(note("n.md", 5L))) }
        env.written("n.md")
        runCurrent()
        env.store.beforeApply = {}
        held.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("new"), env.store.links.getValue("n.md").map { it.target })
        assertTrue("書き終えたら控えは外れる", env.store.marks.isEmpty())
    }

    @Test
    fun `索引に無いノートを初めて書いた直後に書き換えても、次の回で新しい本文を書く`() = runTest {
        val env = Env(this)
        env.body("n.md", "[[old]]")
        env.publish(listOf(note("n.md", 5L)))
        advanceUntilIdle()

        env.body("n.md", "[[new]]")
        env.publishOnRequest = { env.publish(listOf(note("n.md", 5L))) }
        env.written("n.md")
        advanceUntilIdle()

        assertEquals(listOf("new"), env.store.links.getValue("n.md").map { it.target })
        assertTrue(env.store.marks.isEmpty())
    }

    /**
     * 控えは参照の単位で持ち、**外すのも参照の単位**にする。DocumentsProvider は参照を保ったままの名前の変更を認めるので、
     * 古いパスの行を消すときに控えまで消すと、新しいパスの読み込みが番号の照合で落ちて、旧い行も新しい行も無くなる。
     */
    @Test
    fun `控えのあるノートの名前を参照のまま変えても、その読み切れた走査で新しいパスのリンクが入る`() = runTest {
        val env = Env(this)
        env.body("old.md", "[[before]]")
        env.publish(listOf(note("old.md", 5L)))
        advanceUntilIdle()

        env.body("old.md", "[[after]]")
        env.written("old.md")
        advanceUntilIdle()
        val renamed = NoteFile(name = "new.md", ref = refOf("old.md"), lastModified = 5L, vaultRelativePath = "new.md")
        env.controller.onScanPublished(PublishedNoteScan(listOf(renamed), NoteScanKind.COMPLETE, 50L, env.generation))
        advanceUntilIdle()

        assertEquals(setOf("new.md"), env.store.notes.keys)
        assertEquals(listOf("after"), env.store.links.getValue("new.md").map { it.target })
        assertTrue(env.store.marks.isEmpty())
    }

    /** 同じ文書を2つのフォルダに見せるプロバイダでは、1つの参照に2つのパスがある。先に書いた1件が控えを外すと、もう1件が読み直されない。 */
    @Test
    fun `同じ参照の2つのパスを書き換えたら、書く順によらず両方が新しいリンクになる`() = runTest {
        val env = Env(this)
        val shared = DocumentRef("ref:shared")
        fun at(path: String) = NoteFile(name = "n.md", ref = shared, lastModified = 5L, vaultRelativePath = path)
        env.handle.bodies = { if (it == shared) "[[before]]" else null }
        env.controller.onScanPublished(PublishedNoteScan(listOf(at("a/n.md"), at("b/n.md")), NoteScanKind.COMPLETE, 1L, env.generation))
        advanceUntilIdle()

        env.handle.bodies = { if (it == shared) "[[after]]" else null }
        env.controller.markRewrittenNow(shared)
        env.controller.onScanPublished(PublishedNoteScan(listOf(at("a/n.md"), at("b/n.md")), NoteScanKind.COMPLETE, 2L, env.generation))
        advanceUntilIdle()

        assertEquals(listOf("after"), env.store.links.getValue("a/n.md").map { it.target })
        assertEquals(listOf("after"), env.store.links.getValue("b/n.md").map { it.target })
        assertTrue("両方を書き終えたら控えを外す", env.store.marks.isEmpty())
    }

    @Test
    fun `同じ参照の2つのパスを書いている途中に番号が進んだら、新しい控えを残して次の回で両方を読み直す`() = runTest {
        val env = Env(this)
        val shared = DocumentRef("ref:shared")
        fun at(path: String) = NoteFile(name = "n.md", ref = shared, lastModified = 5L, vaultRelativePath = path)
        env.handle.bodies = { if (it == shared) "[[before]]" else null }
        env.controller.onScanPublished(PublishedNoteScan(listOf(at("a/n.md"), at("b/n.md")), NoteScanKind.COMPLETE, 1L, env.generation))
        advanceUntilIdle()

        env.handle.bodies = { if (it == shared) "[[middle]]" else null }
        env.controller.markRewrittenNow(shared)
        var rewrote = false
        env.store.beforeApply = { path ->
            if (path == "b/n.md" && !rewrote) {
                rewrote = true
                env.handle.bodies = { if (it == shared) "[[after]]" else null }
                env.controller.markRewrittenNow(shared)
            }
        }
        env.controller.onScanPublished(PublishedNoteScan(listOf(at("a/n.md"), at("b/n.md")), NoteScanKind.COMPLETE, 2L, env.generation))
        advanceUntilIdle()
        assertEquals("途中で進んだ番号の控えは残る", setOf("ref:shared"), env.store.marks.keys)

        env.store.beforeApply = {}
        env.controller.onScanPublished(PublishedNoteScan(listOf(at("a/n.md"), at("b/n.md")), NoteScanKind.COMPLETE, 3L, env.generation))
        advanceUntilIdle()

        assertEquals(listOf("after"), env.store.links.getValue("a/n.md").map { it.target })
        assertEquals(listOf("after"), env.store.links.getValue("b/n.md").map { it.target })
        assertTrue(env.store.marks.isEmpty())
    }

    /**
     * 一部を読めない走査では、その回に読んだパスが同じ参照の全部のパスとは限らない。**控えを外すのは読み切れた走査のときだけ。**
     * 外すと、フォルダが読めるようになって読み切れた走査が来ても、日時が同じなので見えなかったパスを読み直さない。
     */
    @Test
    fun `一部を読めない走査で同じ参照の片方だけを書いても控えを残し、読み切れた走査で両方を書く`() = runTest {
        listOf("a/n.md" to "b/n.md", "b/n.md" to "a/n.md").forEach { (seen, unseen) ->
            val env = Env(this)
            val shared = DocumentRef("ref:shared")
            fun at(path: String) = NoteFile(name = "n.md", ref = shared, lastModified = 5L, vaultRelativePath = path)
            env.handle.bodies = { if (it == shared) "[[before]]" else null }
            env.controller.onScanPublished(PublishedNoteScan(listOf(at("a/n.md"), at("b/n.md")), NoteScanKind.COMPLETE, 1L, env.generation))
            advanceUntilIdle()

            env.handle.bodies = { if (it == shared) "[[after]]" else null }
            env.controller.markRewrittenNow(shared)
            env.controller.onScanPublished(PublishedNoteScan(listOf(at(seen)), NoteScanKind.PARTIAL, 2L, env.generation))
            advanceUntilIdle()
            assertEquals("$unseen を書くまで控えは残る", setOf("ref:shared"), env.store.marks.keys)
            assertEquals(listOf("before"), env.store.links.getValue(unseen).map { it.target })

            env.controller.onScanPublished(PublishedNoteScan(listOf(at("a/n.md"), at("b/n.md")), NoteScanKind.COMPLETE, 3L, env.generation))
            advanceUntilIdle()
            assertEquals(listOf("after"), env.store.links.getValue("a/n.md").map { it.target })
            assertEquals(listOf("after"), env.store.links.getValue("b/n.md").map { it.target })
            assertTrue(env.store.marks.isEmpty())
        }
    }

    /** 書き換えたノートがその回だけ読めなかったら、控えを外さない。外すと日時が同じなので、以後読み直さない。 */
    @Test
    fun `控えのあるノートがその回に読めなかったら控えを残し、次の回で読み直す`() = runTest {
        val env = Env(this)
        env.body("n.md", "[[before]]")
        env.publish(listOf(note("n.md", 5L)))
        advanceUntilIdle()

        env.written("n.md")
        env.handle.bodies = { null }
        env.publish(listOf(note("n.md", 5L)))
        advanceUntilIdle()
        assertEquals(setOf("ref:n.md"), env.store.marks.keys)

        env.body("n.md", "[[after]]")
        env.publish(listOf(note("n.md", 5L)))
        advanceUntilIdle()
        assertEquals(listOf("after"), env.store.links.getValue("n.md").map { it.target })
        assertTrue(env.store.marks.isEmpty())
    }

    @Test
    fun `本文を書き換えたら控えて走査を頼み、公開が無ければもう一度だけ頼む`() = runTest {
        val env = Env(this)
        env.body("n.md", "本文")
        env.publish(listOf(note("n.md", 1L)))
        advanceUntilIdle()

        env.written("n.md")
        advanceUntilIdle()

        assertEquals(setOf("ref:n.md"), env.store.marks.keys)
        assertEquals("公開されなかったので2回まで", 2, env.scanRequests)

        env.scanRequests = 0
        env.publishOnRequest = { env.publish(listOf(note("n.md", 1L))) }
        env.written("n.md")
        advanceUntilIdle()
        assertEquals("公開されたら頼み直さない", 1, env.scanRequests)
        assertTrue("日時が同じでも読み直して控えを外す", env.store.marks.isEmpty())
    }

    @Test
    fun `索引に無いノートの書き換えも参照で控え、新しいノートとして読んで控えを外す`() = runTest {
        val env = Env(this)
        env.body("old.md", "本文")
        env.publish(listOf(note("old.md", 1L)))
        advanceUntilIdle()

        env.body("new.md", "[[old]]")
        env.publishOnRequest = { env.publish(listOf(note("old.md", 1L), note("new.md", 1L))) }
        env.written("new.md")
        advanceUntilIdle()

        assertTrue(env.store.marks.isEmpty())
        assertEquals(1, env.scanRequests)
        assertEquals(listOf("old"), env.store.links.getValue("new.md").map { it.target })
    }

    /** 書き終えた知らせが Vault を替えた後に届いても、前の Vault の参照は今の Vault の索引に当たらない。 */
    @Test
    fun `前のVaultの参照の書き換えは、替えた後のVaultの索引に控えない`() = runTest {
        val env = Env(this)
        env.body("a.md", "本文")
        env.publish(listOf(note("a.md", 1L)))
        advanceUntilIdle()

        switchVault(env, VAULT_B)
        env.handle.bodies = { ref -> if (ref == DocumentRef("b-ref:a.md")) "本文" else null }
        env.controller.onScanPublished(
            PublishedNoteScan(
                listOf(NoteFile(name = "a.md", ref = DocumentRef("b-ref:a.md"), lastModified = 1L, vaultRelativePath = "a.md")),
                NoteScanKind.COMPLETE,
                publishCount = 100L,
                vaultGeneration = env.generation
            )
        )
        advanceUntilIdle()

        env.written("a.md")
        advanceUntilIdle()

        assertEquals(setOf("a.md"), env.store.notes.keys)
        assertEquals("B の a.md は読み直さない", listOf("b-ref:a.md"), env.reads().filter { it.endsWith("a.md") }.takeLast(1))
        env.handle.readBodyRefs.clear()
        env.controller.onScanPublished(
            PublishedNoteScan(
                listOf(NoteFile(name = "a.md", ref = DocumentRef("b-ref:a.md"), lastModified = 1L, vaultRelativePath = "a.md")),
                NoteScanKind.COMPLETE,
                publishCount = 101L,
                vaultGeneration = env.generation
            )
        )
        advanceUntilIdle()
        assertTrue("A の参照の控えは B のノートを読み直させない", env.handle.readBodyRefs.isEmpty())
        assertTrue("読み切れた走査に無い参照の控えは外す", env.store.marks.isEmpty())
    }

    /**
     * 書いている途中に画面のスコープが破棄されると、走査を頼む側は動かない。控えさえ永続していれば、
     * 同じ置き場で作り直した Controller が、次に Vault を開いたときに操作を足さずに読み直す。
     */
    @Test
    fun `控えだけが残ったまま作り直しても、次にVaultを開いたときに書き換え後の本文を読む`() = runTest {
        val first = Env(this)
        first.body("n.md", "[[old]]")
        first.publish(listOf(note("n.md", 5L)))
        advanceUntilIdle()
        first.controller.markRewrittenNow(refOf("n.md"))

        val second = Env(this, store = first.store)
        second.body("n.md", "[[new]]")
        second.publishOnRequest = { second.publish(listOf(note("n.md", 5L))) }
        second.controller.onVaultOpened()
        advanceUntilIdle()

        assertEquals(listOf("new"), second.store.links.getValue("n.md").map { it.target })
        assertTrue(second.store.marks.isEmpty())
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
        assertEquals(0, env.changes)
        assertNull(env.controller.latestScan)
    }

    /** 最新の公開は今の Vault のものだけ。替えた後に、前の Vault の公開を準備完了と見なさない（→ knowledge_index.md §5）。 */
    @Test
    fun `公開を受けたら最新の公開として持ち、Vaultを替えたら捨てる`() = runTest {
        val env = Env(this)
        env.publish(emptyList())
        assertEquals(1L, env.controller.latestScan?.publishCount)

        env.generation++
        env.controller.onVaultChanged()

        assertNull(env.controller.latestScan)
    }

    // ── 進み具合（→ knowledge_index.md §5） ──────────────────────────────────

    @Test
    fun `最初の作成の間は読んだ数と全体の数を出して終えたら出来上がりにし、その後の差分では出さない`() = runTest {
        val env = Env(this)
        env.handle.bodies = { "本文" }

        env.publish(listOf(note("a.md", 1L), note("b.md", 1L), note("c.md", 1L)))
        advanceUntilIdle()

        assertEquals(
            listOf(
                KnowledgeIndexProgress.Building(0, 3),
                KnowledgeIndexProgress.Building(1, 3),
                KnowledgeIndexProgress.Building(2, 3),
                KnowledgeIndexProgress.Building(3, 3),
                KnowledgeIndexProgress.Ready
            ),
            env.progress
        )

        env.progress.clear()
        env.publish(listOf(note("a.md", 2L), note("b.md", 1L), note("c.md", 1L)))
        advanceUntilIdle()

        assertEquals(listOf(KnowledgeIndexProgress.Ready), env.progress)
    }

    @Test
    fun `Vaultを開いたら、最初の作成を終えていれば出来上がり、まだなら作っている途中と出す`() = runTest {
        val env = Env(this)
        env.controller.onVaultOpened()
        advanceUntilIdle()
        assertEquals(listOf(KnowledgeIndexProgress.Building(0, 0)), env.progress)

        env.publish(emptyList())
        advanceUntilIdle()
        env.progress.clear()
        env.generation++
        env.controller.onVaultChanged()
        env.controller.onVaultOpened()
        advanceUntilIdle()

        assertEquals(listOf(KnowledgeIndexProgress.Ready), env.progress)
    }

    /**
     * 最初の作成が止まったら「作れなかった」にする。「作っています」のまま残すと、待てば出ると誤って伝える（→ knowledge_index.md §5）。
     * 次の正常な作成で通常へ戻る。
     */
    @Test
    fun `最初の作成の書き込みが失敗したら作れなかったにし、次の正常な作成で出来上がりへ戻る`() = runTest {
        val env = Env(this)
        env.handle.bodies = { "本文" }
        env.store.beforeApply = { throw java.io.IOException("置き場が壊れた") }

        env.publish(listOf(note("a.md", 1L)))
        advanceUntilIdle()
        assertEquals(KnowledgeIndexProgress.Failed, env.progress.last())

        env.store.beforeApply = {}
        env.publish(listOf(note("a.md", 1L)))
        advanceUntilIdle()
        assertEquals(KnowledgeIndexProgress.Ready, env.progress.last())
    }

    @Test
    fun `最初の作成の前に走査を頼めなかったら作れなかったにする`() = runTest {
        val env = Env(this)
        env.scanFailure = java.io.IOException("走査できない")

        env.controller.onVaultOpened()
        advanceUntilIdle()

        assertEquals(listOf(KnowledgeIndexProgress.Building(0, 0), KnowledgeIndexProgress.Failed), env.progress)
    }

    @Test
    fun `Vaultを開いたときに置き場を開けなければ作れなかったにする`() = runTest {
        val env = Env(this)
        env.store.failOpen = true

        env.controller.onVaultOpened()
        advanceUntilIdle()

        assertEquals(listOf(KnowledgeIndexProgress.Failed), env.progress)
    }

    /** 作り終えた索引は、差分の読み直しが失敗しても引ける。作れなかったにしない。 */
    @Test
    fun `最初の作成を終えた後の失敗では、作れなかったにしない`() = runTest {
        val env = Env(this)
        env.handle.bodies = { "本文" }
        env.publish(listOf(note("a.md", 1L)))
        advanceUntilIdle()
        env.progress.clear()

        env.store.beforeApply = { throw java.io.IOException("置き場が壊れた") }
        env.publish(listOf(note("a.md", 2L)))
        env.scanFailure = java.io.IOException("走査できない")
        env.controller.onNoteBodyWritten()
        advanceUntilIdle()

        assertEquals(emptyList<KnowledgeIndexProgress>(), env.progress.filter { it == KnowledgeIndexProgress.Failed })
    }

    /**
     * 同じ Vault を開いたときの初期化が遅れて戻る間に、ほかの機能の走査の公開で突き合わせが先に決まる。
     * 遅れた初期化は古い控えなので、決まった進み具合を上書きしない（走査の寿命の中では公開が来ず、上書きされたまま戻らない）。
     */
    @Test
    fun `遅れて戻った初期化は、先に決まった出来上がりと作れなかったを上書きしない`() = runTest {
        data class Case(val label: String, val applyFails: Boolean, val openFails: Boolean, val expected: KnowledgeIndexProgress)
        listOf(
            Case("先に作れなかった → 遅れた初期化（未作成）", applyFails = true, openFails = false, expected = KnowledgeIndexProgress.Failed),
            Case("先に出来上がり → 遅れた初期化（未作成）", applyFails = false, openFails = false, expected = KnowledgeIndexProgress.Ready),
            Case("先に出来上がり → 遅れた初期化の例外", applyFails = false, openFails = true, expected = KnowledgeIndexProgress.Ready)
        ).forEach { case ->
            val env = Env(this)
            env.handle.bodies = { "本文" }
            val held = CompletableDeferred<Unit>()
            env.store.holdNextOpen = held
            env.store.failHeldOpen = case.openFails
            env.controller.onVaultOpened()
            runCurrent()

            if (case.applyFails) env.store.beforeApply = { throw java.io.IOException("置き場が壊れた") }
            env.publish(listOf(note("a.md", 1L)))
            advanceUntilIdle()
            assertEquals(case.label, case.expected, env.progress.last())

            held.complete(Unit)
            advanceUntilIdle()
            assertEquals(case.label, case.expected, env.progress.last())
        }
    }

    /** 突き合わせが走っている間に走査を頼めなくても、走っている突き合わせの結果に任せる（途中で「作れなかった」を挟まない）。 */
    @Test
    fun `突き合わせの途中に走査を頼めなくても作れなかったを挟まず、突き合わせの結果に任せる`() = runTest {
        val env = Env(this)
        env.handle.bodies = { "本文" }
        val held = CompletableDeferred<Unit>()
        env.store.beforeApply = { held.await() }
        env.publish(listOf(note("a.md", 1L)))
        runCurrent()

        env.scanFailure = java.io.IOException("走査できない")
        env.controller.onNoteBodyWritten()
        runCurrent()
        held.complete(Unit)
        advanceUntilIdle()

        assertFalse(KnowledgeIndexProgress.Failed in env.progress)
        assertEquals(KnowledgeIndexProgress.Ready, env.progress.last())
    }

    /** 旧い Vault の作成が遅れて失敗しても、替えた先の Vault に「作れなかった」を出さない。 */
    @Test
    fun `Vaultを替えた後に旧いVaultの作成が失敗しても、作れなかったを書かない`() = runTest {
        val env = Env(this)
        val held = CompletableDeferred<Unit>()
        env.store.beforeApply = {
            withContext(NonCancellable) { held.await() }
            throw java.io.IOException("後着の失敗")
        }
        env.body("a.md", "[[b]]")
        env.publish(listOf(note("a.md", 1L)))
        runCurrent()

        env.generation++
        env.controller.onVaultChanged()
        env.progress.clear()
        held.complete(Unit)
        advanceUntilIdle()

        assertEquals(emptyList<KnowledgeIndexProgress>(), env.progress)
        assertEquals("旧い Vault の失敗では知らせない（公開を受けた知らせだけ）", 1, env.changes)
    }

    /**
     * 突き合わせが途中の書き込みで止まっても、止まる前に書けたノートを引けるよう知らせる。知らせないと、今のノートが索引に入るのを待つ
     * バックナンバーが、次の操作まで出ない（→ features/backlinks.md §5）。
     */
    @Test
    fun `突き合わせが途中の書き込みで止まっても、止まる前に書けたノートを引けるよう知らせる`() = runTest {
        val env = Env(this)
        env.handle.bodies = { "本文" }
        env.publish(listOf(note("b.md", 1L)))
        advanceUntilIdle()
        val before = env.changes
        env.store.beforeApply = { path ->
            if (path == "c.md") {
                while ("a.md" !in env.store.notes) yield()
                throw java.io.IOException("置き場が壊れた")
            }
        }

        env.publish(listOf(note("a.md", 1L), note("b.md", 1L), note("c.md", 1L)))
        advanceUntilIdle()

        assertTrue("止まる前に a は書けた", "a.md" in env.store.notes)
        assertEquals("公開を受けた知らせと、止まった後の知らせ", before + 2, env.changes)
    }

    /** 置き場の書き込みは取り消しで止まらないことがある。止まらずに戻っても、替えた先の Vault の進み具合を上書きしない。 */
    @Test
    fun `Vaultを替えた後に旧いVaultの読み込みが取り消しを無視して終わっても、進み具合を書かない`() = runTest {
        val env = Env(this)
        val held = CompletableDeferred<Unit>()
        env.store.beforeApply = { withContext(NonCancellable) { held.await() } }
        env.body("a.md", "[[b]]")
        env.publish(listOf(note("a.md", 1L)))
        runCurrent()

        env.generation++
        env.controller.onVaultChanged()
        env.progress.clear()
        held.complete(Unit)
        advanceUntilIdle()

        assertEquals(emptyList<KnowledgeIndexProgress>(), env.progress)
        assertEquals("直し終えた知らせも出さない", 1, env.changes)
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
        var failOpen = false

        /** 次の1回の openVault を、控えを作った後に止める。戻すのは止める前の控え（遅れて戻る初期化）。 */
        var holdNextOpen: CompletableDeferred<Unit>? = null
        var failHeldOpen = false

        override suspend fun openVault(vaultKey: String): KnowledgeIndexSnapshot {
            openCount++
            if (failOpen) throw java.io.IOException("置き場を開けない")
            holdNextOpen?.let { held ->
                holdNextOpen = null
                val stale = KnowledgeIndexSnapshot(
                    notes = if (this.vaultKey == vaultKey) notes.mapValues { IndexedNoteState(it.value.lastModified, it.value.fingerprint) } else emptyMap(),
                    writeNumbers = if (this.vaultKey == vaultKey) marks.toMap() else emptyMap(),
                    firstBuildDone = this.vaultKey == vaultKey && firstBuildDone
                )
                held.await()
                if (failHeldOpen) throw java.io.IOException("遅れた初期化の失敗")
                return stale
            }
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

        override fun markRewrittenNow(documentRef: String) {
            if (vaultKey == null) return
            marks[documentRef] = ++lastWriteNumber
        }

        override suspend fun forgetMarks(vaultKey: String, documentRefs: List<String>) {
            if (this.vaultKey != vaultKey) return
            documentRefs.forEach { marks.remove(it) }
        }

        override suspend fun applyRead(
            vaultKey: String,
            note: IndexedNoteRecord,
            links: List<IndexedLink>,
            writeNumberAtRead: Long?
        ): Boolean {
            beforeApply(note.relativePath)
            if (this.vaultKey != vaultKey) return false
            if (!mayApplyIndexedRead(writeNumberAtRead, marks[note.documentRef])) {
                rejectedApplies++
                return false
            }
            applyCount++
            notes[note.relativePath] = note
            this.links[note.relativePath] = links
            return true
        }

        override suspend fun clearMark(vaultKey: String, documentRef: String, writeNumber: Long) {
            if (this.vaultKey != vaultKey) return
            if (marks[documentRef] == writeNumber) marks.remove(documentRef)
        }

        override suspend fun deleteNotes(vaultKey: String, relativePaths: List<String>) {
            if (this.vaultKey != vaultKey) return
            relativePaths.forEach { path -> notes.remove(path); links.remove(path) }
        }

        override suspend fun markFirstBuildDone(vaultKey: String) {
            if (this.vaultKey == vaultKey) firstBuildDone = true
        }

        override suspend fun backlinkLinks(vaultKey: String, nameKey: String, relativePath: String): BacklinkLinks? = null
    }

    private companion object {
        const val VAULT_A = "content://vault/a"
        const val VAULT_B = "content://vault/b"

        fun refOf(path: String) = DocumentRef("ref:$path")

        fun note(path: String, lastModified: Long?) =
            NoteFile(name = path, ref = refOf(path), lastModified = lastModified, vaultRelativePath = path)
    }
}
