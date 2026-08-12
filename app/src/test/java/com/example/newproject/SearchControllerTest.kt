package com.example.newproject

import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.example.newproject.controller.SearchController
import com.example.newproject.model.NoteFolder
import com.example.newproject.model.AiRecommendationStatus
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
 * さがすタブのスコープ切替・フォルダ列挙・検索実行・走査キャッシュを固定する。
 *
 * **以前はスコープ切替の後始末しか検証できなかった。** 検索の実行と `loadFolders` の
 * 世代照合は、非nullの `Uri` と `ContentResolver` を要するため素のJVMでは書けず、
 * 実機確認だけが担保だった。`NoteFile` の `DocumentRef` 化（段階1〜6）と
 * [FakeVaultBrowser]（段階7）でどちらも外れたので、ここで押さえる。
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

    // ── フォルダ列挙 ─────────────────────────────────────────────────────────

    @Test
    fun `フォルダ列挙の結果がchipsへ反映される`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val handle = FakeVaultHandle(folders = listOf(NoteFolder("ideas", "doc-ideas")))

        controller(state, FakeVaultBrowser(handle)).loadFolders()

        assertEquals(listOf("ideas"), state.value.folders.map { it.name })
        assertEquals(null, state.value.foldersError)
    }

    @Test
    fun `Vault未選択ならフォルダ列挙は何もしない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())

        controller(state, FakeVaultBrowser(handle = null)).loadFolders()

        assertTrue(state.value.folders.isEmpty())
        // 「Vaultが無い」は検索側では黙って返る仕様。エラー表示にはしない。
        assertEquals(null, state.value.foldersError)
    }

    /**
     * `cancel()` だけでは足りない経路。SAF列挙から戻った**直後**に切替が起きると、
     * Jobは既に中断点を過ぎているので旧Vaultのフォルダが新Vaultのchipsとして並ぶ。
     */
    @Test
    fun `列挙から戻る直前にVaultが変わったらchipsを書き換えない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        var generation = 0L
        val handle = FakeVaultHandle(
            folders = listOf(NoteFolder("旧Vaultのフォルダ", "doc-old")),
            beforeEachCall = { generation++ }
        )

        controller(state, FakeVaultBrowser(handle), vaultGeneration = { generation }).loadFolders()

        assertTrue(state.value.folders.isEmpty())
    }

    @Test
    fun `フォルダ列挙に失敗したら注記を出す`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val handle = FakeVaultHandle(failure = IllegalStateException("SAF失敗"))

        controller(state, FakeVaultBrowser(handle)).loadFolders()

        assertEquals("フォルダ一覧を取得できませんでした。", state.value.foldersError)
    }

    @Test
    fun `列挙が失敗しても世代が進んでいたら注記を出さない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        var generation = 0L
        val handle = FakeVaultHandle(
            failure = IllegalStateException("SAF失敗"),
            beforeEachCall = { generation++ }
        )

        controller(state, FakeVaultBrowser(handle), vaultGeneration = { generation }).loadFolders()

        assertEquals(null, state.value.foldersError)
    }

    // ── 検索・ランダムの実行 ──────────────────────────────────────────────────

    @Test
    fun `キーワード検索がスコープ内のノートから結果を返す`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val handle = FakeVaultHandle(
            notesByFolder = mapOf(null to listOf(noteFile("習慣.md"), noteFile("読書.md")))
        )

        controller(state, FakeVaultBrowser(handle)).searchByKeyword("習慣")

        val result = state.value.searchState as SearchState.Success
        assertEquals(listOf("習慣.md"), result.results.map { it.title })
    }

    @Test
    fun `空白だけの検索語では走査しない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val handle = FakeVaultHandle()

        controller(state, FakeVaultBrowser(handle)).searchByKeyword("   ")

        assertEquals(0, handle.collectCount)
        assertTrue(state.value.searchState is SearchState.Idle)
    }

    @Test
    fun `ランダムはスコープ内から3件までを返す`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val handle = FakeVaultHandle(
            notesByFolder = mapOf(null to (1..5).map { noteFile("note$it.md") })
        )

        controller(state, FakeVaultBrowser(handle)).pickRandomInScope()

        val result = state.value.searchState as SearchState.Success
        assertEquals(3, result.results.size)
    }

    @Test
    fun `走査が失敗したらエラー状態になる`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val handle = FakeVaultHandle(failure = IllegalStateException("走査失敗"))

        controller(state, FakeVaultBrowser(handle)).pickRandomInScope()

        assertEquals("走査失敗", (state.value.searchState as SearchState.Error).message)
    }

    // ── スコープ走査キャッシュ ────────────────────────────────────────────────

    @Test
    fun `同じスコープの連続操作では走査し直さない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val handle = FakeVaultHandle(notesByFolder = mapOf(null to listOf(noteFile("a.md"))))
        val controller = controller(state, FakeVaultBrowser(handle))

        controller.pickRandomInScope()
        controller.pickRandomInScope()

        assertEquals(1, handle.collectCount)
    }

    /**
     * Vault切替でキャッシュを捨てないと、キーが旧Vaultの documentId のまま残り、
     * 新Vaultで同じスコープを引いたときに旧Vaultのノートが出る。
     */
    @Test
    fun `Vault切替後は走査し直す`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val handle = FakeVaultHandle(notesByFolder = mapOf(null to listOf(noteFile("a.md"))))
        val controller = controller(state, FakeVaultBrowser(handle))

        controller.pickRandomInScope()
        controller.onVaultChanged()
        controller.pickRandomInScope()

        assertEquals(2, handle.collectCount)
    }

    private fun controller(
        state: NoteUiStateStore,
        vault: FakeVaultBrowser = FakeVaultBrowser(handle = null),
        vaultGeneration: () -> Long = { 0L },
        aiClient: AiClient = NoOpAiClient
    ) = SearchController(
        scope = CoroutineScope(Dispatchers.Unconfined),
        vault = vault,
        searchPickerUseCase = SearchPickerUseCase(aiClient),
        state = state.searchWriter,
        vaultGeneration = vaultGeneration
    )

    private object NoOpAiClient : AiClient {
        override suspend fun checkAvailability(): AiAvailability = AiAvailability.Unsupported
        override suspend fun generate(prompt: String): String = ""
        override fun downloadModel(): Flow<DownloadStatus> = emptyFlow()
    }
}
