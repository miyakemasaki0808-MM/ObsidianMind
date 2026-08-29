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
import com.example.newproject.model.state.DistillRangePreset
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
    fun `split clauses are labelled by clause count and carry the parent sentence`() = runTest {
        val sentence = "あ".repeat(40) + "、" + "い".repeat(40) + "。"
        val state = stateWithNote(sentence)
        val controller = controller(state, FakeAiClient.returning("S001"))

        controller.start()
        advanceUntilIdle()

        val item = (state.value.distillState as DistillState.Candidates).items.single()
        // 分母は句数。分子の sourceIndex と同じ並びから採るので「3 / 2」のような矛盾が起きない。
        assertEquals("1 / 2", item.positionLabel)
        // 句だけでは何の断片か読めないので、文脈には親文を出す。
        assertEquals(sentence, item.context)
        assertTrue(item.text.length < sentence.length)
    }

    @Test
    fun `the bold limit exception can be reached by a clause`() = runTest {
        // 実機レビュー 2026-08-17 P2-1（DIST-02）。例外の説明が「1文」固定だった経路。
        // 例外は文だけでなく句でも成立するので、文言も単位に依存できない。
        val sentence = "あ".repeat(30) + "、" + "い".repeat(30) + "。"
        val state = stateWithNote(sentence)
        val controller = controller(state, FakeAiClient.returning("S001"))

        controller.start()
        advanceUntilIdle()

        val candidates = state.value.distillState as DistillState.Candidates
        assertTrue(candidates.isSingleCandidateException)
        assertFalse(candidates.isWithinBoldLimit)
        // 選ばれているのは文全体ではなく句。
        assertTrue(candidates.items.single().text.length < sentence.length)
    }

    @Test
    fun `terms borrow the position of the sentence that contains them`() = runTest {
        // 実機レビュー 2026-08-17 P2-1（DIST-18）。1文しか無いノートで `2 / 5` と出ていた。
        val sentence = "「共通語」と「共通語」と「別語A」と「別語B」を含む本文です。"
        val state = stateWithNote(sentence)
        val controller = controller(state, FakeAiClient.returning("S001"))

        controller.start()
        advanceUntilIdle()

        val item = (state.value.distillState as DistillState.Candidates).items.single()
        assertTrue(item.isTerm)
        // 語句は線形位置を持たないので、含まれる文の位置を借りる。
        assertEquals("1 / 1", item.positionLabel)
        assertEquals(sentence, item.context)
    }

    @Test
    fun `an unsplit sentence after a split one shows the whole previous sentence`() = runTest {
        // 実機レビュー 2026-08-16 P2-1。直前の候補単位の text を使うと、最後の句だけが文脈になる。
        val split = "あ".repeat(40) + "、" + "い".repeat(40) + "。"
        val following = "短い結論です。"
        val state = stateWithNote("$split\n$following")
        val controller = controller(state, FakeAiClient.returning("S003"))

        controller.start()
        advanceUntilIdle()

        val item = (state.value.distillState as DistillState.Candidates).items.single()
        assertEquals(following, item.text)
        assertEquals(split, item.context)
    }

    @Test
    fun `an unsplit sentence after another unsplit one still shows that sentence`() = runTest {
        val first = "これは十分な長さを持つ最初の本文です。"
        val second = "これは二番目の本文です。"
        val state = stateWithNote("$first\n$second")
        val controller = controller(state, FakeAiClient.returning("S002"))

        controller.start()
        advanceUntilIdle()

        val item = (state.value.distillState as DistillState.Candidates).items.single()
        assertEquals(second, item.text)
        assertEquals(first, item.context)
    }

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
        assertTrue(candidates.isSingleCandidateException)
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
        assertFalse(candidates.isSingleCandidateException)
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
        assertTrue(initial.isSingleCandidateException)

        controller.toggleCandidate("S002")
        controller.toggleCandidate("S001")

        val changed = state.value.distillState as DistillState.Candidates
        assertFalse(changed.isSingleCandidateException)
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
        assertFalse(candidates.isSingleCandidateException)
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

    // ── 太字範囲の調整（段階1: プリセット）─────────────────────────────────

    @Test
    fun `an untouched selection is saved exactly like before the adjustment feature`() = runTest {
        val content = adjustableNote()
        val state = stateWithNote(content)
        val persistence = FakePersistence()
        val controller = controller(state, FakeAiClient.returning("S005"), persistence)
        controller.start()
        advanceUntilIdle()

        controller.saveSelection()
        advanceUntilIdle()

        // 未調整なら確定範囲＝提案範囲。出力はv1と1文字も違わない。
        val written = persistence.lastWrite!!.outputBytes.decodeToString()
        assertEquals(withBoldAt(content, 128, 159), written)
        assertEquals(content, written.replace("**", ""))
    }

    @Test
    fun `a widened range moves the bold markers to the confirmed range`() = runTest {
        val content = adjustableNote()
        val state = stateWithNote(content)
        val persistence = FakePersistence()
        val controller = controller(state, FakeAiClient.returning("S005"), persistence)
        controller.start()
        advanceUntilIdle()

        controller.applyRange("S005", DistillRangePreset.Sentence)
        controller.saveSelection()
        advanceUntilIdle()

        // **未調整の一致確認だけでは足りない。** 保存経路が提案範囲を読み続けていても、
        // 未調整のときは同じ出力になって緑のまま通る。変えた範囲で入ることを直接見る。
        val written = persistence.lastWrite!!.outputBytes.decodeToString()
        assertEquals(withBoldAt(content, 128, 191), written)
        assertEquals(content, written.replace("**", ""))
    }

    @Test
    fun `widening a selected candidate deselects the overlapping one and reports it`() = runTest {
        val state = stateWithNote(adjustableNote())
        val controller = controller(state, FakeAiClient.returning("S005 S006"))
        controller.start()
        advanceUntilIdle()
        assertEquals(2, (state.value.distillState as DistillState.Candidates).selectedCount)

        // 同じ親文の2つの句。片方を `文全体` にすると、もう片方を必ず含む。
        controller.applyRange("S006", DistillRangePreset.Sentence)

        val after = state.value.distillState as DistillState.Candidates
        assertEquals(listOf("S005"), after.overlapDeselectedIds)
        assertFalse(after.items.first { it.id == "S005" }.isSelected)
        assertTrue(after.items.first { it.id == "S006" }.isSelected)
    }

    @Test
    fun `re-checking a deselected candidate keeps the last explicit action`() = runTest {
        val state = stateWithNote(adjustableNote())
        val controller = controller(state, FakeAiClient.returning("S005 S006"))
        controller.start()
        advanceUntilIdle()
        controller.applyRange("S006", DistillRangePreset.Sentence)

        // 外された候補をチェックし直す経路。解消を範囲変更だけに置くとここが素通りする。
        controller.toggleCandidate("S005")

        val after = state.value.distillState as DistillState.Candidates
        assertTrue(after.items.first { it.id == "S005" }.isSelected)
        assertFalse(after.items.first { it.id == "S006" }.isSelected)
        assertEquals(listOf("S006"), after.overlapDeselectedIds)
    }

    @Test
    fun `re-applying the same preset keeps the overlap notice`() = runTest {
        val state = stateWithNote(adjustableNote())
        val controller = controller(state, FakeAiClient.returning("S005 S006"))
        controller.start()
        advanceUntilIdle()
        controller.applyRange("S006", DistillRangePreset.Sentence)
        val afterWiden = state.value.distillState as DistillState.Candidates

        // 選択済みの段をもう一度押しても、確定範囲は変わっていない。
        controller.applyRange("S006", DistillRangePreset.Sentence)

        val after = state.value.distillState as DistillState.Candidates
        assertEquals("理由は選択集合か確定範囲が変わるまで残る", listOf("S005"), after.overlapDeselectedIds)
        assertEquals(afterWiden.items, after.items)
        // 最初の範囲へ戻す操作も、既に初期範囲なら告知を消さない。
        controller.resetRange("S005")
        assertEquals(
            listOf("S005"),
            (state.value.distillState as DistillState.Candidates).overlapDeselectedIds
        )
    }

    @Test
    fun `opening the sheet of a deselected candidate keeps the reason attached to it`() = runTest {
        val state = stateWithNote(adjustableNote())
        val controller = controller(state, FakeAiClient.returning("S005 S006"))
        controller.start()
        advanceUntilIdle()
        controller.applyRange("S006", DistillRangePreset.Sentence)
        controller.closeRangeSheet()

        // 外された候補自身のシートを開く。シートを開くことは選択集合も確定範囲も変えない。
        controller.openRangeSheet("S005")

        val after = state.value.distillState as DistillState.Candidates
        assertEquals("S005", after.rangeSheetCandidateId)
        // **理由は対象候補に紐づいたまま残る。** 主語をUIが決められるよう、外したIDを保つ。
        assertEquals(listOf("S005"), after.overlapDeselectedIds)
        assertFalse(after.rangeSheetItem!!.isSelected)
    }

    @Test
    fun `changing to a different preset clears the overlap notice`() = runTest {
        val state = stateWithNote(adjustableNote())
        val controller = controller(state, FakeAiClient.returning("S005 S006"))
        controller.start()
        advanceUntilIdle()
        controller.applyRange("S006", DistillRangePreset.Sentence)

        controller.applyRange("S006", DistillRangePreset.Clause)

        val after = state.value.distillState as DistillState.Candidates
        assertTrue("確定範囲が変われば契約どおり消える", after.overlapDeselectedIds.isEmpty())
    }

    @Test
    fun `adjusting an unselected candidate does not move other selections`() = runTest {
        val state = stateWithNote(adjustableNote())
        val controller = controller(state, FakeAiClient.returning("S005 S006"))
        controller.start()
        advanceUntilIdle()
        controller.toggleCandidate("S005")

        // 保存対象でないものの編集が取捨を動かしてはいけない。
        controller.applyRange("S005", DistillRangePreset.Sentence)

        val after = state.value.distillState as DistillState.Candidates
        assertTrue(after.items.first { it.id == "S006" }.isSelected)
        assertFalse(after.items.first { it.id == "S005" }.isSelected)
        assertTrue(after.overlapDeselectedIds.isEmpty())
    }

    @Test
    fun `widening past the cumulative limit blocks saving and narrowing releases it`() = runTest {
        val state = stateWithNote(adjustableNote())
        val controller = controller(state, FakeAiClient.returning("S001 S003 S005"))
        controller.start()
        advanceUntilIdle()
        assertTrue((state.value.distillState as DistillState.Candidates).isWithinBoldLimit)

        controller.applyRange("S005", DistillRangePreset.Sentence)

        val widened = state.value.distillState as DistillState.Candidates
        assertFalse(widened.isWithinBoldLimit)
        assertFalse(widened.canSaveSelection)

        controller.resetRange("S005")

        val restored = state.value.distillState as DistillState.Candidates
        assertTrue(restored.isWithinBoldLimit)
        assertTrue(restored.canSaveSelection)
        assertFalse(restored.items.first { it.id == "S005" }.isRangeAdjusted)
    }

    @Test
    fun `narrowing removes the need for the short note exception`() = runTest {
        val state = stateWithNote(exceptionNote())
        val controller = controller(state, FakeAiClient.returning("S003"))
        controller.start()
        advanceUntilIdle()
        val before = state.value.distillState as DistillState.Candidates
        assertFalse(before.isWithinBoldLimit)
        assertTrue(before.isSingleCandidateException)

        // 例外の対象IDは固定したまま、判定に使う範囲だけが確定範囲へ差し替わる。
        controller.applyRange("S003", DistillRangePreset.Term)

        val after = state.value.distillState as DistillState.Candidates
        assertTrue(after.isWithinBoldLimit)
        assertFalse(after.isSingleCandidateException)
        assertEquals("語三", after.items.single().text)
    }

    @Test
    fun `closing the range sheet keeps the confirmed range`() = runTest {
        val state = stateWithNote(adjustableNote())
        val controller = controller(state, FakeAiClient.returning("S005"))
        controller.start()
        advanceUntilIdle()

        controller.openRangeSheet("S005")
        controller.applyRange("S005", DistillRangePreset.Sentence)
        assertEquals("S005", (state.value.distillState as DistillState.Candidates).rangeSheetCandidateId)
        controller.closeRangeSheet()

        // **閉じることが確定。** 破棄の契機はキャンセル・再解析・ノート切替・保存完了の4つだけ。
        val closed = state.value.distillState as DistillState.Candidates
        assertEquals(null, closed.rangeSheetCandidateId)
        val item = closed.items.single()
        assertTrue(item.isRangeAdjusted)
        assertEquals(DistillRangePreset.Sentence, item.currentPreset)
        assertEquals(item.parentText, item.text)
    }

    @Test
    fun `saving is refused while the selection still overlaps`() = runTest {
        val state = stateWithNote(adjustableNote())
        val persistence = FakePersistence()
        val controller = controller(state, FakeAiClient.returning("S005 S006"), persistence)
        controller.start()
        advanceUntilIdle()
        controller.toggleCandidate("S006")
        controller.applyRange("S006", DistillRangePreset.Sentence)
        // 操作からは作れない状態を、状態側から直接作って備えを確かめる。
        // `applyDistillBold` は Main で try なしに呼ばれるので、抜けるとその場のクラッシュになる。
        state.distillWriter.update { current ->
            (current as DistillState.Candidates).copy(
                items = current.items.map { it.copy(isSelected = true) }
            )
        }

        controller.saveSelection()
        advanceUntilIdle()

        assertTrue(state.value.distillState is DistillState.Candidates)
        assertEquals(null, persistence.lastWrite)
    }

    /** 句へ割れる6文のノート。`S005` と `S006` は同じ親文（128..191）の2つの句。 */
    private fun adjustableNote(): String = (1..6).joinToString("\n") { index ->
        "${"あ".repeat(30)}$index、「重要語$index」${"い".repeat(24)}。"
    }

    /** 最重要候補ひとつで上限を超える短いノート。`S003` は語句を内側に持つ句。 */
    private fun exceptionNote(): String =
        "「語一」は短い文です。\n「語二」も短い文です。\n「語三」${"あ".repeat(50)}、${"い".repeat(30)}。"

    private fun withBoldAt(content: String, start: Int, endExclusive: Int): String =
        content.substring(0, start) + "**" + content.substring(start, endExclusive) + "**" +
            content.substring(endExclusive)

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

    private fun stateWithNote(content: String = noteContent()): NoteUiStateStore {
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
