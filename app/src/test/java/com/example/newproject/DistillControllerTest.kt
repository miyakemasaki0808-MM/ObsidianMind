package com.example.newproject

import com.example.newproject.controller.DistillController
import com.example.newproject.data.DistillPersistence
import com.example.newproject.data.DistillRecoveryAssessment
import com.example.newproject.data.DistillRecoveryRecord
import com.example.newproject.data.DistillRecoveryResolutionResult
import com.example.newproject.data.DistillWritePhase
import com.example.newproject.data.DistillWriteRequest
import com.example.newproject.data.DistillWriteResult
import com.example.newproject.data.PendingDistillOriginal
import com.example.newproject.data.sha256Hex
import com.example.newproject.model.state.AiNoticeAction
import com.example.newproject.model.state.RemarkState
import com.example.newproject.model.state.DistillState
import com.example.newproject.model.state.NoteState
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.state.QuizCard
import com.example.newproject.model.state.QuizState
import com.example.newproject.model.state.RelatedNotesState
import com.example.newproject.model.state.SectionChatProblem
import com.example.newproject.model.state.SectionChatState
import com.example.newproject.model.state.SummaryState
import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.google.mlkit.genai.common.DownloadStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import com.example.newproject.model.NoteUiStateStore
import com.example.newproject.fakes.FakeAiClient
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DistillControllerTest {

    @Test
    fun `AI IDs become selected original candidate items`() = runTest {
        val state = stateWithNote()
        val controller = controller(state, FakeAiClient.returning("選択: S001"))

        controller.start()
        advanceUntilIdle()

        val candidates = state.value.distillState as DistillState.Candidates
        assertEquals(1, candidates.items.size)
        assertEquals("S001", candidates.items.single().id)
        assertTrue(candidates.items.single().isSelected)
        assertTrue(candidates.items.single().text in noteContent())
    }

    @Test
    fun `candidate toggle updates selection and projected ratio`() = runTest {
        val state = stateWithNote()
        val controller = controller(state, FakeAiClient.returning("S001"))
        controller.start()
        advanceUntilIdle()
        val before = state.value.distillState as DistillState.Candidates

        controller.toggleCandidate("S001")

        val after = state.value.distillState as DistillState.Candidates
        assertEquals(1, before.selectedCount)
        assertEquals(0, after.selectedCount)
        assertEquals(0.0, after.projectedBoldRatio, 0.0)
    }

    @Test
    fun `note switch discards late AI response`() = runTest {
        val ai = FakeAiClient.deferred()
        val state = stateWithNote()
        val controller = controller(state, ai)
        controller.start()
        runCurrent()

        controller.cancelForNoteChange()
        ai.completeAll("S001")
        advanceUntilIdle()

        assertTrue(state.value.distillState is DistillState.Idle)
    }

    @Test
    fun `save preserves whole-note AI states and clears raw markdown contexts`() = runTest {
        val summary = SummaryState.Success("既存要約")
        val related = RelatedNotesState.Success(emptyList(), emptyList())
        val remark = RemarkState.Loading("対象ノート")
        val quiz = QuizState.Success("ノート", listOf(QuizCard("Q", listOf("A", "B"), 0)))
        val state = NoteUiStateStore(
            stateWithNote().value.copy(
                summaryState = summary,
                relatedNotesState = related,
                remarkState = remark,
                quizState = quiz,
                sectionChat = SectionChatState(
                    sectionTitle = "旧セクション",
                    sectionContext = "太字化前の本文",
                    summaryProblem = SectionChatProblem.GenerationFailed("旧エラー")
                ),
                isSectionChatSheetVisible = true
            )
        )
        val persistence = FakePersistence()
        var reloadCalls = 0
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = DistillController(
            scope = this,
            aiClient = FakeAiClient.returning("S001"),
            state = state.distillWriter,
            currentNote = state::currentNote,
            persistence = persistence,
            reloadBody = { _, expectedHash ->
                reloadCalls++
                val current = state.value.noteState as NoteState.Success
                state.applyReloadedBody(
                    current.targetUri,
                    current.copy(content = "再読込本文", originalHash = expectedHash)
                )
                true
            },
            analysisDispatcher = dispatcher,
            ioDispatcher = dispatcher
        )
        controller.start()
        advanceUntilIdle()

        controller.saveSelection()
        advanceUntilIdle()

        assertTrue(state.value.distillState is DistillState.Saved)
        assertEquals(1, reloadCalls)
        assertEquals(summary, state.value.summaryState)
        assertEquals(related, state.value.relatedNotesState)
        assertEquals(remark, state.value.remarkState)
        assertTrue(state.value.quizState is QuizState.Idle)
        assertEquals(null, state.value.sectionChat)
        assertFalse(state.value.isSectionChatSheetVisible)
        assertEquals("再読込本文", (state.value.noteState as NoteState.Success).content)
        assertTrue(persistence.lastWrite!!.outputBytes.decodeToString().contains("**"))
    }

    @Test
    fun `invalid AI response is not presented as heuristic candidates`() = runTest {
        val state = stateWithNote()
        val controller = controller(state, FakeAiClient.returning("候補を選べません"))

        controller.start()
        advanceUntilIdle()

        assertTrue(state.value.distillState is DistillState.Error)
    }

    @Test
    fun `download requirement is explicit and does not start automatically`() = runTest {
        val state = stateWithNote()
        val ai = FakeAiClient.returning("S001", AiAvailability.NeedsDownload)
        val controller = controller(state, ai)

        controller.start()
        advanceUntilIdle()

        val notice = (state.value.distillState as DistillState.AiNotice).notice
        assertEquals(AiNoticeAction.Download, notice.action)
        assertEquals(0, ai.generateCalls)
    }

    /**
     * **`AiClient` が契約を破って投げても、呼び出し側が壊れない。**
     *
     * 修正後の `AICoreClient` は投げない（例外は `TemporarilyUnavailable` という値になる）が、
     * 実装は他にもあり得る。**統一前はこの経路を突くテストダブルが1つも無かった。**
     */
    @Test
    fun `a throwing availability check surfaces as an error, not a crash`() = runTest {
        val state = stateWithNote()
        val ai = FakeAiClient.returning("S001")
        ai.availabilityFailure = { IllegalStateException("AICore not bound") }
        val controller = controller(state, ai)

        controller.start()
        advanceUntilIdle()

        assertTrue(
            "投げられた例外はエラー状態になること: ${state.value.distillState}",
            state.value.distillState is DistillState.Error
        )
    }

    /**
     * **キャンセルはエラーへ変換しない。** ノート切替のたびに偽のエラーが出る。
     * `CancellationException` は `Exception` の子なので、広い catch があると素通りしない。
     */
    @Test
    fun `a cancelled availability check does not become an error`() = runTest {
        val state = stateWithNote()
        val ai = FakeAiClient.returning("S001")
        ai.availabilityFailure = { CancellationException("note changed") }
        val controller = controller(state, ai)

        controller.start()
        advanceUntilIdle()

        assertTrue(
            "キャンセルがエラー表示に化けている: ${state.value.distillState}",
            state.value.distillState is DistillState.Analyzing
        )
    }

    /**
     * **DL実行中は `downloadModel()` を呼ばず、CTAも出さない。**
     *
     * 未取得とDL中を畳んでいたころは、走行中のDLに対して
     * 「通信量を確認してから開始してください」と出していた（押しても始まるものが無い）。
     * 一度は「走行中のDLへ合流する」形にしたが、**beta2 の `downloadFeatureInternal` には
     * 状態の門番が無く、合流できる保証がない**（逆アセンブルで確認）。
     * 合流を装って即 `DownloadCompleted` が返ると、モデルが揃う前に生成が走る。
     */
    @Test
    fun `a running download is waited out without calling download again`() = runTest {
        val state = stateWithNote()
        val downloads = Channel<DownloadStatus>(Channel.UNLIMITED)
        val ai = FakeAiClient(AiAvailability.Downloading, downloads) { "S001" }
        val controller = controller(state, ai)

        controller.start()
        advanceUntilIdle()

        assertEquals("DL中に download() を呼ばないこと", 0, ai.downloadCalls)
        assertEquals(0, ai.generateCalls)
        val notice = (state.value.distillState as DistillState.AiNotice).notice
        assertEquals(AiNoticeAction.None, notice.action)

        downloads.close()
    }

    /** **非対応には再試行導線を出さない。** 何度押しても同じ答えが返る。 */
    @Test
    fun `an unsupported device is told so without a retry affordance`() = runTest {
        val state = stateWithNote()
        val controller = controller(state, FakeAiClient(AiAvailability.Unsupported))

        controller.start()
        advanceUntilIdle()

        val notice = (state.value.distillState as DistillState.AiNotice).notice
        assertEquals(AiNoticeAction.None, notice.action)
    }

    /**
     * **状態を取れなかっただけなら再試行に意味がある。** 非対応と同じ枝へ畳むと、
     * 一時的な失敗が「この端末では使えません」として永久に見えてしまう。
     */
    @Test
    fun `a failed status read offers a retry and hides the SDK message`() = runTest {
        val state = stateWithNote()
        val ai = FakeAiClient(AiAvailability.TemporarilyUnavailable(IllegalStateException("AICore not bound")))
        val controller = controller(state, ai)

        controller.start()
        advanceUntilIdle()

        val notice = (state.value.distillState as DistillState.AiNotice).notice
        assertEquals(AiNoticeAction.Retry, notice.action)
        assertFalse(notice.message, notice.message.contains("AICore"))
    }

    @Test
    fun `short note allows the top sentence as a confirmed limit exception`() = runTest {
        val content = "一つ目の重要な文章です。\n二つ目の重要な文章です。"
        val state = NoteUiStateStore(
            NoteUiState(
                noteState = NoteState.Success(
                    "短いノート",
                    content,
                    "content://short",
                    sha256Hex(content.toByteArray())
                )
            )
        )
        val persistence = FakePersistence()
        val controller = controller(state, FakeAiClient.returning("S001"), persistence)
        controller.start()
        advanceUntilIdle()

        val candidates = state.value.distillState as DistillState.Candidates
        assertFalse(candidates.isWithinBoldLimit)
        assertTrue(candidates.isSingleSentenceException)
        assertTrue(candidates.canSaveSelection)
        controller.saveSelection()
        advanceUntilIdle()
        assertTrue(persistence.lastWrite!!.outputBytes.decodeToString().contains("**"))
    }

    @Test
    fun `initial selection keeps only AI ranked candidates that fit thirty percent`() = runTest {
        val state = stateWithNote()
        val controller = controller(
            state,
            FakeAiClient.returning("S001 S002 S003 S004 S005 S006")
        )

        controller.start()
        advanceUntilIdle()

        val candidates = state.value.distillState as DistillState.Candidates
        assertTrue(candidates.selectedCount in 1 until candidates.items.size)
        assertTrue(candidates.isWithinBoldLimit)
        assertFalse(candidates.isSingleSentenceException)
        assertTrue(candidates.projectedBoldRatio <= 0.30)
    }

    @Test
    fun `only the highest ranked oversized sentence receives the exception`() = runTest {
        val content = "一つ目の重要な文章です。\n二つ目の重要な文章です。"
        val state = NoteUiStateStore(
            NoteUiState(
                noteState = NoteState.Success(
                    "短いノート",
                    content,
                    "content://short",
                    sha256Hex(content.toByteArray())
                )
            )
        )
        val controller = controller(state, FakeAiClient.returning("S002 S001"))
        controller.start()
        advanceUntilIdle()

        val initial = state.value.distillState as DistillState.Candidates
        assertEquals("S002", initial.items.single { it.isSelected }.id)
        assertTrue(initial.isSingleSentenceException)

        controller.toggleCandidate("S002")
        controller.toggleCandidate("S001")

        val changed = state.value.distillState as DistillState.Candidates
        assertFalse(changed.isSingleSentenceException)
        assertFalse(changed.canSaveSelection)
    }

    @Test
    fun `existing body bold at the limit does not grant a sentence exception`() = runTest {
        val content = "**既存の重要な太字です。**\n追加候補です。"
        val state = NoteUiStateStore(
            NoteUiState(
                noteState = NoteState.Success(
                    "既存太字ノート",
                    content,
                    "content://bold",
                    sha256Hex(content.toByteArray())
                )
            )
        )
        val controller = controller(state, FakeAiClient.returning("S001"))
        controller.start()
        advanceUntilIdle()

        val candidates = state.value.distillState as DistillState.Candidates
        assertEquals(0, candidates.selectedCount)
        assertFalse(candidates.isWithinBoldLimit)
        assertFalse(candidates.isSingleSentenceException)
        assertFalse(candidates.canSaveSelection)
    }

    @Test
    fun `conflict retry reloads latest body before reanalysis`() = runTest {
        val state = stateWithNote()
        val persistence = FakePersistence().apply {
            writeResult = { DistillWriteResult.Conflict(sha256Hex("new".toByteArray()), "競合") }
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        var reloadCalls = 0
        val controller = DistillController(
            scope = this,
            aiClient = FakeAiClient.returning("S001"),
            state = state.distillWriter,
            currentNote = state::currentNote,
            persistence = persistence,
            reloadBody = { _, _ ->
                reloadCalls++
                val latest = noteContent() + "\n最新の追記文章です。"
                state.setNoteState(
                    (state.value.noteState as NoteState.Success).copy(
                        content = latest,
                        originalHash = sha256Hex(latest.toByteArray())
                    )
                )
                true
            },
            analysisDispatcher = dispatcher,
            ioDispatcher = dispatcher
        )
        controller.start()
        advanceUntilIdle()
        controller.saveSelection()
        advanceUntilIdle()
        assertTrue(state.value.distillState is DistillState.Conflict)

        controller.retry()
        advanceUntilIdle()

        assertEquals(1, reloadCalls)
        assertTrue(state.value.distillState is DistillState.Candidates)
    }

    @Test
    fun `diverged pending write becomes recovery state`() = runTest {
        val state = stateWithNote()
        val persistence = FakePersistence().apply {
            assessment = DistillRecoveryAssessment.Diverged(record(), sha256Hex("other".toByteArray()))
        }
        val controller = controller(state, FakeAiClient.returning("S001"), persistence)

        controller.checkRecovery()
        advanceUntilIdle()

        val recovery = state.value.distillState as DistillState.RecoveryRequired
        assertTrue(recovery.canRestore)
        assertTrue(recovery.canExport)
    }

    @Test
    fun `already resolved recovery is discarded silently on startup check`() = runTest {
        val state = stateWithNote()
        val persistence = FakePersistence().apply {
            assessment = DistillRecoveryAssessment.OriginalStillPresent(record())
        }
        val controller = controller(state, FakeAiClient.returning("S001"), persistence)

        controller.checkRecovery()
        advanceUntilIdle()

        assertTrue(persistence.discardedResolved)
        assertTrue(state.value.distillState is DistillState.Idle)
    }

    /**
     * 2-7 の本体。復旧確認はSAF I/Oを伴うので遅れることがあり、その間にユーザーが
     * 蒸留を始められる。復旧警告を出した時点で走行中の分析を無効化しないと、
     * 直後に返ってきた候補が警告を上書きしてしまう。
     */
    @Test
    fun `遅れて届いた復旧警告は走行中の分析に上書きされない`() = runTest {
        val state = stateWithNote()
        val ai = FakeAiClient.deferred()
        val persistence = FakePersistence().apply {
            assessment = DistillRecoveryAssessment.Diverged(record(), sha256Hex("other".toByteArray()))
        }
        val controller = controller(state, ai, persistence)

        // 復旧確認を始めた直後に蒸留を開始する（確認はまだI/O待ち）。
        controller.checkRecovery()
        controller.start()
        advanceUntilIdle()

        // 先に復旧警告が出る
        assertTrue(state.value.distillState is DistillState.RecoveryRequired)

        // その後に分析のAI応答が返ってきても、警告を消してはいけない
        ai.completeAll("S001")
        advanceUntilIdle()

        assertTrue(state.value.distillState is DistillState.RecoveryRequired)
    }

    /** 取り下げられた復旧確認は、I/Oそのものが走らない（重複した確認を残さない）。 */
    @Test
    fun `新しい復旧確認が始まると古い確認は取り下げられる`() = runTest {
        val state = stateWithNote()
        val persistence = FakePersistence().apply {
            assessment = DistillRecoveryAssessment.Diverged(record(), sha256Hex("other".toByteArray()))
        }
        val controller = controller(state, FakeAiClient.returning("S001"), persistence)

        controller.checkRecovery()
        controller.checkRecovery()
        advanceUntilIdle()

        assertEquals(1, persistence.assessCalls)
        assertTrue(state.value.distillState is DistillState.RecoveryRequired)
    }

    /**
     * 復旧レコードはアプリ内部の未解決1件で、ノートに紐づかない。
     * ノート切替で確認を打ち切ると、データ安全性の警告が黙って消える。
     */
    @Test
    fun `ノート切替では復旧確認が生き残る`() = runTest {
        val state = stateWithNote()
        val persistence = FakePersistence().apply {
            assessment = DistillRecoveryAssessment.Diverged(record(), sha256Hex("other".toByteArray()))
        }
        val controller = controller(state, FakeAiClient.returning("S001"), persistence)

        controller.checkRecovery()
        controller.cancelForNoteChange()
        advanceUntilIdle()

        assertTrue(state.value.distillState is DistillState.RecoveryRequired)
    }

    @Test
    fun `export writes original bytes and resolves recovery`() = runTest {
        val state = stateWithNote()
        val original = "保存前".toByteArray()
        val persistence = FakePersistence().apply {
            pending = PendingDistillOriginal("content://note", original)
        }
        val controller = controller(state, FakeAiClient.returning("S001"), persistence)
        var exported = byteArrayOf()

        controller.exportOriginal { exported = it.copyOf() }
        advanceUntilIdle()

        assertTrue(exported.contentEquals(original))
        assertTrue(persistence.discarded)
        assertTrue(state.value.distillState is DistillState.RecoveryResolved)
    }

    private fun TestScope.controller(
        state: NoteUiStateStore,
        aiClient: AiClient,
        persistence: FakePersistence = FakePersistence()
    ): DistillController {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return DistillController(
            scope = this,
            aiClient = aiClient,
            state = state.distillWriter,
            currentNote = state::currentNote,
            persistence = persistence,
            reloadBody = { _, _ -> true },
            analysisDispatcher = dispatcher,
            ioDispatcher = dispatcher
        )
    }

    private fun stateWithNote(): NoteUiStateStore {
        val content = noteContent()
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

    private fun noteContent(): String = (1..12).joinToString("\n") { index ->
        "これは十分な長さを持つ重要な本文${index}です。"
    }

    private class FakePersistence : DistillPersistence {
        var lastWrite: DistillWriteRequest? = null
        var assessment: DistillRecoveryAssessment = DistillRecoveryAssessment.None
        /** 復旧確認のI/Oが実際に走った回数。取り下げた確認が走らないことの確認に使う。 */
        var assessCalls = 0
            private set
        var pending: PendingDistillOriginal? = null
        var discarded = false
        var discardedResolved = false
        var restoreResult: DistillRecoveryResolutionResult = DistillRecoveryResolutionResult.NoValidRecord
        var writeResult: (DistillWriteRequest) -> DistillWriteResult = { request ->
            DistillWriteResult.Success(sha256Hex(request.outputBytes), request.outputBytes.size)
        }

        override fun write(request: DistillWriteRequest): DistillWriteResult {
            lastWrite = request
            return writeResult(request)
        }
        override fun assessPendingRecovery(): DistillRecoveryAssessment {
            assessCalls++
            return assessment
        }
        override fun discardResolvedRecovery(assessment: DistillRecoveryAssessment): Boolean {
            discardedResolved = true
            return true
        }
        override fun discardPendingRecovery(): Boolean {
            discarded = true
            return true
        }
        override fun pendingOriginal(): PendingDistillOriginal? = pending
        override fun restoreOriginal(): DistillRecoveryResolutionResult = restoreResult
    }

    private fun record(): DistillRecoveryRecord {
        val bytes = "original".toByteArray()
        return DistillRecoveryRecord(
            targetUri = "content://note",
            originalHash = sha256Hex(bytes),
            expectedHash = sha256Hex("expected".toByteArray()),
            originalBytes = bytes,
            phase = DistillWritePhase.WRITING,
            createdAtEpochMillis = 1L
        )
    }
}
