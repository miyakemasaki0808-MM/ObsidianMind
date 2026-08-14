package com.example.newproject

import com.example.newproject.ai.AiClient
import com.example.newproject.controller.DistillController
import com.example.newproject.controller.QuizController
import com.example.newproject.controller.RemarkController
import com.example.newproject.controller.SectionChatController
import com.example.newproject.controller.ReplySaveOutcome
import com.example.newproject.controller.SummaryController
import com.example.newproject.data.DistillPersistence
import com.example.newproject.data.DistillRecoveryAssessment
import com.example.newproject.data.DistillWriteRequest
import com.example.newproject.data.DistillWriteResult
import com.example.newproject.data.PendingDistillOriginal
import com.example.newproject.data.sha256Hex
import com.example.newproject.data.DistillRecoveryResolutionResult
import com.example.newproject.domain.SummarizeUseCase
import com.example.newproject.domain.markdown.NoteSection
import com.example.newproject.fakes.FakeAiClient
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.NoteUiStateStore
import com.example.newproject.model.state.DistillState
import com.example.newproject.model.state.NoteState
import com.example.newproject.model.state.QuizState
import com.example.newproject.model.state.RemarkState
import com.example.newproject.model.state.SummaryState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **`checkAvailability()` が投げても、どの経路も走行状態を残さない。**
 *
 * ## なぜ経路を横断するのか
 *
 * `AiClient` は他実装を許す**公開契約**で、`FakeAiClient.availabilityFailure` は
 * 「契約違反側に呼び出し側が耐える」ための口として用意してある。
 * ところが**その口を当てていたのは蒸留の2件だけ**で、セクション要約・回答・自動要約の
 * 3経路は状態確認が `try` の外にあり、**例外が `launch` を抜けて走行フラグが真のまま残っていた**
 * （画面が永久に待つ）。
 *
 * **口を足しただけでは緑の意味は広がらない。** 全呼び出し経路へ当てて初めて面が埋まる。
 *
 * ## 表
 *
 * | 経路 | 走行状態 | 例外時に落ちる先 |
 * |---|---|---|
 * | 蒸留 | `Analyzing` | `Error`（再試行あり） |
 * | クイズ | `Loading` | `Error` |
 * | ひとこと | `Loading` | `Error` |
 * | セクション要約 | `isSummaryLoading` | `GenerationFailed`（再試行あり） |
 * | セクション回答 | `isGenerating` | `GenerationFailed`（再試行あり） |
 * | 自動要約 | `SummaryState.Loading` | `AiUnavailable`（自動起動なので黙る） |
 *
 * **`CancellationException` はどの経路でもエラーへ変換しない** — ノート切替のたびに
 * 偽のエラーが出る（→ CLAUDE.md 並行処理）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiAvailabilityContractTest {

    // ── 例外は必ず終端状態へ落ちる ──────────────────────────────

    @Test
    fun `蒸留は状態確認の例外で走行状態を残さない`() = runTest {
        val state = noteState()
        val ai = throwingClient()
        distillController(state, ai).start()
        advanceUntilIdle()

        assertNotRunning("蒸留", state.value.distillState !is DistillState.Analyzing)
        assertTrue(state.value.distillState is DistillState.Error)
    }

    @Test
    fun `クイズは状態確認の例外で走行状態を残さない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        QuizController(this, throwingClient(), state.quizWriter, dispatcher())
            .create("対象ノート.md", "本文")
        advanceUntilIdle()

        assertNotRunning("クイズ", state.value.quizState !is QuizState.Loading)
        assertTrue(state.value.quizState is QuizState.Error)
    }

    @Test
    fun `ひとことは状態確認の例外で走行状態を残さない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        remarkController(state, throwingClient())
            .create("対話について", BODY, relatedNotes = emptyList(), aiNotes = emptyList())
        advanceUntilIdle()

        assertNotRunning("ひとこと", state.value.remarkState !is RemarkState.Loading)
        assertTrue(state.value.remarkState is RemarkState.Error)
    }

    @Test
    fun `セクション要約は状態確認の例外で走行状態を残さない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        sectionChatController(state, throwingClient()).open(SECTION)
        advanceUntilIdle()

        val chat = requireNotNull(state.value.sectionChat)
        assertNotRunning("セクション要約", !chat.isSummaryLoading)
        assertTrue("理由が残ること", chat.summaryProblem != null)
    }

    @Test
    fun `セクション回答は状態確認の例外で走行状態を残さない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val ai = FakeAiClient { "セクションの要約" }
        val controller = sectionChatController(state, ai)
        controller.open(SECTION)
        advanceUntilIdle()

        // 質問を送る時点で契約違反の実装に差し替わる。
        ai.availabilityFailure = { IllegalStateException("AICore not bound") }
        controller.sendMessage("これはどういう意味ですか")
        advanceUntilIdle()

        val chat = requireNotNull(state.value.sectionChat)
        assertNotRunning("セクション回答", !chat.isGenerating)
        assertTrue("理由が残ること", chat.answerProblem != null)
    }

    @Test
    fun `自動要約は状態確認の例外で走行状態を残さない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        summaryController(state, throwingClient()).fetch("ノートA", "Aの本文")
        advanceUntilIdle()

        assertNotRunning("自動要約", state.value.summaryState !is SummaryState.Loading)
        // 自動起動なので黙る側へ倒す（エラー表示は出さない）。
        assertTrue(state.value.summaryState is SummaryState.AiUnavailable)
    }

    // ── キャンセルはエラーへ変換しない ──────────────────────────

    @Test
    fun `どの経路もキャンセルをエラー状態へ変換しない`() = runTest {
        val cancel = { CancellationException("note changed") as Throwable }

        val distill = noteState()
        distillController(distill, cancellingClient(cancel)).start()
        advanceUntilIdle()
        assertFalse("蒸留", distill.value.distillState is DistillState.Error)

        val quiz = NoteUiStateStore(NoteUiState())
        QuizController(this, cancellingClient(cancel), quiz.quizWriter, dispatcher())
            .create("対象ノート.md", "本文")
        advanceUntilIdle()
        assertFalse("クイズ", quiz.value.quizState is QuizState.Error)

        val chat = NoteUiStateStore(NoteUiState())
        sectionChatController(chat, cancellingClient(cancel)).open(SECTION)
        advanceUntilIdle()
        assertFalse("セクション要約", requireNotNull(chat.value.sectionChat).summaryProblem != null)

        val summary = NoteUiStateStore(NoteUiState())
        summaryController(summary, cancellingClient(cancel)).fetch("ノートA", "Aの本文")
        advanceUntilIdle()
        assertFalse("自動要約", summary.value.summaryState is SummaryState.Error)
    }

    // ── 組み立て ────────────────────────────────────────────

    private fun assertNotRunning(label: String, stopped: Boolean) =
        assertTrue("$label: 状態確認が投げた後も走行状態が残っている", stopped)

    private fun throwingClient() = FakeAiClient().apply {
        availabilityFailure = { IllegalStateException("AICore not bound") }
    }

    private fun cancellingClient(error: () -> Throwable) =
        FakeAiClient().apply { availabilityFailure = error }

    private fun TestScope.dispatcher() = StandardTestDispatcher(testScheduler)

    private fun TestScope.sectionChatController(state: NoteUiStateStore, ai: AiClient) =
        SectionChatController(this, ai, state.sectionChatWriter, dispatcher())

    private fun TestScope.summaryController(state: NoteUiStateStore, ai: AiClient) =
        SummaryController(
            scope = CoroutineScope(dispatcher()),
            summarizeUseCase = SummarizeUseCase(ai, excerptDispatcher = dispatcher()),
            aiClient = ai,
            state = state.summaryWriter,
            onModelReady = { _, _ -> }
        )

    private fun TestScope.remarkController(state: NoteUiStateStore, ai: AiClient) =
        RemarkController(
            scope = this,
            aiClient = ai,
            state = state.remarkWriter,
            onRemarkReady = {},
            persistReply = { _, _, _ -> ReplySaveOutcome.Saved },
            loadReflection = { null },
            persistMirrored = { _, _ -> },
            currentContent = { BODY },
            excerptDispatcher = dispatcher()
        )

    private fun TestScope.distillController(state: NoteUiStateStore, ai: AiClient) =
        DistillController(
            scope = this,
            aiClient = ai,
            state = state.distillWriter,
            currentNote = state::currentNote,
            persistence = NoOpPersistence,
            reloadBody = { _, _ -> true },
            analysisDispatcher = dispatcher(),
            ioDispatcher = dispatcher()
        )

    private fun noteState(): NoteUiStateStore {
        val content = (1..12).joinToString("\n") { "これは十分な長さを持つ重要な本文${it}です。" }
        return NoteUiStateStore(
            NoteUiState(
                noteState = NoteState.Success(
                    title = "対象ノート",
                    content = content,
                    targetUri = "content://note",
                    originalHash = sha256Hex(content.toByteArray())
                )
            )
        )
    }

    private object NoOpPersistence : DistillPersistence {
        override fun write(request: DistillWriteRequest): DistillWriteResult =
            DistillWriteResult.Success(sha256Hex(request.outputBytes), request.outputBytes.size)
        override fun assessPendingRecovery(): DistillRecoveryAssessment = DistillRecoveryAssessment.None
        override fun discardResolvedRecovery(assessment: DistillRecoveryAssessment): Boolean = true
        override fun discardPendingRecovery(): Boolean = true
        override fun pendingOriginal(): PendingDistillOriginal? = null
        override fun restoreOriginal(): DistillRecoveryResolutionResult =
            DistillRecoveryResolutionResult.NoValidRecord
    }

    private companion object {
        val SECTION = NoteSection("対象セクション", 2, "## 対象セクション\n本文")
        const val BODY = "読書は著者との対話である。問いを持ち込むことで、書かれていないことまで考えられる。"
    }
}
