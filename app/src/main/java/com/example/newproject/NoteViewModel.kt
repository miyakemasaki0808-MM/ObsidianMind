package com.example.newproject

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

data class NoteUiState(
    val vaultSelected: Boolean = false,
    val noteState: NoteState = NoteState.Idle
)

class NoteViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(PREFS_NAME, Application.MODE_PRIVATE)
    private val repository = NoteRepository()

    private val _uiState = MutableStateFlow(NoteUiState())
    val uiState: StateFlow<NoteUiState> = _uiState.asStateFlow()

    var vaultUri: Uri? = null
        private set

    init {
        restoreVault()
    }

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
            _uiState.value = _uiState.value.copy(noteState = NoteState.Loading)
            _uiState.value = try {
                val notes = repository.collectNotes(contentResolver, uri)
                if (notes.isEmpty()) {
                    _uiState.value.copy(noteState = NoteState.Empty)
                } else {
                    val note = notes.random()
                    val content = repository.readNoteContent(contentResolver, note.uri)
                    _uiState.value.copy(noteState = NoteState.Success(note.name, content))
                }
            } catch (e: Exception) {
                _uiState.value.copy(noteState = NoteState.Error(e.message ?: "Unknown error"))
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "random_note_prefs"
        private const val KEY_VAULT_URI = "vault_uri"
    }
}
