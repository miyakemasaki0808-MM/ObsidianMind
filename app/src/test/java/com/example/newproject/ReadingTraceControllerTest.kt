package com.example.newproject

import com.example.newproject.controller.ReadingPauseReason
import com.example.newproject.controller.ReadingTraceController
import com.example.newproject.controller.MemoDeleteOutcome
import com.example.newproject.controller.MemoSaveOutcome
import com.example.newproject.data.ReadingTracePersistence
import com.example.newproject.model.ReadingTrace
import com.example.newproject.model.ReadingTraceLimits
import com.example.newproject.model.ReadingVisit
import com.example.newproject.model.MarginMemo
import com.example.newproject.model.withVisit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
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
        controller.pause(ReadingPauseReason.AppBackground)
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
        controller.pause(ReadingPauseReason.AppBackground)
        advanceUntilIdle()

        controller.resume(ReadingPauseReason.AppBackground)
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

    /**
     * **冊子を眺めた時間は読書時間に入らない。**
     *
     * 冊子はルート遷移なので Activity は `onStop` しない。冊子側で明示的に止めないと、
     * 眺めていた時間が直前のノートの読書時間として積まれ、**訪問条件を満たさないはずの
     * 短い閲覧が訪問になる**（→ features/booklet_mode.md 判断3）。
     */
    @Test
    fun `冊子を眺めた時間は読書時間へ積まれない`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)

        controller.onNoteOpened("ideas/habit.md", "習慣について", null)
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = "導入")
        clock.advance(5_000L)

        // 冊子へ入る → 20秒眺める → 冊子を出る
        controller.pause(ReadingPauseReason.Booklet)
        advanceUntilIdle()
        clock.advance(20_000L)
        controller.resume(ReadingPauseReason.Booklet)

        controller.flush()
        advanceUntilIdle()

        assertNull("5秒しか読んでいないのに訪問が記録された", persistence.stored("ideas/habit.md"))
    }

    /**
     * **冊子を開いたまま背面へ回って戻っても、計測は再開しない。**
     *
     * `onStart()` はどのルートが前面かを知らないので、真偽1つで停止を持つと
     * 背面復帰が冊子の停止理由まで解いてしまう。停止理由を数える形にした理由がこれ。
     */
    @Test
    fun `冊子表示中に背面復帰しても読書時間は再開しない`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)

        controller.onNoteOpened("ideas/habit.md", "習慣について", null)
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = "導入")
        clock.advance(5_000L)

        controller.pause(ReadingPauseReason.Booklet)
        advanceUntilIdle()
        // 冊子のままホームへ出て、戻る。
        controller.pause(ReadingPauseReason.AppBackground)
        advanceUntilIdle()
        controller.resume(ReadingPauseReason.AppBackground)

        clock.advance(20_000L)
        controller.flush()
        advanceUntilIdle()

        assertNull("冊子を見ている間の20秒が読書時間へ入った", persistence.stored("ideas/habit.md"))
    }

    /**
     * **停止中に始まったセッションは、その時点から計測しない。**
     *
     * 遅いSAFで「これを読む」を押し、本文が出る前にホームへ出ると、
     * **セッションは背面で作られる**。作った時刻から数えると、本文が一度も前景に
     * 出ていないノートが訪問条件を満たす。
     */
    @Test
    fun `背面で始まったセッションは背面時間を計測しない`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)

        controller.pause(ReadingPauseReason.AppBackground)
        advanceUntilIdle()
        controller.onNoteOpened("ideas/habit.md", "習慣について", null)
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = "導入")

        clock.advance(20_000L)
        controller.resume(ReadingPauseReason.AppBackground)
        clock.advance(5_000L)
        controller.flush()
        advanceUntilIdle()

        assertNull("背面の20秒が読書時間へ入った", persistence.stored("ideas/habit.md"))
    }

    /** 復帰してから読み進めれば、**復帰後の時間だけ**で条件を満たす。 */
    @Test
    fun `復帰後に読んだ時間だけで訪問になる`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)

        controller.pause(ReadingPauseReason.AppBackground)
        advanceUntilIdle()
        controller.onNoteOpened("ideas/habit.md", "習慣について", null)
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = "導入")

        clock.advance(20_000L)
        controller.resume(ReadingPauseReason.AppBackground)
        clock.advance(10_000L)
        controller.flush()
        advanceUntilIdle()

        assertEquals(1, persistence.stored("ideas/habit.md")!!.visits.size)
    }

    /** 冊子が前面のまま読込が終わった場合も同じ。 */
    @Test
    fun `冊子表示中に始まったセッションも冊子の時間を計測しない`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)

        controller.pause(ReadingPauseReason.Booklet)
        advanceUntilIdle()
        controller.onNoteOpened("ideas/habit.md", "習慣について", null)
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = "導入")

        clock.advance(20_000L)
        controller.resume(ReadingPauseReason.Booklet)
        clock.advance(5_000L)
        controller.flush()
        advanceUntilIdle()

        assertNull("冊子を見ている間の20秒が読書時間へ入った", persistence.stored("ideas/habit.md"))
    }

    /** 背面のまま冊子を閉じても、背面の時間は積まない。 */
    @Test
    fun `背面のまま冊子を閉じても背面時間は積まない`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)

        controller.onNoteOpened("ideas/habit.md", "習慣について", null)
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = "導入")
        clock.advance(5_000L)

        controller.pause(ReadingPauseReason.Booklet)
        advanceUntilIdle()
        controller.pause(ReadingPauseReason.AppBackground)
        advanceUntilIdle()
        // 背面にいる間に冊子ルートが破棄される（effect の onDispose）。
        controller.resume(ReadingPauseReason.Booklet)

        clock.advance(20_000L)
        controller.resume(ReadingPauseReason.AppBackground)
        controller.flush()
        advanceUntilIdle()

        assertNull("背面の20秒が読書時間へ入った", persistence.stored("ideas/habit.md"))
    }

    /** 冊子から戻って読み進めれば、**冊子の20秒を除いた合計**で条件を満たす。 */
    @Test
    fun `冊子から戻って読み進めれば冊子の時間を除いた合計で訪問になる`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)

        controller.onNoteOpened("ideas/habit.md", "習慣について", null)
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = "導入")
        clock.advance(5_000L)
        controller.pause(ReadingPauseReason.Booklet)
        advanceUntilIdle()
        clock.advance(20_000L)
        controller.resume(ReadingPauseReason.Booklet)

        clock.advance(5_000L)
        controller.flush()
        advanceUntilIdle()

        val stored = persistence.stored("ideas/habit.md")!!
        assertEquals(1, stored.visits.size)
    }

    // ── 保存の寿命と失敗の扱い ──────────────────────────────────────────────

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
        controller.pause(ReadingPauseReason.AppBackground)
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
        controller.pause(ReadingPauseReason.AppBackground)
        advanceUntilIdle()
        assertEquals(20, persistence.stored("ideas/habit.md")!!.visits.single().progressPercent)

        // 復帰して読み進めるが、その保存は失敗する
        persistence.failSave = true
        controller.resume(ReadingPauseReason.AppBackground)
        controller.onReadingProgress(blockIndex = 8, blockFraction = 1f, totalBlocks = 10, sectionTitle = null)
        clock.advance(1_000L)
        controller.pause(ReadingPauseReason.AppBackground)
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
        controller.pause(ReadingPauseReason.AppBackground)
        controller.resume(ReadingPauseReason.AppBackground)
        controller.onReadingProgress(blockIndex = 8, blockFraction = 1f, totalBlocks = 10, sectionTitle = null)
        clock.advance(1_000L)
        controller.pause(ReadingPauseReason.AppBackground)
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

    // 背面にいた時間を10秒判定へ混ぜない（実際には短時間しか読んでいない）。
    @Test
    fun `time spent in the background does not count towards the threshold`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)

        controller.onNoteOpened("ideas/habit.md", "習慣について", null)
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = "導入")
        clock.advance(5_000L)
        controller.pause(ReadingPauseReason.AppBackground)
        clock.advance(600_000L) // 10分放置
        controller.resume(ReadingPauseReason.AppBackground)
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
        controller.pause(ReadingPauseReason.AppBackground)
        clock.advance(600_000L)
        controller.resume(ReadingPauseReason.AppBackground)
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
        controller.pause(ReadingPauseReason.AppBackground)
        advanceUntilIdle()
        val afterFirstPause = persistence.saveAttempts

        controller.pause(ReadingPauseReason.AppBackground)
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
        controller.pause(ReadingPauseReason.AppBackground)
        advanceUntilIdle()

        // 同期で別端末の訪問が末尾に足された
        val synced = persistence.stored("ideas/habit.md")!!
        persistence.put(synced.withVisit(ReadingVisit(9_999_999L, "別端末", 50)))

        controller.resume(ReadingPauseReason.AppBackground)
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

        controller.resume(ReadingPauseReason.AppBackground)
        controller.pause(ReadingPauseReason.AppBackground)
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

    // ── 余白メモの保存 ──────────────────────────────────────────────────────
    //
    // メモの真実は**永続ファイル・セッションの預かり・退避**の3箇所に分かれて存在しうる。
    // どれか1つを真実と見なすと、独立に並ぶ要素が静かに消える
    // （→ features/reflect_margin_memo.md §7）。

    @Test
    fun `痕跡があればメモは離脱を待たずに書かれる`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        persistence.put(storedTrace(count = 1))
        val controller = controller(persistence, clock)
        controller.onNoteOpened("ideas/habit.md", "習慣について", "doc-1")

        val outcome = controller.appendMemo("ideas/habit.md", memoOf("ここが引っかかる", at = 5_000L))
        advanceUntilIdle()

        assertEquals(MemoSaveOutcome.Saved, outcome)
        assertEquals(
            listOf("ここが引っかかる"),
            persistence.stored("ideas/habit.md")?.memos?.map { it.text }
        )
    }

    /**
     * **痕跡ファイルがまだ無い初読でも失わない。**
     *
     * 訪問は離脱・背面化でしか書かれず、検証は訪問が1件以上あることを要求する。
     * **保存の可否を「生成物があるか」に結び付けない**のがこの機能の契約なので、
     * ここは純粋に「置き場所がまだ無い」だけを表す（→ 判断3）。
     */
    @Test
    fun `痕跡が未作成でもメモは離脱時に書かれる`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)
        controller.onNoteOpened("ideas/habit.md", "習慣について", "doc-1")
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = "導入")

        val outcome = controller.appendMemo("ideas/habit.md", memoOf("初読のメモ", at = 5_000L))
        advanceUntilIdle()
        assertEquals(MemoSaveOutcome.Held, outcome)
        assertNull(persistence.stored("ideas/habit.md"))

        clock.advance(10_000L)
        controller.flush()
        advanceUntilIdle()

        val stored = persistence.stored("ideas/habit.md")!!
        assertEquals(listOf("初読のメモ"), stored.memos.map { it.text })
        assertEquals(1, stored.visits.size)
    }

    /**
     * **門番の例外。** 10秒・1ブロックは「一瞬引いてすぐ送った表示」を弾く条件だが、
     * メモを置いたならスクロールより強い関与である。通さないと、条件未達で離れた
     * 瞬間に預かったメモが消える。
     */
    @Test
    fun `メモを置いてすぐ離れても保存される`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)
        controller.onNoteOpened("ideas/habit.md", "習慣について", "doc-1")

        controller.appendMemo("ideas/habit.md", memoOf("すぐ離れる", at = 1_000L))
        advanceUntilIdle()
        clock.advance(500L)
        controller.flush()
        advanceUntilIdle()

        assertEquals(
            listOf("すぐ離れる"),
            persistence.stored("ideas/habit.md")?.memos?.map { it.text }
        )
    }

    @Test
    fun `メモが無ければ短い滞在は記録しない`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)
        controller.onNoteOpened("ideas/habit.md", "習慣について", "doc-1")
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = null)

        clock.advance(500L)
        controller.flush()
        advanceUntilIdle()

        assertTrue(persistence.saved.isEmpty())
    }

    /** **上限で置けないときは、古いものから捨てずに断る。** */
    @Test
    fun `上限に達したら置けないと返す`() = runTest {
        val persistence = FakePersistence()
        persistence.put(
            storedTrace(count = 1).copy(
                memos = (1..ReadingTraceLimits.MAX_MEMOS).map { memoOf("既存$it", at = it * 10L) }
            )
        )
        val controller = controller(persistence, TestClock())
        controller.onNoteOpened("ideas/habit.md", "習慣について", "doc-1")

        val outcome = controller.appendMemo("ideas/habit.md", memoOf("あふれる1件", at = 99_000L))
        advanceUntilIdle()

        assertEquals(MemoSaveOutcome.Full, outcome)
        assertEquals(
            "古いメモを捨てた",
            ReadingTraceLimits.MAX_MEMOS,
            persistence.stored("ideas/habit.md")?.memos?.size
        )
        assertTrue(
            "断ったのに書き込んだ",
            persistence.stored("ideas/habit.md")?.memos?.none { it.text == "あふれる1件" } == true
        )
    }

    @Test
    fun `セッションが無ければメモは失われたと返す`() = runTest {
        val persistence = FakePersistence()
        val controller = controller(persistence, TestClock())

        val outcome = controller.appendMemo("ideas/habit.md", memoOf("行き先の無いメモ", at = 1_000L))
        advanceUntilIdle()

        assertEquals(MemoSaveOutcome.Lost, outcome)
        assertTrue(persistence.saved.isEmpty())
    }

    /** 読込中にノートが変わったら、前のノートのメモを新しいノートへ預けない。 */
    @Test
    fun `読込中にノートが変わったらメモを新しいノートへ預けない`() = runTest {
        val persistence = FakePersistence()
        val controller = controller(persistence, TestClock())
        controller.onNoteOpened("ideas/habit.md", "習慣について", "doc-1")
        persistence.onLoad = {
            persistence.onLoad = null
            controller.onNoteOpened("journal/2026.md", "2026年", "doc-2")
        }

        val outcome = controller.appendMemo("ideas/habit.md", memoOf("前のノートのメモ", at = 1_000L))
        advanceUntilIdle()
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = null)
        controller.flush()
        advanceUntilIdle()

        assertEquals(MemoSaveOutcome.Lost, outcome)
        assertTrue(
            "別ノートの痕跡へ混ぜた",
            persistence.saved.none { it.memos.any { memo -> memo.text == "前のノートのメモ" } }
        )
    }

    // ── 3箇所の合流（§7 の契約）──────────────────────────────────────────

    /**
     * **契約2・3。** Aの保存が失敗して退避に積まれたまま、Bを置いて保存にも失敗する。
     * 退避を丸ごと置換する実装だと、ここでAが消える。
     */
    @Test
    fun `連続して失敗したメモは回復後に両方とも残る`() = runTest {
        val persistence = FakePersistence()
        persistence.put(storedTrace(count = 1))
        val controller = controller(persistence, TestClock())
        controller.onNoteOpened("ideas/habit.md", "習慣について", "doc-1")

        persistence.failSave = true
        controller.appendMemo("ideas/habit.md", memoOf("A", at = 1_000L))
        advanceUntilIdle()
        controller.appendMemo("ideas/habit.md", memoOf("B", at = 2_000L))
        advanceUntilIdle()

        persistence.failSave = false
        controller.appendMemo("ideas/habit.md", memoOf("C", at = 3_000L))
        advanceUntilIdle()

        assertEquals(
            listOf("A", "B", "C"),
            persistence.stored("ideas/habit.md")?.memos?.map { it.text }
        )
    }

    /**
     * **契約3。** 訪問だけの保存が成功しても、退避のメモは書けていない。
     * ノート単位で退避を丸ごと忘れると、ここでメモが消える。
     */
    @Test
    fun `訪問だけの保存が成功しても退避のメモを捨てない`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        persistence.put(storedTrace(count = 1))
        val controller = controller(persistence, clock)
        controller.onNoteOpened("ideas/habit.md", "習慣について", "doc-1")
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = "導入")

        persistence.failSave = true
        controller.appendMemo("ideas/habit.md", memoOf("退避されるメモ", at = 1_000L))
        advanceUntilIdle()

        // 訪問の保存だけが成功する契機を作る
        persistence.failSave = false
        clock.advance(10_000L)
        controller.flush()
        advanceUntilIdle()

        assertEquals(
            listOf("退避されるメモ"),
            persistence.stored("ideas/habit.md")?.memos?.map { it.text }
        )
    }

    /**
     * **契約4・5。** 退避のスナップショットを取った後に削除されたメモを、
     * 遅れて走る書き込みが**復活させない**こと。
     */
    @Test
    fun `削除したメモは遅れて走る退避の書き直しで復活しない`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        persistence.put(storedTrace(count = 1))
        val controller = controller(persistence, clock)
        controller.onNoteOpened("ideas/habit.md", "習慣について", "doc-1")

        persistence.failSave = true
        controller.appendMemo("ideas/habit.md", memoOf("消すメモ", at = 1_000L))
        advanceUntilIdle()

        persistence.failSave = false
        val deleted = controller.deleteMemo("ideas/habit.md", memoOf("消すメモ", at = 1_000L))
        advanceUntilIdle()
        assertEquals(MemoDeleteOutcome.Deleted, deleted)

        // 次の書き込み契機で退避の書き直しが走る
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = null)
        clock.advance(10_000L)
        controller.flush()
        advanceUntilIdle()

        assertTrue(
            "消したメモが復活した",
            persistence.stored("ideas/habit.md")?.memos.orEmpty().none { it.text == "消すメモ" }
        )
    }

    /** 読み出しも3箇所を合流する。ファイルだけ見せると、預かり中のメモが画面から消える。 */
    @Test
    fun `読み出しは預かり中のメモも含める`() = runTest {
        val persistence = FakePersistence()
        persistence.put(storedTrace(count = 1).copy(memos = listOf(memoOf("保存済み", at = 1_000L))))
        val controller = controller(persistence, TestClock())
        controller.onNoteOpened("ideas/habit.md", "習慣について", "doc-1")

        persistence.failSave = true
        controller.appendMemo("ideas/habit.md", memoOf("まだ書けていない", at = 2_000L))
        advanceUntilIdle()

        val memos = controller.loadMemos("ideas/habit.md")
        advanceUntilIdle()

        assertEquals(listOf("保存済み", "まだ書けていない"), memos.map { it.text })
    }

    /**
     * **上限を超えても、保存済みのメモを切り落とさない。**
     *
     * 切り詰めて書いていたころは、別端末や読み戻しで入った20件のうち末尾が消えた。
     * しかも超過分は終了済みセッションへ戻していたので、離脱すると回収できなかった。
     */
    @Test
    fun `合流が上限を超えたら訪問だけ書き、保存済みのメモを削らない`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)
        controller.onNoteOpened("ideas/habit.md", "習慣について", "doc-1")
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = null)

        // 痕跡が無いうちに1件預ける（古い時刻）。
        controller.appendMemo("ideas/habit.md", memoOf("預けた古いメモ", at = 1L))
        advanceUntilIdle()
        // その間に別の書き手が上限ぶんを保存する。
        persistence.put(
            storedTrace(count = 1).copy(
                memos = (1..ReadingTraceLimits.MAX_MEMOS).map { memoOf("既存$it", at = 100L + it) }
            )
        )

        clock.advance(10_000L)
        controller.flush()
        advanceUntilIdle()

        val stored = persistence.stored("ideas/habit.md")!!
        assertEquals(
            "保存済みのメモを削った",
            (1..ReadingTraceLimits.MAX_MEMOS).map { "既存$it" },
            stored.memos.map { it.text }
        )
        // 預けた分は消えていない。上限が空けば次の契機で書かれる。
        assertTrue(
            "預けたメモを失った",
            controller.loadMemos("ideas/habit.md").any { it.text == "預けた古いメモ" }
        )
    }

    /**
     * **起動して待っている訪問保存より先に削除しても、メモは戻らない**（→ §7 の契約4）。
     *
     * 起動前に預かりをローカルへ取り出していたころは、それが削除の届かない第4の写しになり、
     * 錠を共有していても書き戻された。
     */
    @Test
    fun `待機中の訪問保存より先に削除してもメモは復活しない`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val sharedMutex = Mutex()
        val controller = controller(persistence, clock, writeMutex = sharedMutex)
        controller.onNoteOpened("ideas/habit.md", "習慣について", "doc-1")
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = null)
        val target = memoOf("消すメモ", at = 1_000L)
        controller.appendMemo("ideas/habit.md", target)
        advanceUntilIdle()

        // 訪問保存を起動し、錠の手前で待たせる。
        sharedMutex.lock()
        clock.advance(10_000L)
        controller.pause(ReadingPauseReason.AppBackground)
        advanceUntilIdle()

        // 待っているあいだに削除を完了させる（削除も同じ錠を待つので、順に入る）。
        sharedMutex.unlock()
        controller.deleteMemo("ideas/habit.md", target)
        advanceUntilIdle()

        controller.resume(ReadingPauseReason.AppBackground)
        clock.advance(10_000L)
        controller.flush()
        advanceUntilIdle()

        assertTrue(
            "消したメモが訪問保存で戻った",
            persistence.stored("ideas/habit.md")?.memos.orEmpty().none { it.text == "消すメモ" }
        )
    }

    /**
     * **読めなかったファイルを「無い」と扱わない。**
     *
     * `None` はファイルが無いときだけでなく読み取りに失敗したときにも返る。
     * 成功と言うと、ディスクに残ったメモを画面から消したまま確定してしまう。
     */
    @Test
    fun `痕跡を読めないときの削除は成功と言わない`() = runTest {
        val persistence = FakePersistence()
        persistence.put(storedTrace(count = 1).copy(memos = listOf(memoOf("ディスクに残る", at = 100L))))
        val controller = controller(persistence, TestClock())
        controller.onNoteOpened("ideas/habit.md", "習慣について", "doc-1")
        persistence.unreadablePaths += "ideas/habit.md"

        val outcome = controller.deleteMemo("ideas/habit.md", memoOf("ディスクに残る", at = 100L))
        advanceUntilIdle()

        assertEquals(MemoDeleteOutcome.Failed, outcome)
        assertEquals(
            listOf("ディスクに残る"),
            persistence.stored("ideas/habit.md")?.memos?.map { it.text }
        )
    }

    /** 壊れている痕跡でも同じ — 中身を判断できないので消せたと言わない。 */
    @Test
    fun `壊れた痕跡への削除は成功と言わない`() = runTest {
        val persistence = FakePersistence()
        persistence.put(storedTrace(count = 1).copy(memos = listOf(memoOf("残る", at = 100L))))
        persistence.corruptPaths += "ideas/habit.md"
        val controller = controller(persistence, TestClock())
        controller.onNoteOpened("ideas/habit.md", "習慣について", "doc-1")

        val outcome = controller.deleteMemo("ideas/habit.md", memoOf("残る", at = 100L))
        advanceUntilIdle()

        assertEquals(MemoDeleteOutcome.Failed, outcome)
    }

    /**
     * **読めなかったファイルを新規扱いしない。**
     *
     * `None` は不在と読み取り失敗の両方で返る。畳むと、訪問の保存が
     * **読めていないだけの保存済みメモを上書きして消す。**
     * 削除側だけ直しても、保存側に同じ規則を当てなければ残る。
     */
    @Test
    fun `痕跡を読めないときは訪問保存で上書きしない`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        persistence.put(storedTrace(count = 1).copy(memos = listOf(memoOf("保存済み", at = 100L))))
        val controller = controller(persistence, clock)
        controller.onNoteOpened("ideas/habit.md", "習慣について", "doc-1")
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = null)

        // 読み取りだけが失敗する状態にしてから、新しいメモを預ける。
        persistence.unreadablePaths += "ideas/habit.md"
        controller.appendMemo("ideas/habit.md", memoOf("預けた", at = 200L))
        advanceUntilIdle()
        clock.advance(10_000L)
        controller.flush()
        advanceUntilIdle()

        assertEquals(
            "読めないファイルを新規で上書きした",
            listOf("保存済み"),
            persistence.stored("ideas/habit.md")?.memos?.map { it.text }
        )

        // 読めるようになれば、預かりは次の契機で載る。
        persistence.unreadablePaths.clear()
        controller.onNoteOpened("ideas/habit.md", "習慣について", "doc-1")
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = null)
        clock.advance(10_000L)
        controller.flush()
        advanceUntilIdle()

        assertEquals(
            listOf("保存済み", "預けた"),
            persistence.stored("ideas/habit.md")?.memos?.map { it.text }
        )
    }

    /** 未作成なら、これまでどおり新規として作れる。 */
    @Test
    fun `本当に未作成なら訪問保存で新規に作る`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        val controller = controller(persistence, clock)
        controller.onNoteOpened("ideas/habit.md", "習慣について", "doc-1")
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = null)

        controller.appendMemo("ideas/habit.md", memoOf("初読のメモ", at = 200L))
        advanceUntilIdle()
        clock.advance(10_000L)
        controller.flush()
        advanceUntilIdle()

        assertEquals(
            listOf("初読のメモ"),
            persistence.stored("ideas/habit.md")?.memos?.map { it.text }
        )
    }

    /**
     * **削除が失敗したら、預かりも退避も元のまま残す。**
     *
     * 先にメモリ側を消していたころは、永続側が `Failed` を返しても預かりが戻らず、
     * **画面の一覧だけが復旧して、次に開くと消えていた。**
     */
    @Test
    fun `削除に失敗したメモは預かりから消えない`() = runTest {
        val persistence = FakePersistence()
        val controller = controller(persistence, TestClock())
        controller.onNoteOpened("ideas/habit.md", "習慣について", "doc-1")
        val target = memoOf("預けたメモ", at = 1_000L)
        controller.appendMemo("ideas/habit.md", target)
        advanceUntilIdle()

        // 置き場を列挙できない＝不在を確かめられない。
        persistence.listable = false
        val outcome = controller.deleteMemo("ideas/habit.md", target)
        advanceUntilIdle()

        assertEquals(MemoDeleteOutcome.Failed, outcome)
        assertEquals(
            "失敗したのに預かりから消えた",
            listOf("預けたメモ"),
            controller.loadMemos("ideas/habit.md").map { it.text }
        )

        // 回復すれば消せる。
        persistence.listable = true
        assertEquals(
            MemoDeleteOutcome.Deleted,
            controller.deleteMemo("ideas/habit.md", target)
        )
    }

    /** Vault切替で退避したメモは新しいVaultへ書かない。 */
    @Test
    fun `Vault切替で退避したメモは書かれない`() = runTest {
        val clock = TestClock()
        val persistence = FakePersistence()
        persistence.put(storedTrace(count = 1))
        val vault = FakeVault()
        val controller = controller(persistence, clock, vault)
        controller.onNoteOpened("ideas/habit.md", "習慣について", "doc-1")

        persistence.failSave = true
        controller.appendMemo("ideas/habit.md", memoOf("旧Vaultのメモ", at = 1_000L))
        advanceUntilIdle()

        persistence.failSave = false
        vault.key = VAULT_B
        controller.discard()
        controller.onNoteOpened("ideas/habit.md", "習慣について", "doc-1")
        controller.onReadingProgress(blockIndex = 1, blockFraction = 1f, totalBlocks = 10, sectionTitle = null)
        clock.advance(10_000L)
        controller.flush()
        advanceUntilIdle()

        assertTrue(
            "旧Vaultのメモを新しいVaultへ書いた",
            persistence.saved.none { it.memos.any { memo -> memo.text == "旧Vaultのメモ" } }
        )
    }

}

// --- ヘルパ ---------------------------------------------------------------

private fun TestScope.controller(
    persistence: ReadingTracePersistence,
    clock: TestClock,
    vault: FakeVault = FakeVault(),
    persistScope: CoroutineScope = this,
    writeMutex: Mutex = Mutex()
): ReadingTraceController {
    val dispatcher = StandardTestDispatcher(testScheduler)
    return ReadingTraceController(
        persistScope = persistScope,
        persistence = persistence,
        currentVaultKey = { vault.key },
        clock = clock::now,
        ioDispatcher = dispatcher,
        writeMutex = writeMutex
    )
}
