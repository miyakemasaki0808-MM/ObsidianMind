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

        val loading = state.value.quizState as QuizState.Loading
        assertEquals(QuizFormat.TrueFalse, loading.format)
        aiClient.response.complete(trueFalseResponse())
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
        val aiClient = ControllableAiClient()
        val state = MutableStateFlow(NoteUiState())
        val controller = QuizController(this, aiClient, state)

        controller.create("対象ノート", "本文")
        runCurrent()
        controller.create("対象ノート", "本文")
        runCurrent()

        assertEquals(1, aiClient.generateCalls)
        aiClient.response.complete(trueFalseResponse())
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
        aiClient.response.complete(trueFalseResponse())
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

    private fun trueFalseResponse() = """
        Q: 本文には情報がある
        ANSWER: TRUE

        Q: 本文は空である
        ANSWER: FALSE
    """.trimIndent()
}
