package com.example.newproject

import com.example.newproject.controller.ReadingTraceController
import com.example.newproject.controller.ReunionCardController
import com.example.newproject.data.ReadingTracePersistence
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.ReadingTrace
import com.example.newproject.model.ReadingTraceLimits
import com.example.newproject.model.ReadingVisit
import com.example.newproject.model.withMark
import com.example.newproject.model.REUNION_NONE_TOKEN
import com.example.newproject.model.ReunionKind
import com.example.newproject.model.withVisit
import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.example.newproject.ai.AiTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.example.newproject.model.NoteUiStateStore
import com.example.newproject.fakes.FakeAiClient
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReunionCardControllerTest {

    // 31回目以降もAI俯瞰要約が作り直される。保持件数で判定していた頃は
    // visits.size が 30 で固定になり、古い要約が「最新」として出続けていた。
    @Test
    fun `保持上限に達した後も再訪でAI要約が作り直される`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val cap = ReadingTraceLimits.MAX_VISITS
        persistence.put(
            ReadingTrace(
                vaultRelativePath = "ideas/habit.md",
                noteTitle = "習慣について",
                documentId = null,
                visits = List(cap) { ReadingVisit(it.toLong(), null, 10) },
                aiSummary = "30回時点の古い要約",
                aiSummaryVisitCount = cap,
                totalVisitCount = cap
            )
        )
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, clock, state = state)
        val visits = visitController(persistence, clock)

        // 31回目の閲覧を記録してから再会する
        visits.onNoteOpened("ideas/habit.md", "習慣について", null)
        visits.onReadingProgress(blockIndex = 5, blockFraction = 1f, totalBlocks = 10, sectionTitle = null)
        clock.advance(10_000L)
        visits.flush()
        advanceUntilIdle()
        controller.revealTrace("ideas/habit.md", content = "")
        advanceUntilIdle()

        val card = state.value.readingTraceCard!!
        assertEquals(cap + 1, card.visitCount)
        // 古い要約が「最新」として出ていないこと＝作り直されたこと
        assertEquals(AI_SUMMARY, card.aiSummary)
        assertEquals(cap + 1, persistence.stored("ideas/habit.md")!!.aiSummaryVisitCount)
    }

    // ── 再会（revealTrace）────────────────────────────────────────────────────

    @Test
    fun `no trace means no card`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(FakePersistence(), TestClock(), state = state)

        controller.revealTrace("ideas/habit.md", content = "")
        advanceUntilIdle()

        assertNull(state.value.readingTraceCard)
    }

    // 破損はカードを出さないだけ。ユーザーのノートには一切触れない。
    @Test
    fun `corrupt trace means no card`() = runTest {
        val persistence = FakePersistence().apply { corruptPaths += "ideas/habit.md" }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), state = state)

        controller.revealTrace("ideas/habit.md", content = "")
        advanceUntilIdle()

        assertNull(state.value.readingTraceCard)
    }

    @Test
    fun `blank path does nothing`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(FakePersistence(), TestClock(), state = state)

        controller.revealTrace("", content = "")
        advanceUntilIdle()

        assertNull(state.value.readingTraceCard)
    }

    // 訪問1件では「俯瞰」にならないのでAIを呼ばず、生の痕跡だけを出す。
    @Test
    fun `single visit shows raw trace without calling ai`() = runTest {
        val persistence = FakePersistence().apply { put(storedTrace(count = 1)) }
        val ai = FakeAiClient.returning(AI_SUMMARY)
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), ai, state)

        controller.revealTrace("ideas/habit.md", content = "")
        advanceUntilIdle()

        val card = state.value.readingTraceCard!!
        assertEquals(1, card.visitCount)
        assertEquals("導入", card.lastSectionTitle)
        assertEquals(10, card.lastProgressPercent)
        assertNull(card.aiSummary)
        assertTrue(!card.isSummaryLoading)
        assertEquals(0, ai.generateCalls)
    }

    // AIを待たせないのが要点。生成中でも生の痕跡は先に見えている。
    @Test
    fun `raw trace is visible while the summary is still generating`() = runTest {
        val persistence = FakePersistence().apply { put(storedTrace(count = 2)) }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), FakeAiClient.deferred(), state)

        controller.revealTrace("ideas/habit.md", content = "")
        runCurrent()

        val card = state.value.readingTraceCard!!
        assertEquals(2, card.visitCount)
        assertTrue(card.isSummaryLoading)
        assertNull(card.aiSummary)

        // 生成を待たせたまま終わると runTest が未完了コルーチンを待ってタイムアウトする。
        controller.cancelForNoteChange()
    }

    @Test
    fun `summary is generated once visits accumulated`() = runTest {
        val persistence = FakePersistence().apply { put(storedTrace(count = 2)) }
        val ai = FakeAiClient.returning(AI_SUMMARY)
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), ai, state)

        controller.revealTrace("ideas/habit.md", content = "")
        advanceUntilIdle()

        val card = state.value.readingTraceCard!!
        assertEquals(AI_SUMMARY, card.aiSummary)
        assertTrue(!card.isSummaryLoading)
        assertEquals(1, ai.generateCalls)
    }

    /**
     * **状態確認のキャンセルは握りつぶさず伝播する。**
     *
     * この経路は `AiAvailabilityContractTest` の対応表の #10。
     * **「要約が null」では再throwを観測できない** — 握りつぶしても同じ結果になるため、
     * 専用catchを `null` 返却へ変える変異を緑で通していた。
     * しかも握りつぶすと、キャンセル後に**正常な劣化として後続処理へ進んでしまう。**
     * 観測点を**起動Jobの完了原因**にする。
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `状態確認のキャンセルは痕跡のJobごと伝播する`() = runTest {
        val parent = SupervisorJob()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)
        val persistence = FakePersistence().apply { put(storedTrace(count = 2)) }
        val ai = FakeAiClient.returning(AI_SUMMARY)
        ai.availabilityFailure = { CancellationException("note changed") }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), ai, state, scope = scope)

        controller.revealTrace("ideas/habit.md", content = "")
        val revealJob = parent.children.first()
        advanceUntilIdle()

        assertTrue(
            "キャンセルを握りつぶすとJobが正常終了し、劣化として後続へ進む",
            revealJob.isCancelled
        )
    }

    /**
     * **状態確認が契約違反で投げても、生の痕跡カードは出る。**
     *
     * この経路は `AiAvailabilityContractTest` の対応表の #10。無音の経路なので
     * 観測点は「要約なしのカードが出ること」で、足場のあるここへ置いてある。
     */
    @Test
    fun `状態確認が投げても生の痕跡カードは出る`() = runTest {
        val persistence = FakePersistence().apply { put(storedTrace(count = 2)) }
        val ai = FakeAiClient.returning(AI_SUMMARY)
        ai.availabilityFailure = { IllegalStateException("AICore not bound") }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), ai, state)

        controller.revealTrace("ideas/habit.md", content = "")
        advanceUntilIdle()

        val card = state.value.readingTraceCard!!
        assertNull("要約は出さない", card.aiSummary)
        assertTrue("読み込み表示は下げる", !card.isSummaryLoading)
        assertEquals("生の痕跡は残る", 2, card.visitCount)
    }

    @Test
    fun `summary is written back to the sidecar`() = runTest {
        val persistence = FakePersistence().apply { put(storedTrace(count = 2)) }
        val controller = controller(persistence, TestClock())

        controller.revealTrace("ideas/habit.md", content = "")
        advanceUntilIdle()

        val stored = persistence.stored("ideas/habit.md")!!
        assertEquals(AI_SUMMARY, stored.aiSummary)
        assertEquals(2, stored.aiSummaryVisitCount)
    }

    // 訪問が増えていなければ作り直さない。2回目以降の再会は待たずに出る。
    @Test
    fun `cached summary is reused without calling ai`() = runTest {
        val persistence = FakePersistence().apply {
            put(storedTrace(count = 2, aiSummary = "キャッシュ済み", aiSummaryVisitCount = 2))
        }
        val ai = FakeAiClient.returning(AI_SUMMARY)
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), ai, state)

        controller.revealTrace("ideas/habit.md", content = "")
        advanceUntilIdle()

        assertEquals("キャッシュ済み", state.value.readingTraceCard!!.aiSummary)
        assertEquals(0, ai.generateCalls)
    }

    @Test
    fun `stale summary is regenerated when visits grew`() = runTest {
        val persistence = FakePersistence().apply {
            put(storedTrace(count = 3, aiSummary = "古い要約", aiSummaryVisitCount = 2))
        }
        val ai = FakeAiClient.returning(AI_SUMMARY)
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), ai, state)

        controller.revealTrace("ideas/habit.md", content = "")
        advanceUntilIdle()

        assertEquals(AI_SUMMARY, state.value.readingTraceCard!!.aiSummary)
        assertEquals(1, ai.generateCalls)
        assertEquals(3, persistence.stored("ideas/habit.md")!!.aiSummaryVisitCount)
    }

    // 要約が失敗しても生の痕跡は残す。エラー表示も出さない（意識させない機能なので黙って劣化）。
    @Test
    fun `ai failure keeps the raw trace visible`() = runTest {
        val persistence = FakePersistence().apply { put(storedTrace(count = 2)) }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), FakeAiClient.failingGeneration { AiTimeoutException("タイムアウト") }, state)

        controller.revealTrace("ideas/habit.md", content = "")
        advanceUntilIdle()

        val card = state.value.readingTraceCard!!
        assertEquals(2, card.visitCount)
        assertNull(card.aiSummary)
        assertTrue(!card.isSummaryLoading)
    }

    // 読むたびモデルDLを始めない。未DLなら黙って生の痕跡のまま。
    @Test
    fun `needs download does not generate and keeps the raw trace`() = runTest {
        val persistence = FakePersistence().apply { put(storedTrace(count = 2)) }
        val ai = FakeAiClient.returning(AI_SUMMARY, AiAvailability.NeedsDownload)
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), ai, state)

        controller.revealTrace("ideas/habit.md", content = "")
        advanceUntilIdle()

        val card = state.value.readingTraceCard!!
        assertEquals(2, card.visitCount)
        assertNull(card.aiSummary)
        assertEquals(0, ai.generateCalls)
    }

    @Test
    fun `note change discards a late summary`() = runTest {
        val persistence = FakePersistence().apply { put(storedTrace(count = 2)) }
        val ai = FakeAiClient.deferred()
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), ai, state)

        controller.revealTrace("ideas/habit.md", content = "")
        runCurrent()
        controller.cancelForNoteChange()
        ai.completeAll("後から届いた要約")
        advanceUntilIdle()

        assertNull(state.value.readingTraceCard?.aiSummary)
    }

    @Test
    fun `dismiss marks the card`() = runTest {
        val persistence = FakePersistence().apply { put(storedTrace(count = 1)) }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), state = state)

        controller.revealTrace("ideas/habit.md", content = "")
        advanceUntilIdle()
        controller.dismissCard()

        assertTrue(state.value.readingTraceCard!!.isDismissed)
    }

    // 畳んだあとに要約が届いても開き直さない。
    @Test
    fun `dismissed card stays folded when the summary arrives`() = runTest {
        val persistence = FakePersistence().apply { put(storedTrace(count = 2)) }
        val ai = FakeAiClient.deferred()
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), ai, state)

        controller.revealTrace("ideas/habit.md", content = "")
        runCurrent()
        controller.dismissCard()
        ai.completeAll(AI_SUMMARY)
        advanceUntilIdle()

        val card = state.value.readingTraceCard!!
        assertTrue(card.isDismissed)
        assertEquals(AI_SUMMARY, card.aiSummary)
    }

    // 再会カードは「前回まで」を見せる。今回の読書は離脱時に足されるので混ざらない。
    @Test
    fun `card shows only visits recorded before this reading`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence().apply { put(storedTrace(count = 2)) }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, clock, state = state)
        val visits = visitController(persistence, clock)

        controller.revealTrace("ideas/habit.md", content = "")
        advanceUntilIdle()
        assertEquals(2, state.value.readingTraceCard!!.visitCount)

        visits.onNoteOpened("ideas/habit.md", "習慣について", "doc-1")
        visits.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = "導入")
        clock.advance(10_000L)
        visits.flush()
        advanceUntilIdle()

        assertEquals(2, state.value.readingTraceCard!!.visitCount)
        assertEquals(3, persistence.stored("ideas/habit.md")!!.visits.size)
    }
    // --- 再会カードの種別と印（→ features/reunion_card.md）-------------------

    /** 本文に問いがあれば、俯瞰要約ではなく**原文の1文**が出る。AIは選ぶだけ。 */
    @Test
    fun `本文の問いが選ばれると原文がそのままカードへ出る`() = runTest {
        val question = "この方式で本当に速くなるのだろうか。"
        val persistence = FakePersistence().apply {
            put(storedTrace(count = 2))
        }
        val ai = FakeAiClient(onGenerate = { "R01" })
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), aiClient = ai, state = state)

        controller.revealTrace("ideas/habit.md", content = "$question\nこれは説明である。")
        advanceUntilIdle()

        val card = state.value.readingTraceCard!!
        assertEquals(question, card.aiSummary)
        assertEquals(ReunionKind.Question, card.aiSummaryKind)
        // 原文をそのまま出す契約なので、渡した候補はプロンプトに載っている。
        assertTrue(ai.lastPrompt!!.contains("R01 | $question"))
    }

    /** 問いが無ければ俯瞰要約へ倒れる。**新機能は何も見えない**（正本が認めた挙動）。 */
    @Test
    fun `候補が無ければ俯瞰要約になる`() = runTest {
        val persistence = FakePersistence().apply { put(storedTrace(count = 2)) }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), state = state)

        controller.revealTrace("ideas/habit.md", content = "これは説明だけの本文である。")
        advanceUntilIdle()

        val card = state.value.readingTraceCard!!
        assertEquals(AI_SUMMARY, card.aiSummary)
        assertEquals(ReunionKind.Overview, card.aiSummaryKind)
    }

    /**
     * **空振りを記録しないと、開くたびに同じ候補で生成し直す。**
     * Nano は Mutex 直列なので、待ち時間だけが積み上がる。
     */
    @Test
    fun `空振りは記録され、次に開いても生成し直さない`() = runTest {
        val persistence = FakePersistence().apply { put(storedTrace(count = 2)) }
        val ai = FakeAiClient(onGenerate = { REUNION_NONE_TOKEN })
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), aiClient = ai, state = state)

        controller.revealTrace("ideas/habit.md", content = "これは本当に正しいのだろうか。")
        advanceUntilIdle()

        assertNull("空振りなのに枠が出ている", state.value.readingTraceCard!!.aiSummary)
        val stored = persistence.stored("ideas/habit.md")!!
        assertNull(stored.aiSummary)
        assertEquals(2, stored.aiSummaryVisitCount)

        val callsAfterFirst = ai.generateCalls
        controller.revealTrace("ideas/habit.md", content = "これは本当に正しいのだろうか。")
        advanceUntilIdle()

        assertEquals("空振りの後に生成し直している", callsAfterFirst, ai.generateCalls)
    }

    /** 候補外のIDを返されても拾わない（提示した集合とだけ照合する）。 */
    @Test
    fun `候補外のIDは採らない`() = runTest {
        val persistence = FakePersistence().apply { put(storedTrace(count = 2)) }
        val ai = FakeAiClient(onGenerate = { "R99" })
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), aiClient = ai, state = state)

        controller.revealTrace("ideas/habit.md", content = "これは本当に正しいのだろうか。")
        advanceUntilIdle()

        assertNull(state.value.readingTraceCard!!.aiSummary)
    }

    /** **印があれば生成しない。** 保存済みの内容をそのまま再掲する。 */
    @Test
    fun `印があるノートは生成せず保存済みの内容を再掲する`() = runTest {
        val marked = "前回はこの問いで止まっていた。"
        val persistence = FakePersistence().apply {
            put(
                storedTrace(count = 2).withMark(
                    summary = marked,
                    kind = ReunionKind.Question,
                    atEpochMillis = 500L
                )
            )
        }
        val ai = FakeAiClient(onGenerate = { "R01" })
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), aiClient = ai, state = state)

        controller.revealTrace("ideas/habit.md", content = "別の問いはこれでよいのだろうか。")
        advanceUntilIdle()

        val card = state.value.readingTraceCard!!
        assertEquals(marked, card.aiSummary)
        assertEquals(ReunionKind.Question, card.aiSummaryKind)
        assertTrue(card.isMarked)
        assertEquals("印があるのに生成した", 0, ai.generateCalls)
    }

    /** 押すと保存され、もう一度押すと外れる。**「読んだ」では外れない。** */
    @Test
    fun `印は押すと保存され、もう一度押すと外れる`() = runTest {
        val question = "この方式で本当に速くなるのだろうか。"
        val persistence = FakePersistence().apply { put(storedTrace(count = 2)) }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(
            persistence,
            TestClock(),
            aiClient = FakeAiClient(onGenerate = { "R01" }),
            state = state
        )
        controller.revealTrace("ideas/habit.md", content = question)
        advanceUntilIdle()

        controller.toggleMark()
        advanceUntilIdle()

        assertTrue(state.value.readingTraceCard!!.isMarked)
        assertEquals(question, persistence.stored("ideas/habit.md")!!.markedSummary)

        // 「読んだ」で畳んでも印は外れない（閉じる操作と取り消しは別）。
        controller.dismissCard()
        assertTrue(state.value.readingTraceCard!!.isMarked)

        controller.toggleMark()
        advanceUntilIdle()

        assertFalse(state.value.readingTraceCard!!.isMarked)
        assertNull(persistence.stored("ideas/habit.md")!!.markedSummary)
    }

    /** 出ているものが無ければ印は付かない（内容の無い印を作らない）。 */
    @Test
    fun `枠が空のときは印を付けない`() = runTest {
        val persistence = FakePersistence().apply { put(storedTrace(count = 2)) }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(
            persistence,
            TestClock(),
            aiClient = FakeAiClient(onGenerate = { REUNION_NONE_TOKEN }),
            state = state
        )
        controller.revealTrace("ideas/habit.md", content = "これは本当に正しいのだろうか。")
        advanceUntilIdle()

        controller.toggleMark()
        advanceUntilIdle()

        assertFalse(state.value.readingTraceCard!!.isMarked)
        assertNull(persistence.stored("ideas/habit.md")!!.markedSummary)
    }

    /**
     * **モデルが使えない回を「試した」に数えない。**
     * 数えると、利用可能になっても訪問数が変わるまで枠が出ない。
     */
    @Test
    fun `モデル未取得の回は試行として記録せず、使えるようになれば生成する`() = runTest {
        val persistence = FakePersistence().apply { put(storedTrace(count = 2)) }
        val ai = FakeAiClient(availability = AiAvailability.NeedsDownload, onGenerate = { AI_SUMMARY })
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), aiClient = ai, state = state)

        controller.revealTrace("ideas/habit.md", content = "これは説明である。")
        advanceUntilIdle()

        assertEquals("呼んでいないのに生成した", 0, ai.generateCalls)
        assertNull("試行として記録している", persistence.stored("ideas/habit.md")!!.aiSummaryVisitCount)

        // 訪問数はそのままでも、使えるようになれば次の再会で生成される。
        ai.availability = AiAvailability.Ready
        controller.revealTrace("ideas/habit.md", content = "これは説明である。")
        advanceUntilIdle()

        assertEquals(1, ai.generateCalls)
        assertEquals(AI_SUMMARY, state.value.readingTraceCard!!.aiSummary)
    }

    /** 生成が例外になった回も記録しない（次に開いたとき素直に試し直す）。 */
    @Test
    fun `生成が失敗した回は試行として記録しない`() = runTest {
        val persistence = FakePersistence().apply { put(storedTrace(count = 2)) }
        val ai = FakeAiClient(onGenerate = { error("timeout") })
        val controller = controller(persistence, TestClock(), aiClient = ai)

        controller.revealTrace("ideas/habit.md", content = "これは説明である。")
        advanceUntilIdle()

        assertNull(persistence.stored("ideas/habit.md")!!.aiSummaryVisitCount)
    }

    /**
     * **候補外のIDと空応答は「該当が無い」ではない。** 約束違反なので記録せず試し直す。
     * ここを空振りと同じ扱いにすると、モデルが一度おかしな返しをしただけで枠が止まる。
     */
    @Test
    fun `候補外のIDや空応答は空振りとして記録しない`() = runTest {
        listOf("R99", "", "   ").forEach { response ->
            val persistence = FakePersistence().apply { put(storedTrace(count = 2)) }
            val ai = FakeAiClient(onGenerate = { response })
            val controller = controller(persistence, TestClock(), aiClient = ai)

            controller.revealTrace("ideas/habit.md", content = "これは本当に正しいのだろうか。")
            advanceUntilIdle()

            assertNull(
                "[$response] を空振りとして記録している",
                persistence.stored("ideas/habit.md")!!.aiSummaryVisitCount
            )
        }
    }

    /** 飾りを付けて返しても空振りとして読む（表明語だけ厳密一致だと約束違反に化ける）。 */
    @Test
    fun `飾り付きのNONEも空振りとして読む`() = runTest {
        listOf("NONE", "- NONE", "`NONE`", "none.").forEach { response ->
            val persistence = FakePersistence().apply { put(storedTrace(count = 2)) }
            val ai = FakeAiClient(onGenerate = { response })
            val controller = controller(persistence, TestClock(), aiClient = ai)

            controller.revealTrace("ideas/habit.md", content = "これは本当に正しいのだろうか。")
            advanceUntilIdle()

            val stored = persistence.stored("ideas/habit.md")!!
            assertEquals("[$response] が空振りとして読めていない", 2, stored.aiSummaryVisitCount)
            assertNull(stored.aiSummary)
        }
    }

    /**
     * **空振りは種別 `Overview` として記録し、次の生成契機では俯瞰要約へ倒す。**
     * 候補は本文から決まるので、倒さないと同じ候補が同じ理由で拒否され続ける。
     */
    @Test
    fun `空振りの次の生成契機は俯瞰要約になる`() = runTest {
        val question = "これは本当に正しいのだろうか。"
        val persistence = FakePersistence().apply { put(storedTrace(count = 2)) }
        val ai = FakeAiClient(onGenerate = { if (generateCalls == 1) REUNION_NONE_TOKEN else AI_SUMMARY })
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), aiClient = ai, state = state)

        controller.revealTrace("ideas/habit.md", content = question)
        advanceUntilIdle()
        assertEquals(ReunionKind.Overview, persistence.stored("ideas/habit.md")!!.aiSummaryKind)

        // 訪問が増えて次の生成契機になる
        persistence.put(persistence.stored("ideas/habit.md")!!.withVisit(ReadingVisit(9_000L, "導入", 50)))
        controller.revealTrace("ideas/habit.md", content = question)
        advanceUntilIdle()

        val card = state.value.readingTraceCard!!
        assertEquals(ReunionKind.Overview, card.aiSummaryKind)
        assertEquals(AI_SUMMARY, card.aiSummary)
        // 候補選択ではなく俯瞰要約のプロンプトが使われている
        assertTrue("候補選択のプロンプトが使われた", !ai.lastPrompt!!.contains("R01 | "))
    }

    /**
     * **連打しても、最後に押した状態だけが保存される。**
     *
     * `writeMutex` は同時書き込みを防ぐが要求の到着順は保証しないので、
     * 素早く2回押すと保存順が逆転し、**画面では外れているのにサイドカーには付いている**
     * 状態が作れる。ユーザーの明示的な意図を逆に保存することになる。
     */
    @Test
    fun `印を連打しても最後の状態だけが保存される`() = runTest {
        val question = "この方式で本当に速くなるのだろうか。"
        val persistence = FakePersistence().apply { put(storedTrace(count = 2)) }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(
            persistence,
            TestClock(),
            aiClient = FakeAiClient(onGenerate = { "R01" }),
            state = state
        )
        controller.revealTrace("ideas/habit.md", content = question)
        advanceUntilIdle()

        // 2回押す。IOへ流す前に両方の要求を出すので、1つ目は実行時点で既に古い。
        val savesBefore = persistence.saved.size
        controller.toggleMark()
        controller.toggleMark()
        advanceUntilIdle()

        // **古い要求は書かないこと自体を見る。** 最終状態だけを見ると、
        // 順序が保たれた実行でも同じ結果になり、ガードを外しても落ちない。
        assertEquals(
            "古い要求が書き込んでいる（到着順が逆転すればUIと食い違う）",
            1,
            persistence.saved.size - savesBefore
        )
        assertFalse("画面が最後の操作を反映していない", state.value.readingTraceCard!!.isMarked)
        assertNull(
            "画面は外れているのにサイドカーに印が残っている",
            persistence.stored("ideas/habit.md")!!.markedSummary
        )

        // 3連打も、書くのは最後の1回だけ。
        val savesBeforeTriple = persistence.saved.size
        controller.toggleMark()
        controller.toggleMark()
        controller.toggleMark()
        advanceUntilIdle()

        assertEquals(1, persistence.saved.size - savesBeforeTriple)

        assertTrue(state.value.readingTraceCard!!.isMarked)
        assertEquals(question, persistence.stored("ideas/habit.md")!!.markedSummary)
    }

    /**
     * **別ノートへの操作が、こちらの押下を捨てないこと。**
     *
     * 「最新の要求だけが保存する」を全体で1つの世代にすると、対象が違って競合していないのに
     * 失効する。ノートAで押した直後にノートBで押しただけで、**Aの押下が黙って消える。**
     *
     * **並びが不自然に見えるのは、単一スレッドの試験機で交錯を再現するため。**
     * Bのカードが描かれる前に押しているが、要求が別パスへ向かうことと、
     * それがAの世代を進めてしまうかどうかという点は実機と同じである。
     */
    @Test
    fun `別ノートで印を押しても、先に出した要求は捨てられない`() = runTest {
        val markedA = storedTrace(count = 2, path = "ideas/a.md")
            .withMark(summary = "Aの印", kind = ReunionKind.Question, atEpochMillis = 100L)
        val persistence = FakePersistence().apply {
            put(markedA)
            put(storedTrace(count = 2, path = "ideas/b.md"))
        }
        val controller = controller(persistence, TestClock(), state = NoteUiStateStore(NoteUiState()))

        controller.revealTrace("ideas/a.md", content = "")
        advanceUntilIdle()

        // Aの印を外す要求を出す。まだIOへ流れていない。
        controller.toggleMark()
        // 流れる前にBへ移り、Bでも押す。**Aの要求と競合していない。**
        controller.revealTrace("ideas/b.md", content = "")
        controller.toggleMark()
        advanceUntilIdle()

        assertNull(
            "別ノートの操作でAの要求が捨てられている（押下が黙って消える）",
            persistence.stored("ideas/a.md")!!.markedSummary
        )
    }

}

// --- ヘルパ ---------------------------------------------------------------

private fun TestScope.controller(
    persistence: ReadingTracePersistence,
    clock: TestClock,
    aiClient: AiClient = FakeAiClient.returning(AI_SUMMARY),
    state: NoteUiStateStore = NoteUiStateStore(NoteUiState()),
    vault: FakeVault = FakeVault(),
    scope: CoroutineScope = this,
    persistScope: CoroutineScope = this
): ReunionCardController {
    val dispatcher = StandardTestDispatcher(testScheduler)
    return ReunionCardController(
        scope = scope,
        persistScope = persistScope,
        aiClient = aiClient,
        state = state.readingTraceWriter,
        persistence = persistence,
        currentVaultKey = { vault.key },
        clock = clock::now,
        ioDispatcher = dispatcher,
        // 候補の列挙もテストスケジューラで回す。Dispatchers.Default のままだと
        // runTest の進行と独立に走り、結果の到着順が固定できない。
        scanDispatcher = dispatcher
    )
}

/** 訪問を記録する側。再会カードが「前回まで」の訪問を映すことを確かめるときだけ使う。 */
private fun TestScope.visitController(
    persistence: ReadingTracePersistence,
    clock: TestClock
): ReadingTraceController = ReadingTraceController(
    persistScope = this,
    persistence = persistence,
    currentVaultKey = { FakeVault().key },
    clock = clock::now,
    ioDispatcher = StandardTestDispatcher(testScheduler)
)
