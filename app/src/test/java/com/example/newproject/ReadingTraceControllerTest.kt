package com.example.newproject

import com.example.newproject.controller.ReadingTraceController
import com.example.newproject.data.ReadingTraceFolderStatus
import com.example.newproject.data.ReadingTracePersistence
import com.example.newproject.data.ReadingTraceKeyListing
import com.example.newproject.data.ReadingTraceReadResult
import com.example.newproject.data.ReadingTraceSaveResult
import com.example.newproject.data.ReadingTraceStore
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.ReadingTrace
import com.example.newproject.model.ReadingTraceLimits
import com.example.newproject.model.ReadingVisit
import com.example.newproject.model.withVisit
import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.example.newproject.ai.AiTimeoutException
import com.google.mlkit.genai.common.DownloadStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import com.example.newproject.model.NoteUiStateStore
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingTraceControllerTest {

    @Test
    fun `records a visit after reading long enough`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)

        controller.onNoteOpened("ideas/habit.md", "習慣について", "doc-1")
        controller.onReadingProgress(blockIndex = 3, blockFraction = 1f, totalBlocks = 10, sectionTitle = "導入")
        clock.advance(10_000L)
        controller.flush()
        advanceUntilIdle()

        val saved = persistence.saved.single()
        assertEquals("ideas/habit.md", saved.vaultRelativePath)
        assertEquals("習慣について", saved.noteTitle)
        assertEquals("doc-1", saved.documentId)
        val visit = saved.visits.single()
        assertEquals("導入", visit.deepestSectionTitle)
        assertEquals(40, visit.progressPercent)
    }

    // 一瞬引いてすぐ次のノートへ送った分を訪問に数えると痕跡が濁る。
    @Test
    fun `does not record a glance`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)

        controller.onNoteOpened("ideas/habit.md", "習慣について", null)
        controller.onReadingProgress(blockIndex = 0, blockFraction = 1f, totalBlocks = 10, sectionTitle = null)
        clock.advance(9_999L)
        controller.flush()
        advanceUntilIdle()

        assertTrue(persistence.saved.isEmpty())
    }

    // 1画面に収まる短いノートもスクロールなしで記録される（時間だけを条件にしている）。
    @Test
    fun `short note without scrolling is still recorded`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)

        controller.onNoteOpened("short.md", "短いノート", null)
        controller.onReadingProgress(blockIndex = 0, blockFraction = 1f, totalBlocks = 1, sectionTitle = null)
        clock.advance(10_000L)
        controller.flush()
        advanceUntilIdle()

        assertEquals(100, persistence.saved.single().visits.single().progressPercent)
    }

    @Test
    fun `keeps the deepest point when scrolling back up`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)

        controller.onNoteOpened("ideas/habit.md", "習慣について", null)
        controller.onReadingProgress(blockIndex = 8, blockFraction = 1f, totalBlocks = 10, sectionTitle = "まとめ")
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = "導入")
        clock.advance(10_000L)
        controller.flush()
        advanceUntilIdle()

        val visit = persistence.saved.single().visits.single()
        assertEquals("まとめ", visit.deepestSectionTitle)
        assertEquals(90, visit.progressPercent)
    }

    @Test
    fun `reading to the end reports full progress`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)

        controller.onNoteOpened("ideas/habit.md", "習慣について", null)
        controller.onReadingProgress(blockIndex = 9, blockFraction = 1f, totalBlocks = 10, sectionTitle = "まとめ")
        clock.advance(10_000L)
        controller.flush()
        advanceUntilIdle()

        assertEquals(100, persistence.saved.single().visits.single().progressPercent)
    }

    // 長大な段落・コードブロックは1ブロックとして描画される。冒頭しか見ていないのに
    // 「最後まで読んでいます」と断定してしまうのを防ぐ（機能の中心データが誤るため）。
    @Test
    fun `a single huge block seen only at the top is not complete`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)

        controller.onNoteOpened("long.md", "長大な1ブロック", null)
        controller.onReadingProgress(blockIndex = 0, blockFraction = 0.1f, totalBlocks = 1, sectionTitle = null)
        clock.advance(10_000L)
        controller.flush()
        advanceUntilIdle()

        assertEquals(10, persistence.saved.single().visits.single().progressPercent)
    }

    // 100% は最終ブロックの末端が画面へ入った時だけ。末尾が少しでも残っていれば届かない。
    @Test
    fun `partially visible last block does not reach one hundred`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)

        controller.onNoteOpened("ideas/habit.md", "習慣について", null)
        controller.onReadingProgress(blockIndex = 9, blockFraction = 0.5f, totalBlocks = 10, sectionTitle = "まとめ")
        clock.advance(10_000L)
        controller.flush()
        advanceUntilIdle()

        assertEquals(95, persistence.saved.single().visits.single().progressPercent)
    }

    // 同じブロックに留まったまま読み進めた（長大ブロックのスクロール）分も最深に反映する。
    @Test
    fun `scrolling within the same block deepens the progress`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)

        controller.onNoteOpened("long.md", "長大な1ブロック", null)
        controller.onReadingProgress(blockIndex = 0, blockFraction = 0.2f, totalBlocks = 1, sectionTitle = null)
        controller.onReadingProgress(blockIndex = 0, blockFraction = 0.8f, totalBlocks = 1, sectionTitle = null)
        clock.advance(10_000L)
        controller.flush()
        advanceUntilIdle()

        assertEquals(80, persistence.saved.single().visits.single().progressPercent)
    }

    // 巻き戻しは可視割合でも最深を下げない。
    @Test
    fun `scrolling back within the same block keeps the deepest fraction`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)

        controller.onNoteOpened("long.md", "長大な1ブロック", null)
        controller.onReadingProgress(blockIndex = 0, blockFraction = 0.9f, totalBlocks = 1, sectionTitle = null)
        controller.onReadingProgress(blockIndex = 0, blockFraction = 0.2f, totalBlocks = 1, sectionTitle = null)
        clock.advance(10_000L)
        controller.flush()
        advanceUntilIdle()

        assertEquals(90, persistence.saved.single().visits.single().progressPercent)
    }

    // 見出しのないノートは sectionForBlockIndex が null を返すので、到達率だけが残る。
    @Test
    fun `note without headings records null section`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)

        controller.onNoteOpened("plain.md", "見出しなし", null)
        controller.onReadingProgress(blockIndex = 2, blockFraction = 1f, totalBlocks = 4, sectionTitle = null)
        clock.advance(10_000L)
        controller.flush()
        advanceUntilIdle()

        val visit = persistence.saved.single().visits.single()
        assertNull(visit.deepestSectionTitle)
        assertEquals(75, visit.progressPercent)
    }

    @Test
    fun `visits accumulate across readings`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)

        repeat(3) {
            controller.onNoteOpened("ideas/habit.md", "習慣について", null)
            controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = "導入")
            clock.advance(10_000L)
            controller.flush()
            advanceUntilIdle()
        }

        assertEquals(3, persistence.stored("ideas/habit.md")!!.visits.size)
    }

    @Test
    fun `visits are capped and the oldest is dropped`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)

        repeat(ReadingTraceLimits.MAX_VISITS + 3) {
            controller.onNoteOpened("ideas/habit.md", "習慣について", null)
            controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = "導入")
            clock.advance(10_000L)
            controller.flush()
            advanceUntilIdle()
        }

        val visits = persistence.stored("ideas/habit.md")!!.visits
        assertEquals(ReadingTraceLimits.MAX_VISITS, visits.size)
        // 昇順に積まれ、古い方から捨てられている
        assertEquals(visits.sortedBy { it.atEpochMillis }, visits)
    }

    // ノート切替とアプリ背面化で flush が二重に走っても訪問は増えない。
    @Test
    fun `flushing twice records only one visit`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)

        controller.onNoteOpened("ideas/habit.md", "習慣について", null)
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = "導入")
        clock.advance(10_000L)
        controller.flush()
        controller.flush()
        advanceUntilIdle()

        assertEquals(1, persistence.saved.size)
    }

    // ── 背面化・復帰（pause / resume）──────────────────────────────────────────

    // 背面のままプロセスが終了しても読書が失われないよう、背面化の時点で書き出す。
    @Test
    fun `pause records the visit without ending the session`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)

        controller.onNoteOpened("ideas/habit.md", "習慣について", null)
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = "導入")
        clock.advance(10_000L)
        controller.pause()
        advanceUntilIdle()

        assertEquals(1, persistence.stored("ideas/habit.md")!!.visits.size)
    }

    // ホームボタンを押すたび「これまで◯回開いています」が増えてはいけない。
    // 復帰後に読み進めた分は、訪問を増やさず同じ1件を更新する。
    @Test
    fun `reading after resume updates the same visit instead of adding one`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)

        controller.onNoteOpened("ideas/habit.md", "習慣について", null)
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = "導入")
        clock.advance(10_000L)
        controller.pause()
        advanceUntilIdle()

        controller.resume()
        controller.onReadingProgress(blockIndex = 8, blockFraction = 1f, totalBlocks = 10, sectionTitle = "まとめ")
        clock.advance(10_000L)
        controller.flush()
        advanceUntilIdle()

        val stored = persistence.stored("ideas/habit.md")!!
        assertEquals(1, stored.visits.size)
        // 累計も増えない。保持件数だけ見ていると 30 件で頭打ちになって
        // この誤りが隠れるので、累計そのものを確かめる。
        assertEquals(1, stored.totalVisitCount)
        // 復帰後に読み進めた最深が残っていること
        assertEquals(90, stored.visits.single().progressPercent)
        assertEquals("まとめ", stored.visits.single().deepestSectionTitle)
    }

    // ── 保存の寿命と失敗の扱い ──────────────────────────────────────────────

    // タスクスワイプでは onStop() → pause() の直後に onCleared() が走る。
    // 保存が viewModelScope に載っていると、IOへディスパッチされる前に
    // キャンセルされて確定済みの訪問が消える。
    @Test
    fun `UI側のスコープがキャンセルされても訪問は書き出される`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val uiScope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val controller = controller(persistence, clock, scope = uiScope, persistScope = this)

        controller.onNoteOpened("ideas/habit.md", "習慣について", null)
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = null)
        clock.advance(10_000L)
        controller.pause()
        // 書き出しがIOへ渡る前に ViewModel が畳まれる
        uiScope.cancel()
        advanceUntilIdle()

        assertEquals(1, persistence.saved.size)
    }

    // 消費済みの印（dirty=false / recordedVisit）は保存の起動前に立てている。
    // 書けなかったのに戻さないと、そのセッションの訪問は恒久的に失われる。
    //
    // 背面化のあと **resume を挟まずに** 離脱するのが要点。resume() は無条件に
    // dirty を立てるので、それを挟むと巻き戻しを経由しなくても通ってしまう。
    @Test
    fun `保存に失敗したら次の契機で書き直される`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)

        controller.onNoteOpened("ideas/habit.md", "習慣について", null)
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = null)
        clock.advance(10_000L)
        persistence.failSave = true
        controller.pause()
        advanceUntilIdle()
        assertTrue(persistence.saved.isEmpty())

        // 背面のまま離脱する（＝次の契機）。dirty が戻っていなければ二度と書かれない。
        persistence.failSave = false
        controller.flush()
        advanceUntilIdle()

        assertEquals(1, persistence.saved.size)
        assertEquals(1, persistence.stored("ideas/habit.md")!!.totalVisitCount)
    }

    // 成功した保存のあとに失敗した保存が続いても、読み進めた分が失われない。
    // 巻き戻しが「自分が書いた訪問がまだ最新のときだけ」に効くことの確認でもある。
    @Test
    fun `後続の保存が失敗しても読み進めた分は次の契機で書かれる`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)

        controller.onNoteOpened("ideas/habit.md", "習慣について", null)
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = null)
        clock.advance(10_000L)
        controller.pause()
        advanceUntilIdle()
        assertEquals(20, persistence.stored("ideas/habit.md")!!.visits.single().progressPercent)

        // 復帰して読み進めるが、その保存は失敗する
        persistence.failSave = true
        controller.resume()
        controller.onReadingProgress(blockIndex = 8, blockFraction = 1f, totalBlocks = 10, sectionTitle = null)
        clock.advance(1_000L)
        controller.pause()
        advanceUntilIdle()

        // さらに次の契機で書き直される。訪問は増えず、最深だけが進む。
        persistence.failSave = false
        controller.flush()
        advanceUntilIdle()

        val stored = persistence.stored("ideas/habit.md")!!
        assertEquals(1, stored.visits.size)
        assertEquals(1, stored.totalVisitCount)
        assertEquals(90, stored.visits.single().progressPercent)
    }

    // 保存2件が同時に飛んでいるとき、先に失敗した方の巻き戻しが後発の訪問を潰さない。
    //
    // 巻き戻しは「自分が書こうとした訪問がまだ最新のときだけ」に限っている。
    // その照合が無いと、先発の失敗が recordedVisit を古い値へ戻してしまい、
    // 次の離脱で同じ閲覧が2件目の訪問として積まれる（1回の閲覧＝1訪問が崩れる）。
    @Test
    fun `先行した保存の失敗が後発の訪問を巻き戻さない`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)
        persistence.failSaveOnAttempt = 1

        controller.onNoteOpened("ideas/habit.md", "習慣について", null)
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = null)
        clock.advance(10_000L)
        // 1件目の保存を起動したまま（advanceUntilIdle を挟まない）読み進めて2件目を起動する
        controller.pause()
        controller.resume()
        controller.onReadingProgress(blockIndex = 8, blockFraction = 1f, totalBlocks = 10, sectionTitle = null)
        clock.advance(1_000L)
        controller.pause()
        advanceUntilIdle()

        // 2件目は成功しているので、離脱時に書き直すものは無い
        controller.flush()
        advanceUntilIdle()

        val stored = persistence.stored("ideas/habit.md")!!
        assertEquals(1, stored.visits.size)
        assertEquals(1, stored.totalVisitCount)
        assertEquals(90, stored.visits.single().progressPercent)
    }

    // ── 累計回数（保持件数と分離）────────────────────────────────────────────

    // 保持は30件で頭打ちになるが、累計は積み上がる。
    @Test
    fun `訪問の保持上限を超えても累計は増える`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val cap = ReadingTraceLimits.MAX_VISITS
        persistence.put(
            ReadingTrace(
                vaultRelativePath = "ideas/habit.md",
                noteTitle = "習慣について",
                documentId = null,
                visits = List(cap) { ReadingVisit(it.toLong(), null, 10) },
                totalVisitCount = cap
            )
        )
        val controller = controller(persistence, clock)

        controller.onNoteOpened("ideas/habit.md", "習慣について", null)
        controller.onReadingProgress(blockIndex = 5, blockFraction = 1f, totalBlocks = 10, sectionTitle = null)
        clock.advance(10_000L)
        controller.flush()
        advanceUntilIdle()

        val stored = persistence.stored("ideas/habit.md")!!
        assertEquals(cap, stored.visits.size)
        assertEquals(cap + 1, stored.totalVisitCount)
    }

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

        // 31回目の閲覧を記録してから再会する
        controller.onNoteOpened("ideas/habit.md", "習慣について", null)
        controller.onReadingProgress(blockIndex = 5, blockFraction = 1f, totalBlocks = 10, sectionTitle = null)
        clock.advance(10_000L)
        controller.flush()
        advanceUntilIdle()
        controller.revealTrace("ideas/habit.md")
        advanceUntilIdle()

        val card = state.value.readingTraceCard!!
        assertEquals(cap + 1, card.visitCount)
        // 古い要約が「最新」として出ていないこと＝作り直されたこと
        assertEquals(AI_SUMMARY, card.aiSummary)
        assertEquals(cap + 1, persistence.stored("ideas/habit.md")!!.aiSummaryVisitCount)
    }

    // 背面にいた時間を10秒判定へ混ぜない（実際には短時間しか読んでいない）。
    @Test
    fun `time spent in the background does not count towards the threshold`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)

        controller.onNoteOpened("ideas/habit.md", "習慣について", null)
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = "導入")
        clock.advance(5_000L)
        controller.pause()
        clock.advance(600_000L) // 10分放置
        controller.resume()
        clock.advance(3_000L)
        controller.flush()
        advanceUntilIdle()

        assertTrue(persistence.saved.isEmpty())
    }

    // 背面をまたいでも能動読書時間は積算される（5秒＋6秒で条件を満たす）。
    @Test
    fun `active reading time accumulates across a background trip`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)

        controller.onNoteOpened("ideas/habit.md", "習慣について", null)
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = "導入")
        clock.advance(5_000L)
        controller.pause()
        clock.advance(600_000L)
        controller.resume()
        clock.advance(6_000L)
        controller.flush()
        advanceUntilIdle()

        assertEquals(1, persistence.stored("ideas/habit.md")!!.visits.size)
    }

    // 何も変わっていなければ書き込まない（クラウドVaultでの無駄な同期を出さない）。
    @Test
    fun `pausing again without any change does not write`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)

        controller.onNoteOpened("ideas/habit.md", "習慣について", null)
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = "導入")
        clock.advance(10_000L)
        controller.pause()
        advanceUntilIdle()
        val afterFirstPause = persistence.saveAttempts

        controller.pause()
        advanceUntilIdle()

        assertEquals(afterFirstPause, persistence.saveAttempts)
    }

    // 別端末が後から追記していれば末尾が自分の訪問ではない。その場合は差し替えず追記する。
    @Test
    fun `a visit appended by another device is not overwritten`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)

        controller.onNoteOpened("ideas/habit.md", "習慣について", null)
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = "導入")
        clock.advance(10_000L)
        controller.pause()
        advanceUntilIdle()

        // 同期で別端末の訪問が末尾に足された
        val synced = persistence.stored("ideas/habit.md")!!
        persistence.put(synced.withVisit(ReadingVisit(9_999_999L, "別端末", 50)))

        controller.resume()
        controller.onReadingProgress(blockIndex = 8, blockFraction = 1f, totalBlocks = 10, sectionTitle = "まとめ")
        clock.advance(10_000L)
        controller.flush()
        advanceUntilIdle()

        val stored = persistence.stored("ideas/habit.md")!!
        assertEquals(3, stored.visits.size)
        assertEquals("別端末", stored.visits[1].deepestSectionTitle)
    }

    @Test
    fun `resume without a session does nothing`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)

        controller.resume()
        controller.pause()
        advanceUntilIdle()

        assertTrue(persistence.saved.isEmpty())
    }

    // 保存先は書き込み時点の vaultUri から解決されるため、切替前に捨てないと
    // 旧Vaultのノートの痕跡が新Vaultへ書き込まれる。
    @Test
    fun `discard drops the session without recording`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)

        controller.onNoteOpened("ideas/habit.md", "習慣について", null)
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = "導入")
        clock.advance(10_000L)
        controller.discard()
        controller.flush()
        advanceUntilIdle()

        assertTrue(persistence.saved.isEmpty())
    }

    // 保存は非同期に走るため、書込時点の現在Vaultから保存先を解決すると、切替後の
    // 新Vaultへ旧ノートの痕跡が書き込まれ得る。要求は常に「開いた時点のVault」へ向かう。
    @Test
    fun `a save requested before a vault switch still targets the old vault`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val vault = FakeVault(VAULT_A)
        val controller = controller(persistence, clock, vault = vault)

        controller.onNoteOpened("ideas/habit.md", "習慣について", null)
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = "導入")
        clock.advance(10_000L)
        controller.flush()
        // 保存コルーチンが走る前にVaultが切り替わる
        vault.key = VAULT_B
        advanceUntilIdle()

        assertEquals(listOf(VAULT_A), persistence.savedVaultKeys)
    }

    // 切替後に開いたノートは、新しいVaultへ向けて記録される。
    @Test
    fun `a note opened after the switch targets the new vault`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val vault = FakeVault(VAULT_A)
        val controller = controller(persistence, clock, vault = vault)

        vault.key = VAULT_B
        controller.onNoteOpened("ideas/habit.md", "習慣について", null)
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = "導入")
        clock.advance(10_000L)
        controller.flush()
        advanceUntilIdle()

        assertEquals(listOf(VAULT_B), persistence.savedVaultKeys)
    }

    // Vault未選択なら保存先が無いので、そもそも追跡しない。
    @Test
    fun `no vault means no tracking`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock, vault = FakeVault(null))

        controller.onNoteOpened("ideas/habit.md", "習慣について", null)
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = "導入")
        clock.advance(10_000L)
        controller.flush()
        advanceUntilIdle()

        assertTrue(persistence.saved.isEmpty())
    }

    // 相対パスが最後まで分からなかったノート（_AI補記 の一覧から開いた等）は追跡しない。
    @Test
    fun `unresolved relative path is not tracked`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)

        controller.onNoteOpened(null, "パス不明", null)
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = "導入")
        clock.advance(10_000L)
        controller.flush()
        advanceUntilIdle()

        assertTrue(persistence.saved.isEmpty())
    }

    @Test
    fun `blank relative path is treated as unresolved`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)

        controller.onNoteOpened("", "パス不明", null)
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = "導入")
        clock.advance(10_000L)
        controller.flush()
        advanceUntilIdle()

        assertTrue(persistence.saved.isEmpty())
    }

    // さがす・関連から開いた場合、相対パスは表示後にしか分からない。パス未確定でも
    // セッションを作って進捗を溜め、後から結び付けられること。
    @Test
    fun `path bound after display still records the visit`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)

        val session = controller.onNoteOpened(null, "習慣について", "doc-1")
        controller.onReadingProgress(blockIndex = 4, blockFraction = 1f, totalBlocks = 10, sectionTitle = "本題")
        controller.bindPath(session, "ideas/habit.md")
        clock.advance(10_000L)
        controller.flush()
        advanceUntilIdle()

        val saved = persistence.saved.single()
        assertEquals("ideas/habit.md", saved.vaultRelativePath)
        // 結び付ける前に届いた進捗も残っていること
        assertEquals("本題", saved.visits.single().deepestSectionTitle)
        assertEquals(50, saved.visits.single().progressPercent)
    }

    @Test
    fun `bind does not overwrite an already known path`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)

        val session = controller.onNoteOpened("ideas/habit.md", "習慣について", null)
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = "導入")
        controller.bindPath(session, "other/note.md")
        clock.advance(10_000L)
        controller.flush()
        advanceUntilIdle()

        assertEquals("ideas/habit.md", persistence.saved.single().vaultRelativePath)
    }

    // パス未確定のノートを続けて開いた時、前のノートの遅れた解決結果が次のセッションへ
    // 吸い込まれないこと（吸い込まれると別ノートのパスで訪問を記録してしまう）。
    @Test
    fun `late bind for a previous session is ignored`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)

        val first = controller.onNoteOpened(null, "A", null)
        controller.onNoteOpened(null, "B", null)
        // Aの解決結果が遅れて届く
        controller.bindPath(first, "a.md")
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 2, sectionTitle = null)
        clock.advance(10_000L)
        controller.flush()
        advanceUntilIdle()

        // Bはパス未確定のままなので記録されない（a.md で記録されてはならない）
        assertTrue(persistence.saved.isEmpty())
    }

    @Test
    fun `bind is ignored when no session is active`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)

        controller.bindPath(1L, "ideas/habit.md")
        clock.advance(10_000L)
        controller.flush()
        advanceUntilIdle()

        assertTrue(persistence.saved.isEmpty())
    }

    // 本文がまだ描画されていない（進捗報告が来ていない）表示は読んだと見なさない。
    @Test
    fun `no progress report means no visit`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)

        controller.onNoteOpened("ideas/habit.md", "習慣について", null)
        clock.advance(60_000L)
        controller.flush()
        advanceUntilIdle()

        assertTrue(persistence.saved.isEmpty())
    }

    @Test
    fun `switching notes records each separately`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)

        controller.onNoteOpened("a.md", "A", null)
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 2, sectionTitle = null)
        clock.advance(10_000L)
        controller.flush()

        controller.onNoteOpened("b.md", "B", null)
        controller.onReadingProgress(blockIndex = 0, blockFraction = 1f, totalBlocks = 2, sectionTitle = null)
        clock.advance(10_000L)
        controller.flush()
        advanceUntilIdle()

        assertEquals(1, persistence.stored("a.md")!!.visits.size)
        assertEquals(1, persistence.stored("b.md")!!.visits.size)
    }

    // 破損ファイルは上書きで作り直す（過去の痕跡は失うがノートには触れない）。
    @Test
    fun `corrupt existing trace is replaced`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        persistence.corruptPaths += "ideas/habit.md"
        val controller = controller(persistence, clock)

        controller.onNoteOpened("ideas/habit.md", "習慣について", null)
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = "導入")
        clock.advance(10_000L)
        controller.flush()
        advanceUntilIdle()

        assertEquals(1, persistence.saved.single().visits.size)
    }

    // 改名や別端末での再バインドに追従して、タイトルと documentId を最新へ寄せ直す。
    @Test
    fun `existing trace has title and document id refreshed`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        persistence.put(
            ReadingTrace(
                vaultRelativePath = "ideas/habit.md",
                noteTitle = "古いタイトル",
                documentId = "old-doc",
                visits = listOf(ReadingVisit(1L, "導入", 10))
            )
        )
        val controller = controller(persistence, clock)

        controller.onNoteOpened("ideas/habit.md", "新しいタイトル", "new-doc")
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = "導入")
        clock.advance(10_000L)
        controller.flush()
        advanceUntilIdle()

        val stored = persistence.stored("ideas/habit.md")!!
        assertEquals("新しいタイトル", stored.noteTitle)
        assertEquals("new-doc", stored.documentId)
        assertEquals(2, stored.visits.size)
    }

    @Test
    fun `save failure does not crash`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence().apply { failSave = true }
        val controller = controller(persistence, clock)

        controller.onNoteOpened("ideas/habit.md", "習慣について", null)
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = "導入")
        clock.advance(10_000L)
        controller.flush()
        advanceUntilIdle()

        assertEquals(1, persistence.saveAttempts)
    }

    // ── 再会（revealTrace）────────────────────────────────────────────────────

    @Test
    fun `no trace means no card`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(FakePersistence(), TestClock(), state = state)

        controller.revealTrace("ideas/habit.md")
        advanceUntilIdle()

        assertNull(state.value.readingTraceCard)
    }

    // 破損はカードを出さないだけ。ユーザーのノートには一切触れない。
    @Test
    fun `corrupt trace means no card`() = runTest {
        val persistence = FakePersistence().apply { corruptPaths += "ideas/habit.md" }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), state = state)

        controller.revealTrace("ideas/habit.md")
        advanceUntilIdle()

        assertNull(state.value.readingTraceCard)
    }

    @Test
    fun `blank path does nothing`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(FakePersistence(), TestClock(), state = state)

        controller.revealTrace("")
        advanceUntilIdle()

        assertNull(state.value.readingTraceCard)
    }

    // 訪問1件では「俯瞰」にならないのでAIを呼ばず、生の痕跡だけを出す。
    @Test
    fun `single visit shows raw trace without calling ai`() = runTest {
        val persistence = FakePersistence().apply { put(storedTrace(count = 1)) }
        val ai = ImmediateAiClient()
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), ai, state)

        controller.revealTrace("ideas/habit.md")
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
        val controller = controller(persistence, TestClock(), ControllableAiClient(), state)

        controller.revealTrace("ideas/habit.md")
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
        val ai = ImmediateAiClient()
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), ai, state)

        controller.revealTrace("ideas/habit.md")
        advanceUntilIdle()

        val card = state.value.readingTraceCard!!
        assertEquals(AI_SUMMARY, card.aiSummary)
        assertTrue(!card.isSummaryLoading)
        assertEquals(1, ai.generateCalls)
    }

    @Test
    fun `summary is written back to the sidecar`() = runTest {
        val persistence = FakePersistence().apply { put(storedTrace(count = 2)) }
        val controller = controller(persistence, TestClock())

        controller.revealTrace("ideas/habit.md")
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
        val ai = ImmediateAiClient()
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), ai, state)

        controller.revealTrace("ideas/habit.md")
        advanceUntilIdle()

        assertEquals("キャッシュ済み", state.value.readingTraceCard!!.aiSummary)
        assertEquals(0, ai.generateCalls)
    }

    @Test
    fun `stale summary is regenerated when visits grew`() = runTest {
        val persistence = FakePersistence().apply {
            put(storedTrace(count = 3, aiSummary = "古い要約", aiSummaryVisitCount = 2))
        }
        val ai = ImmediateAiClient()
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), ai, state)

        controller.revealTrace("ideas/habit.md")
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
        val controller = controller(persistence, TestClock(), FailingAiClient(), state)

        controller.revealTrace("ideas/habit.md")
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
        val ai = ImmediateAiClient(availability = AiAvailability.NeedsDownload)
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), ai, state)

        controller.revealTrace("ideas/habit.md")
        advanceUntilIdle()

        val card = state.value.readingTraceCard!!
        assertEquals(2, card.visitCount)
        assertNull(card.aiSummary)
        assertEquals(0, ai.generateCalls)
    }

    @Test
    fun `note change discards a late summary`() = runTest {
        val persistence = FakePersistence().apply { put(storedTrace(count = 2)) }
        val ai = ControllableAiClient()
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), ai, state)

        controller.revealTrace("ideas/habit.md")
        runCurrent()
        controller.cancelForNoteChange()
        ai.response.complete("後から届いた要約")
        advanceUntilIdle()

        assertNull(state.value.readingTraceCard?.aiSummary)
    }

    @Test
    fun `dismiss marks the card`() = runTest {
        val persistence = FakePersistence().apply { put(storedTrace(count = 1)) }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), state = state)

        controller.revealTrace("ideas/habit.md")
        advanceUntilIdle()
        controller.dismissCard()

        assertTrue(state.value.readingTraceCard!!.isDismissed)
    }

    // 畳んだあとに要約が届いても開き直さない。
    @Test
    fun `dismissed card stays folded when the summary arrives`() = runTest {
        val persistence = FakePersistence().apply { put(storedTrace(count = 2)) }
        val ai = ControllableAiClient()
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, TestClock(), ai, state)

        controller.revealTrace("ideas/habit.md")
        runCurrent()
        controller.dismissCard()
        ai.response.complete(AI_SUMMARY)
        advanceUntilIdle()

        val card = state.value.readingTraceCard!!
        assertTrue(card.isDismissed)
        assertEquals(AI_SUMMARY, card.aiSummary)
    }

    // ── ノートへのひとことの相乗り保存 ──────────────────────────────────────
    //
    // ひとことは単独では保存できない。痕跡ファイルは離脱・背面化でしか作られず、
    // 検証は訪問が1件以上あることを要求するため、初読の最中に押されるこの機能で
    // 「生成できたら保存」と書くと必ず黙って失われる（→ design/reflect_remark.md §2.1）。

    @Test
    fun `預けたひとことは離脱時の書き込みに相乗りする`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)

        controller.onNoteOpened("ideas/habit.md", "習慣について", "doc-1")
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = "導入")
        clock.advance(10_000L)
        controller.setPendingRemark("この習慣を続けられた日は何が違っただろう？")
        controller.flush()
        advanceUntilIdle()

        val stored = persistence.stored("ideas/habit.md")!!
        assertEquals("この習慣を続けられた日は何が違っただろう？", stored.remark)
        // 痕跡ファイルが無い状態から、訪問と一緒に1回で作られること
        assertEquals(1, stored.visits.size)
    }

    /**
     * **`dirty` を立てないと落ちるケース。** 直前の背面化で訪問を書き終えていると
     * 「変化なし」で早期returnし、ひとことが書かれないままセッションが終わる。
     */
    @Test
    fun `訪問を書いた後に預けたひとことも保存される`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)

        controller.onNoteOpened("ideas/habit.md", "習慣について", "doc-1")
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = "導入")
        clock.advance(10_000L)
        controller.pause()
        advanceUntilIdle()
        assertNull(persistence.stored("ideas/habit.md")!!.remark)

        // 復帰せず（＝進捗も来ず）にひとことだけ預けて離脱する
        controller.setPendingRemark("続けられた日は何が違っただろう？")
        controller.flush()
        advanceUntilIdle()

        assertEquals("続けられた日は何が違っただろう？", persistence.stored("ideas/habit.md")!!.remark)
        // 背面化ぶんの訪問を増やさない（1回の閲覧＝1訪問）
        assertEquals(1, persistence.stored("ideas/habit.md")!!.visits.size)
    }

    // 過去のひとことを、以降の訪問で消さないこと。
    @Test
    fun `預けていない訪問は既存のひとことを保つ`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence().apply {
            put(storedTrace(count = 1).copy(remark = "前回のひとこと"))
        }
        val controller = controller(persistence, clock)

        controller.onNoteOpened("ideas/habit.md", "習慣について", "doc-1")
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = "導入")
        clock.advance(10_000L)
        controller.flush()
        advanceUntilIdle()

        assertEquals("前回のひとこと", persistence.stored("ideas/habit.md")!!.remark)
    }

    @Test
    fun `新しいひとことは古いものを上書きする`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence().apply {
            put(storedTrace(count = 1).copy(remark = "前回のひとこと"))
        }
        val controller = controller(persistence, clock)

        controller.onNoteOpened("ideas/habit.md", "習慣について", "doc-1")
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = "導入")
        clock.advance(10_000L)
        controller.setPendingRemark("今回のひとこと")
        controller.flush()
        advanceUntilIdle()

        assertEquals("今回のひとこと", persistence.stored("ideas/habit.md")!!.remark)
    }

    /**
     * 保存に失敗したら次の契機で書き直す。訪問だけ戻してひとことを捨てると、
     * 生成し直す導線がユーザーの再操作しか無いため恒久的に失われる。
     */
    @Test
    fun `保存に失敗したひとことは次の契機で書き直される`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence().apply { failSaveOnAttempt = 1 }
        val controller = controller(persistence, clock)

        controller.onNoteOpened("ideas/habit.md", "習慣について", "doc-1")
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = "導入")
        clock.advance(10_000L)
        controller.setPendingRemark("失敗しても残したいひとこと")
        controller.pause()
        advanceUntilIdle()
        assertNull(persistence.stored("ideas/habit.md"))

        controller.flush()
        advanceUntilIdle()

        assertEquals("失敗しても残したいひとこと", persistence.stored("ideas/habit.md")!!.remark)
    }

    /**
     * 保存を待っている間に新しいひとことが預けられたら、**そちらを優先する。**
     * 失敗した古い方を無条件に戻すと、新しい方を上書きして捨ててしまう。
     * （訪問側の巻き戻しが「自分が書こうとした訪問がまだ最新なら」だけ戻すのと同じ形）
     */
    @Test
    fun `保存中に預け直されたひとことは古い失敗で上書きされない`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence().apply { failSaveOnAttempt = 1 }
        val controller = controller(persistence, clock)

        controller.onNoteOpened("ideas/habit.md", "習慣について", "doc-1")
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = "導入")
        clock.advance(10_000L)
        controller.setPendingRemark("古いひとこと")
        controller.pause()
        // 保存（失敗する）が走り切る前に、新しいひとことを預け直す
        controller.setPendingRemark("新しいひとこと")
        advanceUntilIdle()

        controller.flush()
        advanceUntilIdle()

        assertEquals("新しいひとこと", persistence.stored("ideas/habit.md")!!.remark)
    }

    // セッションが無ければ保存先が無いので黙って捨てる（例外にしない）。
    @Test
    fun `セッションが無ければひとことは黙って捨てられる`() = runTest {
        val persistence = FakePersistence()
        val controller = controller(persistence, TestClock())

        controller.setPendingRemark("行き先の無いひとこと")
        controller.flush()
        advanceUntilIdle()

        assertTrue(persistence.saved.isEmpty())
    }

    // 再会カードは「前回まで」を見せる。今回の読書は離脱時に足されるので混ざらない。
    @Test
    fun `card shows only visits recorded before this reading`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence().apply { put(storedTrace(count = 2)) }
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(persistence, clock, state = state)

        controller.revealTrace("ideas/habit.md")
        advanceUntilIdle()
        assertEquals(2, state.value.readingTraceCard!!.visitCount)

        controller.onNoteOpened("ideas/habit.md", "習慣について", "doc-1")
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = "導入")
        clock.advance(10_000L)
        controller.flush()
        advanceUntilIdle()

        assertEquals(2, state.value.readingTraceCard!!.visitCount)
        assertEquals(3, persistence.stored("ideas/habit.md")!!.visits.size)
    }
}

// --- ヘルパ ---------------------------------------------------------------

private fun TestScope.controller(
    persistence: ReadingTracePersistence,
    clock: TestClock,
    aiClient: AiClient = ImmediateAiClient(),
    state: NoteUiStateStore = NoteUiStateStore(NoteUiState()),
    vault: FakeVault = FakeVault(),
    // 既定では UI 用と同じスコープ。両者を分ける必要があるのは
    // 「scope をキャンセルしても保存が走る」を確かめるときだけ。
    scope: CoroutineScope = this,
    persistScope: CoroutineScope = this
): ReadingTraceController {
    val dispatcher = StandardTestDispatcher(testScheduler)
    return ReadingTraceController(
        scope = scope,
        persistScope = persistScope,
        aiClient = aiClient,
        state = state.readingTraceWriter,
        persistence = persistence,
        currentVaultKey = { vault.key },
        clock = clock::now,
        ioDispatcher = dispatcher
    )
}

/** 現在選択中のVault。切替を再現するために書き換えられる。 */
private class FakeVault(var key: String? = VAULT_A)

private const val VAULT_A = "content://vault-a"
private const val VAULT_B = "content://vault-b"

private class ImmediateAiClient(
    private val availability: AiAvailability = AiAvailability.Available,
    private val response: String = AI_SUMMARY
) : AiClient {
    var generateCalls = 0
        private set

    override suspend fun checkAvailability(): AiAvailability = availability

    override suspend fun generate(prompt: String): String {
        generateCalls++
        return response
    }

    override fun downloadModel(): Flow<DownloadStatus> = emptyFlow()
}

private class FailingAiClient : AiClient {
    override suspend fun checkAvailability(): AiAvailability = AiAvailability.Available
    override suspend fun generate(prompt: String): String = throw AiTimeoutException("タイムアウト")
    override fun downloadModel(): Flow<DownloadStatus> = emptyFlow()
}

private class ControllableAiClient : AiClient {
    val response = CompletableDeferred<String>()
    override suspend fun checkAvailability(): AiAvailability = AiAvailability.Available
    override suspend fun generate(prompt: String): String = response.await()
    override fun downloadModel(): Flow<DownloadStatus> = emptyFlow()
}

private const val AI_SUMMARY = "これまで2回開いて、いずれも前半で止まっています。"

/** 訪問 [count] 件を持つ痕跡。件数が2以上だとAI俯瞰要約の対象になる。 */
private fun storedTrace(
    count: Int,
    path: String = "ideas/habit.md",
    aiSummary: String? = null,
    aiSummaryVisitCount: Int? = null
) = ReadingTrace(
    vaultRelativePath = path,
    noteTitle = "習慣について",
    documentId = "doc-1",
    visits = (1..count).map { ReadingVisit(it * 1_000L, "導入", 10 * it) },
    aiSummary = aiSummary,
    aiSummaryVisitCount = aiSummaryVisitCount
)

private class TestClock(private var current: Long = 1_000_000L) {
    fun now(): Long = current
    fun advance(millis: Long) {
        current += millis
    }
}

private class FakePersistence : ReadingTracePersistence {
    val saved = mutableListOf<ReadingTrace>()
    val savedVaultKeys = mutableListOf<String>()
    val corruptPaths = mutableSetOf<String>()
    var failSave = false

    /** この回数目の保存だけを失敗させる（1始まり）。先行・後続の順序が要る検証用。 */
    var failSaveOnAttempt: Int? = null
    var saveAttempts = 0
        private set

    private val files = mutableMapOf<String, ReadingTrace>()

    fun put(trace: ReadingTrace) {
        files[trace.vaultRelativePath] = trace
    }

    fun stored(path: String): ReadingTrace? = files[path]

    override fun folderStatus(): ReadingTraceFolderStatus = ReadingTraceFolderStatus.Ready

    override fun load(vaultRelativePath: String, vaultKey: String): ReadingTraceReadResult = when {
        vaultRelativePath in corruptPaths -> ReadingTraceReadResult.Corrupt("壊れています")
        else -> files[vaultRelativePath]
            ?.let { ReadingTraceReadResult.Valid(it) }
            ?: ReadingTraceReadResult.None
    }

    override fun listKeys(vaultKey: String): ReadingTraceKeyListing =
        ReadingTraceKeyListing.Available(files.keys.map { ReadingTraceStore.keyFor(it) }.toSet())

    override fun loadByKey(key: String, vaultKey: String): ReadingTraceReadResult =
        files.keys.firstOrNull { ReadingTraceStore.keyFor(it) == key }
            ?.let { load(it, vaultKey) }
            ?: ReadingTraceReadResult.None

    override fun deleteByKey(key: String, vaultKey: String): Boolean =
        files.keys.firstOrNull { ReadingTraceStore.keyFor(it) == key }
            ?.let { files.remove(it) != null } ?: false

    override fun save(trace: ReadingTrace, vaultKey: String): ReadingTraceSaveResult {
        saveAttempts++
        savedVaultKeys += vaultKey
        if (failSave || failSaveOnAttempt == saveAttempts) {
            return ReadingTraceSaveResult.Failure("書き込めませんでした")
        }
        saved += trace
        files[trace.vaultRelativePath] = trace
        return ReadingTraceSaveResult.Success
    }
}
