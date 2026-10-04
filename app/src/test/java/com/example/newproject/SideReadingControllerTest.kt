package com.example.newproject

import com.example.newproject.controller.SideReadingController
import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteUiStateStore
import com.example.newproject.model.RelatedNote
import com.example.newproject.model.state.SideReadingState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.coroutines.CoroutineContext
import java.io.IOException

/**
 * 並べ読み（→ features/margin_pane.md §5.9）の要求の失効を固定する。
 *
 * **遅れて届いた本文を書かないことは、ジョブの取り消しが守る。** 要求の番号を持たないので、
 * 取り消しの1行を消すと、ここのどれかが前の候補の本文で落ちる。
 *
 * **取り消しに従わない読み出しも分けて注入する**（[uncancellableRead]）。本番の読み出しは同期の I/O で、
 * 取り消した後に例外で終わると、その例外が取り消しより優先して届く。協調する偽物（`CompletableDeferred`）だけでは、
 * その枝へ一度も届かない。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SideReadingControllerTest {

    private class Env(scope: TestScope) {
        val store = NoteUiStateStore()

        /** 解析を止めておける口。**読み終えて解析を待っている間に戻る**状況を作る。 */
        val parse = HeldDispatcher(StandardTestDispatcher(scope.testScheduler))
        val controller = SideReadingController(
            scope = scope,
            state = store.sideReadingWriter,
            parseDispatcher = parse
        )
        val state: SideReadingState get() = store.value.sideReading
        val headings: List<String>? get() = controller.blocks.value?.filterIsInstance<MarkdownBlock.Heading>()?.map { it.text }
    }

    @Test
    fun `開くと読み込み中を経て、読めた本文を右に出す`() = runTest {
        val env = Env(this)
        val body = CompletableDeferred<String>()

        env.controller.open(NOTE_B) { body.await() }
        runCurrent()
        assertEquals(SideReadingState.Loading(NOTE_B), env.state)
        assertNull(env.controller.blocks.value)

        body.complete("# Bの見出し\n\n本文")
        advanceUntilIdle()

        assertEquals(SideReadingState.Ready(NOTE_B), env.state)
        assertEquals(listOf("Bの見出し"), env.headings)
    }

    /** B の読み込み中に C を選び、B が後から届く。**右は C のまま。** */
    @Test
    fun `別の候補を選ぶと、前の候補の本文が後から届いても書かない`() = runTest {
        val env = Env(this)
        val bodyB = CompletableDeferred<String>()
        env.controller.open(NOTE_B) { bodyB.await() }
        runCurrent()

        env.controller.open(NOTE_C) { "# Cの見出し" }
        advanceUntilIdle()
        bodyB.complete("# Bの見出し")
        advanceUntilIdle()

        assertEquals(SideReadingState.Ready(NOTE_C), env.state)
        assertEquals(listOf("Cの見出し"), env.headings)
    }

    /** 読めた B から C を選んだ直後は、C を読み込み中で、B の本文を出さない。 */
    @Test
    fun `読めた候補から選び直すと、読み込み中の間は前の本文を出さない`() = runTest {
        val env = Env(this)
        env.controller.open(NOTE_B) { "# Bの見出し" }
        advanceUntilIdle()
        val bodyC = CompletableDeferred<String>()

        env.controller.open(NOTE_C) { bodyC.await() }
        runCurrent()

        assertEquals(SideReadingState.Loading(NOTE_C), env.state)
        assertNull("前の候補の本文が残った", env.controller.blocks.value)

        bodyC.complete("# Cの見出し")
        advanceUntilIdle()
        assertEquals(listOf("Cの見出し"), env.headings)
    }

    @Test
    fun `読み込み中に余白へ戻ると、後から届いた本文を書かない`() = runTest {
        val env = Env(this)
        val body = CompletableDeferred<String>()
        env.controller.open(NOTE_B) { body.await() }
        runCurrent()

        env.controller.close()
        body.complete("# Bの見出し")
        advanceUntilIdle()

        assertEquals(SideReadingState.Idle, env.state)
        assertNull(env.controller.blocks.value)
    }

    /** 読み終えて解析を待っている間に戻る。解析の結果は書かない。 */
    @Test
    fun `解析の途中で余白へ戻っても、解析の結果を書かない`() = runTest {
        val env = Env(this)
        env.parse.hold()
        env.controller.open(NOTE_B) { "# Bの見出し" }
        runCurrent()
        assertEquals(1, env.parse.heldCount)

        env.controller.close()
        env.parse.release()
        advanceUntilIdle()

        assertEquals(SideReadingState.Idle, env.state)
        assertNull(env.controller.blocks.value)
    }

    @Test
    fun `余白へ戻ると本文を消す`() = runTest {
        val env = Env(this)
        env.controller.open(NOTE_B) { "# Bの見出し" }
        advanceUntilIdle()

        env.controller.close()

        assertEquals(SideReadingState.Idle, env.state)
        assertNull(env.controller.blocks.value)
    }

    @Test
    fun `読めなければ右だけに失敗を出し、同じノートで開き直せる`() = runTest {
        val env = Env(this)
        env.controller.open(NOTE_B) { throw IllegalStateException("見つかりません") }
        advanceUntilIdle()

        assertEquals(SideReadingState.Failed(NOTE_B, "見つかりません"), env.state)
        assertNull(env.controller.blocks.value)

        env.controller.open(NOTE_B) { "# Bの見出し" }
        advanceUntilIdle()

        assertEquals(SideReadingState.Ready(NOTE_B), env.state)
        assertEquals(listOf("Bの見出し"), env.headings)
    }

    /**
     * **取り消しを失敗として出さない。** ノート切替で止めた読み込みを「読めませんでした」と書くと、
     * 状態の一括リセットの後に失敗が後着する。
     */
    @Test
    fun `ノート切替で止めた読み込みを失敗として書かない`() = runTest {
        val env = Env(this)
        val body = CompletableDeferred<String>()
        env.controller.open(NOTE_B) { body.await() }
        runCurrent()

        env.controller.cancelAndClear()
        advanceUntilIdle()

        // 状態を落とすのは withNoteScopedReset() の役目なので、ここでは読み込み中のまま残る。
        assertEquals(SideReadingState.Loading(NOTE_B), env.state)
        assertNull(env.controller.blocks.value)
    }

    /** 余白へ戻った後に、取り消しに従わない読み出しが**成功でも失敗でも**、右に何も書かない。 */
    @Test
    fun `余白へ戻った後に読み出しが終わっても、成功でも失敗でも書かない`() = runTest {
        LATE_ENDINGS.forEach { (label, ending) ->
            val env = Env(this)
            val finish = CompletableDeferred<() -> String>()
            env.controller.open(NOTE_B, uncancellableRead(finish))
            runCurrent()

            env.controller.close()
            finish.complete(ending)
            advanceUntilIdle()

            assertEquals("$label の後着", SideReadingState.Idle, env.state)
            assertNull("$label の後着", env.controller.blocks.value)
        }
    }

    /** B の読み込み中に C を選んで読み終えた後、B が**成功でも失敗でも**終わる。右は C のまま。 */
    @Test
    fun `選び直した後に前の候補の読み出しが終わっても、新しい候補を覆わない`() = runTest {
        LATE_ENDINGS.forEach { (label, ending) ->
            val env = Env(this)
            val finishB = CompletableDeferred<() -> String>()
            env.controller.open(NOTE_B, uncancellableRead(finishB))
            runCurrent()
            env.controller.open(NOTE_C) { "# Cの見出し" }
            advanceUntilIdle()

            finishB.complete(ending)
            advanceUntilIdle()

            assertEquals("$label の後着", SideReadingState.Ready(NOTE_C), env.state)
            assertEquals("$label の後着", listOf("Cの見出し"), env.headings)
        }
    }

    /** 頼まれるまで仕事を溜めておくディスパッチャ。溜めていない間はそのまま流す。 */
    private class HeldDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        private val held = mutableListOf<Pair<CoroutineContext, Runnable>>()
        private var holding = false
        val heldCount: Int get() = held.size

        fun hold() {
            holding = true
        }

        fun release() {
            holding = false
            val pending = held.toList()
            held.clear()
            pending.forEach { (context, block) -> delegate.dispatch(context, block) }
        }

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (holding) held += context to block else delegate.dispatch(context, block)
        }
    }

    private companion object {
        /** 後から届く読み出しの終わり方。成功と、同期の I/O が例外で終わる場合。 */
        val LATE_ENDINGS: List<Pair<String, () -> String>> = listOf(
            "成功" to { "# Bの見出し" },
            "失敗" to { throw IOException("後着の失敗") }
        )

        /**
         * 取り消しに従わない読み出し。**同期の I/O の代わり**で、取り消した後も [finish] の中身どおりに終わる
         * （本番の SAF 読み出しは `withContext(IO)` の中の同期 I/O で、取り消しでは止まらない）。
         */
        fun uncancellableRead(finish: CompletableDeferred<() -> String>): suspend () -> String =
            { withContext(NonCancellable) { finish.await()() } }

        val NOTE_B = RelatedNote(title = "B", ref = DocumentRef("content://vault/b.md"), isWikilinked = false)
        val NOTE_C = RelatedNote(title = "C", ref = DocumentRef("content://vault/c.md"), isWikilinked = true)
    }
}
