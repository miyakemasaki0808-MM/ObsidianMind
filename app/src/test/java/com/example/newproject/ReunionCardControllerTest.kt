package com.example.newproject

import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.example.newproject.ai.AiTimeoutException
import com.example.newproject.ai.PromptBuilder
import com.example.newproject.controller.ReadingTraceController
import com.example.newproject.controller.ReunionCardController
import com.example.newproject.data.ReadingTracePersistence
import com.example.newproject.domain.ReunionSlot
import com.example.newproject.domain.SummaryCache
import com.example.newproject.domain.markdown.NoteSectionModel
import com.example.newproject.domain.markdown.buildNoteSectionModel
import com.example.newproject.domain.reunionSlot
import com.example.newproject.fakes.FakeAiClient
import com.example.newproject.fakes.InMemorySummaryCache
import com.example.newproject.fakes.passDwell
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.NoteUiStateStore
import com.example.newproject.model.REUNION_NONE_TOKEN
import com.example.newproject.model.ReadingTrace
import com.example.newproject.model.ReadingTraceLimits
import com.example.newproject.model.ReadingVisit
import com.example.newproject.model.ReunionKind
import com.example.newproject.model.state.SummaryState
import com.example.newproject.model.withMark
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
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

// 再会カード（→ features/reunion_card.md）。枠の役目は前回の最後の訪問が途中までか
// 最後までかで分かれる（判断6）。受け入れ条件の表はそのままここのテストに対応する。
@OptIn(ExperimentalCoroutinesApi::class)
class ReunionCardControllerTest {

    // ── 照合の前提 ─────────────────────────────────────────────────────

    @Test
    fun `no trace means no card`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(FakePersistence(), TestClock(), state = state)

        controller.revealTrace(PATH, content = "")
        advanceUntilIdle()

        assertNull(state.value.readingTraceCard)
    }

    // 破損はカードを出さないだけ。ユーザーのノートには一切触れない。
    @Test
    fun `corrupt trace means no card`() = runTest {
        val persistence = FakePersistence().apply { corruptPaths += PATH }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), state = state)

        controller.revealTrace(PATH, content = "")
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

    /**
     * **欄を足したら、値を供給する側まで監査する**（→ 影響面監査）。
     *
     * 読む側（UIの入口）と欄だけ足して供給を忘れると、既定値 false のまま出荷され、
     * **「前回のメモを見る」が一度も出ない。** 型もテストも緑のまま通る形である。
     */
    @Test
    fun `メモがある痕跡のカードは入口を出す`() = runTest {
        val persistence = FakePersistence().apply {
            put(storedTrace(count = 1).copy(memos = listOf(memoOf("残したメモ", at = 100L))))
        }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), state = state)

        controller.revealTrace(PATH, content = "")
        advanceUntilIdle()

        assertTrue("メモがあるのに入口が出ない", state.value.readingTraceCard!!.hasMemos)
    }

    @Test
    fun `メモが無い痕跡のカードは入口を出さない`() = runTest {
        val persistence = FakePersistence().apply { put(storedTrace(count = 1)) }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), state = state)

        controller.revealTrace(PATH, content = "")
        advanceUntilIdle()

        assertTrue("メモが無いのに入口が出た", !state.value.readingTraceCard!!.hasMemos)
    }

    @Test
    fun `dismiss marks the card`() = runTest {
        val persistence = FakePersistence().apply { put(storedTrace(count = 1)) }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), state = state)

        controller.revealTrace(PATH, content = "")
        advanceUntilIdle()
        controller.dismissCard()

        assertTrue(state.value.readingTraceCard!!.isDismissed)
    }

    // 再会カードは「前回まで」を見せる。今回の読書は離脱時に足されるので混ざらない。
    @Test
    fun `card shows only visits recorded before this reading`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence().apply { put(storedTrace(count = 2)) }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, clock, state = state)
        val visits = visitController(persistence, clock)

        controller.revealTrace(PATH, content = "")
        advanceUntilIdle()
        assertEquals(2, state.value.readingTraceCard!!.visitCount)

        visits.onNoteOpened(PATH, "習慣について", "doc-1")
        visits.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = "導入")
        clock.advance(10_000L)
        visits.flush()
        advanceUntilIdle()

        assertEquals(2, state.value.readingTraceCard!!.visitCount)
        assertEquals(3, persistence.stored(PATH)!!.visits.size)
    }

    // ── 離れた訪問の保存との順序（→ features/reunion_card.md「1回目の再会から枠を出す」）──

    /** 離れた訪問の保存は後から走る。**照合がそれより先に読むと、初めて読んだ直後の再会でカードが出ない。** */
    @Test
    fun `離れた訪問の保存を待ってから照合する`() = runTest {
        val persistence = FakePersistence()
        val clock = TestClock()
        val visits = leaveAfterReading(persistence, clock)
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, clock, state = state, awaitVisitSaves = visits::awaitVisitSaves)

        controller.revealTrace(PATH, content = HEADED_BODY)
        advanceUntilIdle()

        assertEquals(1, state.value.readingTraceCard?.visitCount)
    }

    /** 逆向き — 保存を待っている間にノートを替えたら照合は止まり、**保存は止めない。** */
    @Test
    fun `保存待ちの間にノートを替えると照合は止まり、訪問は保存される`() = runTest {
        val persistence = FakePersistence()
        val clock = TestClock()
        val visits = leaveAfterReading(persistence, clock)
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, clock, state = state, awaitVisitSaves = visits::awaitVisitSaves)

        controller.revealTrace(PATH, content = HEADED_BODY)
        controller.cancelForNoteChange()
        advanceUntilIdle()

        assertNull(state.value.readingTraceCard)
        assertEquals(1, persistence.stored(PATH)!!.visits.size)
    }

    /** 保存が失敗しても照合は止まらず、手元の痕跡でカードを出す。**待ちも残らない。** */
    @Test
    fun `保存が失敗しても照合は進み、待つ保存は残らない`() = runTest {
        val persistence = FakePersistence().apply {
            put(traceAt(progress = 20))
            failSave = true
        }
        val clock = TestClock()
        val visits = leaveAfterReading(persistence, clock)
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, clock, state = state, awaitVisitSaves = visits::awaitVisitSaves)

        controller.revealTrace(PATH, content = HEADED_BODY)
        advanceUntilIdle()

        assertEquals(2, state.value.readingTraceCard?.visitCount)
        assertNoVisitSaveLeft(visits)
    }

    @Test
    fun `保存が終われば、待つ保存は残らない`() = runTest {
        val visits = leaveAfterReading(FakePersistence(), TestClock())
        assertEquals("保存を待てる形になっていない", 1, visits.runningVisitSaveCount())
        advanceUntilIdle()

        assertNoVisitSaveLeft(visits)
    }

    // ── 途中まで — 前後の要約と続きから読む ───────────────────────────────

    @Test
    fun `途中までなら前後の要約を作り、続きから読むの送り先を載せる`() = runTest {
        val persistence = FakePersistence().apply { put(traceAt(progress = 20)) }
        val ai = FakeAiClient.returning(PASSAGE)
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), ai, state)

        controller.revealTrace(PATH, content = HEADED_BODY)
        advanceUntilIdle()

        val card = state.value.readingTraceCard!!
        assertEquals(PASSAGE, card.aiSummary)
        assertEquals(ReunionKind.Passage, card.aiSummaryKind)
        assertFalse(card.isSummaryLoading)
        // 20% は第1ブロック。見出し（第0ブロック）が近いので見出しへ送る。
        assertEquals(0, card.resumeBlockIndex)
        assertEquals(1, ai.generateCalls)
        assertTrue("前後の要約のプロンプトではない", ai.lastPrompt!!.contains(PromptBuilder.REUNION_READ_MARKER))
    }

    /** 1回では「俯瞰」にならない、という条件は俯瞰要約のためのものだった。前後の要約は1回目から出す。 */
    @Test
    fun `1回目の再会でも前後の要約を作る`() = runTest {
        val persistence = FakePersistence().apply { put(storedTrace(count = 1)) }
        val ai = FakeAiClient.returning(PASSAGE)
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), ai, state)

        controller.revealTrace(PATH, content = HEADED_BODY)
        advanceUntilIdle()

        assertEquals(PASSAGE, state.value.readingTraceCard!!.aiSummary)
        assertEquals(1, ai.generateCalls)
    }

    /** 前後の要約は入力を鍵に端末内へ置く。**痕跡のファイルは書き換えない。** */
    @Test
    fun `前後の要約は痕跡へ書かず、入力を鍵に保存する`() = runTest {
        val persistence = FakePersistence().apply { put(traceAt(progress = 20)) }
        val cache = InMemorySummaryCache()
        val controller = controller(persistence, TestClock(), passageCache = cache)

        controller.revealTrace(PATH, content = HEADED_BODY)
        advanceUntilIdle()

        assertTrue("痕跡を書き換えている", persistence.saved.isEmpty())
        assertEquals(listOf(PASSAGE), cache.entries.values.toList())
    }

    /**
     * 境目の印は入力の区切りで、本文には無い。**モデルが復唱しても、カード・端末内の保存・印の保存の
     * どこにも残さない**（→ features/reunion_card.md 判断6「前後の要約」）。
     */
    @Test
    fun `生成が境目の印を復唱しても、カードにも保存にも印にも残さない`() = runTest {
        val persistence = FakePersistence().apply { put(traceAt(progress = 20)) }
        val cache = InMemorySummaryCache()
        val echoed = "直前は導入の説明を読んでいた。${PromptBuilder.REUNION_READ_MARKER} この先は具体例に入る。"
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(
            persistence, TestClock(), FakeAiClient.returning(echoed), state, passageCache = cache
        )

        controller.revealTrace(PATH, content = HEADED_BODY)
        advanceUntilIdle()
        controller.toggleMark(state.slot())
        advanceUntilIdle()

        assertEquals(PASSAGE, state.value.readingTraceCard!!.aiSummary)
        assertEquals(listOf(PASSAGE), cache.entries.values.toList())
        assertEquals(PASSAGE, persistence.stored(PATH)!!.markedSummary)
    }

    // AIを待たせないのが要点。生成中でも見出しの1文は先に見えている。
    @Test
    fun `raw trace is visible while the passage is still generating`() = runTest {
        val persistence = FakePersistence().apply { put(storedTrace(count = 2)) }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), FakeAiClient.deferred(), state)

        controller.revealTrace(PATH, content = "")
        runCurrent()

        val card = state.value.readingTraceCard!!
        assertEquals(2, card.visitCount)
        assertTrue(card.isSummaryLoading)
        assertNull(card.aiSummary)

        // 生成を待たせたまま終わると runTest が未完了コルーチンを待ってタイムアウトする。
        controller.cancelForNoteChange()
    }

    @Test
    fun `note change discards a late passage`() = runTest {
        val persistence = FakePersistence().apply { put(storedTrace(count = 2)) }
        val ai = FakeAiClient.deferred()
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), ai, state)

        controller.revealTrace(PATH, content = "")
        runCurrent()
        controller.cancelForNoteChange()
        ai.completeAll("後から届いた要約")
        advanceUntilIdle()

        assertNull(state.value.readingTraceCard?.aiSummary)
    }

    // 畳んだあとに届いても開き直さない。
    @Test
    fun `dismissed card stays folded when the passage arrives`() = runTest {
        val persistence = FakePersistence().apply { put(storedTrace(count = 2)) }
        val ai = FakeAiClient.deferred()
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), ai, state)

        controller.revealTrace(PATH, content = "")
        runCurrent()
        controller.dismissCard()
        ai.completeAll(PASSAGE)
        advanceUntilIdle()

        val card = state.value.readingTraceCard!!
        assertTrue(card.isDismissed)
        assertEquals(PASSAGE, card.aiSummary)
    }

    // 要約が失敗しても見出しの1文は残す。エラー表示も出さない（意識させない機能なので黙って劣化）。
    // **続きから読むはAIを使わないので残る。**
    @Test
    fun `途中までで生成が失敗しても、見出しと続きから読むは残す`() = runTest {
        val persistence = FakePersistence().apply { put(traceAt(progress = 20)) }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(
            persistence,
            TestClock(),
            FakeAiClient.failingGeneration { AiTimeoutException("タイムアウト") },
            state
        )

        controller.revealTrace(PATH, content = "")
        advanceUntilIdle()

        val card = state.value.readingTraceCard!!
        assertNull(card.aiSummary)
        assertFalse(card.isSummaryLoading)
        assertEquals(0, card.resumeBlockIndex)
    }

    // 読むたびモデルDLを始めない。未DLなら黙って見出しの1文のまま。
    @Test
    fun `途中までで未取得なら生成せず、続きから読むは残す`() = runTest {
        val persistence = FakePersistence().apply { put(traceAt(progress = 20)) }
        val ai = FakeAiClient.returning(PASSAGE, AiAvailability.NeedsDownload)
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), ai, state)

        controller.revealTrace(PATH, content = "")
        advanceUntilIdle()

        val card = state.value.readingTraceCard!!
        assertNull(card.aiSummary)
        assertEquals(0, card.resumeBlockIndex)
        assertEquals(0, ai.generateCalls)
    }

    /** 生成は決定的なので、同じ入力の保存済みはいま作っても同じ文になる。端末AIの状態を問わず出す。 */
    @Test
    fun `保存済みの前後の要約は端末AIが使えなくても出す`() = runTest {
        val persistence = FakePersistence().apply { put(traceAt(progress = 20)) }
        val cache = InMemorySummaryCache()
        controller(persistence, TestClock(), passageCache = cache).revealTrace(PATH, content = "")
        advanceUntilIdle()

        val ai = FakeAiClient.returning("使われない", AiAvailability.NeedsDownload)
        val state = NoteUiStateStore(NoteUiState())
        controller(persistence, TestClock(), ai, state, passageCache = cache).revealTrace(PATH, content = "")
        advanceUntilIdle()

        assertEquals(PASSAGE, state.value.readingTraceCard!!.aiSummary)
        assertEquals(0, ai.generateCalls)
    }

    @Test
    fun `ブロックが無い本文では送り先も前後の要約も無い`() = runTest {
        val persistence = FakePersistence().apply { put(traceAt(progress = 20)) }
        val ai = FakeAiClient.returning(PASSAGE)
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), ai, state, body = "")

        controller.revealTrace(PATH, content = "")
        advanceUntilIdle()

        val card = state.value.readingTraceCard!!
        assertNull(card.resumeBlockIndex)
        assertNull(card.aiSummary)
        assertFalse(card.isSummaryLoading)
        assertEquals(0, ai.generateCalls)
    }

    // ── 位置は「前回いちばん先まで読んだところ」（設計レビューで固定した受理条件）──────────

    /**
     * **第9ブロックまで読み、第2ブロックへ戻って離れた。** 記録は巻き戻しで下がらないので、
     * 読み進めたところは第9ブロックのまま。前後の要約も送り先もそこを指す。**訪問記録から通しで見る。**
     */
    @Test
    fun `第9ブロックから第2ブロックへ戻って離れても、第9ブロックの前後を出す`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        readThenScrollBack(persistence, clock, deepestBlock = 8)

        val ai = FakeAiClient.returning(PASSAGE)
        val state = NoteUiStateStore(NoteUiState())
        controller(persistence, clock, ai, state, body = PLAIN_BODY).revealTrace(PATH, content = PLAIN_BODY)
        advanceUntilIdle()

        val card = state.value.readingTraceCard!!
        assertEquals(90, card.lastProgressPercent)
        assertEquals(7, card.resumeBlockIndex)
        val body = ai.lastPrompt!!.substringAfter("Note title:")
        val before = body.substringBefore(PromptBuilder.REUNION_READ_MARKER).trimEnd()
        val after = body.substringAfter(PromptBuilder.REUNION_READ_MARKER)
        assertTrue("前が第9ブロックで終わっていない", before.endsWith("段落8の本文。"))
        assertTrue("後ろに第10ブロックが無い", after.contains("段落9の本文。"))
        assertFalse("戻った第2ブロックを境目にしている", after.contains("段落1の本文。"))
    }

    /** **末尾まで読み、第2節へ戻って離れた。** 読了として振り返る側になり、続きから読むは出ない。 */
    @Test
    fun `末尾から第2節へ戻って離れたら読了になり、続きから読むを出さない`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        readThenScrollBack(persistence, clock, deepestBlock = 9)

        val ai = FakeAiClient.returning(PASSAGE)
        val state = NoteUiStateStore(NoteUiState())
        controller(persistence, clock, ai, state, body = PLAIN_BODY).revealTrace(PATH, content = PLAIN_BODY)
        advanceUntilIdle()

        val card = state.value.readingTraceCard!!
        assertEquals(100, card.lastProgressPercent)
        assertNull(card.resumeBlockIndex)
        assertEquals("読了なのに前後の要約を作った", 0, ai.generateCalls)
    }

    // ── 途中までは旧仕様の保存を使わない（設計レビューで固定した受理条件）────────────────

    /**
     * 旧仕様は途中・読了を分けずに選別していたので、途中までのノートにも
     * 問い・古い前提・俯瞰要約・空振りが「訪問数の合った試行済み」として残っている。
     * **4種すべてで選別0回・前後の要約1回。訪問を足さずに再表示すると追加0回。**
     */
    @Test
    fun `途中までは旧仕様の問い・古い前提・俯瞰要約・空振りを使わず前後の要約を作る`() = runTest {
        listOf(
            "古い問いはこれでよいのか。" to ReunionKind.Question,
            "2025年時点の最新は v1.2.3 である。" to ReunionKind.Staleness,
            "これまで2回開いて、いずれも前半で止まっています。" to ReunionKind.Overview,
            null to ReunionKind.Overview
        ).forEach { (text, kind) ->
            val persistence = FakePersistence().apply {
                put(traceAt(progress = 50, aiSummary = text, kind = kind, attemptedAt = 2))
            }
            val ai = FakeAiClient.returning(PASSAGE)
            val state = NoteUiStateStore(NoteUiState())
            val controller = controller(persistence, TestClock(), ai, state)

            controller.revealTrace(PATH, content = "これは本当に正しいのだろうか。")
            advanceUntilIdle()

            val label = "[$kind / $text]"
            assertEquals("$label 前後の要約が1回でない", 1, ai.generateCalls)
            assertTrue("$label 選別を呼んでいる", ai.prompts.all { it.contains(PromptBuilder.REUNION_READ_MARKER) })
            assertEquals(label, PASSAGE, state.value.readingTraceCard!!.aiSummary)
            assertEquals(label, ReunionKind.Passage, state.value.readingTraceCard!!.aiSummaryKind)

            controller.revealTrace(PATH, content = "これは本当に正しいのだろうか。")
            advanceUntilIdle()
            assertEquals("$label 訪問を足していないのに作り直した", 1, ai.generateCalls)
        }
    }

    /** 印は最優先。旧種別の印でも文を変えず、生成しない。続きから読むは出る。 */
    @Test
    fun `途中までで印があれば文を変えず生成しない`() = runTest {
        val marked = "前回はこの問いで止まっていた。"
        val persistence = FakePersistence().apply {
            put(traceAt(progress = 50).withMark(summary = marked, kind = ReunionKind.Question, atEpochMillis = 500L))
        }
        val ai = FakeAiClient.returning(PASSAGE)
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), ai, state)

        controller.revealTrace(PATH, content = "")
        advanceUntilIdle()

        val card = state.value.readingTraceCard!!
        assertEquals(marked, card.aiSummary)
        assertTrue(card.isMarked)
        assertTrue("続きから読むが無い", card.resumeBlockIndex != null)
        assertEquals("印があるのに生成した", 0, ai.generateCalls)
    }

    // ── 通常の読書を挟む一巡（設計レビューで固定した受理条件）──────────────────────────

    /**
     * **前後の要約を作る → 同じ位置を10秒以上読む → 離れて訪問を保存 → 再会。**
     * 訪問数は増えるが、読み進めたところも前後の本文も同じなので作り直さない。
     */
    @Test
    fun `同じ位置を読んで訪問が増えても、前後の要約は作り直さない`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence().apply { put(traceAt(progress = 20)) }
        val ai = FakeAiClient.returning(PASSAGE)
        val controller = controller(persistence, clock, ai)

        controller.revealTrace(PATH, content = "")
        advanceUntilIdle()
        readOnce(persistence, clock, blockIndex = 1)
        assertEquals(3, persistence.stored(PATH)!!.totalVisitCount)

        controller.revealTrace(PATH, content = "")
        advanceUntilIdle()

        assertEquals("同じ入力なのに作り直した", 1, ai.generateCalls)
    }

    @Test
    fun `先へ読み進めた一巡では、前後の要約を1回作り直す`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence().apply { put(traceAt(progress = 20)) }
        val ai = FakeAiClient.returning(PASSAGE)
        val controller = controller(persistence, clock, ai)

        controller.revealTrace(PATH, content = "")
        advanceUntilIdle()
        readOnce(persistence, clock, blockIndex = 5)

        controller.revealTrace(PATH, content = "")
        advanceUntilIdle()

        assertEquals(2, ai.generateCalls)
    }

    // ── 端末AIの状態確認（AiAvailabilityContractTest の対応表）─────────────────

    /**
     * **状態確認のキャンセルは握りつぶさず伝播する。** 途中までと読了で呼び出しが別なので両方で見る。
     *
     * **「要約が null」では再throwを観測できない** — 握りつぶしても同じ結果になるため、
     * 専用catchを `null` 返却へ変える変異を緑で通していた。観測点を**起動Jobの完了原因**にする。
     */
    @Test
    fun `状態確認のキャンセルは痕跡のJobごと伝播する`() = runTest {
        listOf(traceAt(progress = 20), finishedTrace()).forEach { trace ->
            val parent = SupervisorJob()
            val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)
            val persistence = FakePersistence().apply { put(trace) }
            val ai = FakeAiClient.returning(PASSAGE)
            ai.availabilityFailure = { CancellationException("note changed") }
            val controller = controller(persistence, TestClock(), ai, scope = scope)

            controller.revealTrace(PATH, content = QUESTION)
            val revealJob = parent.children.first()
            advanceUntilIdle()

            assertTrue(
                "[${trace.visits.last().progressPercent}%] キャンセルを握りつぶすとJobが正常終了し、劣化として後続へ進む",
                revealJob.isCancelled
            )
        }
    }

    /** **状態確認が契約違反で投げても、見出しの1文のカードは出る。** 途中までと読了の両方で見る。 */
    @Test
    fun `状態確認が投げても生の痕跡カードは出る`() = runTest {
        listOf(traceAt(progress = 20), finishedTrace()).forEach { trace ->
            val persistence = FakePersistence().apply { put(trace) }
            val ai = FakeAiClient.returning(PASSAGE)
            ai.availabilityFailure = { IllegalStateException("AICore not bound") }
            val state = NoteUiStateStore(NoteUiState())
            val controller = controller(persistence, TestClock(), ai, state)

            controller.revealTrace(PATH, content = QUESTION)
            advanceUntilIdle()

            val label = "[${trace.visits.last().progressPercent}%]"
            val card = state.value.readingTraceCard!!
            assertNull("$label 枠の1件は出さない", card.aiSummary)
            assertTrue("$label 読み込み表示は下げる", !card.isSummaryLoading)
            assertEquals("$label 生の痕跡は残る", 2, card.visitCount)
        }
    }

    // ── 最後まで — 問い・古い前提か、ノートの要約 ───────────────────────────

    /** 本文に問いがあれば**原文の1文**が出る。AIは選ぶだけ。 */
    @Test
    fun `読了で本文の問いが選ばれると原文がそのままカードへ出る`() = runTest {
        val persistence = FakePersistence().apply { put(finishedTrace()) }
        val ai = FakeAiClient(onGenerate = { "R01" })
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), aiClient = ai, state = state)

        controller.revealTrace(PATH, content = "$QUESTION\nこれは説明である。")
        advanceUntilIdle()

        val card = state.value.readingTraceCard!!
        assertEquals(QUESTION, card.aiSummary)
        assertEquals(ReunionKind.Question, card.aiSummaryKind)
        assertNull("読了なのに続きから読むがある", card.resumeBlockIndex)
        // 原文をそのまま出す契約なので、渡した候補はプロンプトに載っている。
        assertTrue(ai.lastPrompt!!.contains("R01 | $QUESTION"))
        assertEquals(QUESTION, persistence.stored(PATH)!!.aiSummary)
        assertEquals(2, persistence.stored(PATH)!!.aiSummaryVisitCount)
    }

    /** 1回目の再会でも選ぶ。2回以上という条件は俯瞰要約のためのものだった。 */
    @Test
    fun `読了なら1回目の再会でも選別する`() = runTest {
        val persistence = FakePersistence().apply { put(finishedTrace(count = 1)) }
        val ai = FakeAiClient(onGenerate = { "R01" })
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), aiClient = ai, state = state)

        controller.revealTrace(PATH, content = QUESTION)
        advanceUntilIdle()

        assertEquals(QUESTION, state.value.readingTraceCard!!.aiSummary)
    }

    /** 候補が無ければ Nano を呼ばず、痕跡にも書かない。枠はノートの要約になる（画面が要約の状態から読む）。 */
    @Test
    fun `読了で候補が無ければ生成せず、痕跡にも書かない`() = runTest {
        val persistence = FakePersistence().apply { put(finishedTrace()) }
        val ai = FakeAiClient(onGenerate = { "R01" })
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), aiClient = ai, state = state)

        controller.revealTrace(PATH, content = "これは説明だけの本文である。")
        advanceUntilIdle()

        val card = state.value.readingTraceCard!!
        assertNull(card.aiSummary)
        assertFalse(card.isSummaryLoading)
        assertEquals(0, ai.generateCalls)
        assertTrue(persistence.saved.isEmpty())
    }

    /**
     * **空振りを記録しないと、開くたびに同じ候補で生成し直す。**
     * Nano は Mutex 直列なので、待ち時間だけが積み上がる。
     */
    @Test
    fun `読了の空振りは記録され、次に開いても選び直さない`() = runTest {
        val persistence = FakePersistence().apply { put(finishedTrace()) }
        val ai = FakeAiClient(onGenerate = { REUNION_NONE_TOKEN })
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), aiClient = ai, state = state)

        controller.revealTrace(PATH, content = QUESTION)
        advanceUntilIdle()

        assertNull("空振りなのに枠の1件が出ている", state.value.readingTraceCard!!.aiSummary)
        val stored = persistence.stored(PATH)!!
        assertNull(stored.aiSummary)
        assertEquals(ReunionKind.Overview, stored.aiSummaryKind)
        assertEquals(2, stored.aiSummaryVisitCount)

        controller.revealTrace(PATH, content = QUESTION)
        advanceUntilIdle()

        assertEquals("空振りの後に選び直している", 1, ai.generateCalls)
    }

    /** 保存済みの問いは、訪問数が合えば再利用する（途中までと読了の対照）。 */
    @Test
    fun `読了で訪問数の合った問いは再利用する`() = runTest {
        val persistence = FakePersistence().apply {
            put(finishedTrace(aiSummary = "保存済みの問いはこれか。", kind = ReunionKind.Question, attemptedAt = 2))
        }
        val ai = FakeAiClient(onGenerate = { "R01" })
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), aiClient = ai, state = state)

        controller.revealTrace(PATH, content = QUESTION)
        advanceUntilIdle()

        assertEquals("保存済みの問いはこれか。", state.value.readingTraceCard!!.aiSummary)
        assertEquals(0, ai.generateCalls)
    }

    /** 文を持つ `Overview` は旧仕様の俯瞰要約。**出さずに選び直す。** */
    @Test
    fun `読了で旧仕様の俯瞰要約は出さずに選び直す`() = runTest {
        val persistence = FakePersistence().apply {
            put(finishedTrace(aiSummary = "これまで2回開いています。", kind = ReunionKind.Overview, attemptedAt = 2))
        }
        val ai = FakeAiClient(onGenerate = { "R01" })
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), aiClient = ai, state = state)

        controller.revealTrace(PATH, content = QUESTION)
        advanceUntilIdle()

        assertEquals(QUESTION, state.value.readingTraceCard!!.aiSummary)
        assertEquals(1, ai.generateCalls)
    }

    @Test
    fun `読了で候補が無く旧仕様の俯瞰要約しか無ければ、枠の1件は出さない`() = runTest {
        val persistence = FakePersistence().apply {
            put(finishedTrace(aiSummary = "これまで2回開いています。", kind = ReunionKind.Overview, attemptedAt = 2))
        }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), state = state)

        controller.revealTrace(PATH, content = "これは説明だけの本文である。")
        advanceUntilIdle()

        assertNull(state.value.readingTraceCard!!.aiSummary)
    }

    // 31回目以降も選び直される。保持件数で判定していた頃は visits.size が 30 で固定になり、
    // 古い結果が「最新」として出続けていた。
    @Test
    fun `保持上限に達した後も再訪で選び直す`() = runTest {
        val clock = TestClock()
        val cap = ReadingTraceLimits.MAX_VISITS
        val persistence = FakePersistence().apply {
            put(
                ReadingTrace(
                    vaultRelativePath = PATH,
                    noteTitle = "習慣について",
                    documentId = null,
                    visits = List(cap) { ReadingVisit(it.toLong(), null, 100) },
                    aiSummary = "30回時点の古い問いはこれか。",
                    aiSummaryVisitCount = cap,
                    aiSummaryKind = ReunionKind.Question,
                    totalVisitCount = cap
                )
            )
        }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, clock, FakeAiClient(onGenerate = { "R01" }), state)
        val visits = visitController(persistence, clock)

        // 31回目の閲覧を末尾まで記録してから再会する
        visits.onNoteOpened(PATH, "習慣について", null)
        visits.onReadingProgress(blockIndex = 9, blockFraction = 1f, totalBlocks = 10, sectionTitle = null)
        clock.advance(10_000L)
        visits.flush()
        advanceUntilIdle()
        controller.revealTrace(PATH, content = QUESTION)
        advanceUntilIdle()

        val card = state.value.readingTraceCard!!
        assertEquals(cap + 1, card.visitCount)
        assertEquals(QUESTION, card.aiSummary)
        assertEquals(cap + 1, persistence.stored(PATH)!!.aiSummaryVisitCount)
    }

    /** 候補外のIDを返されても拾わない（提示した集合とだけ照合する）。 */
    @Test
    fun `候補外のIDは採らない`() = runTest {
        val persistence = FakePersistence().apply { put(finishedTrace()) }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), FakeAiClient(onGenerate = { "R99" }), state)

        controller.revealTrace(PATH, content = QUESTION)
        advanceUntilIdle()

        assertNull(state.value.readingTraceCard!!.aiSummary)
    }

    /**
     * **モデルが使えない回を「試した」に数えない。**
     * 数えると、利用可能になっても訪問数が変わるまで枠が出ない。
     */
    @Test
    fun `モデル未取得の回は試行として記録せず、使えるようになれば選ぶ`() = runTest {
        val persistence = FakePersistence().apply { put(finishedTrace()) }
        val ai = FakeAiClient(availability = AiAvailability.NeedsDownload, onGenerate = { "R01" })
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), aiClient = ai, state = state)

        controller.revealTrace(PATH, content = QUESTION)
        advanceUntilIdle()

        assertEquals("呼んでいないのに生成した", 0, ai.generateCalls)
        assertNull("試行として記録している", persistence.stored(PATH)!!.aiSummaryVisitCount)

        // 訪問数はそのままでも、使えるようになれば次の再会で選ばれる。
        ai.availability = AiAvailability.Ready
        controller.revealTrace(PATH, content = QUESTION)
        advanceUntilIdle()

        assertEquals(1, ai.generateCalls)
        assertEquals(QUESTION, state.value.readingTraceCard!!.aiSummary)
    }

    /** 生成が例外になった回も記録しない（次に開いたとき素直に試し直す）。 */
    @Test
    fun `生成が失敗した回は試行として記録しない`() = runTest {
        val persistence = FakePersistence().apply { put(finishedTrace()) }
        val controller = controller(persistence, TestClock(), FakeAiClient(onGenerate = { error("timeout") }))

        controller.revealTrace(PATH, content = QUESTION)
        advanceUntilIdle()

        assertNull(persistence.stored(PATH)!!.aiSummaryVisitCount)
    }

    /**
     * **候補外のIDと空応答は「該当が無い」ではない。** 約束違反なので記録せず試し直す。
     * ここを空振りと同じ扱いにすると、モデルが一度おかしな返しをしただけで枠が止まる。
     */
    @Test
    fun `候補外のIDや空応答は空振りとして記録しない`() = runTest {
        listOf("R99", "", "   ").forEach { response ->
            val persistence = FakePersistence().apply { put(finishedTrace()) }
            val controller = controller(persistence, TestClock(), FakeAiClient(onGenerate = { response }))

            controller.revealTrace(PATH, content = QUESTION)
            advanceUntilIdle()

            assertNull("[$response] を空振りとして記録している", persistence.stored(PATH)!!.aiSummaryVisitCount)
        }
    }

    /** 飾りを付けて返しても空振りとして読む（表明語だけ厳密一致だと約束違反に化ける）。 */
    @Test
    fun `飾り付きのNONEも空振りとして読む`() = runTest {
        listOf("NONE", "- NONE", "`NONE`", "none.").forEach { response ->
            val persistence = FakePersistence().apply { put(finishedTrace()) }
            val controller = controller(persistence, TestClock(), FakeAiClient(onGenerate = { response }))

            controller.revealTrace(PATH, content = QUESTION)
            advanceUntilIdle()

            val stored = persistence.stored(PATH)!!
            assertEquals("[$response] が空振りとして読めていない", 2, stored.aiSummaryVisitCount)
            assertNull(stored.aiSummary)
        }
    }

    // ── 印（「まだ考えたい」）─────────────────────────────────────────────

    /** **印があれば生成しない。** 保存済みの内容をそのまま再掲する。 */
    @Test
    fun `読了で印があるノートは生成せず保存済みの内容を再掲する`() = runTest {
        val marked = "前回はこの問いで止まっていた。"
        val persistence = FakePersistence().apply {
            put(finishedTrace().withMark(summary = marked, kind = ReunionKind.Question, atEpochMillis = 500L))
        }
        val ai = FakeAiClient(onGenerate = { "R01" })
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), aiClient = ai, state = state)

        controller.revealTrace(PATH, content = "別の問いはこれでよいのだろうか。")
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
        val persistence = FakePersistence().apply { put(finishedTrace()) }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), FakeAiClient(onGenerate = { "R01" }), state)
        controller.revealTrace(PATH, content = QUESTION)
        advanceUntilIdle()

        controller.toggleMark(state.slot())
        advanceUntilIdle()

        assertTrue(state.value.readingTraceCard!!.isMarked)
        assertEquals(QUESTION, persistence.stored(PATH)!!.markedSummary)

        // 「読んだ」で畳んでも印は外れない（閉じる操作と取り消しは別）。
        controller.dismissCard()
        assertTrue(state.value.readingTraceCard!!.isMarked)

        controller.toggleMark(state.slot())
        advanceUntilIdle()

        assertFalse(state.value.readingTraceCard!!.isMarked)
        assertNull(persistence.stored(PATH)!!.markedSummary)
    }

    @Test
    fun `前後の要約に印を付けると、種別 Passage で保存する`() = runTest {
        val persistence = FakePersistence().apply { put(traceAt(progress = 20)) }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), state = state)
        controller.revealTrace(PATH, content = "")
        advanceUntilIdle()

        controller.toggleMark(state.slot())
        advanceUntilIdle()

        val stored = persistence.stored(PATH)!!
        assertEquals(PASSAGE, stored.markedSummary)
        assertEquals(ReunionKind.Passage, stored.markedKind)
    }

    /**
     * **ノートの要約はカードの状態に無い。** 表示と同じ純関数で求めた中身を渡さないと、
     * 見えていない文に印が付く。押した時点の先頭1文と種別 `Overview` を保存する。
     */
    @Test
    fun `ノートの要約に印を付けると、枠に出ていた先頭1文を保存する`() = runTest {
        val persistence = FakePersistence().apply { put(finishedTrace()) }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), state = state)
        controller.revealTrace(PATH, content = "これは説明だけの本文である。")
        advanceUntilIdle()

        controller.toggleMark(state.slot(SummaryState.Success("要約の1文。次の文。")))
        advanceUntilIdle()

        val stored = persistence.stored(PATH)!!
        assertEquals("要約の1文。", stored.markedSummary)
        assertEquals(ReunionKind.Overview, stored.markedKind)
        val card = state.value.readingTraceCard!!
        assertTrue(card.isMarked)
        assertEquals("印の付いた枠が押した文を出し続けない", "要約の1文。", card.aiSummary)
    }

    /** 出ているものが無ければ印は付かない（内容の無い印を作らない）。 */
    @Test
    fun `枠が空のときは印を付けない`() = runTest {
        val persistence = FakePersistence().apply { put(finishedTrace()) }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), FakeAiClient(onGenerate = { REUNION_NONE_TOKEN }), state)
        controller.revealTrace(PATH, content = QUESTION)
        advanceUntilIdle()

        controller.toggleMark(state.slot(SummaryState.AiUnavailable))
        advanceUntilIdle()

        assertFalse(state.value.readingTraceCard!!.isMarked)
        assertNull(persistence.stored(PATH)!!.markedSummary)
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
        val persistence = FakePersistence().apply { put(finishedTrace()) }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), FakeAiClient(onGenerate = { "R01" }), state)
        controller.revealTrace(PATH, content = QUESTION)
        advanceUntilIdle()

        // 2回押す。IOへ流す前に両方の要求を出すので、1つ目は実行時点で既に古い。
        val savesBefore = persistence.saved.size
        controller.toggleMark(state.slot())
        controller.toggleMark(state.slot())
        advanceUntilIdle()

        // **古い要求は書かないこと自体を見る。** 最終状態だけを見ると、
        // 順序が保たれた実行でも同じ結果になり、ガードを外しても落ちない。
        assertEquals(
            "古い要求が書き込んでいる（到着順が逆転すればUIと食い違う）",
            1,
            persistence.saved.size - savesBefore
        )
        assertFalse("画面が最後の操作を反映していない", state.value.readingTraceCard!!.isMarked)
        assertNull("画面は外れているのにサイドカーに印が残っている", persistence.stored(PATH)!!.markedSummary)

        // 3連打も、書くのは最後の1回だけ。
        val savesBeforeTriple = persistence.saved.size
        controller.toggleMark(state.slot())
        controller.toggleMark(state.slot())
        controller.toggleMark(state.slot())
        advanceUntilIdle()

        assertEquals(1, persistence.saved.size - savesBeforeTriple)
        assertTrue(state.value.readingTraceCard!!.isMarked)
        assertEquals(QUESTION, persistence.stored(PATH)!!.markedSummary)
    }

    /**
     * **解析を待っている間に印を外しても、解析結果が届いて印が戻らない**（実装レビューで固定した受理条件）。
     *
     * 照合した時点の痕跡には印がある。解析の後にその痕跡からカードを組み直すと、
     * 外した印が画面の上だけ戻り、保存先と食い違う。同じノートのままなので要求の世代では防げない。
     * 通常の本文とブロックの無い本文の両方で見る。畳んだ状態も開き直さない。
     */
    @Test
    fun `解析待ちの間に外した印は、解析結果が届いても戻らない`() = runTest {
        listOf(HEADED_BODY, "").forEach { body ->
            val persistence = FakePersistence().apply {
                put(traceAt(progress = 50).withMark(summary = "前回の文。", kind = ReunionKind.Question, atEpochMillis = 500L))
            }
            val model = CompletableDeferred<NoteSectionModel>()
            val state = NoteUiStateStore(NoteUiState())
            val controller = controller(persistence, TestClock(), state = state, awaitSectionModel = { model.await() })

            controller.revealTrace(PATH, content = "")
            advanceUntilIdle()
            assertTrue("[$body] 印つきのカードが出ていない", state.value.readingTraceCard!!.isMarked)

            controller.toggleMark(state.slot())
            controller.dismissCard()
            advanceUntilIdle()
            model.complete(buildNoteSectionModel(body))
            advanceUntilIdle()

            val card = state.value.readingTraceCard!!
            assertFalse("[${body.take(4)}] 外した印が画面に戻った", card.isMarked)
            assertTrue("[${body.take(4)}] 畳んだカードが開き直った", card.isDismissed)
            assertNull("[${body.take(4)}] 保存先に印が残っている", persistence.stored(PATH)!!.markedSummary)
        }
    }

    @Test
    fun `解析待ちの間に外して付け直すと、最後の印を保持する`() = runTest {
        val persistence = FakePersistence().apply {
            put(traceAt(progress = 50).withMark(summary = "前回の文。", kind = ReunionKind.Question, atEpochMillis = 500L))
        }
        val model = CompletableDeferred<NoteSectionModel>()
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), state = state, awaitSectionModel = { model.await() })

        controller.revealTrace(PATH, content = "")
        advanceUntilIdle()
        controller.toggleMark(state.slot())
        controller.toggleMark(state.slot())
        advanceUntilIdle()
        model.complete(buildNoteSectionModel(HEADED_BODY))
        advanceUntilIdle()

        val card = state.value.readingTraceCard!!
        assertTrue(card.isMarked)
        assertEquals("前回の文。", card.aiSummary)
        assertTrue("送り先が合流していない", card.resumeBlockIndex != null)
        assertEquals("前回の文。", persistence.stored(PATH)!!.markedSummary)
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
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), state = state)

        controller.revealTrace("ideas/a.md", content = "")
        advanceUntilIdle()

        // Aの印を外す要求を出す。まだIOへ流れていない。
        controller.toggleMark(state.slot())
        // 流れる前にBへ移り、Bでも押す。**Aの要求と競合していない。**
        controller.revealTrace("ideas/b.md", content = "")
        controller.toggleMark(state.slot())
        advanceUntilIdle()

        assertNull(
            "別ノートの操作でAの要求が捨てられている（押下が黙って消える）",
            persistence.stored("ideas/a.md")!!.markedSummary
        )
    }
}

// --- ヘルパ ---------------------------------------------------------------

private const val PATH = "ideas/habit.md"

/** 前後の要約として AI が返す文。 */
private const val PASSAGE = "直前は導入の説明を読んでいた。この先は具体例に入る。"

private const val QUESTION = "この方式で本当に速くなるのだろうか。"

/** 痕跡の節名「導入」を持つ10ブロックの本文。第0ブロックが見出し。 */
private val HEADED_BODY = "# 導入\n\n" + (1..9).joinToString("\n\n") { "導入の段落${it}。" }

/** 見出しの無い10ブロック（段落0〜段落9）。番号がそのままブロック番号になる。 */
private val PLAIN_BODY = (0 until 10).joinToString("\n\n") { "段落${it}の本文。" }

/** 最後の訪問だけを [progress]% にした痕跡。それより前の訪問は10%。 */
private fun traceAt(
    progress: Int,
    count: Int = 2,
    aiSummary: String? = null,
    kind: ReunionKind? = null,
    attemptedAt: Int? = null
) = ReadingTrace(
    vaultRelativePath = PATH,
    noteTitle = "習慣について",
    documentId = "doc-1",
    visits = (1..count).map { ReadingVisit(it * 1_000L, "導入", if (it == count) progress else 10) },
    aiSummary = aiSummary,
    aiSummaryVisitCount = attemptedAt,
    aiSummaryKind = kind
)

/** 最後の訪問で末尾まで読んだ痕跡。 */
private fun finishedTrace(
    count: Int = 2,
    aiSummary: String? = null,
    kind: ReunionKind? = null,
    attemptedAt: Int? = null
) = traceAt(progress = 100, count = count, aiSummary = aiSummary, kind = kind, attemptedAt = attemptedAt)

/** いま枠に出ているもの。画面と調停側が使うのと同じ純関数で求める。 */
private fun NoteUiStateStore.slot(summary: SummaryState = SummaryState.Idle): ReunionSlot =
    reunionSlot(value.readingTraceCard!!, summary)

/** [deepestBlock] まで読み、第2ブロック（番号1）へ戻って10秒以上いてから離れる。 */
@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.readThenScrollBack(persistence: FakePersistence, clock: TestClock, deepestBlock: Int) {
    val visits = visitController(persistence, clock)
    visits.onNoteOpened(PATH, "習慣について", "doc-1")
    visits.onReadingProgress(blockIndex = deepestBlock, blockFraction = 1f, totalBlocks = 10, sectionTitle = null)
    visits.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = null)
    clock.advance(10_000L)
    visits.flush()
    advanceUntilIdle()
}

/** 本文を [blockIndex] まで（全部見えるところまで）読んで10秒以上いてから離れる。 */
@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.readOnce(persistence: FakePersistence, clock: TestClock, blockIndex: Int) {
    val visits = visitController(persistence, clock)
    visits.onNoteOpened(PATH, "習慣について", "doc-1")
    visits.onReadingProgress(blockIndex = blockIndex, blockFraction = 1f, totalBlocks = 10, sectionTitle = "導入")
    clock.advance(10_000L)
    visits.flush()
    advanceUntilIdle()
}

/** 初めて読んで離れる。**保存は起動しただけで、まだ流していない。** */
private fun TestScope.leaveAfterReading(persistence: FakePersistence, clock: TestClock): ReadingTraceController {
    val visits = visitController(persistence, clock)
    visits.onNoteOpened(PATH, "習慣について", "doc-1")
    visits.onReadingProgress(blockIndex = 3, blockFraction = 1f, totalBlocks = 10, sectionTitle = "導入")
    clock.advance(10_000L)
    visits.flush()
    return visits
}

/** 終わった保存が1本も残っていない。 */
private fun assertNoVisitSaveLeft(visits: ReadingTraceController) {
    assertEquals("終わった保存が待ちに残っている", 0, visits.runningVisitSaveCount())
}

private fun TestScope.controller(
    persistence: ReadingTracePersistence,
    clock: TestClock,
    aiClient: AiClient = FakeAiClient.returning(PASSAGE),
    state: NoteUiStateStore = NoteUiStateStore(NoteUiState()),
    vault: FakeVault = FakeVault(),
    scope: CoroutineScope = this,
    persistScope: CoroutineScope = this,
    awaitDwell: suspend () -> Unit = passDwell,
    body: String = HEADED_BODY,
    passageCache: SummaryCache = InMemorySummaryCache(),
    awaitSectionModel: suspend () -> NoteSectionModel = { buildNoteSectionModel(body) },
    awaitVisitSaves: suspend () -> Unit = {}
): ReunionCardController {
    val dispatcher = StandardTestDispatcher(testScheduler)
    return ReunionCardController(
        scope = scope,
        persistScope = persistScope,
        aiClient = aiClient,
        awaitDwell = awaitDwell,
        state = state.readingTraceWriter,
        persistence = persistence,
        currentVaultKey = { vault.key },
        awaitSectionModel = awaitSectionModel,
        passageCache = passageCache,
        awaitVisitSaves = awaitVisitSaves,
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
