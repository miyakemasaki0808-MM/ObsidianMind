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
import com.example.newproject.domain.RelatedNote
import com.example.newproject.domain.RelatedNotesResult
import com.example.newproject.domain.RelatedNotesUseCase
import com.example.newproject.domain.SummarizeUseCase
import com.example.newproject.domain.SummaryResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class NoteState {
    object Idle : NoteState()
    object Loading : NoteState()
    data class Success(val title: String, val content: String) : NoteState()
    object Empty : NoteState()
    data class Error(val message: String, val id: Long = System.currentTimeMillis()) : NoteState()
}

sealed class SummaryState {
    object Idle : SummaryState()
    object Loading : SummaryState()
    data class Success(val summary: String) : SummaryState()
    // DL進捗: downloaded=-1 は「開始待ち」、total=0 はサイズ不明
    data class Downloading(val downloaded: Long, val total: Long) : SummaryState()
    object AiUnavailable : SummaryState()
    data class Error(val message: String) : SummaryState()
}

sealed class RelatedNotesState {
    object Idle : RelatedNotesState()
    object Loading : RelatedNotesState()
    data class Success(
        val notes: List<RelatedNote>,
        val prefixNotes: List<RelatedNote> = emptyList()
    ) : RelatedNotesState()
    object AiUnavailable : RelatedNotesState()
    object AiNeedsDownload : RelatedNotesState()
    data class Error(val message: String) : RelatedNotesState()
}

data class QuizCard(
    val question: String,
    val choices: List<String>,
    val correctIndex: Int,
    val explanation: String = ""
)

sealed class QuizState {
    object Idle : QuizState()
    object Loading : QuizState()
    data class Success(val cards: List<QuizCard>) : QuizState()
    data class Error(val message: String) : QuizState()
}

// title(normalized) → Set<linkedTitle(normalized)>
typealias VaultGraph = Map<String, Set<String>>

sealed class GraphState {
    object Idle : GraphState()
    object Loading : GraphState()
    data class Success(val graph: VaultGraph, val allTitles: List<String>) : GraphState()
    data class Error(val message: String) : GraphState()
}

data class NoteUiState(
    val vaultSelected: Boolean = false,
    val noteState: NoteState = NoteState.Idle,
    val summaryState: SummaryState = SummaryState.Idle,
    val relatedNotesState: RelatedNotesState = RelatedNotesState.Idle,
    val quizState: QuizState = QuizState.Idle,
    val wikilinkTitles: Set<String> = emptySet(),
    val graphState: GraphState = GraphState.Idle
)

class NoteViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(PREFS_NAME, Application.MODE_PRIVATE)
    private val repository = NoteRepository()
    private val aiClient: AiClient = AICoreClient()
    private val summarizeUseCase = SummarizeUseCase(aiClient)
    private val relatedNotesUseCase = RelatedNotesUseCase(aiClient)

    private val _uiState = MutableStateFlow(NoteUiState())
    val uiState: StateFlow<NoteUiState> = _uiState.asStateFlow()

    // DL完了後に要約を再実行するために保持
    private var pendingTitle: String = ""
    private var pendingContent: String = ""
    private var cachedNotes: List<NoteFile> = emptyList()

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
        _uiState.value = _uiState.value.copy(
            vaultSelected = true,
            summaryState = SummaryState.Idle,
            relatedNotesState = RelatedNotesState.Idle,
            quizState = QuizState.Idle,
            graphState = GraphState.Idle
        )
    }

    fun loadRandomNote(contentResolver: ContentResolver) {
        val uri = vaultUri ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                noteState = NoteState.Loading,
                summaryState = SummaryState.Idle,
                relatedNotesState = RelatedNotesState.Idle,
                quizState = QuizState.Idle
            )
            try {
                val notes = repository.collectNotes(contentResolver, uri)
                cachedNotes = notes
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
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    noteState = NoteState.Error(e.message ?: "Unknown error")
                )
            }
        }
    }

    fun openNote(contentResolver: ContentResolver, note: RelatedNote) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                noteState = NoteState.Loading,
                summaryState = SummaryState.Idle,
                relatedNotesState = RelatedNotesState.Idle,
                quizState = QuizState.Idle
            )
            try {
                val content = repository.readNoteContent(contentResolver, note.uri)
                _uiState.value = _uiState.value.copy(
                    noteState = NoteState.Success(note.title, content)
                )
                fetchSummary(note.title, content)
                fetchRelatedNotes(note.title, content)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    noteState = NoteState.Error(e.message ?: "Unknown error")
                )
            }
        }
    }

    fun buildVaultGraph(contentResolver: ContentResolver) {
        // 同Vaultなら再利用
        if (_uiState.value.graphState is GraphState.Success) return
        val notes = cachedNotes
        if (notes.isEmpty()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(graphState = GraphState.Loading)
            try {
                val graph = mutableMapOf<String, Set<String>>()
                notes.forEach { note ->
                    val content = repository.readNoteContent(contentResolver, note.uri)
                    val links = repository.parseMeta(content).wikilinkTitles
                        .map { it.trim().removeSuffix(".md").lowercase() }
                        .toSet()
                    graph[note.name.trim().removeSuffix(".md").lowercase()] = links
                }
                val allTitles = notes.map { it.name.trim().removeSuffix(".md") }
                _uiState.value = _uiState.value.copy(
                    graphState = GraphState.Success(graph = graph, allTitles = allTitles)
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    graphState = GraphState.Error(e.message ?: "Unknown error")
                )
            }
        }
    }

    fun openNoteByTitle(contentResolver: ContentResolver, title: String) {
        val normalizedTarget = title.trim().removeSuffix(".md").lowercase()
        val note = cachedNotes.firstOrNull {
            it.name.trim().removeSuffix(".md").lowercase() == normalizedTarget
        } ?: return
        openNote(contentResolver, com.example.newproject.domain.RelatedNote(
            title = note.name,
            uri = note.uri,
            isWikilinked = true
        ))
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

    private fun parseQuizResponse(raw: String): List<QuizCard> {
        return raw.trim().split("\n\n").mapNotNull { block ->
            val lines = block.trim().lines()
            val q = lines.firstOrNull { it.startsWith("Q:") }?.removePrefix("Q:")?.trim()
            val a = lines.firstOrNull { it.startsWith("A:") }?.removePrefix("A:")?.trim()
            val b = lines.firstOrNull { it.startsWith("B:") }?.removePrefix("B:")?.trim()
            val c = lines.firstOrNull { it.startsWith("C:") }?.removePrefix("C:")?.trim()
            val d = lines.firstOrNull { it.startsWith("D:") }?.removePrefix("D:")?.trim()
            val answer = lines.firstOrNull { it.startsWith("ANSWER:") }?.removePrefix("ANSWER:")?.trim()
            val explanation = lines.firstOrNull { it.startsWith("EXPLANATION:") }?.removePrefix("EXPLANATION:")?.trim() ?: ""
            if (q != null && a != null && b != null && c != null && d != null && answer != null) {
                val choices = listOf(a, b, c, d)
                val correctIndex = listOf("A", "B", "C", "D").indexOf(answer)
                if (correctIndex >= 0) QuizCard(q, choices, correctIndex, explanation) else null
            } else null
        }
    }

    private fun fetchSummary(title: String, content: String) {
        viewModelScope.launch {
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
        viewModelScope.launch {
            if (cachedNotes.isEmpty()) {
                _uiState.value = _uiState.value.copy(
                    relatedNotesState = RelatedNotesState.Success(emptyList())
                )
                return@launch
            }

            _uiState.value = _uiState.value.copy(relatedNotesState = RelatedNotesState.Loading)
            val wikilinkTitles = repository.parseMeta(content).wikilinkTitles
            _uiState.value = _uiState.value.copy(wikilinkTitles = wikilinkTitles)
            when (val result = relatedNotesUseCase.findRelated(title, content, cachedNotes, wikilinkTitles)) {
                is RelatedNotesResult.Success -> {
                    _uiState.value = _uiState.value.copy(
                        relatedNotesState = RelatedNotesState.Success(
                            notes = result.notes,
                            prefixNotes = result.prefixNotes
                        )
                    )
                }
                is RelatedNotesResult.AiUnavailable -> {
                    _uiState.value = _uiState.value.copy(
                        relatedNotesState = RelatedNotesState.AiUnavailable
                    )
                }
                is RelatedNotesResult.AiNeedsDownload -> {
                    _uiState.value = _uiState.value.copy(
                        relatedNotesState = RelatedNotesState.AiNeedsDownload
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
    }
}
