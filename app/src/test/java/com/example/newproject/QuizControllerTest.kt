package com.example.newproject

import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.google.mlkit.genai.common.DownloadStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
        val aiClient = ControllableAiClient()
        val state = MutableStateFlow(NoteUiState())
        val controller = QuizController(this, aiClient, state)

        controller.create("対象ノート.md", "本文")
        runCurrent()

        assertTrue(state.value.quizState is QuizState.Loading)
        aiClient.response.complete(validResponse())
        advanceUntilIdle()

        val success = state.value.quizState as QuizState.Success
        assertEquals("対象ノート", success.sourceTitle)
        assertEquals(1, success.cards.size)
        assertFalse(success.isViewed)
    }

    @Test
    fun `生成中の再タップでは要求を重複させない`() = runTest {
        val aiClient = ControllableAiClient()
        val state = MutableStateFlow(NoteUiState())
        val controller = QuizController(this, aiClient, state)

        controller.create("対象ノート", "本文")
        runCurrent()
        controller.create("対象ノート", "本文")
        runCurrent()

        assertEquals(1, aiClient.generateCalls)
        aiClient.response.complete(validResponse())
        advanceUntilIdle()
    }

    @Test
    fun `ノート切替時の破棄後に古い生成結果を反映しない`() = runTest {
        val aiClient = ControllableAiClient()
        val state = MutableStateFlow(NoteUiState())
        val controller = QuizController(this, aiClient, state)

        controller.create("古いノート", "本文")
        runCurrent()
        controller.cancelAndClear()
        aiClient.response.complete(validResponse())
        advanceUntilIdle()

        assertTrue(state.value.quizState is QuizState.Idle)
    }

    @Test
    fun `有効な問題がない応答は成功にしない`() = runTest {
        val state = MutableStateFlow(NoteUiState())
        val controller = QuizController(this, ImmediateAiClient("生成できませんでした"), state)

        controller.create("対象ノート", "本文")
        advanceUntilIdle()

        assertTrue(state.value.quizState is QuizState.Error)
    }

    @Test
    fun `Q&Aを開くと完了通知が確認済みになる`() = runTest {
        val state = MutableStateFlow(
            NoteUiState(
                quizState = QuizState.Success(
                    sourceTitle = "対象ノート",
                    cards = listOf(QuizCard("問題", listOf("A", "B", "C", "D"), 0))
                )
            )
        )
        val controller = QuizController(this, ImmediateAiClient(""), state)

        controller.markViewed()

        val success = state.value.quizState as QuizState.Success
        assertTrue(success.isViewed)
    }

    @Test
    fun `もう2問で既存カードに追加され既出問題が除外指定される`() = runTest {
        val aiClient = RecordingAiClient(validResponse(), secondResponse())
        val state = MutableStateFlow(NoteUiState())
        val controller = QuizController(this, aiClient, state)

        controller.create("セクション", "周辺テキスト")
        advanceUntilIdle()
        controller.generateMore()
        advanceUntilIdle()

        val success = state.value.quizState as QuizState.Success
        assertEquals(2, success.cards.size)
        assertFalse(success.isAppending)
        assertEquals(null, success.appendError)
        // 2回目のプロンプトに既出問題文が除外リストとして含まれる
        assertTrue(aiClient.prompts[1].contains("問題"))
    }

    @Test
    fun `追い生成では広い周辺テキストと一般知識許可の指示を使う`() = runTest {
        val aiClient = RecordingAiClient(validResponse(), secondResponse())
        val state = MutableStateFlow(NoteUiState())
        val controller = QuizController(this, aiClient, state)

        controller.create("セクション", "狭い素材", "広い素材")
        advanceUntilIdle()
        controller.generateMore()
        advanceUntilIdle()

        assertTrue(aiClient.prompts[0].contains("狭い素材"))
        assertFalse(aiClient.prompts[0].contains("一般知識"))
        assertTrue(aiClient.prompts[1].contains("広い素材"))
        assertTrue(aiClient.prompts[1].contains("一般知識"))
    }

    @Test
    fun `追い生成の失敗では既存カードを保持しappendErrorだけ立てる`() = runTest {
        val aiClient = RecordingAiClient(validResponse(), "読み取れない応答")
        val state = MutableStateFlow(NoteUiState())
        val controller = QuizController(this, aiClient, state)

        controller.create("セクション", "周辺テキスト")
        advanceUntilIdle()
        controller.generateMore()
        advanceUntilIdle()

        val success = state.value.quizState as QuizState.Success
        assertEquals(1, success.cards.size)
        assertFalse(success.isAppending)
        assertTrue(success.appendError != null)
    }

    @Test
    fun `追い生成が既出と同一問題しか返さなければ追加しない`() = runTest {
        val aiClient = RecordingAiClient(validResponse(), validResponse())
        val state = MutableStateFlow(NoteUiState())
        val controller = QuizController(this, aiClient, state)

        controller.create("セクション", "周辺テキスト")
        advanceUntilIdle()
        controller.generateMore()
        advanceUntilIdle()

        val success = state.value.quizState as QuizState.Success
        assertEquals(1, success.cards.size)
        assertTrue(success.appendError != null)
    }

    @Test
    fun `破棄後のもう2問は何もしない`() = runTest {
        val aiClient = RecordingAiClient(validResponse())
        val state = MutableStateFlow(NoteUiState())
        val controller = QuizController(this, aiClient, state)

        controller.create("セクション", "周辺テキスト")
        advanceUntilIdle()
        controller.cancelAndClear()
        controller.generateMore()
        advanceUntilIdle()

        assertTrue(state.value.quizState is QuizState.Idle)
        assertEquals(1, aiClient.prompts.size)
    }

    private class ControllableAiClient : AiClient {
        val response = CompletableDeferred<String>()
        var generateCalls = 0
            private set

        override suspend fun checkAvailability(): AiAvailability = AiAvailability.Available

        override suspend fun generate(prompt: String): String {
            generateCalls++
            return response.await()
        }

        override fun downloadModel(): Flow<DownloadStatus> = emptyFlow()
    }

    private class ImmediateAiClient(private val response: String) : AiClient {
        override suspend fun checkAvailability(): AiAvailability = AiAvailability.Available
        override suspend fun generate(prompt: String): String = response
        override fun downloadModel(): Flow<DownloadStatus> = emptyFlow()
    }

    // 呼び出しごとに応答を順番に返し、渡されたプロンプトを記録するフェイク
    private class RecordingAiClient(vararg responses: String) : AiClient {
        val prompts = mutableListOf<String>()
        private val queue = ArrayDeque(responses.toList())

        override suspend fun checkAvailability(): AiAvailability = AiAvailability.Available

        override suspend fun generate(prompt: String): String {
            prompts += prompt
            return queue.removeFirst()
        }

        override fun downloadModel(): Flow<DownloadStatus> = emptyFlow()
    }

    private fun validResponse() = """
        Q: 問題
        A: 選択肢A
        B: 選択肢B
        C: 選択肢C
        D: 選択肢D
        ANSWER: A
        EXPLANATION: 解説
    """.trimIndent()

    private fun secondResponse() = """
        Q: 問題2
        A: 選択肢A
        B: 選択肢B
        C: 選択肢C
        D: 選択肢D
        ANSWER: B
        EXPLANATION: 解説2
    """.trimIndent()
}
