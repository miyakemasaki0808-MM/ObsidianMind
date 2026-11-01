package com.example.newproject

import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.example.newproject.controller.SearchController
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteFile
import com.example.newproject.model.NoteFolder
import com.example.newproject.domain.SearchPickerUseCase
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.state.SearchState
import com.example.newproject.model.state.TitleSearchState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.example.newproject.model.NoteUiStateStore
import com.example.newproject.fakes.FakeAiClient
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Explore の範囲の切替・フォルダ列挙・タイトルの照合・AI の検索を固定する。
 *
 * **以前はスコープ切替の後始末しか検証できなかった。** 検索の実行と `loadFolders` の
 * 世代照合は、非nullの `Uri` と `ContentResolver` を要するため素のJVMでは書けず、
 * 実機確認だけが担保だった。`NoteFile` の `DocumentRef` 化（段階1〜6）と
 * [FakeVaultBrowser]（段階7）でどちらも外れたので、ここで押さえる。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchControllerTest {

    @Test
    fun `スコープを切り替えると前のスコープの検索結果が消える`() = runTest {
        val state = NoteUiStateStore(
            NoteUiState(
                searchState = SearchState.Success(emptyList())
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
    fun `「すべて」へ戻しても検索結果は破棄される`() = runTest {
        val state = NoteUiStateStore(
            NoteUiState(
                selectedFolder = NoteFolder(name = "ideas", documentId = "doc-ideas"),
                searchState = SearchState.Success(emptyList())
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
                searchState = SearchState.Success(emptyList())
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

    // ── タイトル ─────────────────────────────────────────────────────────────

    @Test
    fun `開いた後に打つと、フォルダを選ばずどの階層のノートも出る`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, openVault())

        controller.onOpened { listOf(notePath("習慣.md"), notePath("ideas/2026/習慣化メモ.md"), notePath("読書.md")) }
        controller.searchTitle("習慣")

        assertEquals(listOf("習慣.md", "習慣化メモ.md"), titles(state))
    }

    @Test
    fun `打っていないなら照らさず、消すと結果も消える`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, openVault())
        controller.onOpened { listOf(notePath("習慣.md")) }

        controller.searchTitle("習慣")
        controller.searchTitle("")

        assertTrue(state.value.titleSearch is TitleSearchState.Idle)
    }

    @Test
    fun `一覧が届く前に打っても、届いたらその言葉で照らす`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val scan = CompletableDeferred<List<NoteFile>>()
        val controller = controller(state, openVault())

        controller.onOpened { scan.await() }
        controller.searchTitle("習慣")
        assertTrue(state.value.titleSearch is TitleSearchState.Scanning)

        scan.complete(listOf(notePath("習慣.md")))

        assertEquals(listOf("習慣.md"), titles(state))
    }

    /** 打つたびに引くと、一覧の寿命が切れた時点で打っている途中に全走査が走る。 */
    @Test
    fun `打っている間は開いたときの一覧を照らし、走査を頼まない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, openVault())
        var collects = 0

        controller.onOpened { collects++; listOf(notePath("習慣.md")) }
        listOf("習", "習慣", "習慣化").forEach(controller::searchTitle)

        assertEquals(1, collects)
    }

    @Test
    fun `開き直して読み直している間も前の一覧で照らし、届いたら照らし直す`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, openVault())
        controller.onOpened { listOf(notePath("古い習慣.md")) }

        val rescan = CompletableDeferred<List<NoteFile>>()
        controller.onOpened { rescan.await() }
        controller.searchTitle("習慣")
        assertEquals(listOf("古い習慣.md"), titles(state))

        rescan.complete(listOf(notePath("新しい習慣.md")))

        assertEquals(listOf("新しい習慣.md"), titles(state))
    }

    @Test
    fun `一覧を読めなければ失敗を出し、読めた一覧があればそれで照らし続ける`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, openVault())

        controller.onOpened { throw IllegalStateException("走査失敗") }
        controller.searchTitle("習慣")
        assertEquals("走査失敗", (state.value.titleSearch as TitleSearchState.Error).message)

        controller.onOpened { listOf(notePath("習慣.md")) }
        controller.searchTitle("習慣")
        assertEquals(listOf("習慣.md"), titles(state))

        controller.onOpened { throw IllegalStateException("走査失敗") }
        controller.searchTitle("習慣")
        assertEquals(listOf("習慣.md"), titles(state))
    }

    @Test
    fun `Vault未選択なら打っても何も起きない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())

        controller(state, FakeVaultBrowser(handle = null)).searchTitle("習慣")

        assertTrue(state.value.titleSearch is TitleSearchState.Idle)
    }

    /** 一覧が返った直後に切替が起きると、Job は中断点を過ぎているので `cancel()` では止まらない。 */
    @Test
    fun `一覧が返る直前にVaultが変わったら、旧い一覧を照らさない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        var generation = 0L
        val controller = controller(state, openVault(), vaultGeneration = { generation })

        controller.onOpened {
            generation++
            listOf(notePath("旧Vault.md"))
        }
        controller.searchTitle("旧")

        assertTrue(state.value.titleSearch is TitleSearchState.Scanning)
    }

    @Test
    fun `Vaultを替えた後は前の一覧も打った言葉も残らない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, openVault())
        controller.onOpened { listOf(notePath("旧Vault.md")) }
        controller.searchTitle("旧")

        controller.onVaultChanged()
        val scan = CompletableDeferred<List<NoteFile>>()
        controller.onOpened { scan.await() }
        scan.complete(listOf(notePath("旧の字を持つ新Vault.md")))

        assertTrue("切替の前の言葉で照らし直した", state.value.titleSearch is TitleSearchState.Idle)
        controller.searchTitle("旧")
        assertEquals(listOf("旧の字を持つ新Vault.md"), titles(state))
    }

    /** 照らしの途中で次の文字を打つと、前の文字の結果を書かない（照合が Main の外で走るため）。 */
    @Test
    fun `照らしの途中で打ち直したら、前の言葉の結果を書かない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = controller(state, openVault(), matchDispatcher = dispatcher)
        controller.onOpened { listOf(notePath("習慣.md"), notePath("読書.md")) }

        controller.searchTitle("習慣")
        controller.searchTitle("読書")
        advanceUntilIdle()

        assertEquals(listOf("読書.md"), titles(state))
    }

    // ── AI ───────────────────────────────────────────────────────────────────

    @Test
    fun `「すべて」は開いたときの一覧の全体から選ぶ`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, openVault())
        controller.onOpened { listOf(notePath("直下の習慣.md"), notePath("ideas/奥の習慣.md"), notePath("読書.md")) }

        controller.searchByKeyword("習慣", ::noNotes)

        val result = state.value.searchState as SearchState.Success
        assertEquals(setOf("直下の習慣.md", "奥の習慣.md"), result.results.map { it.title }.toSet())
    }

    @Test
    fun `フォルダを選ぶとその中の奥まで選び、名前が前方で重なるフォルダは含めない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, openVault())
        controller.onOpened {
            listOf(
                notePath("直下の習慣.md"),
                notePath("ideas/浅い習慣.md"),
                notePath("ideas/2026/深い習慣.md"),
                notePath("ideas2/別の習慣.md")
            )
        }

        controller.selectFolder(NoteFolder("ideas", "doc-ideas"))
        controller.searchByKeyword("習慣", ::noNotes)

        val result = state.value.searchState as SearchState.Success
        assertEquals(setOf("浅い習慣.md", "深い習慣.md"), result.results.map { it.title }.toSet())
    }

    @Test
    fun `一覧がまだなら頼んだときに受け取る`() = runTest {
        val state = NoteUiStateStore(NoteUiState())

        controller(state, openVault()).searchByKeyword("習慣") { listOf(notePath("習慣.md")) }

        assertEquals(listOf("習慣.md"), (state.value.searchState as SearchState.Success).results.map { it.title })
    }

    @Test
    fun `空白だけの検索語では一覧を頼まない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        var collects = 0

        controller(state, openVault()).searchByKeyword("   ") { collects++; emptyList() }

        assertEquals(0, collects)
        assertTrue(state.value.searchState is SearchState.Idle)
    }

    @Test
    fun `一覧を読めなければAIの検索もエラーになる`() = runTest {
        val state = NoteUiStateStore(NoteUiState())

        controller(state, openVault()).searchByKeyword("習慣") { throw IllegalStateException("走査失敗") }

        assertEquals("走査失敗", (state.value.searchState as SearchState.Error).message)
    }

    /** 範囲は頼んだ時点のもの。頼んだ後にチップを替えた結果は、新しい範囲の Idle を上書きしない。 */
    @Test
    fun `一覧を待つ間に範囲を替えたら、前の範囲の結果を書かない`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val scan = CompletableDeferred<List<NoteFile>>()
        val controller = controller(state, openVault())

        controller.searchByKeyword("習慣") { scan.await() }
        controller.selectFolder(NoteFolder("ideas", "doc-ideas"))
        scan.complete(listOf(notePath("習慣.md")))

        assertTrue(state.value.searchState is SearchState.Idle)
    }

    @Test
    fun `Vaultを替えた後のAIの検索は、新しい一覧を頼み直す`() = runTest {
        val state = NoteUiStateStore(NoteUiState())
        val controller = controller(state, openVault())
        controller.onOpened { listOf(notePath("旧の習慣.md")) }

        controller.onVaultChanged()
        controller.searchByKeyword("習慣") { listOf(notePath("新の習慣.md")) }

        assertEquals(listOf("新の習慣.md"), (state.value.searchState as SearchState.Success).results.map { it.title })
    }

    private fun openVault() = FakeVaultBrowser(FakeVaultHandle())

    private fun titles(state: NoteUiStateStore): List<String> =
        (state.value.titleSearch as TitleSearchState.Matches).matches.map { it.note.title }

    private suspend fun noNotes(): List<NoteFile> = throw AssertionError("開いたときの一覧を使うはず")

    private fun notePath(path: String) = NoteFile(
        name = path.substringAfterLast('/'),
        ref = DocumentRef("content://fake/$path"),
        vaultRelativePath = path
    )

    private fun controller(
        state: NoteUiStateStore,
        vault: FakeVaultBrowser = FakeVaultBrowser(handle = null),
        vaultGeneration: () -> Long = { 0L },
        aiClient: AiClient = FakeAiClient(AiAvailability.Unsupported),
        matchDispatcher: CoroutineDispatcher = Dispatchers.Unconfined
    ) = SearchController(
        scope = CoroutineScope(Dispatchers.Unconfined),
        vault = vault,
        searchPickerUseCase = SearchPickerUseCase(aiClient),
        state = state.searchWriter,
        vaultGeneration = vaultGeneration,
        matchDispatcher = matchDispatcher
    )

}
