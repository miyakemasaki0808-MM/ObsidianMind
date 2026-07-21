package com.example.newproject

import android.content.ContentResolver
import android.net.Uri
import com.example.newproject.domain.PickerResult
import com.example.newproject.domain.RelatedNote
import com.example.newproject.domain.SearchPickerUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
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
    private val uiState: MutableStateFlow<NoteUiState>,
    private val vaultUri: () -> Uri?
) {
    // スコープ（フォルダ）単位の走査結果キャッシュ。SAFの再帰走査は1フォルダごとに
    // IPCが発生して重いため、短時間の連続操作（検索→ランダム→検索）で再走査しない。
    // キーは selectedFolder の documentId（null はルート直下スコープ）。
    private data class ScopeCacheEntry(val notes: List<NoteFile>, val loadedAt: Long)
    private val scopeNotesCache = mutableMapOf<String?, ScopeCacheEntry>()

    // Vault切替時に NoteViewModel の saveVault() から呼ばれる契約。
    // 旧Vaultの documentId をキーに持つキャッシュを破棄する。
    fun onVaultChanged() {
        scopeNotesCache.clear()
    }

    // タブ表示時にフォルダchips用の第一階層フォルダを列挙する。
    fun loadFolders(contentResolver: ContentResolver) {
        val uri = vaultUri() ?: return
        scope.launch {
            try {
                val folders = repository.listTopLevelFolders(contentResolver, uri)
                uiState.update { current -> current.copy(folders = folders) }
            } catch (_: Exception) {
                // フォルダ列挙の失敗は致命的でない（ルート直下スコープは使える）
            }
        }
    }

    fun selectFolder(folder: NoteFolder?) {
        uiState.update { current -> current.copy(selectedFolder = folder) }
    }

    // キーワードモード: スコープ収集 → SearchPickerUseCase で3件選定。
    fun searchByKeyword(contentResolver: ContentResolver, query: String) {
        val uri = vaultUri() ?: return
        val q = query.trim()
        if (q.isBlank()) return
        scope.launch {
            uiState.update { current -> current.copy(searchState = SearchState.Loading) }
            try {
                val folder = uiState.value.selectedFolder
                val notes = collectInScopeCached(contentResolver, uri, folder)
                when (val result = searchPickerUseCase.pick(q, notes)) {
                    is PickerResult.Success -> uiState.update { current ->
                        current.copy(searchState = SearchState.Success(result.notes, result.aiStatus))
                    }
                    is PickerResult.Error -> uiState.update { current ->
                        current.copy(searchState = SearchState.Error(result.message))
                    }
                }
            } catch (e: Exception) {
                uiState.update { current ->
                    current.copy(searchState = SearchState.Error(e.message ?: "Unknown error"))
                }
            }
        }
    }

    // ランダムモード: スコープ内からシャッフルして3件（AI不使用）。
    fun pickRandomInScope(contentResolver: ContentResolver) {
        val uri = vaultUri() ?: return
        scope.launch {
            uiState.update { current -> current.copy(searchState = SearchState.Loading) }
            try {
                val folder = uiState.value.selectedFolder
                val notes = collectInScopeCached(contentResolver, uri, folder)
                val picked = notes.shuffled().take(3).map {
                    RelatedNote(title = it.name, uri = it.uri, isWikilinked = false, lastModified = it.lastModified)
                }
                uiState.update { current -> current.copy(searchState = SearchState.Success(picked)) }
            } catch (e: Exception) {
                uiState.update { current ->
                    current.copy(searchState = SearchState.Error(e.message ?: "Unknown error"))
                }
            }
        }
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
