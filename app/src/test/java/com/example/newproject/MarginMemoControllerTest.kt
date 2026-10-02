package com.example.newproject

import com.example.newproject.controller.MarginMemoController
import com.example.newproject.controller.MemoDeleteOutcome
import com.example.newproject.controller.MemoLoad
import com.example.newproject.controller.MemoSaveOutcome
import com.example.newproject.domain.submissionOf
import com.example.newproject.model.MarginMemo
import com.example.newproject.model.SectionRef
import com.example.newproject.model.state.MarginMemoDraft
import com.example.newproject.model.MemoFileRead
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.NoteUiStateStore
import com.example.newproject.model.ReadingTraceLimits
import com.example.newproject.model.state.MarginMemoState
import com.example.newproject.model.state.MemoSaveStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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

    private fun ready(store: NoteUiStateStore): MarginMemoState.Ready =
        store.value.marginMemoState as MarginMemoState.Ready

    /** 読めた一覧。 */
    private fun read(vararg memos: MarginMemo) = MemoLoad(memos.toList(), MemoFileRead.Read)

    /** 書いて置く。書き込み先は [sectionTitle] の節（null は見出しより前）。 */
    private fun MarginMemoController.place(text: String, sectionTitle: String?) {
        edit(text, SectionRef(sectionTitle))
        submit(path, bodySection = null)
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
        loadMemos: suspend (String, Boolean) -> MemoLoad = { _, _ -> MemoLoad(loaded, MemoFileRead.Read) }
    ) = MarginMemoController(
        scope = this,
        state = store.marginMemoWriter,
        drafts = drafts,
        currentNoteKey = { NOTE },
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
        clock = { 5_000L }
    )
}
