package com.example.newproject

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.genai.common.DownloadStatus
import com.example.newproject.ai.AICoreClient
import com.example.newproject.ai.AiClient
import com.example.newproject.ai.PromptBuilder
import com.example.newproject.domain.AiRecommendationStatus
import com.example.newproject.domain.RelatedNote
import com.example.newproject.domain.RelatedNotesResult
import com.example.newproject.domain.RelatedNotesUseCase
import com.example.newproject.domain.PickerResult
import com.example.newproject.domain.SearchPickerUseCase
import com.example.newproject.domain.SummarizeUseCase
import com.example.newproject.domain.SummaryResult
import com.example.newproject.ui.markdown.NoteSection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// 状態定義は NoteUiState.kt を参照。

class NoteViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(PREFS_NAME, Application.MODE_PRIVATE)
    private val repository = NoteRepository()
    private val aiClient: AiClient = AICoreClient()
    private val summarizeUseCase = SummarizeUseCase(aiClient)
    private val relatedNotesUseCase = RelatedNotesUseCase(aiClient)
    private val searchPickerUseCase = SearchPickerUseCase(aiClient)

    private val _uiState = MutableStateFlow(NoteUiState())
    val uiState: StateFlow<NoteUiState> = _uiState.asStateFlow()

    // 機能ごとのController。scope と状態Flowを共有し、担当領域の状態のみ更新する
    private val sectionChat = SectionChatController(viewModelScope, aiClient, _uiState)
    private val annotation = AnnotationController(viewModelScope, repository, aiClient, _uiState) { vaultUri }

    // DL完了後に要約を再実行するために保持
    private var pendingTitle: String = ""
    private var pendingContent: String = ""
    private var cachedNotes: List<NoteFile> = emptyList()
    private var cachedNotesLoadedAt = 0L

    // スコープ（フォルダ）単位の走査結果キャッシュ。SAFの再帰走査は1フォルダごとに
    // IPCが発生して重いため、短時間の連続操作（検索→ランダム→検索）で再走査しない。
    // キーは selectedFolder の documentId（null はルート直下スコープ）。
    private data class ScopeCacheEntry(val notes: List<NoteFile>, val loadedAt: Long)
    private val scopeNotesCache = mutableMapOf<String?, ScopeCacheEntry>()

    // ノート切替時に前のノートのAI応答が後から届いて上書きしないよう、
    // 実行中ジョブを保持して新規要求時にキャンセルする
    // （セクションチャットのジョブは SectionChatController が保持）
    private var noteLoadJob: Job? = null
    private var summaryJob: Job? = null
    private var relatedNotesJob: Job? = null

    var vaultUri: Uri? = null
        private set

    init { restoreVault() }

    private fun restoreVault() {
        val savedUri = prefs.getString(KEY_VAULT_URI, null) ?: return
        vaultUri = Uri.parse(savedUri)
        _uiState.value = _uiState.value.copy(vaultSelected = true)
    }

    fun saveVault(uri: Uri) {
        vaultUri = uri
        prefs.edit().putString(KEY_VAULT_URI, uri.toString()).apply()
        cachedNotes = emptyList()
        cachedNotesLoadedAt = 0L
        scopeNotesCache.clear()
        cancelNoteScopedJobs()
        // Vault切替時はノート単位の状態に加え、さがすタブのスコープも破棄する
        // （selectedFolder は旧Vaultの documentId を保持しているため必須）
        _uiState.value = _uiState.value.resetNoteScopedStates().copy(
            vaultSelected = true,
            folders = emptyList(),
            selectedFolder = null,
            searchState = SearchState.Idle
        )
    }

    // ノートを開き直す・Vaultを切り替える際に、ノート単位の状態をまとめて初期化する。
    // リセットをここに集約することで、状態を追加したときのリセット漏れを防ぐ。
    private fun NoteUiState.resetNoteScopedStates(): NoteUiState = copy(
        summaryState = SummaryState.Idle,
        relatedNotesState = RelatedNotesState.Idle,
        quizState = QuizState.Idle,
        annotationState = AnnotationState.Idle,
        sectionChat = null
    )

    // ノート単位の実行中AIジョブをまとめて止める（状態リセットと対で呼ぶ）。
    // 旧ノートの生成が残っていると、結果の上書きだけでなく generate() の
    // 直列化ロックを握り続けて新ノートの要約開始も遅らせてしまう。
    private fun cancelNoteScopedJobs() {
        noteLoadJob?.cancel()
        summaryJob?.cancel()
        relatedNotesJob?.cancel()
        sectionChat.cancelJobs()
    }

    // Vault全体のノート一覧をTTL付きで取得する。期限内は cachedNotes を再利用し、
    // ランダム表示の連打や関連ノートの補填で毎回の全走査を避ける。
    private suspend fun collectAllNotesCached(
        contentResolver: ContentResolver,
        vaultUri: Uri
    ): List<NoteFile> {
        val now = System.currentTimeMillis()
        if (cachedNotes.isNotEmpty() && now - cachedNotesLoadedAt < NOTES_CACHE_TTL_MS) {
            return cachedNotes
        }
        val notes = repository.collectNotes(contentResolver, vaultUri)
        cachedNotes = notes
        cachedNotesLoadedAt = now
        return notes
    }

    // さがすタブのスコープ走査をTTL付きで取得する。
    private suspend fun collectNotesInScopeCached(
        contentResolver: ContentResolver,
        vaultUri: Uri,
        scope: NoteFolder?
    ): List<NoteFile> {
        val key = scope?.documentId
        val now = System.currentTimeMillis()
        scopeNotesCache[key]?.let { entry ->
            if (now - entry.loadedAt < NOTES_CACHE_TTL_MS) return entry.notes
        }
        val notes = repository.collectNotesInScope(contentResolver, vaultUri, scope)
        scopeNotesCache[key] = ScopeCacheEntry(notes, now)
        return notes
    }

    fun loadRandomNote(contentResolver: ContentResolver) {
        val uri = vaultUri ?: return
        cancelNoteScopedJobs()
        noteLoadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.resetNoteScopedStates().copy(
                noteState = NoteState.Loading
            )
            try {
                val notes = collectAllNotesCached(contentResolver, uri)
                if (notes.isEmpty()) {
                    _uiState.value = _uiState.value.copy(noteState = NoteState.Empty)
                    return@launch
                }
                val note = notes.random()
                val content = repository.readNoteContent(contentResolver, note.uri)
                _uiState.value = _uiState.value.copy(
                    noteState = NoteState.Success(note.name, content)
                )
                fetchSummary(note.name, content)
                fetchRelatedNotes(note.name, content)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    noteState = NoteState.Error(e.message ?: "Unknown error")
                )
            }
        }
    }

    fun openNote(contentResolver: ContentResolver, note: RelatedNote) {
        cancelNoteScopedJobs()
        noteLoadJob = viewModelScope.launch {
            _uiState.value = _uiState.value.resetNoteScopedStates().copy(
                noteState = NoteState.Loading
            )
            try {
                val content = repository.readNoteContent(contentResolver, note.uri)
                _uiState.value = _uiState.value.copy(
                    noteState = NoteState.Success(note.title, content)
                )
                fetchSummary(note.title, content)
                fetchRelatedNotes(note.title, content)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    noteState = NoteState.Error(e.message ?: "Unknown error")
                )
            }
        }
    }

    // ── さがすタブ（AIピッカー）─────────────────────────────────────────────

    // タブ表示時にフォルダchips用の第一階層フォルダを列挙する。
    fun loadFolders(contentResolver: ContentResolver) {
        val uri = vaultUri ?: return
        viewModelScope.launch {
            try {
                val folders = repository.listTopLevelFolders(contentResolver, uri)
                _uiState.value = _uiState.value.copy(folders = folders)
            } catch (_: Exception) {
                // フォルダ列挙の失敗は致命的でない（ルート直下スコープは使える）
            }
        }
    }

    fun selectSearchFolder(folder: NoteFolder?) {
        _uiState.value = _uiState.value.copy(selectedFolder = folder)
    }

    // キーワードモード: スコープ収集 → SearchPickerUseCase で3件選定。
    fun searchByKeyword(contentResolver: ContentResolver, query: String) {
        val uri = vaultUri ?: return
        val q = query.trim()
        if (q.isBlank()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(searchState = SearchState.Loading)
            try {
                val scope = _uiState.value.selectedFolder
                val notes = collectNotesInScopeCached(contentResolver, uri, scope)
                when (val result = searchPickerUseCase.pick(q, notes)) {
                    is PickerResult.Success -> _uiState.value = _uiState.value.copy(
                        searchState = SearchState.Success(result.notes, result.aiStatus)
                    )
                    is PickerResult.Error -> _uiState.value = _uiState.value.copy(
                        searchState = SearchState.Error(result.message)
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    searchState = SearchState.Error(e.message ?: "Unknown error")
                )
            }
        }
    }

    // ランダムモード: スコープ内からシャッフルして3件（AI不使用）。
    fun pickRandomInScope(contentResolver: ContentResolver) {
        val uri = vaultUri ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(searchState = SearchState.Loading)
            try {
                val scope = _uiState.value.selectedFolder
                val notes = collectNotesInScopeCached(contentResolver, uri, scope)
                val picked = notes.shuffled().take(3).map {
                    RelatedNote(title = it.name, uri = it.uri, isWikilinked = false)
                }
                _uiState.value = _uiState.value.copy(
                    searchState = SearchState.Success(picked)
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    searchState = SearchState.Error(e.message ?: "Unknown error")
                )
            }
        }
    }

    fun generateQuiz(title: String, content: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(quizState = QuizState.Loading)
            try {
                val prompt = PromptBuilder.buildQuizPrompt(title, content)
                val raw = aiClient.generate(prompt)
                val cards = parseQuizResponse(raw)
                _uiState.value = _uiState.value.copy(quizState = QuizState.Success(cards))
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    quizState = QuizState.Error(e.message ?: "Unknown error")
                )
            }
        }
    }

    fun clearQuiz() {
        _uiState.value = _uiState.value.copy(quizState = QuizState.Idle)
    }

    // ── AI補記メモ（実装は AnnotationController）───────────────────────────────

    fun loadAnnotations(contentResolver: ContentResolver) = annotation.loadList(contentResolver)
    fun deleteAnnotation(contentResolver: ContentResolver, uri: Uri) = annotation.delete(contentResolver, uri)
    fun deleteAllAnnotations(contentResolver: ContentResolver) = annotation.deleteAll(contentResolver)

    // ── セクション単位のAIチャット（実装は SectionChatController）─────────────

    fun openSection(section: NoteSection) = sectionChat.open(section)
    fun sendSectionMessage(text: String) = sectionChat.sendMessage(text)
    fun closeSectionChat() = sectionChat.close()

    fun createAnnotation(
        contentResolver: ContentResolver,
        title: String,
        content: String,
        summary: String?,
        relatedNotes: List<RelatedNote>,
        aiNotes: List<RelatedNote>,
        wikilinkTitles: Set<String>
    ) = annotation.create(contentResolver, title, content, summary, relatedNotes, aiNotes, wikilinkTitles)

    private fun fetchSummary(title: String, content: String) {
        summaryJob?.cancel()
        summaryJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(summaryState = SummaryState.Loading)
            when (val result = summarizeUseCase.summarize(title, content)) {
                is SummaryResult.Success      -> {
                    _uiState.value = _uiState.value.copy(
                        summaryState = SummaryState.Success(result.summary)
                    )
                }
                is SummaryResult.AiUnavailable -> {
                    _uiState.value = _uiState.value.copy(summaryState = SummaryState.AiUnavailable)
                }
                is SummaryResult.AiNeedsDownload -> {
                    // モデル未DL → 自動でダウンロード開始
                    pendingTitle = title
                    pendingContent = content
                    startModelDownload()
                }
                is SummaryResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        summaryState = SummaryState.Error(result.message)
                    )
                }
            }
        }
    }

    private fun fetchRelatedNotes(title: String, content: String) {
        relatedNotesJob?.cancel()
        relatedNotesJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(relatedNotesState = RelatedNotesState.Loading)

            // さがすタブ等、loadRandomNote を経由しない導線では未収集のことがあるため補填する
            if (cachedNotes.isEmpty()) {
                val uri = vaultUri
                if (uri != null) {
                    try {
                        collectAllNotesCached(getApplication<Application>().contentResolver, uri)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // 収集に失敗した場合は従来どおり候補なしとして扱う
                    }
                }
            }
            if (cachedNotes.isEmpty()) {
                _uiState.value = _uiState.value.copy(
                    relatedNotesState = RelatedNotesState.Success(
                        relatedNotes = emptyList(),
                        aiNotes = emptyList()
                    )
                )
                return@launch
            }

            val wikilinkTitles = repository.parseMeta(content).wikilinkTitles
            _uiState.value = _uiState.value.copy(wikilinkTitles = wikilinkTitles)
            when (val result = relatedNotesUseCase.findRelated(title, content, cachedNotes, wikilinkTitles)) {
                is RelatedNotesResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        relatedNotesState = RelatedNotesState.Success(
                            relatedNotes = result.relatedNotes,
                            aiNotes = result.aiNotes,
                            aiStatus = result.aiStatus,
                            aiErrorMessage = result.aiErrorMessage
                        )
                    )
                }
                is RelatedNotesResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        relatedNotesState = RelatedNotesState.Error(result.message)
                    )
                }
            }
        }
    }

    private fun startModelDownload() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                summaryState = SummaryState.Downloading(downloaded = -1L, total = 0L)
            )
            try {
                aiClient.downloadModel().collect { status ->
                    when (status) {
                        is DownloadStatus.DownloadStarted -> {
                            _uiState.value = _uiState.value.copy(
                                summaryState = SummaryState.Downloading(0L, status.bytesToDownload)
                            )
                        }
                        is DownloadStatus.DownloadProgress -> {
                            val total = (_uiState.value.summaryState as? SummaryState.Downloading)?.total ?: 0L
                            _uiState.value = _uiState.value.copy(
                                summaryState = SummaryState.Downloading(status.totalBytesDownloaded, total)
                            )
                        }
                        is DownloadStatus.DownloadCompleted -> {
                            // DL完了 → 要約を実行
                            fetchSummary(pendingTitle, pendingContent)
                            fetchRelatedNotes(pendingTitle, pendingContent)
                        }
                        is DownloadStatus.DownloadFailed -> {
                            _uiState.value = _uiState.value.copy(
                                summaryState = SummaryState.Error(
                                    "モデルのダウンロードに失敗しました: ${status.e.message}"
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    summaryState = SummaryState.Error("ダウンロードエラー: ${e.message}")
                )
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "random_note_prefs"
        private const val KEY_VAULT_URI = "vault_uri"
        // ノート一覧キャッシュの有効期間。この間の連続操作は再走査しない。
        // Vault側の編集（Obsidian同期等）は最大この時間だけ反映が遅れる。
        private const val NOTES_CACHE_TTL_MS = 60_000L
    }
}
