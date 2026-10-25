package com.example.newproject

import com.example.newproject.controller.MarginMemoController
import com.example.newproject.controller.MemoDeleteOutcome
import com.example.newproject.controller.MemoLoad
import com.example.newproject.controller.MemoSaveOutcome
import com.example.newproject.domain.markdown.buildNoteSectionModel
import com.example.newproject.domain.submissionOf
import com.example.newproject.model.MarginMemo
import com.example.newproject.model.MemoPassage
import com.example.newproject.model.QuoteContext
import com.example.newproject.model.SectionRef
import com.example.newproject.model.state.DraftQuote
import com.example.newproject.model.state.MarginMemoDraft
import com.example.newproject.model.MemoFileRead
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.NoteUiStateStore
import com.example.newproject.model.ReadingTraceLimits
import com.example.newproject.model.state.MarginMemoState
import com.example.newproject.model.state.MemoSaveStatus
import com.example.newproject.model.state.PreviousVisit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

/**
 * 余白メモの画面状態。**保存そのものは [com.example.newproject.controller.ReadingTraceController] が持つ**ので、
 * ここが見るのは「結果をどう見せるか」だけ。
 *
 * **保存の結果ごとに次の行動が違う**のが要点で、Boolean へ畳むとそれが消える。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MarginMemoControllerTest {

    private val path = "ideas/habit.md"

    private companion object {
        const val NOTE = "content://vault/ideas/habit.md"
    }

    @Test
    fun `開くと保存済みのメモを新しい順で出す`() = runTest {
        val store = NoteUiStateStore(NoteUiState())
        controller(store, loaded = listOf(memoOf("古い", at = 100L), memoOf("新しい", at = 200L)))
            .open(path)
        advanceUntilIdle()

        assertEquals(
            listOf("新しい", "古い"),
            ready(store).memos.map { it.text }
        )
    }

    @Test
    fun `置けたら一覧へ足す`() = runTest {
        val store = NoteUiStateStore(NoteUiState())
        val controller = controller(store, outcome = MemoSaveOutcome.Saved)
        controller.open(path)
        advanceUntilIdle()

        controller.place("置いた断片", sectionTitle = "導入")
        advanceUntilIdle()

        assertEquals(MemoSaveStatus.Saved, ready(store).status)
        assertEquals(listOf("置いた断片"), ready(store).memos.map { it.text })
        assertEquals("導入", ready(store).memos.single().sectionTitle)
    }

    /** **預かりは「保存済み」ではない。** 離脱時の書き込みで確定する。 */
    @Test
    fun `預かったときは保存済みと呼ばない`() = runTest {
        val store = NoteUiStateStore(NoteUiState())
        val controller = controller(store, outcome = MemoSaveOutcome.Held)
        controller.open(path)
        advanceUntilIdle()

        controller.place("預けた断片", sectionTitle = null)
        advanceUntilIdle()

        assertEquals(MemoSaveStatus.Held, ready(store).status)
        // 預かっていても画面には出す（書いたのに消えたように見せない）。
        assertEquals(listOf("預けた断片"), ready(store).memos.map { it.text })
    }

    /**
     * **置けなかったものを一覧へ足さない。**
     * 足すと、画面には在るのにどこにも保存されていないメモができる。
     */
    @Test
    fun `満杯なら一覧へ足さず、置けない理由を出す`() = runTest {
        val store = NoteUiStateStore(NoteUiState())
        val controller = controller(store, outcome = MemoSaveOutcome.Full)
        controller.open(path)
        advanceUntilIdle()

        controller.place("あふれる断片", sectionTitle = null)
        advanceUntilIdle()

        assertEquals(MemoSaveStatus.Full, ready(store).status)
        assertTrue("置けなかったメモを一覧へ足した", ready(store).memos.isEmpty())
    }

    @Test
    fun `どこにも残らなかったときは未保存として見せる`() = runTest {
        val store = NoteUiStateStore(NoteUiState())
        val controller = controller(store, outcome = MemoSaveOutcome.Lost)
        controller.open(path)
        advanceUntilIdle()

        controller.place("消えた断片", sectionTitle = null)
        advanceUntilIdle()

        assertEquals(MemoSaveStatus.Failed, ready(store).status)
        assertTrue(ready(store).memos.isEmpty())
    }

    /** **切ったら必ず示す。** 保存の成否とは独立に立つ。 */
    @Test
    fun `切り詰めたことを保存の成否と別に持つ`() = runTest {
        val store = NoteUiStateStore(NoteUiState())
        val controller = controller(store, outcome = MemoSaveOutcome.Saved)
        controller.open(path)
        advanceUntilIdle()

        controller.place("あ".repeat(2_000), sectionTitle = null)
        advanceUntilIdle()

        assertEquals(MemoSaveStatus.Saved, ready(store).status)
        assertTrue("切ったのに知らせない", ready(store).wasTruncated)
    }

    @Test
    fun `空白だけの入力は置かない`() = runTest {
        val store = NoteUiStateStore(NoteUiState())
        val controller = controller(store, outcome = MemoSaveOutcome.Saved)
        controller.open(path)
        advanceUntilIdle()

        controller.place("   ", sectionTitle = null)
        advanceUntilIdle()

        assertEquals(MemoSaveStatus.None, ready(store).status)
        assertTrue(ready(store).memos.isEmpty())
    }

    @Test
    fun `消せたら一覧から消える`() = runTest {
        val store = NoteUiStateStore(NoteUiState())
        val target = memoOf("消す断片", at = 100L)
        val controller = controller(store, loaded = listOf(target))
        controller.open(path)
        advanceUntilIdle()

        controller.delete(path, target)
        advanceUntilIdle()

        assertTrue(ready(store).memos.isEmpty())
    }

    /**
     * **消せなかったら戻す。** 画面から消したままにすると、
     * 次に開いたとき復活して「消したはずのものが戻った」に見える。
     */
    @Test
    fun `消せなかったメモは一覧へ戻す`() = runTest {
        val store = NoteUiStateStore(NoteUiState())
        val target = memoOf("消せない断片", at = 100L)
        val controller = controller(
            store,
            loaded = listOf(target),
            deleteOutcome = MemoDeleteOutcome.Failed
        )
        controller.open(path)
        advanceUntilIdle()

        controller.delete(path, target)
        advanceUntilIdle()

        assertEquals(listOf("消せない断片"), ready(store).memos.map { it.text })
        assertEquals(MemoSaveStatus.Failed, ready(store).status)
    }

    /**
     * **保存中に削除しても、保存の結果が落ちない。**
     *
     * 保存と削除で1本のJobを共有していたころは、削除が保存を取り消して
     * `Saving` のまま止まった（以後この画面から保存できない）。
     */
    @Test
    fun `保存中に削除しても保存の状態が残らない`() = runTest {
        val store = NoteUiStateStore(NoteUiState())
        val existing = memoOf("既にあるメモ", at = 100L)
        val gate = Mutex(locked = true)
        val controller = controller(
            store,
            loaded = listOf(existing),
            beforeAppend = { gate.withLock { } }
        )
        controller.open(path)
        advanceUntilIdle()

        controller.place("新しいメモ", sectionTitle = null)
        advanceUntilIdle()
        assertEquals(MemoSaveStatus.Saving, ready(store).status)

        // 保存を待たせたまま削除する。
        controller.delete(path, existing)
        advanceUntilIdle()
        gate.unlock()
        advanceUntilIdle()

        assertEquals("保存が取り消されて Saving が残った", MemoSaveStatus.Saved, ready(store).status)
        assertEquals(listOf("新しいメモ"), ready(store).memos.map { it.text })
    }

    /**
     * **削除中に保存しても、削除が取り消されない。**
     *
     * 取り消されると、消えていないメモを消えたように見せたまま確定する。
     */
    @Test
    fun `削除中に保存しても削除が取り消されない`() = runTest {
        val store = NoteUiStateStore(NoteUiState())
        val existing = memoOf("消すメモ", at = 100L)
        val deleted = mutableListOf<MarginMemo>()
        val gate = Mutex(locked = true)
        val controller = controller(
            store,
            loaded = listOf(existing),
            beforeDelete = { gate.withLock { } },
            onDeleted = { deleted += it }
        )
        controller.open(path)
        advanceUntilIdle()

        controller.delete(path, existing)
        advanceUntilIdle()
        controller.place("あとから置くメモ", sectionTitle = null)
        advanceUntilIdle()
        gate.unlock()
        advanceUntilIdle()

        assertEquals("削除が取り消された", listOf(existing), deleted)
        assertEquals(listOf("あとから置くメモ"), ready(store).memos.map { it.text })
    }

    /**
     * **受け取れた回だけ入力欄を空にする**（→ features/margin_pane.md §6.2）。
     * 置けなかった回に空にすると、原文が手元から消える。
     */
    @Test
    fun `置けたら入力欄を空にし、書き込み先も放す`() = runTest {
        val store = NoteUiStateStore(NoteUiState())
        val drafts = InMemoryMarginMemoDrafts()
        val controller = controller(store, drafts = drafts, outcome = MemoSaveOutcome.Saved)
        controller.open(path)
        advanceUntilIdle()

        controller.place("置けた断片", sectionTitle = "導入")
        advanceUntilIdle()

        assertEquals(MarginMemoDraft(), drafts.draft(NOTE))
    }

    @Test
    fun `満杯や失敗では原文を残し、送信を取り下げる`() = runTest {
        listOf(MemoSaveOutcome.Full, MemoSaveOutcome.Lost).forEach { outcome ->
            val store = NoteUiStateStore(NoteUiState())
            val drafts = InMemoryMarginMemoDrafts()
            val controller = controller(store, drafts = drafts, outcome = outcome)
            controller.open(path)
            advanceUntilIdle()

            controller.place("置けない断片", sectionTitle = null)
            advanceUntilIdle()

            assertEquals("$outcome で原文を消した", "置けない断片", drafts.draft(NOTE).text)
            assertNull("$outcome で送信を追い続けた", drafts.draft(NOTE).pending)
        }
    }

    /** 預かりも「受け取れた」側。離脱時に書かれるので、入力欄は空にしてよい。 */
    @Test
    fun `預かったときも受理として入力欄を空にする`() = runTest {
        val store = NoteUiStateStore(NoteUiState())
        val drafts = InMemoryMarginMemoDrafts()
        val controller = controller(store, drafts = drafts, outcome = MemoSaveOutcome.Held)
        controller.open(path)
        advanceUntilIdle()

        controller.place("預けた断片", sectionTitle = null)
        advanceUntilIdle()

        assertTrue(drafts.draft(NOTE).isEmpty)
    }

    /** 待っている間に書き直した文字は、受理されても消さない。 */
    @Test
    fun `保存を待っている間に書き直した文字は受理されても残す`() = runTest {
        val store = NoteUiStateStore(NoteUiState())
        val drafts = InMemoryMarginMemoDrafts()
        val gate = Mutex(locked = true)
        val controller = controller(store, drafts = drafts, beforeAppend = { gate.withLock { } })
        controller.open(path)
        advanceUntilIdle()

        controller.place("1件目", sectionTitle = null)
        advanceUntilIdle()
        controller.edit("待っているあいだに書いた", bodySection = null)
        gate.unlock()
        advanceUntilIdle()

        assertEquals("待っているあいだに書いた", drafts.draft(NOTE).text)
        assertNull(drafts.draft(NOTE).pending)
    }

    // ── 引用と控えた位置（→ features/margin_quote.md §5.3）────────────────────────

    /** **送信は引用を運び、書き込み先は引用元の節になる。** 置けたら引用も書きかけから消える。 */
    @Test
    fun `引用を付けて置くと引用つきのメモを引用元の節へ書く`() = runTest {
        val store = NoteUiStateStore(NoteUiState())
        val drafts = InMemoryMarginMemoDrafts()
        var received: MarginMemo? = null
        val controller = controller(store, drafts = drafts, onAppend = { received = it })
        controller.open(path)
        advanceUntilIdle()
        val quote = DraftQuote(MemoPassage.Quoted("一節", QuoteContext("前", "後"), truncated = false))

        controller.edit("書いていた", bodySection = SectionRef("節B"))
        controller.attachQuote(quote, SectionRef("節C"))
        controller.submit(path, bodySection = SectionRef("節B"))
        advanceUntilIdle()

        assertEquals(quote.passage, received?.passage)
        assertEquals("節C", received?.sectionTitle)
        assertEquals(MarginMemoDraft(), drafts.draft(NOTE))
    }

    @Test
    fun `引用だけでは置かない`() = runTest {
        val store = NoteUiStateStore(NoteUiState())
        val appended = mutableListOf<MarginMemo>()
        val controller = controller(store, onAppend = { appended += it })
        controller.open(path)
        advanceUntilIdle()

        controller.attachQuote(DraftQuote(MemoPassage.Quoted("一節", context = null, truncated = false)), SectionRef("節C"))
        controller.submit(path, bodySection = null)
        advanceUntilIdle()

        assertTrue(appended.isEmpty())
    }

    @Test
    fun `引用の無いメモは書き始めた頃の位置を運ぶ`() = runTest {
        val store = NoteUiStateStore(NoteUiState())
        var received: MarginMemo? = null
        val controller = controller(store, onAppend = { received = it })
        controller.open(path)
        advanceUntilIdle()

        controller.edit("書", bodySection = SectionRef("節B"), seenAtStart = MemoPassage.Seen("最上端の段落"))
        controller.edit("書き足す", bodySection = SectionRef("節B"), seenAtStart = MemoPassage.Seen("別の段落"))
        controller.submit(path, bodySection = null)
        advanceUntilIdle()

        assertEquals(MemoPassage.Seen("最上端の段落"), received?.passage)
    }

    /** 保存を待つ間に引用を替えたら、受理されても新しい引用と文字は残る。 */
    @Test
    fun `保存を待っている間に替えた引用は受理されても残す`() = runTest {
        val store = NoteUiStateStore(NoteUiState())
        val drafts = InMemoryMarginMemoDrafts()
        val gate = Mutex(locked = true)
        val controller = controller(store, drafts = drafts, beforeAppend = { gate.withLock { } })
        controller.open(path)
        advanceUntilIdle()
        val replaced = DraftQuote(MemoPassage.Quoted("替えた一節", context = null, truncated = false))

        controller.attachQuote(DraftQuote(MemoPassage.Quoted("最初の一節", context = null, truncated = false)), SectionRef("節B"))
        controller.edit("添える", bodySection = SectionRef("節B"))
        controller.submit(path, bodySection = null)
        advanceUntilIdle()
        controller.attachQuote(replaced, SectionRef("節B"))
        gate.unlock()
        advanceUntilIdle()

        assertEquals(replaced, drafts.draft(NOTE).quote)
        assertEquals("添える", drafts.draft(NOTE).text)
        assertNull(drafts.draft(NOTE).pending)
    }

    /** **保存を待つ間に引用元の節だけを替えたら、前の送信の受理で書きかけを消さない**（引用の値は同じでも別の書きかけ）。 */
    @Test
    fun `保存を待っている間に引用元の節だけを替えても受理で書きかけを消さない`() = runTest {
        val store = NoteUiStateStore(NoteUiState())
        val drafts = InMemoryMarginMemoDrafts()
        val gate = Mutex(locked = true)
        val controller = controller(store, drafts = drafts, beforeAppend = { gate.withLock { } })
        controller.open(path)
        advanceUntilIdle()
        val sameQuote = DraftQuote(MemoPassage.Quoted("同じ一節", QuoteContext("", ""), truncated = false))

        controller.attachQuote(sameQuote, SectionRef("A"))
        controller.edit("添える言葉", bodySection = SectionRef("A"))
        controller.submit(path, bodySection = null)
        advanceUntilIdle()
        controller.attachQuote(sameQuote, SectionRef("B"))
        gate.unlock()
        advanceUntilIdle()

        val draft = drafts.draft(NOTE)
        assertEquals("添える言葉", draft.text)
        assertEquals(SectionRef("B"), draft.target)
        assertEquals(sameQuote, draft.quote)
        assertNull(draft.pending)
    }

    // ── 書き込み先（→ features/margin_pane.md §5.3）─────────────────────────────

    /** **書き込み先は書き始めた節で、本文を先へ進めても動かない。** 置いた時点の節へは書かない。 */
    @Test
    fun `書き始めた節へ書き、置くときの本文の節へは書かない`() = runTest {
        val store = NoteUiStateStore(NoteUiState())
        var received: MarginMemo? = null
        val controller = controller(store, onAppend = { received = it })
        controller.open(path)
        advanceUntilIdle()

        controller.edit("書", bodySection = SectionRef("節B"))
        controller.edit("書き足す", bodySection = SectionRef("節C"))
        controller.submit(path, bodySection = SectionRef("節C"))
        advanceUntilIdle()

        assertEquals("節B", received?.sectionTitle)
    }

    /** 書き始めた時点で節が分からなかったときは、置いた時点の本文の節へ書く。 */
    @Test
    fun `書き込み先が決まっていなければ置いた時点の節へ書く`() = runTest {
        val store = NoteUiStateStore(NoteUiState())
        var received: MarginMemo? = null
        val controller = controller(store, onAppend = { received = it })
        controller.open(path)
        advanceUntilIdle()

        controller.edit("節が分かる前に書いた", bodySection = null)
        controller.submit(path, bodySection = SectionRef("節C"))
        advanceUntilIdle()

        assertEquals("節C", received?.sectionTitle)
    }

    // ── 戻って読んだときの照合（→ features/margin_pane.md §6.2 の②）───────────────

    /** 送信が無ければ、置き場の全列挙（不在の確認）を頼まない。 */
    @Test
    fun `送信の無いノートを読むときは不在の確認を頼まない`() = runTest {
        val store = NoteUiStateStore(NoteUiState())
        val confirms = mutableListOf<Boolean>()
        val controller = controller(store, loadMemos = { _, confirm -> confirms += confirm; read() })

        controller.open(path)
        advanceUntilIdle()

        assertEquals(listOf(false), confirms)
    }

    /** **受理が後から分かっても、切ったことを知らせる。** 知らせないと、切られた後半が消えたことに気づけない。 */
    @Test
    fun `戻って読んだ一覧に送信があれば受理し、切ったことも知らせる`() = runTest {
        val store = NoteUiStateStore(NoteUiState())
        val drafts = InMemoryMarginMemoDrafts()
        val raw = "あ".repeat(2_000)
        val submission = submissionOf(raw, null, 7L)!!
        drafts.update(NOTE) { MarginMemoDraft(text = raw, pending = submission) }
        val confirms = mutableListOf<Boolean>()
        val controller = controller(store, drafts = drafts, loadMemos = { _, confirm ->
            confirms += confirm
            read(submission.memo)
        })

        controller.open(path)
        advanceUntilIdle()

        assertEquals("送信があるのに不在の確認を頼まなかった", listOf(true), confirms)
        assertEquals(MemoSaveStatus.Saved, ready(store).status)
        assertTrue("切ったことを知らせなかった", ready(store).wasTruncated)
        assertTrue(drafts.draft(NOTE).isEmpty)
    }

    @Test
    fun `戻って読めたのに無ければ未受理にし、原文を残す`() = runTest {
        val store = NoteUiStateStore(NoteUiState())
        val drafts = InMemoryMarginMemoDrafts()
        val submission = submissionOf("届かなかった", null, 7L)!!
        drafts.update(NOTE) { MarginMemoDraft(text = "届かなかった", pending = submission) }
        val controller = controller(store, drafts = drafts, loadMemos = { _, _ ->
            MemoLoad(emptyList(), MemoFileRead.ConfirmedAbsent)
        })

        controller.open(path)
        advanceUntilIdle()

        assertEquals(MemoSaveStatus.Failed, ready(store).status)
        assertEquals("届かなかった", drafts.draft(NOTE).text)
        assertNull(drafts.draft(NOTE).pending)
    }

    /**
     * **読めないことを未受理と読まない**（→ lessons L47）。未確定のまま持ち、入力を少し直しても
     * 新しく保存せずに元の送信を確かめる。読めるようになったら受理して、重複は0件。
     */
    @Test
    fun `戻って読めなければ未確定のまま持ち、確かめても新しく保存しない`() = runTest {
        val store = NoteUiStateStore(NoteUiState())
        val drafts = InMemoryMarginMemoDrafts()
        val raw = "読めない間のメモ"
        val submission = submissionOf(raw, null, 7L)!!
        drafts.update(NOTE) { MarginMemoDraft(text = raw, pending = submission) }
        var readable = false
        val appended = mutableListOf<MarginMemo>()
        val controller = controller(
            store,
            drafts = drafts,
            onAppend = { appended += it },
            loadMemos = { _, _ ->
                if (readable) read(submission.memo) else MemoLoad(emptyList(), MemoFileRead.Unconfirmed)
            }
        )

        controller.open(path)
        advanceUntilIdle()
        assertEquals(MemoSaveStatus.Unconfirmed, ready(store).status)
        assertEquals(submission, drafts.draft(NOTE).pending)

        // 末尾に空白を足しただけ。整えた本文は同じなので、別のメモとして送らない。
        controller.edit("$raw ", bodySection = null)
        readable = true
        controller.submit(path, bodySection = null)
        advanceUntilIdle()

        assertTrue("同じ本文を別のメモとして保存した", appended.isEmpty())
        assertEquals(MemoSaveStatus.Saved, ready(store).status)
        assertNull(drafts.draft(NOTE).pending)
    }

    // ── 書きかけの寿命（→ docs/dev/system/architecture.md 判断4の3行目）──────────

    @Test
    fun `書きかけはノート切替では預かり、Vault切替でだけ捨てる`() = runTest {
        val store = NoteUiStateStore(NoteUiState())
        val drafts = InMemoryMarginMemoDrafts()
        val controller = controller(store, drafts = drafts)

        controller.edit("預けておく", bodySection = SectionRef("節B"))
        controller.cancelAndClear()
        assertEquals(MarginMemoDraft(text = "預けておく", target = SectionRef("節B")), drafts.draft(NOTE))

        controller.clearVaultScoped()
        assertTrue(drafts.draft(NOTE).isEmpty)
    }

    /**
     * **長すぎる見出しは切ってから渡す。**
     *
     * そのまま渡すと検証で弾かれ続け、短いメモまで永久に保存できなくなる
     * （しかも再試行で直らないのに、I/O失敗と同じ顔で預かられる）。
     */
    @Test
    fun `長すぎる見出しは保存できる長さへ切ってから渡す`() = runTest {
        val store = NoteUiStateStore(NoteUiState())
        var received: MarginMemo? = null
        val controller = controller(store, onAppend = { received = it })
        controller.open(path)
        advanceUntilIdle()

        controller.place("短いメモ", sectionTitle = "あ".repeat(171))
        advanceUntilIdle()

        val title = received?.sectionTitle
        assertNotNull(title)
        assertTrue(
            "見出しが上限を超えたまま渡された",
            title!!.toByteArray(Charsets.UTF_8).size <= ReadingTraceLimits.MAX_SECTION_TITLE_BYTES
        )
    }

    /** ノート切替では**シートも閉じる** — 前のノートのメモを載せた面を残さない。 */
    @Test
    fun `切替で状態とシートの両方が落ちる`() = runTest {
        val store = NoteUiStateStore(NoteUiState())
        val controller = controller(store)
        controller.setSheetVisible(true)
        controller.open(path)
        advanceUntilIdle()

        controller.cancelAndClear()

        assertTrue(store.value.marginMemoState is MarginMemoState.Idle)
        assertFalse(store.value.isMarginMemoSheetVisible)
    }

    // ── 余白ペインからの読み込み（→ features/margin_pane.md §11 段1）──────────────

    /** ペインは再表示のたびに頼むので、**読み込み済みなら読み直さない。** */
    @Test
    fun `読み込み済みなら頼まれても読み直さない`() = runTest {
        val store = NoteUiStateStore(NoteUiState())
        val loads = mutableListOf<String>()
        val controller = controller(store, loadMemos = { p, _ -> loads += p; read() })

        controller.ensureLoaded(path)
        advanceUntilIdle()
        controller.ensureLoaded(path)
        advanceUntilIdle()

        assertEquals(listOf(path), loads)
        assertTrue(store.value.marginMemoState is MarginMemoState.Ready)
    }

    /**
     * **読み込み済みの中身を別の面へ移しても、走行中の保存の結果を捨てない。**
     *
     * [MarginMemoController.open] は世代を進めるので、保存中に呼ぶと結果が照合で捨てられ `Saving` が残る。
     */
    @Test
    fun `保存中に頼まれても保存の結果が残る`() = runTest {
        val store = NoteUiStateStore(NoteUiState())
        val gate = Mutex(locked = true)
        val controller = controller(store, beforeAppend = { gate.withLock { } })
        controller.ensureLoaded(path)
        advanceUntilIdle()

        controller.place("ペインで書いた", sectionTitle = null)
        advanceUntilIdle()
        controller.ensureLoaded(path)
        gate.unlock()
        advanceUntilIdle()

        assertEquals(MemoSaveStatus.Saved, ready(store).status)
        assertEquals(listOf("ペインで書いた"), ready(store).memos.map { it.text })
    }

    @Test
    fun `読めなかったときだけ、頼まれたら読み直す`() = runTest {
        val store = NoteUiStateStore(NoteUiState())
        var fail = true
        var loads = 0
        val controller = controller(store, loadMemos = { _, _ ->
            loads++
            if (fail) error("読めない") else read()
        })

        controller.ensureLoaded(path)
        advanceUntilIdle()
        assertTrue(store.value.marginMemoState is MarginMemoState.Error)

        fail = false
        controller.ensureLoaded(path)
        advanceUntilIdle()

        assertEquals(2, loads)
        assertTrue(store.value.marginMemoState is MarginMemoState.Ready)
    }

    /**
     * **パスが分かる前に頼まれたら、空の一覧で確定しない。**
     *
     * 確定すると、そのノートの間は一覧が空のまま「置く」が何も保存しない。
     */
    @Test
    fun `パスが分かる前に頼まれたら待ち、分かったときに読む`() = runTest {
        val store = NoteUiStateStore(NoteUiState())
        val loads = mutableListOf<String>()
        val controller = controller(store, loadMemos = { p, _ -> loads += p; read(memoOf("前に書いた", at = 1L)) })

        controller.ensureLoaded(null)
        advanceUntilIdle()
        assertTrue(store.value.marginMemoState is MarginMemoState.Idle)

        controller.onPathBound(path)
        advanceUntilIdle()

        assertEquals(listOf(path), loads)
        assertEquals(listOf("前に書いた"), ready(store).memos.map { it.text })
    }

    /** シートをパスの無いまま開いたときも、分かったら読み直す（空の一覧のまま残さない）。 */
    @Test
    fun `パスの無いまま開いたシートも、パスが分かったら読み直す`() = runTest {
        val store = NoteUiStateStore(NoteUiState())
        val controller = controller(store, loaded = listOf(memoOf("前に書いた", at = 1L)))

        controller.open(null)
        assertTrue(ready(store).memos.isEmpty())
        controller.onPathBound(path)
        advanceUntilIdle()

        assertEquals(listOf("前に書いた"), ready(store).memos.map { it.text })
    }

    @Test
    fun `待っていなければ、パスが分かっても読まない`() = runTest {
        val store = NoteUiStateStore(NoteUiState())
        val loads = mutableListOf<String>()
        val controller = controller(store, loadMemos = { p, _ -> loads += p; read() })

        controller.onPathBound(path)
        advanceUntilIdle()

        assertTrue(loads.isEmpty())
        assertTrue(store.value.marginMemoState is MarginMemoState.Idle)
    }

    /** **待ちはノート単位。** 切替の後に前のノートのパスが届いても、次のノートで読まない。 */
    @Test
    fun `ノート切替でパスの待ちを捨てる`() = runTest {
        val store = NoteUiStateStore(NoteUiState())
        val loads = mutableListOf<String>()
        val controller = controller(store, loadMemos = { p, _ -> loads += p; read() })

        controller.ensureLoaded(null)
        controller.cancelAndClear()
        controller.onPathBound(path)
        advanceUntilIdle()

        assertTrue("切替の前の待ちが残った", loads.isEmpty())
    }

    /** 1度読んだら待ちは消える。パスの確定が重ねて届いても、読み直して保存の結果を捨てない。 */
    @Test
    fun `読んだ後にパスが届いても読み直さない`() = runTest {
        val store = NoteUiStateStore(NoteUiState())
        val loads = mutableListOf<String>()
        val controller = controller(store, loadMemos = { p, _ -> loads += p; read() })

        controller.ensureLoaded(null)
        controller.onPathBound(path)
        advanceUntilIdle()
        controller.onPathBound(path)
        advanceUntilIdle()

        assertEquals(listOf(path), loads)
    }

    /** **前回の読書はメモと同じ1回の読み込みで届く**（→ features/margin_pane.md §6.3）。置いても消えない。 */
    @Test
    fun `前回の読書を一覧と一緒に持ち、メモを置いても保つ`() = runTest {
        val store = NoteUiStateStore(NoteUiState())
        val previous = PreviousVisit("本論", atEpochMillis = 1_000L, readToEnd = false)
        val controller = controller(store, loadMemos = { _, _ -> read().copy(previousVisit = previous) })

        controller.open(path)
        advanceUntilIdle()
        assertEquals(previous, ready(store).previousVisit)

        controller.place("置いたメモ", "本論")
        advanceUntilIdle()

        assertEquals(listOf("置いたメモ"), ready(store).memos.map { it.text })
        assertEquals(previous, ready(store).previousVisit)
    }

    private fun ready(store: NoteUiStateStore): MarginMemoState.Ready =
        store.value.marginMemoState as MarginMemoState.Ready

    /** 読めた一覧。 */
    private fun read(vararg memos: MarginMemo) = MemoLoad(memos.toList(), MemoFileRead.Read)

    /** 書いて置く。書き込み先は [sectionTitle] の節（null は見出しより前）。 */
    private fun MarginMemoController.place(text: String, sectionTitle: String?) {
        edit(text, SectionRef(sectionTitle))
        submit(path, bodySection = null)
    }

    // ── 選択メニューの「引用して書く」（→ features/margin_quote.md §5.2）────────────────

    private val twoSameBody = buildNoteSectionModel("## 節A\n\n同じ一節\n\n## 節B\n\n同じ一節\n\n## 節C\n\n一度だけの一節").passages

    @Test
    fun `引用すると引用元を決めて書きかけへ付け、書き込み先を引用元の節にする`() = runTest {
        val drafts = InMemoryMarginMemoDrafts()
        val controller = controller(NoteUiStateStore(NoteUiState()), drafts = drafts)

        controller.quote("同じ一節", sourceBlock = 3, hintBlock = 3, bodySection = SectionRef("節A"), passages = twoSameBody)
        // 引用元を探すのは Main の外。結果が届くまで書きかけは変えない。
        assertNull(drafts.draft(NOTE).quote)
        advanceUntilIdle()

        val draft = drafts.draft(NOTE)
        assertEquals(MemoPassage.Quoted("同じ一節", QuoteContext("", ""), truncated = false), draft.quote?.passage)
        assertEquals(SectionRef("節B"), draft.target)
    }

    @Test
    fun `引用元が決まらなければ、そのときの本文の節を書き込み先にする`() = runTest {
        val drafts = InMemoryMarginMemoDrafts()
        val controller = controller(NoteUiStateStore(NoteUiState()), drafts = drafts)

        controller.quote("本文に無い一節", sourceBlock = null, hintBlock = null, bodySection = SectionRef("節C"), passages = twoSameBody)
        advanceUntilIdle()

        assertNull(drafts.draft(NOTE).quote?.passage?.context)
        assertEquals(SectionRef("節C"), drafts.draft(NOTE).target)
    }

    @Test
    fun `空白だけの選択では何も付けない`() = runTest {
        val drafts = InMemoryMarginMemoDrafts()
        controller(NoteUiStateStore(NoteUiState()), drafts = drafts)
            .quote("  \n ", sourceBlock = 1, hintBlock = 1, bodySection = SectionRef("節A"), passages = twoSameBody)
        advanceUntilIdle()

        assertEquals(MarginMemoDraft(), drafts.draft(NOTE))
    }

    @Test
    fun `引用元を探す間にもう一度引用したら後のものだけを付ける`() = runTest {
        val drafts = InMemoryMarginMemoDrafts()
        val controller = controller(NoteUiStateStore(NoteUiState()), drafts = drafts)

        controller.quote("同じ一節", sourceBlock = 1, hintBlock = 1, bodySection = null, passages = twoSameBody)
        controller.quote("一度だけの一節", sourceBlock = 5, hintBlock = 5, bodySection = null, passages = twoSameBody)
        advanceUntilIdle()

        assertEquals("一度だけの一節", drafts.draft(NOTE).quote?.passage?.text)
        assertEquals(SectionRef("節C"), drafts.draft(NOTE).target)
    }

    /** 渡された仕事を溜め、**新しいものから**流す。後から頼んだ仕事が先に終わる順序を作る。 */
    private class NewestFirstDispatcher : CoroutineDispatcher() {
        private val held = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            held.addLast(block)
        }

        fun runNewestFirst() {
            while (held.isNotEmpty()) held.removeLast().run()
        }
    }

    /** 前の引用の仕事（本文の全体を探す）が後の引用（位置で決まる）より遅れて終わっても、前のものを付けない。 */
    @Test
    fun `前の引用の仕事が後から終わっても、後の引用だけを付ける`() = heldTest {
        val drafts = InMemoryMarginMemoDrafts()
        val work = NewestFirstDispatcher().also { heldWork += it }
        val controller = controller(NoteUiStateStore(NoteUiState()), drafts = drafts, quoteDispatcher = work)

        controller.quote("同じ一節", sourceBlock = null, hintBlock = 1, bodySection = null, passages = twoSameBody)
        controller.quote("一度だけの一節", sourceBlock = 5, hintBlock = 5, bodySection = null, passages = twoSameBody)
        advanceUntilIdle()
        work.runNewestFirst()
        advanceUntilIdle()

        assertEquals("一度だけの一節", drafts.draft(NOTE).quote?.passage?.text)
        assertEquals(SectionRef("節C"), drafts.draft(NOTE).target)
    }

    /** 書きかけは Vault 単位で預かるが、付ける仕事はノート単位。待つ間に離れたノートへ前のノートの引用を付けない。 */
    @Test
    fun `引用元を探す間にノートを替えたら、どちらのノートにも付けない`() = runTest {
        val drafts = InMemoryMarginMemoDrafts()
        var note = NOTE
        val controller = controller(NoteUiStateStore(NoteUiState()), drafts = drafts, currentNoteKey = { note })

        controller.quote("同じ一節", sourceBlock = 1, hintBlock = 1, bodySection = null, passages = twoSameBody)
        note = "content://vault/other.md"
        advanceUntilIdle()

        assertNull(drafts.draft(NOTE).quote)
        assertNull(drafts.draft(note).quote)
    }

    @Test
    fun `ノート切替とVault切替の後始末で止めた引用は付けない`() = runTest {
        listOf<(MarginMemoController) -> Unit>({ it.cancelAndClear() }, { it.clearVaultScoped() }).forEach { stop ->
            val drafts = InMemoryMarginMemoDrafts()
            val controller = controller(NoteUiStateStore(NoteUiState()), drafts = drafts)

            controller.quote("同じ一節", sourceBlock = 1, hintBlock = 1, bodySection = null, passages = twoSameBody)
            stop(controller)
            advanceUntilIdle()

            assertNull(drafts.draft(NOTE).quote)
        }
    }

    @Test
    fun `本文に2か所以上あれば選んでもらい、選んだ候補で前後と書き込み先が決まる`() = runTest {
        val drafts = InMemoryMarginMemoDrafts()
        val controller = controller(NoteUiStateStore(NoteUiState()), drafts = drafts)
        controller.quote("同じ一節", sourceBlock = null, hintBlock = 1, bodySection = SectionRef("節C"), passages = twoSameBody)
        advanceUntilIdle()
        val choosing = checkNotNull(drafts.draft(NOTE).quote)
        assertTrue(choosing.choosing)
        assertEquals(SectionRef("節C"), drafts.draft(NOTE).target)

        controller.chooseQuoteSource(choosing.passage, block = 3, passages = twoSameBody)
        advanceUntilIdle()

        assertEquals(QuoteContext("", ""), drafts.draft(NOTE).quote?.passage?.context)
        assertFalse(drafts.draft(NOTE).quote!!.choosing)
        assertEquals(SectionRef("節B"), drafts.draft(NOTE).target)
    }

    /** 両方向 — 選んだ結果を待つ間に別の引用を付けた／別の引用を待つ間に選んだ。後の操作だけが残る。 */
    @Test
    fun `選ぶのと引用を付け替えるのが交錯しても、後の操作だけが残る`() = runTest {
        val drafts = InMemoryMarginMemoDrafts()
        val controller = controller(NoteUiStateStore(NoteUiState()), drafts = drafts)
        controller.quote("同じ一節", sourceBlock = null, hintBlock = 1, bodySection = null, passages = twoSameBody)
        advanceUntilIdle()
        val choosing = checkNotNull(drafts.draft(NOTE).quote).passage

        // 選んだ結果を待つ間に別の引用を付けた → 別の引用が残り、遅れた選択では何も変えない。
        controller.chooseQuoteSource(choosing, block = 3, passages = twoSameBody)
        controller.quote("一度だけの一節", sourceBlock = 5, hintBlock = 5, bodySection = null, passages = twoSameBody)
        advanceUntilIdle()
        assertEquals("一度だけの一節", drafts.draft(NOTE).quote?.passage?.text)

        // 別の引用を待つ間に、前に出ていた候補を選んだ → 選んだ時点の書きかけの引用が別物なので何も変えない。
        controller.quote("同じ一節", sourceBlock = null, hintBlock = 1, bodySection = null, passages = twoSameBody)
        advanceUntilIdle()
        val again = checkNotNull(drafts.draft(NOTE).quote).passage
        controller.quote("一度だけの一節", sourceBlock = 5, hintBlock = 5, bodySection = null, passages = twoSameBody)
        controller.chooseQuoteSource(again, block = 1, passages = twoSameBody)
        advanceUntilIdle()
        assertEquals("同じ一節", drafts.draft(NOTE).quote?.passage?.text)
        assertEquals(QuoteContext("", ""), drafts.draft(NOTE).quote?.passage?.context)
    }

    @Test
    fun `候補を選ばずに閉じれば、引用元を決めないまま付けておく`() = runTest {
        val drafts = InMemoryMarginMemoDrafts()
        val controller = controller(NoteUiStateStore(NoteUiState()), drafts = drafts)
        controller.quote("同じ一節", sourceBlock = null, hintBlock = 1, bodySection = null, passages = twoSameBody)
        advanceUntilIdle()

        controller.dismissQuoteChoice()

        assertFalse(drafts.draft(NOTE).quote!!.choosing)
        assertNull(drafts.draft(NOTE).quote?.passage?.context)
    }

    // ── 引用の結果を待つ間の、保存の受理・✕・置く（→ features/margin_quote.md §5.3）────────────
    //
    // 保存の戻りと引用の仕事を別々に止め、届く順を作る。止めた仕事を再開してから確かめる（操作の直後の値で終えない）。

    private val abBody = buildNoteSectionModel("## A\n\n前の引用\n\n## B\n\n次の引用").passages
    private val quoteA = DraftQuote(MemoPassage.Quoted("前の引用", QuoteContext("", ""), truncated = false))

    private class Held(val controller: MarginMemoController, val drafts: InMemoryMarginMemoDrafts, val save: CompletableDeferred<Unit>, val work: NewestFirstDispatcher, val appended: MutableList<MarginMemo>)

    /** 止めた引用の仕事。試験が途中で落ちても最後に流す（→ [heldTest]）。 */
    private val heldWork = mutableListOf<NewestFirstDispatcher>()

    /**
     * 止めた仕事を、試験が途中で落ちても最後に流す。流さないと、取り消した仕事が止めたディスパッチャの中で終われず、
     * 落ちたことを報告する前に試験が止まったままになる（本番の Default では仕事は必ず流れる）。
     */
    private fun heldTest(block: suspend TestScope.() -> Unit) = runTest {
        try {
            block()
        } finally {
            heldWork.forEach { it.runNewestFirst() }
        }
    }

    private fun TestScope.held(): Held {
        val drafts = InMemoryMarginMemoDrafts()
        val save = CompletableDeferred<Unit>()
        val work = NewestFirstDispatcher().also { heldWork += it }
        val appended = mutableListOf<MarginMemo>()
        val controller = controller(
            NoteUiStateStore(NoteUiState()),
            drafts = drafts,
            beforeAppend = { save.await() },
            onAppend = { appended += it },
            quoteDispatcher = work
        )
        return Held(controller, drafts, save, work, appended)
    }

    /** 前の送信の保存を待つ間に引用Bを頼み、Bの結果より先に保存の成功が届いても、言葉とBの引用・書き込み先が残る。逆の順でも同じ。 */
    @Test
    fun `引用の結果を待つ間に前の保存の成功が届いても、言葉と後の引用が残る`() = heldTest {
        listOf(true, false).forEach { saveFirst ->
            val h = held()
            h.controller.open(path)
            advanceUntilIdle()
            h.controller.attachQuote(quoteA, SectionRef("A"))
            h.controller.edit("残したい言葉", SectionRef("A"))
            h.controller.submit(path, SectionRef("A"))
            advanceUntilIdle()

            h.controller.quote("次の引用", sourceBlock = 3, hintBlock = 3, bodySection = SectionRef("A"), passages = abBody)
            advanceUntilIdle()
            if (saveFirst) {
                h.save.complete(Unit)
                advanceUntilIdle()
                h.work.runNewestFirst()
            } else {
                h.work.runNewestFirst()
                advanceUntilIdle()
                h.save.complete(Unit)
            }
            advanceUntilIdle()

            val draft = h.drafts.draft(NOTE)
            assertEquals("保存が先=$saveFirst", "残したい言葉", draft.text)
            assertEquals("次の引用", draft.quote?.passage?.text)
            assertEquals(SectionRef("B"), draft.target)
            assertNull(draft.pending)
            assertNull(draft.quoting)
            // 保存されたのは前の引用のメモ。
            assertEquals("前の引用", (h.appended.single().passage as MemoPassage.Quoted).text)
        }
    }

    @Test
    fun `引用の結果を待つ間に外したら、届いた結果で引用を戻さず書き込み先も動かさない`() = heldTest {
        val h = held()
        h.controller.attachQuote(quoteA, SectionRef("A"))
        h.controller.edit("ひとこと", SectionRef("A"))

        h.controller.quote("次の引用", sourceBlock = 3, hintBlock = 3, bodySection = SectionRef("A"), passages = abBody)
        advanceUntilIdle()
        h.controller.detachQuote()
        h.work.runNewestFirst()
        advanceUntilIdle()

        val draft = h.drafts.draft(NOTE)
        assertNull(draft.quote)
        assertEquals(SectionRef("A"), draft.target)
        assertEquals("ひとこと", draft.text)
        assertNull(draft.quoting)
    }

    /** 届く前に置いても、前の引用のまま黙って保存しない（置けないことは画面が示す → `sendAction`）。届いた後は後の引用で置ける。 */
    @Test
    fun `引用の結果を待つ間は置かず、届いた後は後の引用で置く`() = heldTest {
        val h = held()
        h.save.complete(Unit)
        h.controller.open(path)
        advanceUntilIdle()
        h.controller.attachQuote(quoteA, SectionRef("A"))
        h.controller.edit("ひとこと", SectionRef("A"))

        h.controller.quote("次の引用", sourceBlock = 3, hintBlock = 3, bodySection = SectionRef("A"), passages = abBody)
        advanceUntilIdle()
        h.controller.submit(path, SectionRef("A"))
        advanceUntilIdle()
        assertTrue("待つ間に置いた", h.appended.isEmpty())
        assertNull(h.drafts.draft(NOTE).pending)

        h.work.runNewestFirst()
        advanceUntilIdle()
        h.controller.submit(path, SectionRef("A"))
        advanceUntilIdle()

        assertEquals("次の引用", (h.appended.single().passage as MemoPassage.Quoted).text)
        assertEquals("B", h.appended.single().sectionTitle)
    }

    @Test
    fun `付ける場で選んだ結果を待つ間に前の保存の成功が届いても、選んだ引用元と言葉が残る`() = heldTest {
        val h = held()
        h.controller.open(path)
        advanceUntilIdle()
        h.controller.quote("同じ一節", sourceBlock = null, hintBlock = 1, bodySection = SectionRef("節C"), passages = twoSameBody)
        advanceUntilIdle()
        h.work.runNewestFirst()
        advanceUntilIdle()
        val choosing = checkNotNull(h.drafts.draft(NOTE).quote).passage
        h.controller.edit("候補を選び直しても残したい", SectionRef("節C"))
        h.controller.submit(path, SectionRef("節C"))
        advanceUntilIdle()

        h.controller.chooseQuoteSource(choosing, block = 3, passages = twoSameBody)
        advanceUntilIdle()
        h.save.complete(Unit)
        advanceUntilIdle()
        h.work.runNewestFirst()
        advanceUntilIdle()

        val draft = h.drafts.draft(NOTE)
        assertEquals("候補を選び直しても残したい", draft.text)
        assertEquals(QuoteContext("", ""), draft.quote?.passage?.context)
        assertEquals(SectionRef("節B"), draft.target)
        assertNull(draft.quoting)
    }

    /** 待っている印は、結果を当てずに終わったとき（空白だけ・取り消した・始まる前に取り消した）にも残さない。 */
    @Test
    fun `引用の仕事が結果を当てずに終わっても待っている印を残さない`() = heldTest {
        val h = held()
        h.controller.quote("  ", sourceBlock = 1, hintBlock = 1, bodySection = null, passages = abBody)
        advanceUntilIdle()
        h.work.runNewestFirst()
        advanceUntilIdle()
        assertNull(h.drafts.draft(NOTE).quoting)
        assertTrue(h.drafts.draft(NOTE).isEmpty)

        h.controller.quote("次の引用", sourceBlock = 3, hintBlock = 3, bodySection = null, passages = abBody)
        assertEquals(true, h.drafts.draft(NOTE).quoting != null)
        h.controller.cancelAndClear()
        advanceUntilIdle()
        h.work.runNewestFirst()
        advanceUntilIdle()
        assertNull(h.drafts.draft(NOTE).quoting)
        assertNull(h.drafts.draft(NOTE).quote)
    }

    // ── 受理で書きかけを空にできたことを並べ読みへ伝える（→ features/margin_quote.md §5.6）────────────

    /** 送ったままの書きかけが受け取れたら1回知らせる。待つ間に書き直していた・失敗した・満杯なら知らせない。 */
    @Test
    fun `受理で書きかけを空にできたときだけ知らせる`() = runTest {
        data class Case(val label: String, val outcome: MemoSaveOutcome, val editWhileSaving: Boolean, val expected: Int)
        listOf(
            Case("そのまま受け取れた", MemoSaveOutcome.Saved, editWhileSaving = false, expected = 1),
            Case("預かった", MemoSaveOutcome.Held, editWhileSaving = false, expected = 1),
            Case("待つ間に書き直した", MemoSaveOutcome.Saved, editWhileSaving = true, expected = 0),
            Case("失敗した", MemoSaveOutcome.Lost, editWhileSaving = false, expected = 0),
            Case("満杯", MemoSaveOutcome.Full, editWhileSaving = false, expected = 0)
        ).forEach { case ->
            val drafts = InMemoryMarginMemoDrafts()
            val save = CompletableDeferred<Unit>()
            var consumed = 0
            val controller = controller(
                NoteUiStateStore(NoteUiState()),
                outcome = case.outcome,
                drafts = drafts,
                beforeAppend = { save.await() },
                onDraftConsumed = { consumed++ }
            )
            controller.open(path)
            advanceUntilIdle()
            controller.edit("送る言葉", SectionRef("A"))
            controller.submit(path, SectionRef("A"))
            advanceUntilIdle()
            if (case.editWhileSaving) controller.edit("送る言葉と書き足し", SectionRef("A"))

            save.complete(Unit)
            advanceUntilIdle()

            assertEquals(case.label, case.expected, consumed)
        }
    }

    /** 送ったノートを離れてから受け取れたら知らせない（今のノートの並べ読みではない）。 */
    @Test
    fun `別のノートへ移った後の受理では知らせない`() = runTest {
        val drafts = InMemoryMarginMemoDrafts()
        val save = CompletableDeferred<Unit>()
        var note = NOTE
        var consumed = 0
        val controller = controller(
            NoteUiStateStore(NoteUiState()),
            drafts = drafts,
            beforeAppend = { save.await() },
            currentNoteKey = { note },
            onDraftConsumed = { consumed++ }
        )
        controller.open(path)
        advanceUntilIdle()
        controller.edit("送る言葉", SectionRef("A"))
        controller.submit(path, SectionRef("A"))
        advanceUntilIdle()

        note = "content://vault/other.md"
        save.complete(Unit)
        advanceUntilIdle()

        assertEquals(0, consumed)
        assertTrue(drafts.draft(NOTE).isEmpty)
    }

    private fun TestScope.controller(
        store: NoteUiStateStore,
        loaded: List<MarginMemo> = emptyList(),
        outcome: MemoSaveOutcome = MemoSaveOutcome.Saved,
        deleteOutcome: MemoDeleteOutcome = MemoDeleteOutcome.Deleted,
        beforeAppend: suspend () -> Unit = {},
        beforeDelete: suspend () -> Unit = {},
        onAppend: (MarginMemo) -> Unit = {},
        onDeleted: (MarginMemo) -> Unit = {},
        drafts: InMemoryMarginMemoDrafts = InMemoryMarginMemoDrafts(),
        loadMemos: suspend (String, Boolean) -> MemoLoad = { _, _ -> MemoLoad(loaded, MemoFileRead.Read) },
        currentNoteKey: () -> String? = { NOTE },
        // 引用元を探す仕事も試験の時計で進める（実の Default に出すと advanceUntilIdle が待たない）。
        quoteDispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
        onDraftConsumed: () -> Unit = {}
    ) = MarginMemoController(
        scope = this,
        state = store.marginMemoWriter,
        drafts = drafts,
        currentNoteKey = currentNoteKey,
        loadMemos = loadMemos,
        appendMemo = { _, memo ->
            beforeAppend()
            onAppend(memo)
            outcome
        },
        deleteMemo = { _, memo ->
            beforeDelete()
            if (deleteOutcome == MemoDeleteOutcome.Deleted) onDeleted(memo)
            deleteOutcome
        },
        clock = { 5_000L },
        quoteDispatcher = quoteDispatcher,
        onDraftConsumed = onDraftConsumed
    )
}
