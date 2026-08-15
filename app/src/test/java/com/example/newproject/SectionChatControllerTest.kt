package com.example.newproject

import com.example.newproject.controller.SectionChatController
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.state.AiNoticeAction
import com.example.newproject.model.state.ChatRole
import com.example.newproject.model.state.SectionChatProblem
import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiTimeoutException
import com.example.newproject.domain.markdown.NoteSection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.example.newproject.model.NoteUiStateStore
import com.example.newproject.fakes.FakeAiClient
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SectionChatControllerTest {

    @Test
    fun `シートを閉じても要約生成が継続して結果が保持される`() = runTest {
        val (aiClient, summaryResponse) = sectionChatAi()
        val state = NoteUiStateStore(NoteUiState())
        val controller = SectionChatController(
            this,
            aiClient,
            state.sectionChatWriter,
            StandardTestDispatcher(testScheduler)
        )

        controller.open(NoteSection("対象セクション", 2, "## 対象セクション\n本文"))
        runCurrent()

        assertTrue(state.value.isSectionChatSheetVisible)
        assertTrue(state.value.sectionChat?.isSummaryLoading == true)

        controller.dismissSheet()
        assertFalse(state.value.isSectionChatSheetVisible)
        assertNotNull(state.value.sectionChat)

        summaryResponse.complete("生成された要約")
        advanceUntilIdle()

        assertFalse(state.value.isSectionChatSheetVisible)
        assertEquals("生成された要約", state.value.sectionChat?.summary)
        assertFalse(state.value.sectionChat?.isSummaryLoading ?: true)
        assertEquals(listOf("質問1", "質問2"), state.value.sectionChat?.suggestions)
    }

    @Test
    fun `生成中に再度開いても二重生成せず元のセクションを再表示する`() = runTest {
        val (aiClient, summaryResponse) = sectionChatAi()
        val state = NoteUiStateStore(NoteUiState())
        val controller = SectionChatController(
            this,
            aiClient,
            state.sectionChatWriter,
            StandardTestDispatcher(testScheduler)
        )

        controller.open(NoteSection("最初のセクション", 2, "最初の本文"))
        runCurrent()
        controller.dismissSheet()

        controller.open(NoteSection("スクロール先", 2, "別の本文"))
        runCurrent()

        assertTrue(state.value.isSectionChatSheetVisible)
        assertEquals("最初のセクション", state.value.sectionChat?.sectionTitle)
        assertEquals(1, aiClient.generateCalls)

        controller.cancelAndClear()
    }

    @Test
    fun `完了後に吹き出しを開くと再生成せず既存結果を表示する`() = runTest {
        val (aiClient, summaryResponse) = sectionChatAi()
        val state = NoteUiStateStore(NoteUiState())
        val controller = SectionChatController(
            this,
            aiClient,
            state.sectionChatWriter,
            StandardTestDispatcher(testScheduler)
        )

        controller.open(NoteSection("対象", 2, "本文"))
        runCurrent()
        summaryResponse.complete("完成した要約")
        advanceUntilIdle()
        val callsAfterCompletion = aiClient.generateCalls

        controller.dismissSheet()
        controller.open(NoteSection("別の位置", 2, "別本文"))
        runCurrent()

        assertTrue(state.value.isSectionChatSheetVisible)
        assertEquals("完成した要約", state.value.sectionChat?.summary)
        assertEquals(callsAfterCompletion, aiClient.generateCalls)
    }

    @Test
    fun `明示終了すると生成をキャンセルしてセッションを破棄する`() = runTest {
        val (aiClient, summaryResponse) = sectionChatAi()
        val state = NoteUiStateStore(NoteUiState())
        val controller = SectionChatController(
            this,
            aiClient,
            state.sectionChatWriter,
            StandardTestDispatcher(testScheduler)
        )

        controller.open(NoteSection("対象", 2, "本文"))
        runCurrent()
        controller.cancelAndClear()

        assertNull(state.value.sectionChat)
        assertFalse(state.value.isSectionChatSheetVisible)

        summaryResponse.complete("キャンセル後の結果")
        advanceUntilIdle()
        assertNull(state.value.sectionChat)
    }

    /**
     * **状態の説明を `error` へ文字列で入れない。**
     *
     * 文字列だけにすると導線（[AiNoticeAction]）が消えて再試行できず、
     * さらにシートが赤いエラー表示で描いてしまう（状態の説明は失敗ではない）。
     */
    @Test
    fun `一時的に使えないときは再試行できる説明を持つ`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = SectionChatController(
            this,
            FakeAiClient(
                AiAvailability.TemporarilyUnavailable(IllegalStateException("AICore not bound"))
            ),
            state.sectionChatWriter,
            StandardTestDispatcher(testScheduler)
        )

        controller.open(NoteSection("対象セクション", 2, "## 対象セクション\n本文"))
        advanceUntilIdle()

        val chat = requireNotNull(state.value.sectionChat)
        val problem = requireNotNull(chat.summaryProblem)
        assertTrue("生成の失敗として扱わないこと", problem is SectionChatProblem.AiStatus)
        assertEquals(AiNoticeAction.Retry, (problem as SectionChatProblem.AiStatus).notice.action)
        assertFalse(chat.isSummaryLoading)
    }

    /** **非対応には再試行導線を出さない。** 何度押しても同じ答えが返る。 */
    @Test
    fun `非対応の説明は再試行導線を持たない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = SectionChatController(
            this,
            FakeAiClient(AiAvailability.Unsupported),
            state.sectionChatWriter,
            StandardTestDispatcher(testScheduler)
        )

        controller.open(NoteSection("対象セクション", 2, "## 対象セクション\n本文"))
        advanceUntilIdle()

        val problem = requireNotNull(state.value.sectionChat?.summaryProblem)
        assertEquals(
            AiNoticeAction.None,
            (problem as SectionChatProblem.AiStatus).notice.action
        )
    }

    /** 再試行は開いているセクションのまま試し直す（別のセクションへは移らない）。 */
    @Test
    fun `再試行すると同じセクションで生成し直す`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val ai = FakeAiClient(
            AiAvailability.TemporarilyUnavailable(IllegalStateException("boom"))
        ) { "生成された要約" }
        val controller = SectionChatController(
            this,
            ai,
            state.sectionChatWriter,
            StandardTestDispatcher(testScheduler)
        )

        controller.open(NoteSection("対象セクション", 2, "## 対象セクション\n本文"))
        advanceUntilIdle()
        assertNotNull(state.value.sectionChat?.summaryProblem)

        ai.availability = AiAvailability.Ready
        controller.retrySummary()
        advanceUntilIdle()

        val chat = requireNotNull(state.value.sectionChat)
        assertNull(chat.summaryProblem)
        assertEquals("生成された要約", chat.summary)
        assertEquals("対象セクション", chat.sectionTitle)
    }

    /**
     * **回答が出せなかったときの再試行は、その質問を作り直す。**
     *
     * 説明を畳むだけだと**未回答の発言だけがログに残り**、同じ候補を押し直すと
     * 質問が重複する。既存テストは要約側の再試行しか通していなかった。
     */
    @Test
    fun `回答が出せなかった質問は再試行で作り直される`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val ai = FakeAiClient { "セクションの要約" }
        val controller = SectionChatController(
            this,
            ai,
            state.sectionChatWriter,
            StandardTestDispatcher(testScheduler)
        )

        controller.open(NoteSection("対象セクション", 2, "## 対象セクション\n本文"))
        advanceUntilIdle()

        // 質問を送った時点で端末AIが使えなくなる。
        ai.availability =
            AiAvailability.TemporarilyUnavailable(IllegalStateException("AICore not bound"))
        controller.sendMessage("これはどういう意味ですか")
        advanceUntilIdle()

        val afterFailure = requireNotNull(state.value.sectionChat)
        assertEquals(
            AiNoticeAction.Retry,
            (requireNotNull(afterFailure.answerProblem) as SectionChatProblem.AiStatus).notice.action
        )
        assertEquals(1, afterFailure.messages.size)
        assertEquals(ChatRole.User, afterFailure.messages.single().role)

        ai.availability = AiAvailability.Ready
        ai.onGenerate = { "生成された回答" }
        controller.retryAnswer()
        advanceUntilIdle()

        val afterRetry = requireNotNull(state.value.sectionChat)
        assertNull(afterRetry.answerProblem)
        // **質問は積み直さない。** 積み直すと再試行のたびに重複する。
        assertEquals(2, afterRetry.messages.size)
        assertEquals("これはどういう意味ですか", afterRetry.messages.first().text)
        assertEquals(ChatRole.Ai, afterRetry.messages.last().role)
        assertEquals("生成された回答", afterRetry.messages.last().text)
        assertFalse(afterRetry.isGenerating)
    }

    /**
     * **生成が例外で落ちたときも再試行できる。**
     *
     * 回答の失敗を要約と同じ `error` 欄へ入れていたころは、**要約の表示が優先されて
     * 文言が出ず、未回答の質問だけが残った**（タイムアウト・出力打ち切りがこれ）。
     * availability の失敗しか通していなかったため、テストもすり抜けていた。
     */
    @Test
    fun `回答がタイムアウトしても文言が残り再試行で作り直される`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val ai = FakeAiClient { "セクションの要約" }
        val controller = SectionChatController(
            this,
            ai,
            state.sectionChatWriter,
            StandardTestDispatcher(testScheduler)
        )

        controller.open(NoteSection("対象セクション", 2, "## 対象セクション\n本文"))
        advanceUntilIdle()

        ai.onGenerate = { throw AiTimeoutException("AI応答がタイムアウトしました（60秒）") }
        controller.sendMessage("これはどういう意味ですか")
        advanceUntilIdle()

        val afterFailure = requireNotNull(state.value.sectionChat)
        // **要約の欄へ入れない。** 入れると要約が優先されて画面から消える。
        assertNull(afterFailure.summaryProblem)
        assertEquals(
            SectionChatProblem.GenerationFailed("AI応答がタイムアウトしました（60秒）"),
            afterFailure.answerProblem
        )
        assertEquals("セクションの要約", afterFailure.summary)
        assertEquals(1, afterFailure.messages.size)
        assertFalse(afterFailure.isGenerating)

        ai.onGenerate = { "生成された回答" }
        controller.retryAnswer()
        advanceUntilIdle()

        val afterRetry = requireNotNull(state.value.sectionChat)
        assertNull(afterRetry.answerProblem)
        assertEquals(2, afterRetry.messages.size)
        assertEquals(ChatRole.Ai, afterRetry.messages.last().role)
        assertEquals("生成された回答", afterRetry.messages.last().text)
    }

    /** 要約の生成が落ちた場合も、同じ導線で作り直せる。 */
    @Test
    fun `要約の生成が落ちても再試行で作り直せる`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val ai = FakeAiClient { throw AiTimeoutException("タイムアウト") }
        val controller = SectionChatController(
            this,
            ai,
            state.sectionChatWriter,
            StandardTestDispatcher(testScheduler)
        )

        controller.open(NoteSection("対象セクション", 2, "## 対象セクション\n本文"))
        advanceUntilIdle()
        assertEquals(
            SectionChatProblem.GenerationFailed("タイムアウト"),
            state.value.sectionChat?.summaryProblem
        )

        ai.onGenerate = { "生成された要約" }
        controller.retrySummary()
        advanceUntilIdle()

        val chat = requireNotNull(state.value.sectionChat)
        assertNull(chat.summaryProblem)
        assertEquals("生成された要約", chat.summary)
    }

    /**
     * **要約と回答が同時に失敗しても、押した側だけが作り直される。**
     *
     * 再試行が1本だったころは「ログ末尾がユーザー発言なら常に回答を優先」していたので、
     * **要約エリアの再試行を押しても回答が走り、要約は永久に作り直せなかった。**
     * 押されたボタンの位置が対象を決めることを、両方が失敗した状態で固定する。
     */
    @Test
    fun `要約と回答が同時に失敗しても押した側だけを作り直す`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        // 要約・候補質問・回答のすべてを落とす。候補質問の失敗は黙って捨てられる。
        val ai = FakeAiClient { throw AiTimeoutException("タイムアウト") }
        val controller = SectionChatController(
            this,
            ai,
            state.sectionChatWriter,
            StandardTestDispatcher(testScheduler)
        )

        controller.open(NoteSection("対象セクション", 2, "## 対象セクション\n本文"))
        advanceUntilIdle()
        controller.sendMessage("これはどういう意味ですか")
        advanceUntilIdle()

        val bothFailed = requireNotNull(state.value.sectionChat)
        assertNotNull("要約が失敗していること", bothFailed.summaryProblem)
        assertNotNull("回答も失敗していること", bothFailed.answerProblem)
        assertNull(bothFailed.summary)

        // 要約側だけを押す。**回答は作り直さない。**
        ai.onGenerate = { "生成された要約" }
        controller.retrySummary()
        advanceUntilIdle()

        val afterSummaryRetry = requireNotNull(state.value.sectionChat)
        assertEquals("生成された要約", afterSummaryRetry.summary)
        assertNull(afterSummaryRetry.summaryProblem)
        assertNotNull("回答側は手つかずのまま", afterSummaryRetry.answerProblem)
        assertEquals(1, afterSummaryRetry.messages.size)

        // 回答側を押すと、質問を積み直さずに答えだけが付く。
        ai.onGenerate = { "生成された回答" }
        controller.retryAnswer()
        advanceUntilIdle()

        val afterAnswerRetry = requireNotNull(state.value.sectionChat)
        assertNull(afterAnswerRetry.answerProblem)
        assertEquals(2, afterAnswerRetry.messages.size)
        assertEquals(ChatRole.Ai, afterAnswerRetry.messages.last().role)
        assertEquals("生成された要約", afterAnswerRetry.summary)
    }

    /**
     * **要約の再試行が、走行中の回答を巻き添えにしない。**
     *
     * 共通の `cancelJobs()` を呼んでいたころは `answerJob` も止まったが、
     * 回答側はキャンセルで状態を戻さないので **`isGenerating` が真のまま固まり、
     * 「回答を生成中…」が永久に残った**（質問候補も無効のまま）。
     * 追加済みのテストは「両方とも既に失敗済み」の場合しか通していなかった。
     */
    @Test
    fun `要約の再試行は生成中の回答を巻き添えにしない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val answerResponse = CompletableDeferred<String>()
        val ai = FakeAiClient { throw AiTimeoutException("タイムアウト") }
        val controller = SectionChatController(
            this,
            ai,
            state.sectionChatWriter,
            StandardTestDispatcher(testScheduler)
        )

        // 要約が落ちた状態を作る（`summary` が null なので再試行が作り直しへ進む）。
        controller.open(NoteSection("対象セクション", 2, "## 対象セクション\n本文"))
        advanceUntilIdle()
        assertNotNull(state.value.sectionChat?.summaryProblem)

        // 回答は保留のまま走らせる。
        ai.onGenerate = { answerResponse.await() }
        controller.sendMessage("これはどういう意味ですか")
        advanceUntilIdle()
        assertTrue("回答が走っていること", state.value.sectionChat?.isGenerating == true)

        ai.onGenerate = { "生成された要約" }
        controller.retrySummary()
        advanceUntilIdle()

        val afterRetry = requireNotNull(state.value.sectionChat)
        assertEquals("生成された要約", afterRetry.summary)
        assertNull(afterRetry.summaryProblem)
        // **回答は止まっていない。** 止めると isGenerating が真のまま残る。
        assertTrue("走行中の回答を巻き添えにしないこと", afterRetry.isGenerating)

        answerResponse.complete("生成された回答")
        advanceUntilIdle()

        val afterAnswer = requireNotNull(state.value.sectionChat)
        assertFalse("回答が届けば生成中は解除されること", afterAnswer.isGenerating)
        assertEquals(2, afterAnswer.messages.size)
        assertEquals("生成された回答", afterAnswer.messages.last().text)
    }

    /**
     * 要約の生成だけを保留し、候補質問は即返すダブル。
     *
     * シートは「要約 → 候補質問」の順に2回生成するので、1回目だけ止めれば
     * 「要約待ちのあいだ何が起きるか」を作れる。
     */
    private fun sectionChatAi(): Pair<FakeAiClient, CompletableDeferred<String>> {
        val summaryResponse = CompletableDeferred<String>()
        val client = FakeAiClient {
            if (generateCalls == 1) summaryResponse.await() else "質問1\n質問2"
        }
        return client to summaryResponse
    }
}
