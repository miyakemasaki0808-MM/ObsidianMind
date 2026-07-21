package com.example.newproject

import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.google.mlkit.genai.common.DownloadStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
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
        val controller = controller(state, ImmediateAiClient(response = "選択: S001"))

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
        val controller = controller(state, ImmediateAiClient(response = "S001"))
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
        val ai = ControllableAiClient()
        val state = stateWithNote()
        val controller = controller(state, ai)
        controller.start()
        runCurrent()

        controller.cancelForNoteChange()
        ai.response.complete("S001")
        advanceUntilIdle()

        assertTrue(state.value.distillState is DistillState.Idle)
    }

    @Test
    fun `save preserves whole-note AI states and clears raw markdown contexts`() = runTest {
        val summary = SummaryState.Success("既存要約")
        val related = RelatedNotesState.Success(emptyList(), emptyList())
        val annotation = AnnotationState.Loading("対象ノート")
        val quiz = QuizState.Success("ノート", listOf(QuizCard("Q", listOf("A", "B"), 0)))
        val state = stateWithNote().apply {
            update { current ->
                current.copy(
                    summaryState = summary,
                    relatedNotesState = related,
                    annotationState = annotation,
                    quizState = quiz,
                    sectionChat = SectionChatState(
                        sectionTitle = "旧セクション",
                        sectionContext = "太字化前の本文",
                        error = "旧エラー"
                    ),
                    isSectionChatSheetVisible = true
                )
            }
        }
        val persistence = FakePersistence()
        var reloadCalls = 0
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = DistillController(
            this,
            ImmediateAiClient(response = "S001"),
            state,
            persistence,
            reloadBody = { _, expectedHash ->
                reloadCalls++
                val current = state.value.noteState as NoteState.Success
                state.update { uiState ->
                    uiState.withDistillBodyReloaded(
                        current.copy(content = "再読込本文", originalHash = expectedHash)
                    )
                }
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
        assertEquals(annotation, state.value.annotationState)
        assertTrue(state.value.quizState is QuizState.Idle)
        assertEquals(null, state.value.sectionChat)
        assertFalse(state.value.isSectionChatSheetVisible)
        assertEquals("再読込本文", (state.value.noteState as NoteState.Success).content)
        assertTrue(persistence.lastWrite!!.outputBytes.decodeToString().contains("**"))
    }

    @Test
    fun `invalid AI response is not presented as heuristic candidates`() = runTest {
        val state = stateWithNote()
        val controller = controller(state, ImmediateAiClient(response = "候補を選べません"))

        controller.start()
        advanceUntilIdle()

        assertTrue(state.value.distillState is DistillState.Error)
    }

    @Test
    fun `download requirement is explicit and does not start automatically`() = runTest {
        val state = stateWithNote()
        val ai = ImmediateAiClient(AiAvailability.NeedsDownload, "S001")
        val controller = controller(state, ai)

        controller.start()
        advanceUntilIdle()

        assertTrue(state.value.distillState is DistillState.NeedsDownload)
        assertEquals(0, ai.generateCalls)
    }

    @Test
    fun `short note allows the top sentence as a confirmed limit exception`() = runTest {
        val content = "一つ目の重要な文章です。\n二つ目の重要な文章です。"
        val state = MutableStateFlow(
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
        val controller = controller(state, ImmediateAiClient(response = "S001"), persistence)
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
            ImmediateAiClient(response = "S001 S002 S003 S004 S005 S006")
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
        val state = MutableStateFlow(
            NoteUiState(
                noteState = NoteState.Success(
                    "短いノート",
                    content,
                    "content://short",
                    sha256Hex(content.toByteArray())
                )
            )
        )
        val controller = controller(state, ImmediateAiClient(response = "S002 S001"))
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
        val state = MutableStateFlow(
            NoteUiState(
                noteState = NoteState.Success(
                    "既存太字ノート",
                    content,
                    "content://bold",
                    sha256Hex(content.toByteArray())
                )
            )
        )
        val controller = controller(state, ImmediateAiClient(response = "S001"))
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
            this,
            ImmediateAiClient(response = "S001"),
            state,
            persistence,
            reloadBody = { _, _ ->
                reloadCalls++
                val latest = noteContent() + "\n最新の追記文章です。"
                state.value = state.value.copy(
                    noteState = (state.value.noteState as NoteState.Success).copy(
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
        val controller = controller(state, ImmediateAiClient(), persistence)

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
        val controller = controller(state, ImmediateAiClient(), persistence)

        controller.checkRecovery()
        advanceUntilIdle()

        assertTrue(persistence.discardedResolved)
        assertTrue(state.value.distillState is DistillState.Idle)
    }

    @Test
    fun `export writes original bytes and resolves recovery`() = runTest {
        val state = stateWithNote()
        val original = "保存前".toByteArray()
        val persistence = FakePersistence().apply {
            pending = PendingDistillOriginal("content://note", original)
        }
        val controller = controller(state, ImmediateAiClient(), persistence)
        var exported = byteArrayOf()

        controller.exportOriginal { exported = it.copyOf() }
        advanceUntilIdle()

        assertTrue(exported.contentEquals(original))
        assertTrue(persistence.discarded)
        assertTrue(state.value.distillState is DistillState.RecoveryResolved)
    }

    private fun TestScope.controller(
        state: MutableStateFlow<NoteUiState>,
        aiClient: AiClient,
        persistence: FakePersistence = FakePersistence()
    ): DistillController {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return DistillController(
            scope = this,
            aiClient = aiClient,
            uiState = state,
            persistence = persistence,
            reloadBody = { _, _ -> true },
            analysisDispatcher = dispatcher,
            ioDispatcher = dispatcher
        )
    }

    private fun stateWithNote(): MutableStateFlow<NoteUiState> {
        val content = noteContent()
        return MutableStateFlow(
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

    private class ImmediateAiClient(
        private val availability: AiAvailability = AiAvailability.Available,
        private val response: String = "S001"
    ) : AiClient {
        var generateCalls = 0
        override suspend fun checkAvailability(): AiAvailability = availability
        override suspend fun generate(prompt: String): String {
            generateCalls++
            return response
        }
        override fun downloadModel(): Flow<DownloadStatus> = emptyFlow()
    }

    private class ControllableAiClient : AiClient {
        val response = CompletableDeferred<String>()
        override suspend fun checkAvailability(): AiAvailability = AiAvailability.Available
        override suspend fun generate(prompt: String): String = response.await()
        override fun downloadModel(): Flow<DownloadStatus> = emptyFlow()
    }

    private class FakePersistence : DistillPersistence {
        var lastWrite: DistillWriteRequest? = null
        var assessment: DistillRecoveryAssessment = DistillRecoveryAssessment.None
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
        override fun assessPendingRecovery(): DistillRecoveryAssessment = assessment
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
