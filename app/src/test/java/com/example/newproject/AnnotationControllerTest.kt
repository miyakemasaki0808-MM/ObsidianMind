package com.example.newproject

import com.example.newproject.controller.AnnotationController
import com.example.newproject.model.state.AnnotationListState
import com.example.newproject.model.state.AnnotationState
import com.example.newproject.model.NoteUiState
import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.google.mlkit.genai.common.DownloadStatus
import kotlinx.coroutines.flow.Flow
import com.example.newproject.model.NoteUiStateStore
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationControllerTest {

    @Test
    fun `エラー結果を開くと通知が確認済みになる`() = runTest {
        val state = NoteUiStateStore(
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
        val state = NoteUiStateStore(
            NoteUiState(annotationState = AnnotationState.Loading("対象ノート"))
        )
        val controller = controller(state)

        controller.cancelAndClear()

        assertTrue(state.value.annotationState is AnnotationState.Idle)
        assertFalse(state.value.annotationState is AnnotationState.Loading)
    }

    @Test
    fun `Vault切替で補記一覧が破棄される`() = runTest {
        val state = NoteUiStateStore(
            NoteUiState(annotationListState = AnnotationListState.Loading)
        )
        val controller = controller(state)

        controller.onVaultChanged()

        assertTrue(state.value.annotationListState is AnnotationListState.Idle)
    }

    // 補記管理画面はノートと無関係なので、ノート切替では一覧を巻き込まない。
    // createJob と listJob を分けている理由がここ。
    @Test
    fun `ノート切替では補記一覧を巻き込まない`() = runTest {
        val state = NoteUiStateStore(
            NoteUiState(
                annotationState = AnnotationState.Loading("対象ノート"),
                annotationListState = AnnotationListState.Error("読み込み失敗")
            )
        )
        val controller = controller(state)

        controller.cancelAndClear()

        assertTrue(state.value.annotationState is AnnotationState.Idle)
        assertTrue(state.value.annotationListState is AnnotationListState.Error)
    }

    private fun controller(
        state: NoteUiStateStore,
        vault: FakeVaultBrowser = FakeVaultBrowser(handle = null),
        vaultGeneration: () -> Long = { 0L }
    ) = AnnotationController(
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
        vault = vault,
        aiClient = NoOpAiClient,
        state = state.annotationWriter,
        vaultGeneration = vaultGeneration
    )

    private object NoOpAiClient : AiClient {
        override suspend fun checkAvailability(): AiAvailability = AiAvailability.Unavailable
        override suspend fun generate(prompt: String): String = ""
        override fun downloadModel(): Flow<DownloadStatus> = emptyFlow()
    }
}
