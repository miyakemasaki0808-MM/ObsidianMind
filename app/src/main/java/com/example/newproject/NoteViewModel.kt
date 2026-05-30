package com.example.newproject

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.genai.common.DownloadStatus
import com.example.newproject.ai.AICoreClient
import com.example.newproject.ai.AiAvailability
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

data class NoteUiState(
    val vaultSelected: Boolean = false,
    val noteState: NoteState = NoteState.Idle,
    val summaryState: SummaryState = SummaryState.Idle
)

class NoteViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(PREFS_NAME, Application.MODE_PRIVATE)
    private val repository = NoteRepository()
    private val aiClient = AICoreClient()
    private val summarizeUseCase = SummarizeUseCase(aiClient)

    private val _uiState = MutableStateFlow(NoteUiState())
    val uiState: StateFlow<NoteUiState> = _uiState.asStateFlow()

    // DL完了後に要約を再実行するために保持
    private var pendingTitle: String = ""
    private var pendingContent: String = ""

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
        _uiState.value = _uiState.value.copy(vaultSelected = true)
    }

    fun loadRandomNote(contentResolver: ContentResolver) {
        val uri = vaultUri ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                noteState = NoteState.Loading,
                summaryState = SummaryState.Idle
            )
            try {
                val notes = repository.collectNotes(contentResolver, uri)
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
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    noteState = NoteState.Error(e.message ?: "Unknown error")
                )
            }
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
