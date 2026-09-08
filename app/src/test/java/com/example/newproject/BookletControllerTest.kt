package com.example.newproject

import com.example.newproject.controller.BOOKLET_SIZE
import com.example.newproject.controller.BookletController
import com.example.newproject.model.BookletCover
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteFile
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.NoteUiStateStore
import com.example.newproject.model.BookletSeed
import com.example.newproject.model.RelatedNote
import com.example.newproject.model.state.BookletMode
import com.example.newproject.model.state.BookletState
import com.example.newproject.model.state.RelatedNotesState
import com.example.newproject.model.state.WeaveBlockedReason
import com.example.newproject.model.state.WeaveState
import com.example.newproject.model.state.visibleBundle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

        controller(state).openPlain { notes }

        val open = state.value.bookletState as BookletState.Open
        assertEquals(BOOKLET_SIZE, open.drawn.entries.size)
        assertEquals(notes.take(BOOKLET_SIZE).map { it.name }, open.drawn.entries.map { it.title })
    }

    /** **束の中では重複させない。** `random()` の10回呼びではなく並べ替えて先頭を取る。 */
    @Test
    fun `束の中に同じノートは入らない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val notes = (1..25).map { noteFile("ノート$it.md") }

        controller(state).openPlain { notes }

        val open = state.value.bookletState as BookletState.Open
        assertEquals(open.drawn.entries.size, open.drawn.entries.map { it.ref }.toSet().size)
    }

    @Test
    fun `10件より少なければある分だけで束を作る`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val notes = (1..3).map { noteFile("ノート$it.md") }

        controller(state).openPlain { notes }

        assertEquals(3, (state.value.bookletState as BookletState.Open).drawn.entries.size)
    }

    /** 0件は失敗ではない。**空の束**として画面へ渡す（variantを増やさない）。 */
    @Test
    fun `ノートが0件なら空の束になる`() = runTest {
        val state = NoteUiStateStore(NoteUiState())

        controller(state).openPlain { emptyList() }

        assertEquals(emptyList<Any>(), (state.value.bookletState as BookletState.Open).drawn.entries)
    }

    @Test
    fun `走査に失敗したら束は作れない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())

        controller(state).openPlain { throw IllegalStateException("走査失敗") }

        assertEquals("走査失敗", (state.value.bookletState as BookletState.Failed).message)
    }

    @Test
    fun `引き直すと新しい束になる`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state)

        controller.openPlain { listOf(noteFile("一冊目.md")) }
        controller.drawAgain { listOf(noteFile("二冊目.md")) }

        val open = state.value.bookletState as BookletState.Open
        assertEquals(listOf("二冊目.md"), open.drawn.entries.map { it.title })
    }

    // ── 扉（代表文）─────────────────────────────────────────────────────────

    @Test
    fun `扉は現在ページと前後1ページだけ読む`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val notes = (1..10).map { noteFile("ノート$it.md") }
        val handle = FakeVaultHandle(snippets = { "本文である。" })
        val controller = controller(state, FakeVaultBrowser(handle))

        controller.openPlain { notes }
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

        controller.openPlain { listOf(noteFile("ノート.md")) }
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

        controller.openPlain { listOf(missing, alive) }
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

        controller.openPlain { listOf(noteFile("開けないノート.md")) }
        controller.onPageSettled(page = 0)

        assertEquals(BookletCover.Failed, entry(state, 0).cover)
    }

    @Test
    fun `中身が空のノートはタイトルを扉にする`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val handle = FakeVaultHandle(snippets = { "" })
        val controller = controller(state, FakeVaultBrowser(handle))

        controller.openPlain { listOf(noteFile("空のノート.md")) }
        controller.onPageSettled(page = 0)

        assertEquals(BookletCover.Ready("空のノート.md"), entry(state, 0).cover)
    }

    /** 消えたノートに対して、めくるたびにSAFを叩き続けない。 */
    @Test
    fun `失敗した扉は読み直さない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val handle = FakeVaultHandle(snippets = { throw IllegalStateException("見つからない") })
        val controller = controller(state, FakeVaultBrowser(handle))

        controller.openPlain { listOf(noteFile("消えたノート.md")) }
        controller.onPageSettled(page = 0)
        controller.onPageSettled(page = 0)

        assertEquals(1, handle.readSnippetRefs.size)
    }

    @Test
    fun `読めている扉は読み直さない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val handle = FakeVaultHandle(snippets = { "本文である。" })
        val controller = controller(state, FakeVaultBrowser(handle))

        controller.openPlain { listOf(noteFile("ノート.md")) }
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

        controller.openPlain { listOf(noteFile("ノート.md")) }
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
        controller.openPlain { (1..10).map { noteFile("ノート$it.md") } }

        controller.onPageSettled(page = 3)

        assertEquals(3, (state.value.bookletState as BookletState.Open).drawn.page)
    }

    @Test
    fun `引き直すとページ位置は1枚目へ戻る`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state)
        controller.openPlain { (1..10).map { noteFile("ノート$it.md") } }
        controller.onPageSettled(page = 5)

        controller.drawAgain { (1..10).map { noteFile("別のノート$it.md") } }

        assertEquals(0, (state.value.bookletState as BookletState.Open).drawn.page)
    }

    /** 扉が後から届いてもページ位置は動かない（`copy` で消さない）。 */
    @Test
    fun `扉の読み込みでページ位置が消えない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val handle = FakeVaultHandle(snippets = { "本文である。" })
        val controller = controller(state, FakeVaultBrowser(handle))
        controller.openPlain { (1..10).map { noteFile("ノート$it.md") } }

        controller.onPageSettled(page = 4)

        val open = state.value.bookletState as BookletState.Open
        assertEquals(4, open.drawn.page)
        assertTrue(open.drawn.entries[4].cover is BookletCover.Ready)
    }

    // ── 世代照合 ─────────────────────────────────────────────────────────────

    /** `cancel()` だけでは足りない経路。走査から戻る**直前**に切り替わった場合。 */
    @Test
    fun `走査から戻る直前にVaultが変わったら束を作らない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        var generation = 0L

        controller(state, vaultGeneration = { generation }).openPlain {
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

        controller.openPlain { listOf(noteFile("ノート.md")) }
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
        var straddled = false
        val handle = FakeVaultHandle(
            snippets = { ref -> "${ref.value.substringAfterLast('/')}の本文である。" },
            beforeEachCall = {
                // **すれ違わせるのは1回だけ。** 引き直しは自分でも扉の読込を起こすので
                // （→ レビュー `P2-2` の修正）、毎回引き直すと際限なく往復する。
                // **本番にこの再入経路は無い** — ここは読み出しの境界へ差し込んだ再現である。
                if (!straddled) {
                    straddled = true
                    controller.drawAgain { listOf(noteFile("二冊目.md")) }
                }
            }
        )
        controller = controller(state, FakeVaultBrowser(handle))

        controller.openPlain { listOf(noteFile("一冊目.md")) }
        controller.onPageSettled(page = 0)

        val entry = entry(state, 0)
        assertEquals("二冊目.md", entry.title)
        // **一冊目の扉が二冊目の位置へ入っていない。**
        assertEquals(BookletCover.Ready("二冊目.mdの本文である。"), entry.cover)
    }

    // ── Vault切替 ────────────────────────────────────────────────────────────

    /** 状態を落とすのは `withVaultScopedReset()` の役目。**Controller は二重に落とさない。** */
    @Test
    fun `Vault切替でControllerは状態を書き換えない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state)
        controller.openPlain { listOf(noteFile("ノート.md")) }

        controller.onVaultChanged()

        assertTrue(state.value.bookletState is BookletState.Open)
    }

    @Test
    fun `Vault未選択なら扉を読みにいかない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, FakeVaultBrowser(handle = null))

        controller.openPlain { listOf(noteFile("ノート.md")) }
        controller.onPageSettled(page = 0)

        assertEquals(BookletCover.Loading, entry(state, 0).cover)
    }

    // ── 編む束（判断12）──────────────────────────────────────────────────────

    @Test
    fun `冊子をひらくと引く束と編む束が同時にできる`() = runTest {
        val state = NoteUiStateStore(NoteUiState())

        controller(state).openWith(related = success("編む1.md")) { listOf(noteFile("引く1.md")) }

        val open = state.value.bookletState as BookletState.Open
        assertEquals(listOf("引く1.md"), open.drawn.entries.map { it.title })
        assertEquals(listOf("編む1.md"), ready(state).bundle.entries.map { it.title })
    }

    /** **入口は偶然の再会のまま。** 編む束があっても、開いた直後は引く側に居る。 */
    @Test
    fun `ひらいた直後は必ず引く側`() = runTest {
        val state = NoteUiStateStore(NoteUiState())

        controller(state).openWith(related = success("編む1.md")) { listOf(noteFile("引く1.md")) }

        assertEquals(BookletMode.Draw, (state.value.bookletState as BookletState.Open).mode)
    }

    /** 2つの束が同じ世代を持つと、切り替えても「別の束が届いた」と読めない。 */
    @Test
    fun `引く束と編む束は別の世代を持つ`() = runTest {
        val state = NoteUiStateStore(NoteUiState())

        controller(state).openWith(related = success("編む1.md")) { listOf(noteFile("引く1.md")) }

        val open = state.value.bookletState as BookletState.Open
        assertNotEquals(open.drawn.bundleId, ready(state).bundle.bundleId)
    }

    @Test
    fun `切り替えると表示中の束が変わる`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state)
        controller.openWith(related = success("編む1.md")) { listOf(noteFile("引く1.md")) }

        controller.setMode(BookletMode.Weave)

        assertEquals(listOf("編む1.md"), visible(state).entries.map { it.title })
    }

    /** **「押せない」を画面だけの約束にしない。** */
    @Test
    fun `編む束が無いときは切り替わらない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state)
        controller.openWith(related = RelatedNotesState.Loading) { listOf(noteFile("引く1.md")) }

        controller.setMode(BookletMode.Weave)

        assertEquals(BookletMode.Draw, (state.value.bookletState as BookletState.Open).mode)
    }

    /**
     * **「もう10枚引く」で種を取り直さない。**
     *
     * 取り直すと、冊子の中で別のノートを読んで戻った後に**種が黙ってすり替わる**。
     * 種は束と同じ寿命で固定すると決めてある。
     */
    @Test
    fun `もう10枚引いても編む束と種は変わらない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state)
        controller.openWith(related = success("編む1.md")) { listOf(noteFile("引く1.md")) }
        val before = ready(state)

        controller.drawAgain { listOf(noteFile("引く2.md")) }

        assertEquals(listOf("引く2.md"), (state.value.bookletState as BookletState.Open).drawn.entries.map { it.title })
        assertEquals(before, ready(state))
    }

    /** 引き直しは引く束だけを新しくする。**編む束の世代まで進めない。** */
    @Test
    fun `もう10枚引いても編む束のページ位置は残る`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state)
        controller.openWith(related = success("編む1.md", "編む2.md", "編む3.md")) {
            (1..10).map { noteFile("引く$it.md") }
        }
        controller.setMode(BookletMode.Weave)
        controller.onPageSettled(page = 2)

        controller.drawAgain { (1..10).map { noteFile("別$it.md") } }

        assertEquals(2, ready(state).bundle.page)
    }

    /**
     * **ページ位置は束ごとに残る。**
     *
     * 1つを共有すると、10枚の束の7枚目から3枚の束へ切り替えたときに位置が丸められ、
     * **戻ったときに元の位置を失う。**
     */
    @Test
    fun `ページ位置は束ごとに残る`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state)
        controller.openWith(related = success("編む1.md", "編む2.md", "編む3.md")) {
            (1..10).map { noteFile("引く$it.md") }
        }
        controller.onPageSettled(page = 5)

        controller.setMode(BookletMode.Weave)
        controller.onPageSettled(page = 2)
        controller.setMode(BookletMode.Draw)

        assertEquals(5, (state.value.bookletState as BookletState.Open).drawn.page)
        assertEquals(2, ready(state).bundle.page)
    }

    @Test
    fun `冊子をひらき直すと種を取り直す`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state)
        controller.openWith(seedTitle = "一つ目", related = success("編む1.md")) { listOf(noteFile("引く1.md")) }

        controller.openWith(seedTitle = "二つ目", related = success("編む2.md")) { listOf(noteFile("引く2.md")) }

        assertEquals("二つ目", ready(state).seedTitle)
    }

    // ── 引き直しの失敗（2026-09-07 レビュー P2-1）───────────────────────────

    /**
     * **引き直しの失敗で、生きている編む束と種を巻き添えにしない。**
     *
     * `BookletState.Failed` は「冊子そのものが作れない」ための状態である。
     * 引き直しに使うと、**押してすらいない編む側が消えてトグルごと無くなる。**
     */
    @Test
    fun `引き直しに失敗しても編む束と種は残る`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state)
        controller.openWith(related = success("編む1.md")) { listOf(noteFile("引く1.md")) }
        val before = ready(state)

        controller.drawAgain { throw IllegalStateException("走査失敗") }

        assertEquals(before, ready(state))
    }

    /** 引く束も捨てない。**前の10枚がそのまま残る**ので、そこから読み進められる。 */
    @Test
    fun `引き直しに失敗しても前の引く束は残る`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state)
        controller.openWith(related = success("編む1.md")) { listOf(noteFile("引く1.md")) }

        controller.drawAgain { throw IllegalStateException("走査失敗") }

        val open = state.value.bookletState as BookletState.Open
        assertEquals(listOf("引く1.md"), open.drawn.entries.map { it.title })
    }

    /** **黙って失敗しない。** 押したのに何も起きないと、壊れたのか引けなかったのか分からない。 */
    @Test
    fun `引き直しの失敗は理由として残る`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state)
        controller.openPlain { listOf(noteFile("引く1.md")) }

        controller.drawAgain { throw IllegalStateException("走査失敗") }

        assertEquals("走査失敗", (state.value.bookletState as BookletState.Open).redrawError)
    }

    /** 再試行して成功したら理由は消える。**古い理由が残ると、また失敗したように見える。** */
    @Test
    fun `引き直しに成功すると失敗の理由は消える`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state)
        controller.openPlain { listOf(noteFile("引く1.md")) }
        controller.drawAgain { throw IllegalStateException("走査失敗") }

        controller.drawAgain { listOf(noteFile("引く2.md")) }

        assertNull((state.value.bookletState as BookletState.Open).redrawError)
    }

    // ── 引き直し後の扉（2026-09-07 レビュー P2-2）───────────────────────────

    /**
     * **ページ番号も枚数も変わらない引き直しで、新しい束の扉が読まれる。**
     *
     * `Loading` を挟まなくなったので、画面側の読込Effect（鍵は現在ページと枚数）は
     * どちらの鍵も変わらず再実行されない。位置合わせも同じ位置なら動かないので、
     * **Controller が起こさないと誰も扉を要求しない。**
     */
    @Test
    fun `引き直しの完了で新しい束の扉を読み始める`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val handle = FakeVaultHandle(snippets = { ref -> "${ref.value.substringAfterLast('/')}の本文である。" })
        val controller = controller(state, FakeVaultBrowser(handle))
        controller.openPlain { listOf(noteFile("引く1.md")) }
        controller.onPageSettled(page = 0)

        // 枚数もページ位置も引き直しの前後で同じ。**画面からは何も動いて見えない。**
        controller.drawAgain { listOf(noteFile("引く2.md")) }

        assertEquals(BookletCover.Ready("引く2.mdの本文である。"), entry(state, 0).cover)
    }

    /** 編む側を見ているあいだの引き直しでは、**見ている側の扉を読み直さない。** */
    @Test
    fun `編む側を見ているときの引き直しは編む束の扉を読み直さない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val handle = FakeVaultHandle(snippets = { "本文である。" })
        val controller = controller(state, FakeVaultBrowser(handle))
        controller.openWith(related = success("編む1.md")) { listOf(noteFile("引く1.md")) }
        controller.setMode(BookletMode.Weave)
        controller.onPageSettled(page = 0)
        val readsBefore = handle.readSnippetRefs.size

        controller.drawAgain { listOf(noteFile("引く2.md")) }

        assertEquals(readsBefore, handle.readSnippetRefs.size)
    }

    // ── 2つの束が同時に動く面（→ CLAUDE.md「共存しうる2つの処理」）─────────────

    /**
     * **引く束向けの扉を、編む束の同じ位置へ書かない。**
     *
     * 束が2つになって初めて開いた面である。読み出しが戻る前に切り替えると、
     * 位置（index）だけでは行き先が決まらない。
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `切り替えとすれ違った引く束の扉は編む束へ書かない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val handle = FakeVaultHandle(snippets = { ref -> "${ref.value.substringAfterLast('/')}の本文である。" })
        val controller = pendingController(state, handle)

        controller.openWith(related = success("編む1.md")) { listOf(noteFile("引く1.md")) }
        advanceUntilIdle()
        // 引く束の扉を読み始めるが、まだ戻ってこない。
        controller.onPageSettled(page = 0)
        controller.setMode(BookletMode.Weave)
        advanceUntilIdle()

        val open = state.value.bookletState as BookletState.Open
        assertEquals(BookletCover.Ready("引く1.mdの本文である。"), open.drawn.entries[0].cover)
        assertEquals(BookletCover.Ready("編む1.mdの本文である。"), ready(state).bundle.entries[0].cover)
    }

    /**
     * **引き直しは引く束の扉だけを止める。**
     *
     * まとめて止めると、編む側を見ている最中に「もう10枚引く」が届いただけで
     * **見ているページの扉が読み込み中へ戻る**（本人は引いてすらいない）。
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `編む束の扉は引き直しで道連れにならない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val handle = FakeVaultHandle(snippets = { "本文である。" })
        val controller = pendingController(state, handle)

        controller.openWith(related = success("編む1.md")) { listOf(noteFile("引く1.md")) }
        advanceUntilIdle()
        controller.setMode(BookletMode.Weave)
        // 編む束の扉を読み始めるが、まだ戻ってこない。
        controller.onPageSettled(page = 0)
        controller.drawAgain { listOf(noteFile("引く2.md")) }
        advanceUntilIdle()

        assertTrue(ready(state).bundle.entries[0].cover is BookletCover.Ready)
    }

    private fun entry(state: NoteUiStateStore, index: Int) =
        (state.value.bookletState as BookletState.Open).visibleBundle.entries[index]

    /** 種の無い経路で冊子をひらく（編む束は作られない）。 */
    private fun BookletController.openPlain(loadNotes: suspend () -> List<NoteFile>) =
        open(seed = null, related = RelatedNotesState.Idle, loadNotes = loadNotes)

    /** 種のある経路で冊子をひらく。 */
    private fun BookletController.openWith(
        seedTitle: String = "種",
        related: RelatedNotesState,
        loadNotes: suspend () -> List<NoteFile>
    ) = open(
        seed = BookletSeed(DocumentRef("content://fake/$seedTitle.md"), seedTitle),
        related = related,
        loadNotes = loadNotes
    )

    private fun success(vararg titles: String) = RelatedNotesState.Success(
        relatedNotes = emptyList(),
        aiNotes = titles.map { RelatedNote(title = it, ref = DocumentRef("content://fake/$it"), isWikilinked = false) }
    )

    private fun ready(state: NoteUiStateStore): WeaveState.Ready =
        (state.value.bookletState as BookletState.Open).weave as WeaveState.Ready

    private fun visible(state: NoteUiStateStore) =
        (state.value.bookletState as BookletState.Open).visibleBundle

    /**
     * 実行を溜める dispatcher を使う Controller。
     * `Unconfined` では launch が即完走してしまい、**「まだ戻ってきていない扉」を作れない。**
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun kotlinx.coroutines.test.TestScope.pendingController(
        state: NoteUiStateStore,
        handle: FakeVaultHandle
    ) = BookletController(
        scope = CoroutineScope(StandardTestDispatcher(testScheduler)),
        vault = FakeVaultBrowser(handle),
        state = state.bookletWriter,
        vaultGeneration = { 0L },
        coverDispatcher = StandardTestDispatcher(testScheduler),
        shuffle = { it }
    )

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
