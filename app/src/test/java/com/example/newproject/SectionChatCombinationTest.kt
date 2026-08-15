package com.example.newproject

import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiTimeoutException
import com.example.newproject.controller.QuizController
import com.example.newproject.controller.SectionChatController
import com.example.newproject.domain.markdown.NoteSection
import com.example.newproject.fakes.FakeAiClient
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.NoteUiStateStore
import com.example.newproject.model.state.AiNoticeAction
import com.example.newproject.model.state.QuizState
import com.example.newproject.model.state.SectionChatProblem
import com.example.newproject.model.state.isQuizActionEnabled
import com.example.newproject.model.state.quizNotice
import com.example.newproject.model.state.showsQuizAction
import com.example.newproject.model.state.SuggestionsDisplay
import com.example.newproject.model.state.suggestionsDisplay
import com.example.newproject.ui.vigilith.VigilithActionStatus
import com.example.newproject.ui.vigilith.sectionChatStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **要約と回答が同時に動く／同時に壊れる組み合わせを、面で押さえる。**
 *
 * ## なぜ独立したクラスなのか
 *
 * `SectionChatController` は**独立した2つのJob**（要約・回答）を持ち、
 * それぞれに「実行中／失敗／端末AIが使えない」の3状態がある。
 * 機能ごとのテストは各Jobを単独で通すので、**片方が走っている最中にもう片方を
 * 操作する経路が丸ごと空く**。実際にそこで2件の欠陥を出した。
 *
 * - 要約の再試行が共通の `cancelJobs()` を呼び、**走行中の回答を巻き添えにして
 *   `isGenerating` を真のまま固めた**（「回答を生成中…」が永久に残る）
 * - 端末AIが使えないだけの状態が派生状態で `Working` に落ち、
 *   **生成していないのにスピナーが回り続けた**
 *
 * どちらも「直した場所の隣」で壊れている。**片方ずつのテストでは永久に出ない。**
 *
 * ## ここが守る不変条件
 *
 * 1. **一方の操作が、他方の走行中Jobを止めない**
 * 2. **どの経路を通っても、走っていないのに `isSummaryLoading` / `isGenerating` が残らない**
 * 3. **理由の欄（`summaryProblem` / `answerProblem`）は互いを消さない**
 * 4. 派生状態（`sectionChatStatus`）が、走っていないのに `Working` にならない
 * 5. **クイズが使えない理由は、要約側の失敗表示に相乗りしない**
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SectionChatCombinationTest {

    // ── 1. 実行中 × もう片方の操作（両方向）─────────────────────────

    /** 回答の走行中に要約を再試行しても、回答は生き続ける。 */
    @Test
    fun `回答実行中に要約を再試行しても回答は止まらない`() = runTest {
        val env = Env(this)
        env.failSummaryThenOpen()

        val answer = env.startAnswerAndHold("これはどういう意味ですか")
        assertTrue(env.chat().isGenerating)

        env.ai.onGenerate = { "生成された要約" }
        env.controller.retrySummary()
        advanceUntilIdle()

        assertEquals("生成された要約", env.chat().summary)
        assertTrue("走行中の回答を巻き添えにしない", env.chat().isGenerating)

        answer.complete("生成された回答")
        advanceUntilIdle()
        assertFalse(env.chat().isGenerating)
        assertEquals(2, env.chat().messages.size)
    }

    /** 要約の走行中に回答を再試行しても、要約は生き続ける。 */
    @Test
    fun `要約実行中に回答を再試行しても要約は止まらない`() = runTest {
        val env = Env(this)
        val gates = env.holdEveryGeneration()

        env.controller.open(SECTION)
        advanceUntilIdle()
        assertTrue("要約が走っていること", env.chat().isSummaryLoading)
        val summaryGate = gates.single()

        // 要約を待っている間に質問し、その回答も保留させる。
        env.controller.sendMessage("これはどういう意味ですか")
        advanceUntilIdle()
        assertEquals(2, gates.size)

        env.controller.retryAnswer()
        advanceUntilIdle()

        assertTrue("走行中の要約を巻き添えにしない", env.chat().isSummaryLoading)
        summaryGate.complete("生成された要約")
        advanceUntilIdle()
        assertEquals("生成された要約", env.chat().summary)
        assertFalse(env.chat().isSummaryLoading)

        // 保留したままの生成があるとテストスコープが終われない。
        env.controller.cancelAndClear()
        advanceUntilIdle()
    }

    // ── 2. 失敗 × 失敗 ─────────────────────────────────────────

    /** 両方の理由が並んでも、互いを消さない。 */
    @Test
    fun `要約と回答が同時に失敗しても理由が両方残る`() = runTest {
        val env = Env(this)
        env.ai.onGenerate = { throw AiTimeoutException("タイムアウト") }
        env.controller.open(SECTION)
        advanceUntilIdle()
        env.controller.sendMessage("これはどういう意味ですか")
        advanceUntilIdle()

        assertNotNull(env.chat().summaryProblem)
        assertNotNull(env.chat().answerProblem)
        assertFalse(env.chat().isSummaryLoading)
        assertFalse(env.chat().isGenerating)
    }

    // ── 3. 端末AIの状態 × 回答の成否 ───────────────────────────

    /** 要約が端末AIの状態で止まっていても、回復後の回答は通り、理由は残る。 */
    @Test
    fun `要約が端末AI状態でも回復後の回答は成功する`() = runTest {
        val env = Env(this)
        env.ai.availability = AiAvailability.Unsupported
        env.controller.open(SECTION)
        advanceUntilIdle()
        assertTrue(env.chat().summaryProblem is SectionChatProblem.AiStatus)

        env.ai.availability = AiAvailability.Ready
        env.ai.onGenerate = { "生成された回答" }
        env.controller.sendMessage("これはどういう意味ですか")
        advanceUntilIdle()

        assertEquals(2, env.chat().messages.size)
        assertNull(env.chat().answerProblem)
        assertTrue("要約側の理由を消さない", env.chat().summaryProblem is SectionChatProblem.AiStatus)
        assertFalse(env.chat().isGenerating)
    }

    /** 要約が端末AIの状態のまま回答も失敗したら、2つの理由が別の欄に並ぶ。 */
    @Test
    fun `要約が端末AI状態で回答が失敗したら理由の種類が分かれる`() = runTest {
        val env = Env(this)
        env.ai.availability = AiAvailability.Unsupported
        env.controller.open(SECTION)
        advanceUntilIdle()

        env.ai.availability = AiAvailability.Ready
        env.ai.onGenerate = { throw AiTimeoutException("タイムアウト") }
        env.controller.sendMessage("これはどういう意味ですか")
        advanceUntilIdle()

        assertTrue(env.chat().summaryProblem is SectionChatProblem.AiStatus)
        assertTrue(env.chat().answerProblem is SectionChatProblem.GenerationFailed)
    }

    // ── 4. 走っていないのにフラグ・派生状態が残らない ────────────

    /**
     * **どの終わり方をしても、走行フラグは残らない。**
     *
     * 走行中Jobを止める経路をすべて通し、`isSummaryLoading` / `isGenerating` が
     * 真のまま固まらないことを確かめる。ここが今回の欠陥の本体だった。
     */
    @Test
    fun `セッションを破棄すれば走行フラグごと消える`() = runTest {
        val env = Env(this)
        env.holdEveryGeneration()
        env.controller.open(SECTION)
        advanceUntilIdle()
        env.controller.sendMessage("これはどういう意味ですか")
        advanceUntilIdle()
        assertTrue(env.chat().isSummaryLoading)
        assertTrue(env.chat().isGenerating)

        env.controller.cancelAndClear()
        advanceUntilIdle()

        assertNull("セッションごと消えるので、残るフラグ自体が無い", env.state.value.sectionChat)
    }

    /** 端末AIが使えず要約も無いなら、派生状態は「生成中」にならない。 */
    @Test
    fun `端末AIが使えないだけなら派生状態はWorkingにならない`() = runTest {
        val env = Env(this)
        env.ai.availability = AiAvailability.Unsupported
        env.controller.open(SECTION)
        advanceUntilIdle()

        assertEquals(VigilithActionStatus.Idle, sectionChatStatus(env.chat()))
    }

    /** 生成が落ちたときはエラーとして見せる（状態の説明と混ぜない）。 */
    @Test
    fun `生成が落ちたときだけ派生状態はErrorになる`() = runTest {
        val env = Env(this)
        env.ai.onGenerate = { throw AiTimeoutException("タイムアウト") }
        env.controller.open(SECTION)
        advanceUntilIdle()

        assertEquals(VigilithActionStatus.Error, sectionChatStatus(env.chat()))
    }

    // ── 5. 質問候補の進行表示 ────────────────────────────────

    /**
     * **候補の進行を空リストから推測しない。**
     *
     * 推測していたころは、端末AIが使えず候補生成を**始めてすらいない**のに
     * 「質問候補を準備中…」が永久に残った。派生状態が Idle なのにシート内だけ
     * 処理中に見える、という食い違いも起きていた。
     */
    @Test
    fun `端末AIが使えないなら質問候補は準備中にならない`() = runTest {
        val env = Env(this)
        env.ai.availability = AiAvailability.Unsupported
        env.controller.open(SECTION)
        advanceUntilIdle()

        assertEquals(SuggestionsDisplay.None, env.chat().suggestionsDisplay())
        // 受理条件: 派生状態が Idle のとき、シート内に処理中表示が同時成立しない。
        // 候補Jobを始めていないので、走行判定へ足しても Idle のままであること。
        assertFalse(env.chat().isSuggestionsLoading)
        assertEquals(VigilithActionStatus.Idle, sectionChatStatus(env.chat()))
    }

    /** 候補生成が例外で落ちても、処理中表示は解除される。 */
    @Test
    fun `候補生成が落ちても準備中は解除される`() = runTest {
        val env = Env(this)
        var call = 0
        env.ai.onGenerate = {
            call++
            if (call == 1) "セクションの要約" else throw AiTimeoutException("タイムアウト")
        }
        env.controller.open(SECTION)
        advanceUntilIdle()

        assertEquals("セクションの要約", env.chat().summary)
        assertFalse(env.chat().isSuggestionsLoading)
        assertEquals(SuggestionsDisplay.None, env.chat().suggestionsDisplay())
    }

    /** 正常に0件で終わった場合も終端として扱う。 */
    @Test
    fun `候補が0件で終わっても準備中は解除される`() = runTest {
        val env = Env(this)
        var call = 0
        env.ai.onGenerate = { if (++call == 1) "セクションの要約" else "   " }
        env.controller.open(SECTION)
        advanceUntilIdle()

        assertTrue(env.chat().suggestions.isEmpty())
        assertEquals(SuggestionsDisplay.None, env.chat().suggestionsDisplay())
    }

    /** 走っている間だけ「準備中」を出す。 */
    @Test
    fun `候補生成を保留している間だけ準備中になる`() = runTest {
        val env = Env(this)
        val gates = env.holdEveryGeneration()
        env.controller.open(SECTION)
        advanceUntilIdle()

        // 要約待ちの時点でも「これから来る」ので準備中でよい。
        assertEquals(SuggestionsDisplay.Loading, env.chat().suggestionsDisplay())
        gates[0].complete("セクションの要約")
        advanceUntilIdle()

        assertTrue("候補が走っていること", env.chat().isSuggestionsLoading)
        assertEquals(SuggestionsDisplay.Loading, env.chat().suggestionsDisplay())
        // **シートと派生表示を食い違わせない。** 候補生成中に「完了」を示すと、
        // 全画面FABでは「AI生成完了。タップで開く」と読まれる。
        assertEquals(VigilithActionStatus.Working, sectionChatStatus(env.chat()))

        gates[1].complete("質問1\n質問2")
        advanceUntilIdle()

        assertFalse(env.chat().isSuggestionsLoading)
        assertEquals(SuggestionsDisplay.Ready, env.chat().suggestionsDisplay())
        assertEquals(VigilithActionStatus.Ready, sectionChatStatus(env.chat()))
    }

    // ── 6. 出せない案内は、実在する操作しか求めない ──────────────

    /**
     * **セクションチャットは `Download` の導線を作らない。**
     *
     * 機能正本は「ここではモデルDLを始めない」と決めているのに、共通変換の
     * `Download` action をそのまま運んでいた。終端UIは `onDownload` を渡さないので
     * **ボタンは描かれず、「開始してください」という文言だけが残った** —
     * 押す操作が存在しない案内になっていた。
     */
    @Test
    fun `未取得の案内は開始を求めず、DLも始めない`() = runTest {
        val env = Env(this)
        env.ai.availability = AiAvailability.NeedsDownload
        env.controller.open(SECTION)
        advanceUntilIdle()

        val notice = (env.chat().summaryProblem as SectionChatProblem.AiStatus).notice
        assertNotEquals(
            "ここから開始できないので Download を運ばない",
            AiNoticeAction.Download,
            notice.action
        )
        assertFalse("存在しない操作を求めない: ${notice.message}", notice.message.contains("開始してください"))
        assertEquals("シート操作でDLを始めない", 0, env.ai.downloadCalls)
        // あとで使えるようになるので、入口は閉じない。
        assertTrue(notice.canTryAgainLater)
    }

    /** 回答側の案内も同じ契約に従う。 */
    @Test
    fun `回答側の未取得の案内も開始を求めない`() = runTest {
        val env = Env(this)
        env.controller.open(SECTION)
        advanceUntilIdle()

        env.ai.availability = AiAvailability.NeedsDownload
        env.controller.sendMessage("これはどういう意味ですか")
        advanceUntilIdle()

        val notice = (env.chat().answerProblem as SectionChatProblem.AiStatus).notice
        assertNotEquals(AiNoticeAction.Download, notice.action)
        assertEquals(0, env.ai.downloadCalls)
    }

    // ── 5. 要約が成功 × クイズだけ使えない ────────────────────────

    /**
     * **要約が成功していても、クイズが使えない理由はクイズの欄に出る。**
     *
     * 実機で見つかった欠陥の再現順序そのもの: 要約までは `Ready` で通り、
     * そのあとクイズの状態確認だけが `Unsupported` を返す。
     * このときシートは状態をボタンのラベルへ潰し、「クイズを使えません」とだけ出したうえで
     * **ボタンを無効にした** — 理由を描く `QuizScreen` へ到達する手が無くなった。
     *
     * 要約側の表示は正常（`summaryProblem` は null）なので、
     * **要約の失敗欄に相乗りする実装ではこの面が埋まらない。**
     */
    @Test
    fun `要約が成功した後にクイズだけ恒久非対応なら理由が出て再試行は出ない`() = runTest {
        val env = Env(this)
        env.controller.open(SECTION)
        advanceUntilIdle()
        assertNotNull("要約は成功していること", env.chat().summary)
        assertNull("要約側は失敗表示を持たないこと", env.chat().summaryProblem)

        env.ai.availability = AiAvailability.Unsupported
        env.quiz.create("対象ノート.md", "本文")
        advanceUntilIdle()

        val quizState = env.state.value.quizState
        assertTrue("説明状態であること: $quizState", quizState is QuizState.AiNotice)
        // 理由がクイズ欄から取れる。**QuizScreen を開けることを前提にしない。**
        val notice = requireNotNull(quizState.quizNotice())
        assertTrue("理由が非空であること", notice.message.isNotBlank())
        assertFalse("恒久非対応では再試行を出さない", notice.canTryAgainLater)
        assertFalse("押せないボタンを理由の隣に並べない", quizState.showsQuizAction())
        assertFalse(quizState.isQuizActionEnabled())
    }

    /** 同じ順序で一時的な不可なら、理由と再試行の両方が同じ場所に出る。 */
    @Test
    fun `要約が成功した後にクイズだけ一時的に使えないなら理由と再試行が出る`() = runTest {
        val env = Env(this)
        env.controller.open(SECTION)
        advanceUntilIdle()
        assertNull(env.chat().summaryProblem)

        env.ai.availability =
            AiAvailability.TemporarilyUnavailable(IllegalStateException("AICore not bound"))
        env.quiz.create("対象ノート.md", "本文")
        advanceUntilIdle()

        val quizState = env.state.value.quizState
        val notice = requireNotNull(quizState.quizNotice())
        assertTrue("時間をおけば変わることが読み取れること", notice.canTryAgainLater)
        assertEquals(AiNoticeAction.Retry, notice.action)
        assertTrue("押し直せること", quizState.showsQuizAction())
        assertTrue(quizState.isQuizActionEnabled())
    }

    // ── 組み立て ────────────────────────────────────────────

    private class Env(private val scope: TestScope) {
        val ai = FakeAiClient { "セクションの要約" }
        val state = NoteUiStateStore(NoteUiState())
        val controller = SectionChatController(
            scope,
            ai,
            state.sectionChatWriter,
            StandardTestDispatcher(scope.testScheduler)
        )

        /** 同じ端末・同じ `AiClient` をクイズも見る（状態確認の結果は途中で変わりうる）。 */
        val quiz = QuizController(
            scope,
            ai,
            state.quizWriter,
            StandardTestDispatcher(scope.testScheduler)
        )

        fun chat() = requireNotNull(state.value.sectionChat)

        /** 生成をすべて保留させ、保留中の口を返す。順に 要約 → 候補質問 → 回答。 */
        fun holdEveryGeneration(): MutableList<CompletableDeferred<String>> {
            val gates = mutableListOf<CompletableDeferred<String>>()
            ai.onGenerate = { CompletableDeferred<String>().also { gates += it }.await() }
            return gates
        }

        /** 要約が落ちた状態でセッションを開く（`summary` が null のまま残る）。 */
        fun failSummaryThenOpen() {
            ai.onGenerate = { throw AiTimeoutException("タイムアウト") }
            controller.open(SECTION)
            scope.advanceUntilIdle()
        }

        /** 質問を送り、その回答を保留したままにする。 */
        fun startAnswerAndHold(question: String): CompletableDeferred<String> {
            val gate = CompletableDeferred<String>()
            ai.onGenerate = { gate.await() }
            controller.sendMessage(question)
            scope.advanceUntilIdle()
            return gate
        }
    }

    private companion object {
        val SECTION = NoteSection("対象セクション", 2, "## 対象セクション\n本文")
    }
}
