package com.example.newproject

import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.example.newproject.controller.SearchController
import com.example.newproject.data.NoteFolder
import com.example.newproject.data.NoteRepository
import com.example.newproject.domain.AiRecommendationStatus
import com.example.newproject.domain.SearchPickerUseCase
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.state.SearchState
import com.google.mlkit.genai.common.DownloadStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import com.example.newproject.model.NoteUiStateStore
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * さがすタブのスコープ切替に伴う状態の後始末を固定する。
 *
 * **このテストが保証しないこと:** 検索の実行そのものと、Vault切替時の
 * `loadFolders` の世代照合は検証できない。どちらも非nullの `android.net.Uri`
 * （`vaultUri()` と `NoteFile.uri`）を必要とするが、JVMユニットテストでは
 * `Uri` がスタブで実行時例外になるため。これらは実機確認で担保する。
 */
class SearchControllerTest {

    @Test
    fun `スコープを切り替えると前のスコープの検索結果が消える`() = runTest {
        val state = NoteUiStateStore(
            NoteUiState(
                searchState = SearchState.Success(emptyList(), AiRecommendationStatus.Ready)
            )
        )
        val controller = controller(state)

        controller.selectFolder(NoteFolder(name = "ideas", documentId = "doc-ideas"))

        assertTrue(state.value.searchState is SearchState.Idle)
        assertEquals("doc-ideas", state.value.selectedFolder?.documentId)
    }

    @Test
    fun `検索中にスコープを切り替えるとLoadingが残らない`() = runTest {
        val state = NoteUiStateStore(NoteUiState(searchState = SearchState.Loading))
        val controller = controller(state)

        controller.selectFolder(NoteFolder(name = "ideas", documentId = "doc-ideas"))

        assertTrue(state.value.searchState is SearchState.Idle)
    }

    @Test
    fun `ルート直下へ戻しても検索結果は破棄される`() = runTest {
        val state = NoteUiStateStore(
            NoteUiState(
                selectedFolder = NoteFolder(name = "ideas", documentId = "doc-ideas"),
                searchState = SearchState.Success(emptyList(), AiRecommendationStatus.Ready)
            )
        )
        val controller = controller(state)

        controller.selectFolder(null)

        assertTrue(state.value.searchState is SearchState.Idle)
        assertEquals(null, state.value.selectedFolder)
    }

    // 同じchipの再タップで結果を失わない（取り違え対策）。
    @Test
    fun `同じスコープを選び直しても結果は保持される`() = runTest {
        val folder = NoteFolder(name = "ideas", documentId = "doc-ideas")
        val state = NoteUiStateStore(
            NoteUiState(
                selectedFolder = folder,
                searchState = SearchState.Success(emptyList(), AiRecommendationStatus.Ready)
            )
        )
        val controller = controller(state)

        controller.selectFolder(folder)

        assertTrue(state.value.searchState is SearchState.Success)
    }

    private fun controller(state: NoteUiStateStore) = SearchController(
        scope = CoroutineScope(Dispatchers.Unconfined),
        repository = NoteRepository(),
        searchPickerUseCase = SearchPickerUseCase(NoOpAiClient),
        state = state.searchWriter,
        vaultUri = { null },
        vaultGeneration = { 0L }
    )

    private object NoOpAiClient : AiClient {
        override suspend fun checkAvailability(): AiAvailability = AiAvailability.Unavailable
        override suspend fun generate(prompt: String): String = ""
        override fun downloadModel(): Flow<DownloadStatus> = emptyFlow()
    }
}
