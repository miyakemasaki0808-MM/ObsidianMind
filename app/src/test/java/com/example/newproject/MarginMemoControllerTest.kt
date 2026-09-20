package com.example.newproject

import com.example.newproject.controller.MarginMemoController
import com.example.newproject.controller.MemoDeleteOutcome
import com.example.newproject.controller.MemoSaveOutcome
import com.example.newproject.model.MarginMemo
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.NoteUiStateStore
import com.example.newproject.model.state.MarginMemoState
import com.example.newproject.model.state.MemoSaveStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        deleteOutcome: MemoDeleteOutcome = MemoDeleteOutcome.Deleted
    ) = MarginMemoController(
        scope = this,
        state = store.marginMemoWriter,
        loadMemos = { loaded },
        appendMemo = { _, _ -> outcome },
        deleteMemo = { _, _ -> deleteOutcome },
        clock = { 5_000L }
    )
}
