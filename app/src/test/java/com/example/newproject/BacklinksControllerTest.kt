package com.example.newproject

import com.example.newproject.controller.BacklinksController
import com.example.newproject.data.BacklinkLinks
import com.example.newproject.data.IndexedNoteRecord
import com.example.newproject.data.KnowledgeIndexSnapshot
import com.example.newproject.data.KnowledgeIndexStore
import com.example.newproject.domain.BacklinkCandidate
import com.example.newproject.domain.IndexedLink
import com.example.newproject.domain.IndexedLinkKind
import com.example.newproject.domain.NoteLink
import com.example.newproject.domain.NoteLinkIndex
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteFile
import com.example.newproject.model.NoteScanKind
import com.example.newproject.model.NoteUiStateStore
import com.example.newproject.model.PublishedNoteScan
import com.example.newproject.model.state.BacklinksState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * 今のノートへのリンクを引く側（→ docs/dev/features/backlinks.md §5・§10、knowledge_index.md §5）。
 *
 * **遅れて届いた照会を書かないことは、ジョブの取り消しと書く前の生存確認が守る。** 置き場の偽物は取り消しに従わない
 * （[Env.hold]）— 本番の置き場は取り消しで止まらないことがあり、協調する偽物ではその枝へ届かない。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BacklinksControllerTest {

    private class Env(scope: TestScope) {
        val state = NoteUiStateStore()
        val store = FakeStore()
        val traces = FakePersistence()
        var latest: PublishedNoteScan? = null
        var index = NoteLinkIndex(listOf(CURRENT, OTHER, SOURCE, SOURCE_2))
        var vaultKey: String? = VAULT

        val controller = BacklinksController(
            scope = scope,
            store = store,
            state = state.backlinksWriter,
            vaultKey = { vaultKey },
            latestScan = { latest },
            linkIndex = { index },
            traces = traces,
            resolveDispatcher = StandardTestDispatcher(scope.testScheduler),
            ioDispatcher = StandardTestDispatcher(scope.testScheduler)
        )

        val backlinks: BacklinksState get() = state.value.backlinks

        fun published(kind: NoteScanKind = NoteScanKind.COMPLETE) {
            latest = PublishedNoteScan(emptyList(), kind, (latest?.publishCount ?: 0L) + 1, vaultGeneration = 0L)
        }

        /** ノート切替の本番の順: ジョブを止めてから状態を落とす。 */
        fun noteChanged() {
            controller.cancelAndClear()
            state.beginNoteLoad()
        }
    }

    /** 止めた照会。**テストが落ちても必ず終わらせる** — 取り消しに従わない照会が残ると、runTest が後始末で待ち続けて止まる。 */
    private val holds = mutableListOf<CompletableDeferred<() -> List<BacklinkCandidate>?>>()

    /** 次の照会を、取り消しに従わずに返す値どおりに終わらせる。 */
    private fun Env.hold(): CompletableDeferred<() -> List<BacklinkCandidate>?> {
        val finish = CompletableDeferred<() -> List<BacklinkCandidate>?>()
        holds += finish
        store.answer = { withContext(NonCancellable) { finish.await()() } }
        return finish
    }

    private fun heldTest(body: suspend TestScope.() -> Unit) = runTest {
        try {
            body()
        } finally {
            holds.forEach { it.complete { null } }
        }
    }

    @Test
    fun `準備完了の公開がまだ無い間は引いているのまま待ち、0件に畳まない`() = runTest {
        val env = Env(this)
        env.store.answer = { listOf(linkFrom(SOURCE)) }

        env.controller.refresh(CURRENT.ref)
        advanceUntilIdle()
        assertEquals(BacklinksState.Loading, env.backlinks)
        assertEquals("公開が無い間は置き場を引かない", emptyList<String>(), env.store.keys)

        env.published()
        env.controller.refresh(CURRENT.ref)
        advanceUntilIdle()

        assertEquals(listOf("日記.md"), env.titles())
        assertEquals("今のノートの名前の鍵で絞る", listOf("習慣"), env.store.keys)
    }

    /**
     * 索引が今のノートをまだ読んでいなければ、本文がリンクしている相手を互いのリンクとして除けない。判定済みの一覧として出さずに待ち、
     * 読み終えたら除く。読んだうえで出るリンクが無ければ、そのまま出す。
     */
    @Test
    fun `索引が今のノートをまだ読んでいなければ一覧を出さずに待ち、読み終えたら互いのリンクを除く`() = runTest {
        val env = Env(this)
        env.published()
        env.store.answer = { listOf(linkFrom(SOURCE), linkFrom(SOURCE_2)) }
        env.store.outgoing = null

        env.controller.refresh(CURRENT.ref)
        advanceUntilIdle()
        assertEquals(BacklinksState.Loading, env.backlinks)

        env.store.outgoing = listOf(NoteLink.ByName("日記", null))
        env.controller.refresh(CURRENT.ref)
        advanceUntilIdle()
        assertEquals(listOf("週報.md"), env.titles())

        env.store.outgoing = emptyList()
        env.controller.refresh(CURRENT.ref)
        advanceUntilIdle()
        assertEquals("読んだうえで出るリンクが無ければ両方出す", listOf("日記.md", "週報.md"), env.titles())
    }

    /** 互いのリンクは今のノートのパスで索引から引く（→ features/backlinks.md 判断5）。 */
    @Test
    fun `今のノートのパスで出るリンクを引いてその相手を除く`() = runTest {
        val env = Env(this)
        env.published()
        env.store.answer = { listOf(linkFrom(SOURCE), linkFrom(SOURCE_2)) }
        env.store.outgoing = listOf(NoteLink.ByName("日記", null))

        env.controller.refresh(CURRENT.ref)
        advanceUntilIdle()

        assertEquals(listOf("週報.md"), env.titles())
        assertEquals(listOf("notes/習慣.md"), env.store.fromPaths)
    }

    /**
     * 入るリンクと出るリンクは、置き場の1回の読み取りで一組に受け取る。読んだ直後に索引が互いのリンクを両方とも消しても、
     * 一覧は読んだ時点の索引のとおりで、どの時点にも無い組み合わせ（入るリンクは旧く、出るリンクは新しい）にならない。
     * 索引が直り終えたときの引き直しで新しい結果になる。
     */
    @Test
    fun `入るリンクと出るリンクを1回の読み取りで一組に受け取り、どの時点にも無い組み合わせを出さない`() = runTest {
        val env = Env(this)
        env.published()
        env.store.answer = { listOf(linkFrom(SOURCE), linkFrom(SOURCE_2)) }
        env.store.outgoing = listOf(NoteLink.ByName("日記", null))
        env.store.afterRead = {
            env.store.answer = { listOf(linkFrom(SOURCE_2)) }
            env.store.outgoing = emptyList()
        }

        env.controller.refresh(CURRENT.ref)
        advanceUntilIdle()
        assertEquals("読んだ時点の索引では日記は互いのリンク", listOf("週報.md"), env.titles())
        assertEquals("置き場は1回だけ読む", 1, env.store.keys.size)

        env.store.afterRead = {}
        env.controller.refresh(CURRENT.ref)
        advanceUntilIdle()
        assertEquals(listOf("週報.md"), env.titles())
    }

    /** 再会の入口なので、読んだ記録の無い相手を先に出す（→ features/backlinks.md 判断3）。壊れた痕跡は記録なしとして並べ、節は読めないにしない。 */
    @Test
    fun `出どころごとに痕跡を読んで最後に読んだ日の古い順に並べ、壊れた痕跡は読んだ記録が無いとして扱う`() = runTest {
        val env = Env(this)
        env.published()
        env.store.answer = { listOf(linkFrom(SOURCE), linkFrom(SOURCE_2)) }

        env.controller.refresh(CURRENT.ref)
        advanceUntilIdle()
        assertEquals("どちらも記録なし・更新日時も同じなら題名の順", listOf("日記.md", "週報.md"), env.titles())

        env.traces.put(storedTrace(count = 5, path = SOURCE.vaultRelativePath))
        env.traces.corruptPaths += SOURCE_2.vaultRelativePath
        env.controller.refresh(CURRENT.ref)
        advanceUntilIdle()
        assertEquals(listOf("週報.md", "日記.md"), env.titles())
    }

    /** 痕跡の読み込みは同期の I/O で、取り消しでは止まらない。読み終えた後に旧いノートの結果を書かない。 */
    @Test
    fun `痕跡を読んでいる途中でノートを替えたら、旧いノートの結果を書かない`() = runTest {
        val env = Env(this)
        env.published()
        env.store.answer = { listOf(linkFrom(SOURCE), linkFrom(SOURCE_2)) }
        var loads = 0
        env.traces.onLoad = {
            loads += 1
            if (loads == 1) env.noteChanged()
        }

        env.controller.refresh(CURRENT.ref)
        advanceUntilIdle()

        assertEquals("替えた後は残りの出どころの痕跡を読まない", 1, loads)
        assertEquals(BacklinksState.Idle, env.backlinks)
    }

    @Test
    fun `最新の公開が読めずに空なら読めないにし、次に読めた公開で一覧へ戻る`() = runTest {
        val env = Env(this)
        env.store.answer = { listOf(linkFrom(SOURCE)) }
        env.published(NoteScanKind.UNREADABLE_EMPTY)

        env.controller.refresh(CURRENT.ref)
        advanceUntilIdle()
        assertEquals(BacklinksState.Unavailable, env.backlinks)

        env.published(NoteScanKind.PARTIAL)
        env.controller.refresh(CURRENT.ref)
        advanceUntilIdle()
        assertEquals(listOf("日記.md"), env.titles())
    }

    @Test
    fun `置き場が使えないときと、別のVaultの索引しか無いときは読めないにする`() = runTest {
        val env = Env(this)
        env.published()

        env.store.answer = { throw IOException("置き場が壊れた") }
        env.controller.refresh(CURRENT.ref)
        advanceUntilIdle()
        assertEquals(BacklinksState.Unavailable, env.backlinks)

        env.store.answer = { null }
        env.controller.refresh(CURRENT.ref)
        advanceUntilIdle()
        assertEquals(BacklinksState.Unavailable, env.backlinks)
    }

    @Test
    fun `今のノートが走査に無ければ、置き場を引かずに0件にする`() = runTest {
        val env = Env(this)
        env.published()
        env.index = NoteLinkIndex(listOf(SOURCE))

        env.controller.refresh(CURRENT.ref)
        advanceUntilIdle()

        assertEquals(BacklinksState.Ready(emptyList()), env.backlinks)
        assertEquals(emptyList<String>(), env.store.keys)
    }

    @Test
    fun `引き直している間は前に引けた行を残す`() = heldTest {
        val env = Env(this)
        env.published()
        env.store.answer = { listOf(linkFrom(SOURCE)) }
        env.controller.refresh(CURRENT.ref)
        advanceUntilIdle()

        val finish = env.hold()
        env.controller.refresh(CURRENT.ref)
        runCurrent()
        assertEquals(listOf("日記.md"), env.titles())

        finish.complete { listOf(linkFrom(SOURCE), linkFrom(SOURCE_2)) }
        advanceUntilIdle()
        assertEquals(setOf("日記.md", "週報.md"), env.titles().toSet())
    }

    /** 前のノートの照会が、次のノートの結果より**後に**届く。成功・別の Vault・失敗のどれでも書かない。 */
    @Test
    fun `ノートを替えた後に前のノートの照会が遅れて届いても、次のノートの結果を上書きしない`() = heldTest {
        LATE_ENDINGS.forEach { (label, ending) ->
            val env = Env(this)
            env.published()
            val finish = env.hold()
            env.controller.refresh(CURRENT.ref)
            runCurrent()

            env.noteChanged()
            env.store.answer = { listOf(linkFrom(SOURCE, target = "別")) }
            env.controller.refresh(OTHER.ref)
            advanceUntilIdle()
            assertEquals(label, listOf("日記.md"), env.titles())

            finish.complete(ending)
            advanceUntilIdle()
            assertEquals(label, listOf("日記.md"), env.titles())
        }
    }

    /** 逆の順: 次のノートの照会がまだ終わっていない間に、前のノートの照会が届く。 */
    @Test
    fun `次のノートの照会の途中に前のノートの照会が届いても書かず、次のノートの結果を待つ`() = heldTest {
        LATE_ENDINGS.forEach { (label, ending) ->
            val env = Env(this)
            env.published()
            val first = env.hold()
            env.controller.refresh(CURRENT.ref)
            runCurrent()

            env.noteChanged()
            val second = env.hold()
            env.controller.refresh(OTHER.ref)
            runCurrent()

            first.complete(ending)
            advanceUntilIdle()
            assertEquals(label, BacklinksState.Loading, env.backlinks)

            second.complete { listOf(linkFrom(SOURCE, target = "別")) }
            advanceUntilIdle()
            assertEquals(label, listOf("日記.md"), env.titles())
        }
    }

    /** 索引が直った（→ knowledge_index.md §10）。前の照会は古い行を持っているので、引き直した結果だけを書く。 */
    @Test
    fun `引いている途中に索引が直ったら、前の照会を捨てて引き直した結果を書く`() = heldTest {
        LATE_ENDINGS.forEach { (label, ending) ->
            val env = Env(this)
            env.published()
            val stale = env.hold()
            env.controller.refresh(CURRENT.ref)
            runCurrent()

            env.store.answer = { listOf(linkFrom(SOURCE), linkFrom(SOURCE_2)) }
            env.controller.refresh(CURRENT.ref)
            advanceUntilIdle()
            stale.complete(ending)
            advanceUntilIdle()

            assertEquals(label, listOf("日記.md", "週報.md"), env.titles())
        }
    }

    private fun Env.titles(): List<String> = (backlinks as BacklinksState.Ready).rows.map { it.note.title }

    /** 照会の答えを差し替えられる置き場。引いた名前の鍵を記録する。ほかの口は使わない。 */
    private class FakeStore : KnowledgeIndexStore {
        val keys = mutableListOf<String>()
        var answer: suspend () -> List<BacklinkCandidate>? = { emptyList() }

        val fromPaths = mutableListOf<String>()
        var outgoing: List<NoteLink>? = emptyList()

        /** 照会のたびに呼ぶ。読んだ後に索引が書き換わる順序を作る。 */
        var afterRead: () -> Unit = {}

        override suspend fun backlinkLinks(vaultKey: String, nameKey: String, relativePath: String): BacklinkLinks? {
            keys += nameKey
            fromPaths += relativePath
            val links = answer()?.let { BacklinkLinks(it, outgoing) }
            afterRead()
            return links
        }

        override suspend fun openVault(vaultKey: String) = KnowledgeIndexSnapshot(emptyMap(), emptyMap(), firstBuildDone = true)
        override fun markRewrittenNow(documentRef: String) = Unit
        override suspend fun applyRead(vaultKey: String, note: IndexedNoteRecord, links: List<IndexedLink>, writeNumberAtRead: Long?) = true
        override suspend fun deleteNotes(vaultKey: String, relativePaths: List<String>) = Unit
        override suspend fun forgetMarks(vaultKey: String, documentRefs: List<String>) = Unit
        override suspend fun clearMark(vaultKey: String, documentRef: String, writeNumber: Long) = Unit
        override suspend fun markFirstBuildDone(vaultKey: String) = Unit
    }

    private companion object {
        const val VAULT = "content://vault"

        fun note(path: String) = NoteFile(name = path.substringAfterLast('/'), ref = DocumentRef("ref:$path"), lastModified = 1L, vaultRelativePath = path)

        val CURRENT = note("notes/習慣.md")
        val OTHER = note("notes/別.md")
        val SOURCE = note("notes/日記.md")
        val SOURCE_2 = note("notes/週報.md")

        fun linkFrom(source: NoteFile, target: String = "習慣") = BacklinkCandidate(
            sourcePath = source.vaultRelativePath,
            ordinal = 0,
            kind = IndexedLinkKind.NAME,
            target = target,
            heading = null,
            sectionHeading = null,
            sectionHeadingOccurrence = 0,
            sentence = "$target を読む。",
            sentenceLink = target.indices
        )

        /** 遅れて届く照会の終わり方。成功・別の Vault（置き場が null を返す）・失敗。 */
        val LATE_ENDINGS: List<Pair<String, () -> List<BacklinkCandidate>?>> = listOf(
            "成功" to { listOf(linkFrom(SOURCE_2)) },
            "別のVault" to { null },
            "失敗" to { throw IOException("後着の失敗") }
        )
    }
}
