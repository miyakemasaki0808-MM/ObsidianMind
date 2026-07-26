package com.example.newproject.controller

import com.example.newproject.model.NoteFile
import com.example.newproject.model.NoteFolder
import com.example.newproject.data.NoteRepository
import com.example.newproject.model.SearchStateWriter
import com.example.newproject.model.state.SearchState
import android.content.ContentResolver
import android.net.Uri
import com.example.newproject.domain.PickerResult
import com.example.newproject.model.RelatedNote
import com.example.newproject.domain.SearchPickerUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * さがすタブ（AIピッカー）を担当する。
 * フォルダ列挙・キーワード検索・スコープ内ランダムと、スコープ単位の走査キャッシュを持つ。
 * folders / selectedFolder / searchState の更新のみを行う。
 */
class SearchController(
    private val scope: CoroutineScope,
    private val repository: NoteRepository,
    private val searchPickerUseCase: SearchPickerUseCase,
    private val state: SearchStateWriter,
    private val vaultUri: () -> Uri?,
    // Vault切替の世代。NoteViewModel が saveVault() で採番する。
    // 検索用の activeRequestId とは寿命が違う（フォルダ列挙は検索要求では無効化しない）。
    private val vaultGeneration: () -> Long
) {
    // スコープ（フォルダ）単位の走査結果キャッシュ。SAFの再帰走査は1フォルダごとに
    // IPCが発生して重いため、短時間の連続操作（検索→ランダム→検索）で再走査しない。
    // キーは selectedFolder の documentId（null はルート直下スコープ）。
    //
    // 素の mutableMapOf で足りるのは、読み書きが必ず Main 単一スレッドで起きるため。
    // scope は viewModelScope（Dispatchers.Main.immediate）で、SAF走査の
    // withContext(Dispatchers.IO) は repository の内側に閉じている。
    // ここを別ディスパッチャから触る変更を入れる場合は同期が要る。
    private data class ScopeCacheEntry(val notes: List<NoteFile>, val loadedAt: Long)
    private val scopeNotesCache = mutableMapOf<String?, ScopeCacheEntry>()

    // 検索・ランダムは同じ searchState を奪い合うので、Jobは1本で共有する
    // （検索→ランダムの切替でも前の要求を止めたい）。
    private var searchJob: Job? = null
    private var activeRequestId = 0L

    // フォルダ列挙は検索とは別寿命（検索要求では止めない／Vault切替では止める）なので、
    // searchJob には相乗りさせず独立して保持する。
    private var foldersJob: Job? = null

    // Vault切替時に NoteViewModel の saveVault() から呼ばれる契約。
    // 旧Vaultの documentId をキーに持つキャッシュを破棄する。
    // 走行中の要求も止める。放置すると旧Vaultのノートが、切替後に
    // Idle へ戻したはずの searchState を上書きして現れる。
    fun onVaultChanged() {
        activeRequestId++
        searchJob?.cancel()
        searchJob = null
        foldersJob?.cancel()
        foldersJob = null
        scopeNotesCache.clear()
    }

    /**
     * タブ表示時にフォルダchips用の第一階層フォルダを列挙する。
     *
     * 起動時の世代を捕まえて `update` の直前に照合する。`onVaultChanged()` の
     * `cancel()` だけに頼ると、SAF走査から戻った直後に切替が起きた場合に
     * 旧Vaultのフォルダが新Vaultの chips として並ぶ。
     */
    fun loadFolders(contentResolver: ContentResolver) {
        val uri = vaultUri() ?: return
        val generation = vaultGeneration()
        foldersJob?.cancel()
        foldersJob = scope.launch {
            try {
                val folders = repository.listTopLevelFolders(contentResolver, uri)
                if (generation != vaultGeneration()) return@launch
                state.update { current -> current.copy(folders = folders, foldersError = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // ルート直下スコープは使えるので致命的ではないが、黙って消すと
                // 「フォルダが無い」と区別できない。chips の下に注記として出す。
                if (generation != vaultGeneration()) return@launch
                state.update { current ->
                    current.copy(foldersError = "フォルダ一覧を取得できませんでした。")
                }
            }
        }
    }

    /**
     * 検索スコープ（フォルダ）を切り替える。
     *
     * 進行中の検索・ランダムは走査対象のスコープごと変わるため、ここで無効化する。
     * [startRequest] で採番を進めておくと、走行中の要求が結果を持ち帰っても
     * [setStateIfCurrent] の照合で弾かれる（`cancel()` だけでは足りない理由は同KDoc参照）。
     *
     * 表示中の結果も前のスコープのものなので `Idle` へ戻す。同じchipの再タップでは
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

    // キーワードモード: スコープ収集 → SearchPickerUseCase で3件選定。
    fun searchByKeyword(contentResolver: ContentResolver, query: String) {
        val uri = vaultUri() ?: return
        val q = query.trim()
        if (q.isBlank()) return
        val requestId = startRequest()
        searchJob = scope.launch {
            setStateIfCurrent(requestId, SearchState.Loading)
            try {
                val folder = state.current.selectedFolder
                val notes = collectInScopeCached(contentResolver, uri, folder)
                when (val result = searchPickerUseCase.pick(q, notes)) {
                    is PickerResult.Success ->
                        setStateIfCurrent(requestId, SearchState.Success(result.notes, result.aiStatus))
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

    // ランダムモード: スコープ内からシャッフルして3件（AI不使用）。
    fun pickRandomInScope(contentResolver: ContentResolver) {
        val uri = vaultUri() ?: return
        val requestId = startRequest()
        searchJob = scope.launch {
            setStateIfCurrent(requestId, SearchState.Loading)
            try {
                val folder = state.current.selectedFolder
                val notes = collectInScopeCached(contentResolver, uri, folder)
                val picked = notes.shuffled().take(3).map {
                    RelatedNote(title = it.name, uri = it.uri, isWikilinked = false, lastModified = it.lastModified)
                }
                setStateIfCurrent(requestId, SearchState.Success(picked))
            } catch (e: CancellationException) {
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

    // さがすタブのスコープ走査をTTL付きで取得する。
    private suspend fun collectInScopeCached(
        contentResolver: ContentResolver,
        vaultUri: Uri,
        folder: NoteFolder?
    ): List<NoteFile> {
        val key = folder?.documentId
        val now = System.currentTimeMillis()
        scopeNotesCache[key]?.let { entry ->
            if (now - entry.loadedAt < NOTES_CACHE_TTL_MS) return entry.notes
        }
        val notes = repository.collectNotesInScope(contentResolver, vaultUri, folder)
        scopeNotesCache[key] = ScopeCacheEntry(notes, now)
        return notes
    }
}

// ノート一覧キャッシュの有効期間。NoteViewModel の全体キャッシュと
// SearchController のスコープキャッシュで共用する。
// Vault側の編集（Obsidian同期等）は最大この時間だけ反映が遅れる。
internal const val NOTES_CACHE_TTL_MS = 60_000L
