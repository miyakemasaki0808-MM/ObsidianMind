package com.example.newproject

import com.example.newproject.controller.BOOKLET_SIZE
import com.example.newproject.controller.BookletController
import com.example.newproject.model.BookletCover
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteFile
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.NoteUiStateStore
import com.example.newproject.model.state.BookletState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 冊子（10枚の束）の契約を固定する。
 *
 * **Vault単位である**こと、**本文を10枚ぶん抱えない**こと、
 * **引き直しとすれ違った結果を書かない**ことが要点（→ features/booklet_mode.md 判断4・判断6・判断7）。
 */
class BookletControllerTest {

    @Test
    fun `10枚を引く`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val notes = (1..25).map { noteFile("ノート$it.md") }

        controller(state).draw { notes }

        val open = state.value.bookletState as BookletState.Open
        assertEquals(BOOKLET_SIZE, open.entries.size)
        assertEquals(notes.take(BOOKLET_SIZE).map { it.name }, open.entries.map { it.title })
    }

    /** **束の中では重複させない。** `random()` の10回呼びではなく並べ替えて先頭を取る。 */
    @Test
    fun `束の中に同じノートは入らない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val notes = (1..25).map { noteFile("ノート$it.md") }

        controller(state).draw { notes }

        val open = state.value.bookletState as BookletState.Open
        assertEquals(open.entries.size, open.entries.map { it.ref }.toSet().size)
    }

    @Test
    fun `10件より少なければある分だけで束を作る`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val notes = (1..3).map { noteFile("ノート$it.md") }

        controller(state).draw { notes }

        assertEquals(3, (state.value.bookletState as BookletState.Open).entries.size)
    }

    /** 0件は失敗ではない。**空の束**として画面へ渡す（variantを増やさない）。 */
    @Test
    fun `ノートが0件なら空の束になる`() = runTest {
        val state = NoteUiStateStore(NoteUiState())

        controller(state).draw { emptyList() }

        assertEquals(emptyList<Any>(), (state.value.bookletState as BookletState.Open).entries)
    }

    @Test
    fun `走査に失敗したら束は作れない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())

        controller(state).draw { throw IllegalStateException("走査失敗") }

        assertEquals("走査失敗", (state.value.bookletState as BookletState.Failed).message)
    }

    @Test
    fun `引き直すと新しい束になる`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state)

        controller.draw { listOf(noteFile("一冊目.md")) }
        controller.draw { listOf(noteFile("二冊目.md")) }

        val open = state.value.bookletState as BookletState.Open
        assertEquals(listOf("二冊目.md"), open.entries.map { it.title })
    }

    // ── 扉（代表文）─────────────────────────────────────────────────────────

    @Test
    fun `扉は現在ページと前後1ページだけ読む`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val notes = (1..10).map { noteFile("ノート$it.md") }
        val handle = FakeVaultHandle(snippets = { "本文である。" })
        val controller = controller(state, FakeVaultBrowser(handle))

        controller.draw { notes }
        controller.onPageSettled(page = 4)

        assertEquals(
            listOf("ノート4.md", "ノート5.md", "ノート6.md"),
            handle.readSnippetRefs.map { ref -> notes.first { it.ref == ref }.name }.sorted()
        )
    }

    @Test
    fun `読めた扉には本文の1文が入る`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val handle = FakeVaultHandle(snippets = { "# 見出し\n最初の文である。次は出さない。" })
        val controller = controller(state, FakeVaultBrowser(handle))

        controller.draw { listOf(noteFile("ノート.md")) }
        controller.onPageSettled(page = 0)

        val cover = entry(state, 0).cover as BookletCover.Ready
        assertEquals("最初の文である。", cover.line)
    }

    /** 束を作った後に削除・改名されるとここへ来る。**そのページだけ**失敗にする。 */
    @Test
    fun `読めなかったページだけ失敗になる`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val missing = noteFile("消えたノート.md")
        val alive = noteFile("生きているノート.md")
        val handle = FakeVaultHandle(
            snippets = { ref ->
                if (ref == missing.ref) throw IllegalStateException("見つからない") else "本文である。"
            }
        )
        val controller = controller(state, FakeVaultBrowser(handle))

        controller.draw { listOf(missing, alive) }
        controller.onPageSettled(page = 0)

        assertEquals(BookletCover.Failed, entry(state, 0).cover)
        assertTrue(entry(state, 1).cover is BookletCover.Ready)
    }

    /**
     * **「開けない」と「空の本文」を分ける。** 畳むと、消えたノートのページが
     * タイトル表示のまま「読めた」ように見え、「これを読む」も押せてしまう。
     */
    @Test
    fun `ストリームを開けなかったページは失敗になる`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val handle = FakeVaultHandle(snippets = { null })
        val controller = controller(state, FakeVaultBrowser(handle))

        controller.draw { listOf(noteFile("開けないノート.md")) }
        controller.onPageSettled(page = 0)

        assertEquals(BookletCover.Failed, entry(state, 0).cover)
    }

    @Test
    fun `中身が空のノートはタイトルを扉にする`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val handle = FakeVaultHandle(snippets = { "" })
        val controller = controller(state, FakeVaultBrowser(handle))

        controller.draw { listOf(noteFile("空のノート.md")) }
        controller.onPageSettled(page = 0)

        assertEquals(BookletCover.Ready("空のノート.md"), entry(state, 0).cover)
    }

    /** 消えたノートに対して、めくるたびにSAFを叩き続けない。 */
    @Test
    fun `失敗した扉は読み直さない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val handle = FakeVaultHandle(snippets = { throw IllegalStateException("見つからない") })
        val controller = controller(state, FakeVaultBrowser(handle))

        controller.draw { listOf(noteFile("消えたノート.md")) }
        controller.onPageSettled(page = 0)
        controller.onPageSettled(page = 0)

        assertEquals(1, handle.readSnippetRefs.size)
    }

    @Test
    fun `読めている扉は読み直さない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val handle = FakeVaultHandle(snippets = { "本文である。" })
        val controller = controller(state, FakeVaultBrowser(handle))

        controller.draw { listOf(noteFile("ノート.md")) }
        controller.onPageSettled(page = 0)
        controller.onPageSettled(page = 0)

        assertEquals(1, handle.readSnippetRefs.size)
    }

    /**
     * ページャはスクロール中に何度も [BookletController.ensureCovers] を呼ぶ。
     * **1枚目の読み出しが戻る前に2回目が来ても、SAFを二度叩かない。**
     *
     * ここだけ実行を溜める dispatcher を使う — `Unconfined` では launch が即完走してしまい、
     * 「まだ読み込み中のページ」という状態を作れない。
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `読み込み中の扉を二重に読みにいかない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val handle = FakeVaultHandle(snippets = { "本文である。" })
        val controller = BookletController(
            scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
            vault = FakeVaultBrowser(handle),
            state = state.bookletWriter,
            vaultGeneration = { 0L },
            coverDispatcher = StandardTestDispatcher(testScheduler),
            shuffle = { it }
        )

        controller.draw { listOf(noteFile("ノート.md")) }
        advanceUntilIdle()
        controller.onPageSettled(page = 0)
        controller.onPageSettled(page = 0)
        advanceUntilIdle()

        assertEquals(1, handle.readSnippetRefs.size)
    }

    // ── ページ位置 ───────────────────────────────────────────────────────────

    /**
     * **ページ位置は束と同じ場所に残る。**
     *
     * 画面ローカルに置いていた実装は、実機の `冊子 → ノート → 戻る` で1枚目へ戻った
     * （2026-08-31）。束が残っているのにページ位置だけ消えるのは、
     * 「戻れば同じ10枚が同じページ位置」という1つの条件を2つの寿命で持っていたため。
     */
    @Test
    fun `決まったページを束が覚える`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state)
        controller.draw { (1..10).map { noteFile("ノート$it.md") } }

        controller.onPageSettled(page = 3)

        assertEquals(3, (state.value.bookletState as BookletState.Open).page)
    }

    @Test
    fun `引き直すとページ位置は1枚目へ戻る`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state)
        controller.draw { (1..10).map { noteFile("ノート$it.md") } }
        controller.onPageSettled(page = 5)

        controller.draw { (1..10).map { noteFile("別のノート$it.md") } }

        assertEquals(0, (state.value.bookletState as BookletState.Open).page)
    }

    /** 扉が後から届いてもページ位置は動かない（`copy` で消さない）。 */
    @Test
    fun `扉の読み込みでページ位置が消えない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val handle = FakeVaultHandle(snippets = { "本文である。" })
        val controller = controller(state, FakeVaultBrowser(handle))
        controller.draw { (1..10).map { noteFile("ノート$it.md") } }

        controller.onPageSettled(page = 4)

        val open = state.value.bookletState as BookletState.Open
        assertEquals(4, open.page)
        assertTrue(open.entries[4].cover is BookletCover.Ready)
    }

    // ── 世代照合 ─────────────────────────────────────────────────────────────

    /** `cancel()` だけでは足りない経路。走査から戻る**直前**に切り替わった場合。 */
    @Test
    fun `走査から戻る直前にVaultが変わったら束を作らない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        var generation = 0L

        controller(state, vaultGeneration = { generation }).draw {
            generation++
            listOf(noteFile("旧Vaultのノート.md"))
        }

        assertTrue(state.value.bookletState !is BookletState.Open)
    }

    @Test
    fun `扉が戻る直前にVaultが変わったら書き込まない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        var generation = 0L
        val handle = FakeVaultHandle(snippets = { "本文である。" }, beforeEachCall = { generation++ })
        val controller = controller(state, FakeVaultBrowser(handle), vaultGeneration = { generation })

        controller.draw { listOf(noteFile("ノート.md")) }
        controller.onPageSettled(page = 0)

        assertEquals(BookletCover.Loading, entry(state, 0).cover)
    }

    /**
     * 引き直しとすれ違った扉。**同じ位置に別のノートが入っている**ので書いてはいけない。
     * キャンセルでは止まらない（読み出しは既に戻ってきている）。
     */
    @Test
    fun `引き直しとすれ違った扉は新しい束へ書かない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        lateinit var controller: BookletController
        val handle = FakeVaultHandle(
            snippets = { "一冊目の本文である。" },
            beforeEachCall = { controller.draw { listOf(noteFile("二冊目.md")) } }
        )
        controller = controller(state, FakeVaultBrowser(handle))

        controller.draw { listOf(noteFile("一冊目.md")) }
        controller.onPageSettled(page = 0)

        val entry = entry(state, 0)
        assertEquals("二冊目.md", entry.title)
        assertEquals(BookletCover.Loading, entry.cover)
    }

    // ── Vault切替 ────────────────────────────────────────────────────────────

    /** 状態を落とすのは `withVaultScopedReset()` の役目。**Controller は二重に落とさない。** */
    @Test
    fun `Vault切替でControllerは状態を書き換えない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state)
        controller.draw { listOf(noteFile("ノート.md")) }

        controller.onVaultChanged()

        assertTrue(state.value.bookletState is BookletState.Open)
    }

    @Test
    fun `Vault未選択なら扉を読みにいかない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, FakeVaultBrowser(handle = null))

        controller.draw { listOf(noteFile("ノート.md")) }
        controller.onPageSettled(page = 0)

        assertEquals(BookletCover.Loading, entry(state, 0).cover)
    }

    private fun entry(state: NoteUiStateStore, index: Int) =
        (state.value.bookletState as BookletState.Open).entries[index]

    private fun controller(
        state: NoteUiStateStore,
        vault: FakeVaultBrowser = FakeVaultBrowser(FakeVaultHandle()),
        vaultGeneration: () -> Long = { 0L }
    ) = BookletController(
        scope = CoroutineScope(Dispatchers.Unconfined),
        vault = vault,
        state = state.bookletWriter,
        vaultGeneration = vaultGeneration,
        coverDispatcher = Dispatchers.Unconfined,
        // 並べ替えを固定して、枚数と重複だけを見る（本番は素の shuffled）。
        shuffle = { it }
    )

    private fun noteFile(name: String): NoteFile =
        NoteFile(name = name, ref = DocumentRef("content://fake/$name"))
}
