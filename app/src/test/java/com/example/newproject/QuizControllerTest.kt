package com.example.newproject

import com.example.newproject.controller.QuizController
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.state.AiNoticeAction
import com.example.newproject.model.state.QuizCard
import com.example.newproject.model.state.QuizFormat
import com.example.newproject.model.state.QuizState
import com.example.newproject.model.state.isQuizActionEnabled
import com.example.newproject.ai.AiAvailability
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.example.newproject.model.NoteUiStateStore
import com.example.newproject.fakes.FakeAiClient
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QuizControllerTest {

    @Test
    fun `生成画面を開かなくてもQ&A生成が完了して保持される`() = runTest {
        val aiClient = FakeAiClient.deferred()
        val state = NoteUiStateStore(NoteUiState())
        val controller = QuizController(
            this,
            aiClient,
            state.quizWriter,
            StandardTestDispatcher(testScheduler)
        )

        controller.create("対象ノート.md", "本文")
        runCurrent()

        val loading = state.value.quizState as QuizState.Loading
        assertEquals(QuizFormat.TrueFalse, loading.format)
        aiClient.completeAll(trueFalseResponse())
        advanceUntilIdle()

        val success = state.value.quizState as QuizState.Success
        assertEquals("対象ノート", success.sourceTitle)
        assertEquals(2, success.cards.size)
        assertEquals(QuizFormat.TrueFalse, success.cards.first().format)
        assertEquals(listOf("正しい", "誤り"), success.cards.first().choices)
        assertFalse(success.isViewed)
    }

    @Test
    fun `生成中の再タップでは要求を重複させない`() = runTest {
        val aiClient = FakeAiClient.deferred()
        val state = NoteUiStateStore(NoteUiState())
        val controller = QuizController(
            this,
            aiClient,
            state.quizWriter,
            StandardTestDispatcher(testScheduler)
        )

        controller.create("対象ノート", "本文")
        runCurrent()
        controller.create("対象ノート", "本文")
        runCurrent()

        assertEquals(1, aiClient.generateCalls)
        aiClient.completeAll(trueFalseResponse())
        advanceUntilIdle()
    }

    @Test
    fun `ノート切替時の破棄後に古い生成結果を反映しない`() = runTest {
        val aiClient = FakeAiClient.deferred()
        val state = NoteUiStateStore(NoteUiState())
        val controller = QuizController(
            this,
            aiClient,
            state.quizWriter,
            StandardTestDispatcher(testScheduler)
        )

        controller.create("古いノート", "本文")
        runCurrent()
        controller.cancelAndClear()
        aiClient.completeAll(trueFalseResponse())
        advanceUntilIdle()

        assertTrue(state.value.quizState is QuizState.Idle)
    }

    @Test
    fun `有効な問題がない応答は成功にしない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = QuizController(
            this,
            FakeAiClient.returning("生成できませんでした"),
            state.quizWriter,
            StandardTestDispatcher(testScheduler)
        )

        controller.create("対象ノート", "本文")
        advanceUntilIdle()

        assertTrue(state.value.quizState is QuizState.Error)
    }

    @Test
    fun `Q&Aを開くと完了通知が確認済みになる`() = runTest {
        val state = NoteUiStateStore(
            NoteUiState(
                quizState = QuizState.Success(
                    sourceTitle = "対象ノート",
                    cards = listOf(QuizCard("問題", listOf("A", "B", "C", "D"), 0))
                )
            )
        )
        val controller = QuizController(
            this,
            FakeAiClient.returning(""),
            state.quizWriter,
            StandardTestDispatcher(testScheduler)
        )

        controller.markViewed()

        val success = state.value.quizState as QuizState.Success
        assertTrue(success.isViewed)
    }

    /**
     * **非対応をエラーへ畳まない。** 畳んでいたころは
     * `エラー: Q&Aはこの端末では利用できません。` と出たうえ、シートのボタンが
     * 「↻ クイズを再試行」になっていた（何度押しても同じ答えが返る）。
     */
    @Test
    fun `AI非対応は失敗ではなく説明になり、クイズのボタンが無効になる`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = QuizController(
            this,
            FakeAiClient(AiAvailability.Unsupported),
            state.quizWriter,
            StandardTestDispatcher(testScheduler)
        )

        controller.create("対象ノート.md", "本文")
        advanceUntilIdle()

        val notice = (state.value.quizState as QuizState.AiNotice).notice
        assertEquals("この端末ではQ&Aを利用できません。", notice.message)
        assertEquals(AiNoticeAction.None, notice.action)
        assertFalse(state.value.quizState.isQuizActionEnabled())
    }

    /**
     * **DL実行中は自動DLを始めない。** `downloadModel()` を呼んでよいのは
     * `DOWNLOADABLE` のときだけ（beta2 の `downloadFeatureInternal` に状態の門番が無い）。
     */
    @Test
    fun `DL実行中は自動DLを始めず待つ`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val ai = FakeAiClient(AiAvailability.Downloading)
        val controller = QuizController(
            this,
            ai,
            state.quizWriter,
            StandardTestDispatcher(testScheduler)
        )

        controller.create("対象ノート.md", "本文")
        advanceUntilIdle()

        assertEquals("DL中に download() を呼ばないこと", 0, ai.downloadCalls)
        assertEquals(0, ai.generateCalls)
        val notice = (state.value.quizState as QuizState.AiNotice).notice
        assertEquals(AiNoticeAction.None, notice.action)
    }

    /** **取得失敗は押す意味がある。** 非対応と畳むとボタンごと死ぬ。 */
    @Test
    fun `状態を取得できなかっただけならクイズを押し直せる`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = QuizController(
            this,
            FakeAiClient(
                AiAvailability.TemporarilyUnavailable(IllegalStateException("AICore not bound"))
            ),
            state.quizWriter,
            StandardTestDispatcher(testScheduler)
        )

        controller.create("対象ノート.md", "本文")
        advanceUntilIdle()

        val notice = (state.value.quizState as QuizState.AiNotice).notice
        assertEquals(AiNoticeAction.Retry, notice.action)
        assertTrue(state.value.quizState.isQuizActionEnabled())
    }

    private fun trueFalseResponse() = """
        Q: 本文には情報がある
        ANSWER: TRUE

        Q: 本文は空である
        ANSWER: FALSE
    """.trimIndent()
}
