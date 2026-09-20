package com.example.newproject

import com.example.newproject.controller.MarginMemoController
import com.example.newproject.controller.MemoDeleteOutcome
import com.example.newproject.controller.MemoSaveOutcome
import com.example.newproject.model.MarginMemo
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

        controller.save(path, "置いた断片", sectionTitle = "導入")
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

        controller.save(path, "預けた断片", sectionTitle = null)
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

        controller.save(path, "あふれる断片", sectionTitle = null)
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

        controller.save(path, "消えた断片", sectionTitle = null)
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

        controller.save(path, "あ".repeat(2_000), sectionTitle = null)
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

        controller.save(path, "   ", sectionTitle = null)
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

        controller.save(path, "新しいメモ", sectionTitle = null)
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
        controller.save(path, "あとから置くメモ", sectionTitle = null)
        advanceUntilIdle()
        gate.unlock()
        advanceUntilIdle()

        assertEquals("削除が取り消された", listOf(existing), deleted)
        assertEquals(listOf("あとから置くメモ"), ready(store).memos.map { it.text })
    }

    /** **置けなかった入力を画面へ返す。** シートは押した瞬間に入力欄を空にするため。 */
    @Test
    fun `置けなかった入力を書き直せるよう返す`() = runTest {
        val store = NoteUiStateStore(NoteUiState())
        val controller = controller(store, outcome = MemoSaveOutcome.Full)
        controller.open(path)
        advanceUntilIdle()

        controller.save(path, "あふれた断片", sectionTitle = null)
        advanceUntilIdle()

        assertEquals("あふれた断片", ready(store).rejectedText)
    }

    @Test
    fun `置けたときは入力を返さない`() = runTest {
        val store = NoteUiStateStore(NoteUiState())
        val controller = controller(store, outcome = MemoSaveOutcome.Saved)
        controller.open(path)
        advanceUntilIdle()

        controller.save(path, "置けた断片", sectionTitle = null)
        advanceUntilIdle()

        assertNull(ready(store).rejectedText)
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

        controller.save(path, "短いメモ", sectionTitle = "あ".repeat(171))
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

    private fun ready(store: NoteUiStateStore): MarginMemoState.Ready =
        store.value.marginMemoState as MarginMemoState.Ready

    private fun TestScope.controller(
        store: NoteUiStateStore,
        loaded: List<MarginMemo> = emptyList(),
        outcome: MemoSaveOutcome = MemoSaveOutcome.Saved,
        deleteOutcome: MemoDeleteOutcome = MemoDeleteOutcome.Deleted,
        beforeAppend: suspend () -> Unit = {},
        beforeDelete: suspend () -> Unit = {},
        onAppend: (MarginMemo) -> Unit = {},
        onDeleted: (MarginMemo) -> Unit = {}
    ) = MarginMemoController(
        scope = this,
        state = store.marginMemoWriter,
        loadMemos = { loaded },
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
