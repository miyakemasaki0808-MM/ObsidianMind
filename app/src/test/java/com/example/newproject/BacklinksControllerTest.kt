package com.example.newproject

import com.example.newproject.controller.BacklinksController
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
            resolveDispatcher = StandardTestDispatcher(scope.testScheduler)
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

    /** 互いのリンクは今のノートのパスで索引から引く（→ features/backlinks.md 判断5）。引けない（別の Vault）なら数えられない。 */
    @Test
    fun `今のノートのパスで出るリンクを引いてその相手を除き、引けなければ読めないにする`() = runTest {
        val env = Env(this)
        env.published()
        env.store.answer = { listOf(linkFrom(SOURCE), linkFrom(SOURCE_2)) }
        env.store.outgoing = listOf(NoteLink.ByName("日記", null))

        env.controller.refresh(CURRENT.ref)
        advanceUntilIdle()
        assertEquals(listOf("週報.md"), env.titles())
        assertEquals(listOf("notes/習慣.md"), env.store.fromPaths)

        env.store.outgoing = null
        env.controller.refresh(CURRENT.ref)
        advanceUntilIdle()
        assertEquals(BacklinksState.Unavailable, env.backlinks)
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

        override suspend fun backlinkCandidates(vaultKey: String, nameKey: String): List<BacklinkCandidate>? {
            keys += nameKey
            return answer()
        }

        val fromPaths = mutableListOf<String>()
        var outgoing: List<NoteLink>? = emptyList()

        override suspend fun linksFrom(vaultKey: String, relativePath: String): List<NoteLink>? {
            fromPaths += relativePath
            return outgoing
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
