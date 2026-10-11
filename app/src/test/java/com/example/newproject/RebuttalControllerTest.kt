package com.example.newproject

import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiTimeoutException
import com.example.newproject.controller.REBUTTAL_SOURCE_NOTE_LIMIT
import com.example.newproject.controller.RebuttalController
import com.example.newproject.controller.rebuttalSources
import com.example.newproject.fakes.FakeAiClient
import com.example.newproject.fakes.passDwell
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.NoteUiStateStore
import com.example.newproject.model.RelatedNote
import com.example.newproject.model.state.RebuttalSentence
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * 反証の一文の Controller（→ features/rebuttal_sentence.md §5・§7）。
 *
 * **見ているのは「いつ呼ぶか」と「何を書くか」だけ。** 選ばれた文が本当に反証かは測らない。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RebuttalControllerTest {

    @Test
    fun `選ばれたIDの原文を、出どころの関連ノートと組で書く`() = runTest {
        val ai = FakeAiClient.returning("X01")
        val store = NoteUiStateStore(NoteUiState())

        controller(store, ai).select(TITLE, CURRENT, relatedNotes = emptyList(), aiNotes = listOf(NOTE_A), readContent = bodies())
        advanceUntilIdle()

        assertEquals(1, ai.generateCalls)
        assertEquals(RebuttalSentence(CLAIM_A, NOTE_A), store.value.rebuttalSentence)
    }

    /** 枠には本文にある1文を出す。応答に文が添えてあっても、手元の候補から引く。 */
    @Test
    fun `応答の本文は使わず、手元の候補から引く`() = runTest {
        val ai = FakeAiClient.returning("X01 | 言い換えた別の文。")
        val store = NoteUiStateStore(NoteUiState())

        controller(store, ai).select(TITLE, CURRENT, emptyList(), listOf(NOTE_A), bodies())
        advanceUntilIdle()

        assertEquals(CLAIM_A, store.value.rebuttalSentence?.text)
    }

    /** `NONE` は空振りで、空・候補外・散文は約束違反。**どちらも何も出さない。** */
    @Test
    fun `NONE・空・候補外・散文では何も書かない`() = runTest {
        listOf("NONE", "- none.", "", "X99", "C01", "The first sentence conflicts.").forEach { response ->
            val ai = FakeAiClient.returning(response)
            val store = NoteUiStateStore(NoteUiState())

            controller(store, ai).select(TITLE, CURRENT, emptyList(), listOf(NOTE_A), bodies())
            advanceUntilIdle()

            assertEquals(response, 1, ai.generateCalls)
            assertNull(response, store.value.rebuttalSentence)
        }
    }

    @Test
    fun `候補の文が1つも無ければ生成しない`() = runTest {
        val ai = FakeAiClient.returning("X01")
        val store = NoteUiStateStore(NoteUiState())

        controller(store, ai).select(
            TITLE, CURRENT, emptyList(), listOf(NOTE_A),
            readContent = { "# 見出しだけのノート\n\nこれで本当によいのだろうか。" }
        )
        advanceUntilIdle()

        assertEquals(0, ai.generateCalls)
        assertNull(store.value.rebuttalSentence)
    }

    @Test
    fun `関連ノートが無ければ本文も読まず可用性も確かめない`() = runTest {
        val ai = FakeAiClient.returning("X01").apply { availabilityFailure = { error("呼ばれてはいけない") } }
        var reads = 0

        controller(NoteUiStateStore(NoteUiState()), ai).select(TITLE, CURRENT, emptyList(), emptyList()) { reads++; "" }
        advanceUntilIdle()

        assertEquals(0, reads)
        assertEquals(0, ai.generateCalls)
    }

    /** **モデルDLは始めない。** 未取得・DL中は黙って諦め、DL完了で関連ノートが呼び戻されたときに試し直す。 */
    @Test
    fun `Ready以外では本文も読まず、生成もDLもしない`() = runTest {
        listOf(
            AiAvailability.NeedsDownload,
            AiAvailability.Downloading,
            AiAvailability.Unsupported,
            AiAvailability.TemporarilyUnavailable(IllegalStateException("busy"))
        ).forEach { availability ->
            val ai = FakeAiClient.returning("X01", availability)
            val store = NoteUiStateStore(NoteUiState())
            var reads = 0

            controller(store, ai).select(TITLE, CURRENT, emptyList(), listOf(NOTE_A)) { reads++; CLAIM_A }
            advanceUntilIdle()

            assertEquals("$availability", 0, reads)
            assertEquals("$availability", 0, ai.generateCalls)
            assertEquals("$availability", 0, ai.downloadCalls)
            assertNull("$availability", store.value.rebuttalSentence)
        }
    }

    @Test
    fun `可用性の確認が落ちても何も書かない`() = runTest {
        val ai = FakeAiClient.returning("X01").apply { availabilityFailure = { IllegalStateException("AICore") } }
        val store = NoteUiStateStore(NoteUiState())

        controller(store, ai).select(TITLE, CURRENT, emptyList(), listOf(NOTE_A), bodies())
        advanceUntilIdle()

        assertEquals(0, ai.generateCalls)
        assertNull(store.value.rebuttalSentence)
    }

    @Test
    fun `読めなかった関連ノートだけ落として続ける`() = runTest {
        val ai = FakeAiClient.returning("X01")
        val store = NoteUiStateStore(NoteUiState())

        controller(store, ai).select(TITLE, CURRENT, emptyList(), listOf(NOTE_A, NOTE_B)) { ref ->
            if (ref == NOTE_A.ref) throw java.io.IOException("読めない") else CLAIM_B
        }
        advanceUntilIdle()

        assertEquals(RebuttalSentence(CLAIM_B, NOTE_B), store.value.rebuttalSentence)
    }

    /** 自動で走る機能なので、生成の失敗は画面に出さない。 */
    @Test
    fun `生成が失敗したら何も書かない`() = runTest {
        val ai = FakeAiClient.failingGeneration { AiTimeoutException("60秒") }
        val store = NoteUiStateStore(NoteUiState())

        controller(store, ai).select(TITLE, CURRENT, emptyList(), listOf(NOTE_A), bodies())
        advanceUntilIdle()

        assertEquals(1, ai.generateCalls)
        assertNull(store.value.rebuttalSentence)
    }

    @Test
    fun `ノートに留まるまで生成しない`() = runTest {
        val ai = FakeAiClient.returning("X01")
        val store = NoteUiStateStore(NoteUiState())
        val dwell = CompletableDeferred<Unit>()

        controller(store, ai, awaitDwell = { dwell.await() }).select(TITLE, CURRENT, emptyList(), listOf(NOTE_A), bodies())
        advanceUntilIdle()
        assertEquals(0, ai.generateCalls)

        dwell.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, ai.generateCalls)
        assertEquals(CLAIM_A, store.value.rebuttalSentence?.text)
    }

    @Test
    fun `生成中にノートを離れると、後から返っても書かない`() = runTest {
        val ai = FakeAiClient.deferred()
        val store = NoteUiStateStore(NoteUiState())
        val controller = controller(store, ai)

        controller.select(TITLE, CURRENT, emptyList(), listOf(NOTE_A), bodies())
        advanceUntilIdle()
        assertEquals(1, ai.generateCalls)

        controller.cancelAndClear()
        ai.completeAll("X01")
        advanceUntilIdle()

        assertNull(store.value.rebuttalSentence)
    }

    /**
     * **取り消しに従わない生成が遅れて届いても書かない。** `AiClient` は他実装を許す契約なので、
     * 取り消しだけでは止まらない生成がありうる。照合（requestId）を消すとここが落ちる。
     */
    @Test
    fun `取り消しに従わない生成の後着は、ノートを離れた後に書かない`() = runTest {
        val waiting = mutableListOf<Continuation<String>>()
        val ai = FakeAiClient { suspendCoroutine { waiting += it } }
        val store = NoteUiStateStore(NoteUiState())
        val controller = controller(store, ai)

        controller.select(TITLE, CURRENT, emptyList(), listOf(NOTE_A), bodies())
        advanceUntilIdle()
        controller.cancelAndClear()
        waiting.single().resume("X01")
        advanceUntilIdle()

        assertNull(store.value.rebuttalSentence)
    }

    /**
     * 同じノートで選び直す（モデルDLの完了で関連ノートが呼び戻される）。**どちらの順で返っても、新しい要求だけが書く。**
     * 判定は全部の生成を再開してから行う。途中で落ちると、取り消しに従わない生成が残ってテストが終われない。
     */
    @Test
    fun `選び直した後に前の要求が返っても、新しい要求の結果だけを書く`() = runTest {
        val waiting = mutableListOf<Continuation<String>>()
        val ai = FakeAiClient { suspendCoroutine { waiting += it } }
        val store = NoteUiStateStore(NoteUiState())
        val controller = controller(store, ai)

        controller.select(TITLE, CURRENT, emptyList(), listOf(NOTE_A), bodies())
        advanceUntilIdle()
        controller.select(TITLE, CURRENT, emptyList(), listOf(NOTE_B), bodies())
        advanceUntilIdle()
        assertEquals(2, waiting.size)

        waiting[0].resume("X01")
        advanceUntilIdle()
        val afterLate = store.value.rebuttalSentence
        waiting[1].resume("X01")
        advanceUntilIdle()

        assertNull("前の要求の結果を書いた", afterLate)
        assertEquals(RebuttalSentence(CLAIM_B, NOTE_B), store.value.rebuttalSentence)
    }

    @Test
    fun `ノートを離れると出していた一文を消す`() = runTest {
        val store = NoteUiStateStore(NoteUiState())
        val controller = controller(store, FakeAiClient.returning("X01"))
        controller.select(TITLE, CURRENT, emptyList(), listOf(NOTE_A), bodies())
        advanceUntilIdle()
        assertTrue(store.value.rebuttalSentence != null)

        controller.cancelAndClear()

        assertNull(store.value.rebuttalSentence)
    }

    /** 抜粋と列挙は本文に比例するので、Main の外へ逃がす（→ lessons L13）。 */
    @Test
    fun `抜粋と候補の列挙は渡された口で走る`() = runTest {
        val counting = CountingDispatcher(StandardTestDispatcher(testScheduler))
        val store = NoteUiStateStore(NoteUiState())

        controller(store, FakeAiClient.returning("X01"), excerptDispatcher = counting)
            .select(TITLE, CURRENT, emptyList(), listOf(NOTE_A), bodies())
        advanceUntilIdle()

        assertTrue("抜粋と列挙が口を通っていない", counting.dispatches >= 2)
        assertEquals(CLAIM_A, store.value.rebuttalSentence?.text)
    }

    // --- 候補を取る関連ノート -------------------------------------------------

    @Test
    fun `候補を取るのは関連タブの並びで、重複を畳み上限で切る`() {
        val linked = note("linked", wikilinked = true)
        val unlinked = note("unlinked")
        val ai = note("ai")

        assertEquals(
            listOf(ai, unlinked, linked),
            rebuttalSources(relatedNotes = listOf(linked, unlinked), aiNotes = listOf(ai, unlinked))
        )
        val many = (1..10).map { note("n$it") }
        assertEquals(many.take(REBUTTAL_SOURCE_NOTE_LIMIT), rebuttalSources(relatedNotes = many, aiNotes = emptyList()))
    }

    // --- ヘルパー -----------------------------------------------------------

    private fun TestScope.controller(
        store: NoteUiStateStore,
        ai: FakeAiClient,
        awaitDwell: suspend () -> Unit = passDwell,
        excerptDispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler)
    ) = RebuttalController(
        scope = this,
        aiClient = ai,
        awaitDwell = awaitDwell,
        state = store.rebuttalWriter,
        excerptDispatcher = excerptDispatcher
    )

    private fun bodies(): suspend (DocumentRef) -> String = { ref ->
        when (ref) {
            NOTE_A.ref -> CLAIM_A
            NOTE_B.ref -> CLAIM_B
            else -> ""
        }
    }

    private fun note(name: String, wikilinked: Boolean = false) =
        RelatedNote(title = name, ref = DocumentRef("content://vault/$name.md"), isWikilinked = wikilinked)

    private class CountingDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        var dispatches = 0
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatches++
            delegate.dispatch(context, block)
        }
    }

    private companion object {
        const val TITLE = "睡眠について"
        const val CURRENT = "睡眠は朝の光で整う。夜更かしをしても朝に光を浴びれば戻る。"
        const val CLAIM_A = "睡眠は朝の光では整わない。"
        const val CLAIM_B = "夜更かしの影響は翌朝の光では戻らない。"
        val NOTE_A = RelatedNote(title = "A", ref = DocumentRef("content://vault/a.md"), isWikilinked = false)
        val NOTE_B = RelatedNote(title = "B", ref = DocumentRef("content://vault/b.md"), isWikilinked = false)
    }
}
