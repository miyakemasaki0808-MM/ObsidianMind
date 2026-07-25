package com.example.newproject

import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.example.newproject.ai.AiTimeoutException
import com.google.mlkit.genai.common.DownloadStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
        // 復帰後に読み進めた最深が残っていること
        assertEquals(90, stored.visits.single().progressPercent)
        assertEquals("まとめ", stored.visits.single().deepestSectionTitle)
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
        val state = MutableStateFlow(NoteUiState())
        val controller = controller(FakePersistence(), TestClock(), state = state)

        controller.revealTrace("ideas/habit.md")
        advanceUntilIdle()

        assertNull(state.value.readingTraceCard)
    }

    // 破損はカードを出さないだけ。ユーザーのノートには一切触れない。
    @Test
    fun `corrupt trace means no card`() = runTest {
        val persistence = FakePersistence().apply { corruptPaths += "ideas/habit.md" }
        val state = MutableStateFlow(NoteUiState())
        val controller = controller(persistence, TestClock(), state = state)

        controller.revealTrace("ideas/habit.md")
        advanceUntilIdle()

        assertNull(state.value.readingTraceCard)
    }

    @Test
    fun `blank path does nothing`() = runTest {
        val state = MutableStateFlow(NoteUiState())
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
        val state = MutableStateFlow(NoteUiState())
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
        val state = MutableStateFlow(NoteUiState())
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
        val state = MutableStateFlow(NoteUiState())
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
        val state = MutableStateFlow(NoteUiState())
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
        val state = MutableStateFlow(NoteUiState())
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
        val state = MutableStateFlow(NoteUiState())
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
        val state = MutableStateFlow(NoteUiState())
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
        val state = MutableStateFlow(NoteUiState())
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
        val state = MutableStateFlow(NoteUiState())
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
        val state = MutableStateFlow(NoteUiState())
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

    // 再会カードは「前回まで」を見せる。今回の読書は離脱時に足されるので混ざらない。
    @Test
    fun `card shows only visits recorded before this reading`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence().apply { put(storedTrace(count = 2)) }
        val state = MutableStateFlow(NoteUiState())
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
    state: MutableStateFlow<NoteUiState> = MutableStateFlow(NoteUiState())
): ReadingTraceController {
    val dispatcher = StandardTestDispatcher(testScheduler)
    return ReadingTraceController(
        scope = this,
        aiClient = aiClient,
        uiState = state,
        persistence = persistence,
        clock = clock::now,
        ioDispatcher = dispatcher
    )
}

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
    val corruptPaths = mutableSetOf<String>()
    var failSave = false
    var saveAttempts = 0
        private set

    private val files = mutableMapOf<String, ReadingTrace>()

    fun put(trace: ReadingTrace) {
        files[trace.vaultRelativePath] = trace
    }

    fun stored(path: String): ReadingTrace? = files[path]

    override fun folderStatus(): ReadingTraceFolderStatus = ReadingTraceFolderStatus.Ready

    override fun load(vaultRelativePath: String): ReadingTraceReadResult = when {
        vaultRelativePath in corruptPaths -> ReadingTraceReadResult.Corrupt("壊れています")
        else -> files[vaultRelativePath]
            ?.let { ReadingTraceReadResult.Valid(it) }
            ?: ReadingTraceReadResult.None
    }

    override fun save(trace: ReadingTrace): ReadingTraceSaveResult {
        saveAttempts++
        if (failSave) return ReadingTraceSaveResult.Failure("書き込めませんでした")
        saved += trace
        files[trace.vaultRelativePath] = trace
        return ReadingTraceSaveResult.Success
    }
}
