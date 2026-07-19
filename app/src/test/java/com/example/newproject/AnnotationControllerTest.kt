package com.example.newproject

import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.google.mlkit.genai.common.DownloadStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationControllerTest {

    @Test
    fun `エラー結果を開くと通知が確認済みになる`() = runTest {
        val state = MutableStateFlow(
            NoteUiState(
                annotationState = AnnotationState.Error(
                    message = "生成エラー",
                    sourceTitle = "対象ノート"
                )
            )
        )
        val controller = controller(state)

        controller.markViewed()

        val error = state.value.annotationState as AnnotationState.Error
        assertTrue(error.isViewed)
    }

    @Test
    fun `ノート切替時の破棄で補記状態がIdleに戻る`() = runTest {
        val state = MutableStateFlow(
            NoteUiState(annotationState = AnnotationState.Loading("対象ノート"))
        )
        val controller = controller(state)

        controller.cancelAndClear()

        assertTrue(state.value.annotationState is AnnotationState.Idle)
        assertFalse(state.value.annotationState is AnnotationState.Loading)
    }

    private fun controller(state: MutableStateFlow<NoteUiState>) = AnnotationController(
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        repository = NoteRepository(),
        aiClient = NoOpAiClient,
        uiState = state,
        vaultUri = { null }
    )

    private object NoOpAiClient : AiClient {
        override suspend fun checkAvailability(): AiAvailability = AiAvailability.Unavailable
        override suspend fun generate(prompt: String): String = ""
        override fun downloadModel(): Flow<DownloadStatus> = emptyFlow()
    }
}
