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
import com.example.newproject.domain.PickerResult
import com.example.newproject.domain.RelatedNotesResult
import com.example.newproject.domain.RelatedNotesUseCase
import com.example.newproject.domain.SearchPickerUseCase
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteFile
import com.example.newproject.model.NoteMeta
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
 * 3経路は状態確認が `try` の外にあり、**例外が `launch` を抜けて走行フラグが真のまま残っていた**。
 *
 * **口を足しただけでは緑の意味は広がらない。** 全呼び出し経路へ当てて初めて面が埋まる。
 *
 * ## 本番の呼び出し式との対応（1対1）
 *
 * 母数は `grep -rn "aiClient.checkAvailability()" app/src/main` の**10件**。
 * **6件を「全経路」と名乗って4件を実行していなかった**ので、対応を明示する。
 *
 * | # | 本番の呼び出し | 起点 | 例外 | キャンセル |
 * |---|---|---|---|---|
 * | 1 | `DistillController:107` | `start()` | ここ | ここ |
 * | 2 | `QuizController:61` | `create()` | ここ | ここ |
 * | 3 | `RemarkController:107` | `create()` | ここ | ここ |
 * | 4 | `RemarkController:263` | `saveReply()`（映し返し） | `RemarkControllerTest` | 同左 |
 * | 5 | `SectionChatController:113` | `open()` | ここ | ここ |
 * | 6 | `SectionChatController:207` | `sendMessage()` | ここ | ここ |
 * | 7 | `SummarizeUseCase:29` | `summarize()` | ここ | ここ（**同一インスタンスの再throw**） |
 * | 8 | `SearchPickerUseCase:46` | `pick()` | ここ | ここ |
 * | 9 | `RelatedNotesUseCase:68` | `findRelated()` | ここ | ここ |
 * | 10 | `ReadingTraceController:746` | `revealTrace()` | `ReadingTraceControllerTest` | 同左 |
 *
 * 4と10だけ他ファイルなのは、**無音の経路で観測点が状態ではない**ため
 * （映し返しは「何も出さない」、読書痕跡は「要約なしのカード」）。
 * どちらも足場が既存テストにあるので、そちらへ置いた。
 *
 * ## キャンセルは「変換されない」だけでは足りない
 *
 * 旧版は `SummaryState.Error` でないことしか見ておらず、
 * **再throwを外して `AiUnavailable` へ畳ませても緑だった**（レビューが変異で確認）。
 * 純関数側は**投げたインスタンスがそのまま出ること**を、Controller側は
 * **走行状態のまま止まること**（＝Jobが落ちただけで状態を触っていない）を見る。
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

    /**
     * **純関数側は、投げたインスタンスがそのまま出ることを見る。**
     *
     * 「Error にならない」だけだと、広い catch が `AiUnavailable` へ畳んでも通ってしまう
     * （実際に旧版はその変異を緑で通した）。
     */
    @Test
    fun `自動要約はキャンセルを畳まず同じインスタンスを再throwする`() = runTest {
        val cancel = CancellationException("note changed")
        val useCase = SummarizeUseCase(
            FakeAiClient().apply { availabilityFailure = { cancel } },
            excerptDispatcher = dispatcher()
        )

        val thrown = try {
            useCase.summarize("ノートA", "Aの本文")
            null
        } catch (e: CancellationException) {
            e
        }
        assertSame("投げたインスタンスがそのまま出ること", cancel, thrown)
    }

    /** さがすと関連ノートも同じ（どちらも結果型へ畳まない）。 */
    @Test
    fun `さがすと関連ノートはキャンセルを結果型へ畳まない`() = runTest {
        val cancel = CancellationException("note changed")
        val ai = FakeAiClient().apply { availabilityFailure = { cancel } }

        val picked = try {
            SearchPickerUseCase(ai).pick("問い", listOf(noteFile()))
            null
        } catch (e: CancellationException) {
            e
        }
        assertSame("さがす", cancel, picked)

        val related = try {
            RelatedNotesUseCase(ai, excerptDispatcher = dispatcher()).findRelated(
                currentTitle = "対象",
                currentContent = BODY,
                allNotes = listOf(noteFile()),
                wikilinkTitles = emptySet(),
                readContent = { "" },
                parseMeta = { NoteMeta(emptyList(), emptyList()) }
            )
            null
        } catch (e: CancellationException) {
            e
        }
        assertSame("関連ノート", cancel, related)
    }

    /**
     * **Controller側は「走行状態のまま止まる」ことを見る。**
     *
     * キャンセルはノート切替なので、状態を触らずJobだけが落ちるのが正しい。
     * 終端状態へ変換されていたら、切替のたびに偽の理由が出る。
     */
    @Test
    fun `Controllerはキャンセルを終端状態へ変換しない`() = runTest {
        val cancel = { CancellationException("note changed") as Throwable }

        val distill = noteState()
        distillController(distill, cancellingClient(cancel)).start()
        advanceUntilIdle()
        assertTrue("蒸留は分析中のまま", distill.value.distillState is DistillState.Analyzing)

        val quiz = NoteUiStateStore(NoteUiState())
        QuizController(this, cancellingClient(cancel), quiz.quizWriter, dispatcher())
            .create("対象ノート.md", "本文")
        advanceUntilIdle()
        assertTrue("クイズは生成中のまま", quiz.value.quizState is QuizState.Loading)

        val remark = NoteUiStateStore(NoteUiState())
        remarkController(remark, cancellingClient(cancel))
            .create("対話について", BODY, relatedNotes = emptyList(), aiNotes = emptyList())
        advanceUntilIdle()
        assertTrue("ひとことは生成中のまま", remark.value.remarkState is RemarkState.Loading)

        val summary = NoteUiStateStore(NoteUiState())
        summaryController(summary, cancellingClient(cancel)).fetch("ノートA", "Aの本文")
        advanceUntilIdle()
        assertTrue("自動要約は生成中のまま", summary.value.summaryState is SummaryState.Loading)
    }

    /** セクションチャットは要約・回答の両方向とも同じ。 */
    @Test
    fun `セクションチャットはキャンセルを理由へ変換しない`() = runTest {
        val cancel = { CancellationException("note changed") as Throwable }

        val opened = NoteUiStateStore(NoteUiState())
        sectionChatController(opened, cancellingClient(cancel)).open(SECTION)
        advanceUntilIdle()
        val summaryChat = requireNotNull(opened.value.sectionChat)
        assertNull("要約側は理由を持たない", summaryChat.summaryProblem)
        assertTrue("走行状態のまま止まる", summaryChat.isSummaryLoading)

        val answering = NoteUiStateStore(NoteUiState())
        val ai = FakeAiClient { "セクションの要約" }
        val controller = sectionChatController(answering, ai)
        controller.open(SECTION)
        advanceUntilIdle()
        ai.availabilityFailure = cancel
        controller.sendMessage("これはどういう意味ですか")
        advanceUntilIdle()
        val answerChat = requireNotNull(answering.value.sectionChat)
        assertNull("回答側は理由を持たない", answerChat.answerProblem)
        assertTrue("走行状態のまま止まる", answerChat.isGenerating)
    }

    // ── 状態を持たない経路（結果型で受ける）────────────────────

    @Test
    fun `さがすは状態確認の例外をエラー結果にする`() = runTest {
        val result = SearchPickerUseCase(throwingClient()).pick("問い", listOf(noteFile()))
        assertTrue("エラー結果へ落ちること: $result", result is PickerResult.Error)
    }

    @Test
    fun `関連ノートは状態確認の例外でも決定的候補を返す`() = runTest {
        val result = RelatedNotesUseCase(throwingClient(), excerptDispatcher = dispatcher())
            .findRelated(
                currentTitle = "対象",
                currentContent = BODY,
                allNotes = listOf(noteFile()),
                wikilinkTitles = emptySet(),
                readContent = { "" },
                parseMeta = { NoteMeta(emptyList(), emptyList()) }
            )
        // 自動起動なので黙って劣化する（例外を見せない）。
        assertTrue("成功として返ること: $result", result is RelatedNotesResult.Success)
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

    private fun noteFile() = NoteFile(
        name = "候補ノート",
        ref = DocumentRef("content://candidate"),
        lastModified = 1L
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
