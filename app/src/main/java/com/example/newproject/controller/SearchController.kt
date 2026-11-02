package com.example.newproject.controller

import com.example.newproject.model.NoteFile
import com.example.newproject.model.NoteFolder
import com.example.newproject.data.VaultBrowser
import com.example.newproject.model.SearchStateWriter
import com.example.newproject.model.state.SearchState
import com.example.newproject.model.state.TitleSearchState
import com.example.newproject.domain.PickerResult
import com.example.newproject.domain.SearchPickerUseCase
import com.example.newproject.domain.matchNotesByTitle
import com.example.newproject.domain.notesInFolder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Explore（さがすタブ）を担当する（→ features/ai_picker.md）。
 * タイトルのモードは Vault 全体の一覧を照らし、AI のモードは同じ一覧を範囲で絞って Nano に選ばせる。
 * folders / selectedFolder / searchState / titleSearch の更新のみを行う。
 */
class SearchController(
    private val scope: CoroutineScope,
    private val vault: VaultBrowser,
    private val searchPickerUseCase: SearchPickerUseCase,
    private val state: SearchStateWriter,
    // Vault切替の世代。NoteViewModel が saveVault() で採番する。
    // 検索用の activeRequestId とは寿命が違う（フォルダ列挙は検索要求では無効化しない）。
    private val vaultGeneration: () -> Long,
    private val matchDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    // 開いたときに受け取った Vault 全体の一覧。打っている間はこれを照らす。
    // 打つたびに引くと、一覧の寿命が切れた時点で打っている途中に全走査が走る。
    // 開き直して読み直している間も前の一覧で照らし、届いたら照らし直す。
    private var vaultNotes: List<NoteFile>? = null
    private var notesFailure: String? = null
    private var notesJob: Job? = null

    // 一覧を頼んだ順番。後から頼んだ一覧ほど新しい（本文を書き換えた後の要求は、前の走査へ相乗りしない）ので、
    // 遅れて届いた前の要求の一覧で、受け取り済みの新しい一覧を上書きしない。
    private var lastNotesRequest = 0L
    private var receivedNotesRequest = 0L

    private var titleQuery = ""
    private var titleJob: Job? = null
    private var titleRequestId = 0L

    private var searchJob: Job? = null
    private var activeRequestId = 0L

    // フォルダ列挙は検索とは別寿命（検索要求では止めない／Vault切替では止める）なので、
    // searchJob には相乗りさせず独立して保持する。
    private var foldersJob: Job? = null

    // Vault切替時に NoteViewModel の saveVault() から呼ばれる契約。
    // 旧Vaultの一覧を捨て、走行中の要求も止める。放置すると旧Vaultのノートが、切替後に
    // Idle へ戻したはずの状態を上書きして現れる。
    fun onVaultChanged() {
        activeRequestId++
        searchJob?.cancel()
        searchJob = null
        foldersJob?.cancel()
        foldersJob = null
        notesJob?.cancel()
        notesJob = null
        titleRequestId++
        titleJob?.cancel()
        titleJob = null
        vaultNotes = null
        notesFailure = null
    }

    /** タブを開いた。フォルダの一覧と、Vault 全体の一覧を受け取る。 */
    fun onOpened(collectNotes: suspend () -> List<NoteFile>) {
        loadFolders()
        searchTitle("")
        val generation = vaultGeneration()
        val notesRequest = ++lastNotesRequest
        notesJob?.cancel()
        notesJob = scope.launch {
            try {
                receiveNotes(generation, notesRequest, collectNotes())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation != vaultGeneration()) return@launch
                notesFailure = e.message ?: "Unknown error"
                searchTitle(titleQuery)
            }
        }
    }

    /**
     * 受け取った一覧を両方のモードで使えるようにし、今の言葉で照らし直す。
     * 受け取る間に Vault が替わっていたら捨てる。後から頼んだ一覧を受け取り済みなら捨てる。
     */
    private fun receiveNotes(generation: Long, notesRequest: Long, notes: List<NoteFile>) {
        if (generation != vaultGeneration() || notesRequest < receivedNotesRequest) return
        receivedNotesRequest = notesRequest
        vaultNotes = notes
        notesFailure = null
        searchTitle(titleQuery)
    }

    /**
     * タブ表示時にフォルダchips用の第一階層フォルダを列挙する。
     *
     * 起動時の世代を捕まえて `update` の直前に照合する。`onVaultChanged()` の
     * `cancel()` だけに頼ると、SAF走査から戻った直後に切替が起きた場合に
     * 旧Vaultのフォルダが新Vaultの chips として並ぶ。
     */
    fun loadFolders() {
        val handle = vault.current() ?: return
        val generation = vaultGeneration()
        foldersJob?.cancel()
        foldersJob = scope.launch {
            try {
                val folders = handle.listTopLevelFolders()
                if (generation != vaultGeneration()) return@launch
                state.update { current -> current.copy(folders = folders, foldersError = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 「すべて」は使えるので致命的ではないが、黙って消すと
                // 「フォルダが無い」と区別できない。chips の下に注記として出す。
                if (generation != vaultGeneration()) return@launch
                state.update { current ->
                    current.copy(foldersError = "フォルダ一覧を取得できませんでした。")
                }
            }
        }
    }

    /**
     * タイトルを照らす。打つたびに呼ばれる。一覧がまだなら届いた後に照らし直す（→ [onOpened]）。
     * 前の一覧も無く読めなかったときだけ失敗を出す。
     */
    fun searchTitle(query: String) {
        vault.current() ?: return
        val requestId = ++titleRequestId
        titleJob?.cancel()
        titleQuery = query
        if (query.isBlank()) {
            setTitleIfCurrent(requestId, TitleSearchState.Idle)
            return
        }
        val notes = vaultNotes
        if (notes == null) {
            val failure = notesFailure
            setTitleIfCurrent(requestId, if (failure != null) TitleSearchState.Error(failure) else TitleSearchState.Scanning)
            return
        }
        titleJob = scope.launch {
            val matches = withContext(matchDispatcher) { matchNotesByTitle(query, notes) }
            setTitleIfCurrent(requestId, TitleSearchState.Matches(matches))
        }
    }

    /**
     * AI のモードの範囲（フォルダ）を切り替える。
     *
     * 進行中の検索は範囲ごと変わるため、ここで無効化する。
     * [startRequest] で採番を進めておくと、走行中の要求が結果を持ち帰っても
     * [setStateIfCurrent] の照合で弾かれる（`cancel()` だけでは足りない理由は同KDoc参照）。
     *
     * 表示中の結果も前の範囲のものなので `Idle` へ戻す。同じchipの再タップでは
     * 何も起きないようにして、取り違えで結果を失わないようにする。
     */
    fun selectFolder(folder: NoteFolder?) {
        if (state.current.selectedFolder?.documentId == folder?.documentId) return
        startRequest()
        searchJob = null
        state.update { current ->
            current.copy(selectedFolder = folder, searchState = SearchState.Idle)
        }
    }

    /** AI のモード: 範囲で絞って [SearchPickerUseCase] で3件選ぶ。範囲は頼んだ時点のものを使う。 */
    fun searchByKeyword(query: String, collectNotes: suspend () -> List<NoteFile>) {
        vault.current() ?: return
        val q = query.trim()
        if (q.isBlank()) return
        val folder = state.current.selectedFolder
        val generation = vaultGeneration()
        val notesRequest = ++lastNotesRequest
        val requestId = startRequest()
        searchJob = scope.launch {
            setStateIfCurrent(requestId, SearchState.Loading)
            try {
                val notes = vaultNotes ?: collectNotes().also { receiveNotes(generation, notesRequest, it) }
                val candidates = if (folder == null) notes else notesInFolder(notes, folder)
                when (val result = searchPickerUseCase.pick(q, candidates)) {
                    is PickerResult.Success ->
                        setStateIfCurrent(
                            requestId,
                            SearchState.Success(result.notes, result.isAiAssisted)
                        )
                    is PickerResult.Error ->
                        setStateIfCurrent(requestId, SearchState.Error(result.message))
                }
            } catch (e: CancellationException) {
                // 新しい要求に追い越されただけ。エラー表示にはしない。
                throw e
            } catch (e: Exception) {
                setStateIfCurrent(requestId, SearchState.Error(e.message ?: "Unknown error"))
            }
        }
    }

    // 新しい要求を採番し、走行中の前の要求を止める。
    private fun startRequest(): Long {
        val requestId = ++activeRequestId
        searchJob?.cancel()
        return requestId
    }

    /**
     * 最後の要求だけが searchState を更新できるようにする。
     *
     * cancel() だけでは足りない。キャンセルが効くのは中断点までで、
     * [SearchPickerUseCase.pick] のように内部で結果型を返す経路は
     * 中断せずに戻り、そのまま update に到達し得るため。
     */
    private fun setStateIfCurrent(requestId: Long, next: SearchState) {
        if (requestId != activeRequestId) return
        state.update { current -> current.copy(searchState = next) }
    }

    private fun setTitleIfCurrent(requestId: Long, next: TitleSearchState) {
        if (requestId != titleRequestId) return
        state.update { current -> current.copy(titleSearch = next) }
    }
}
