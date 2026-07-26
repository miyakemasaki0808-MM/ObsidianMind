package com.example.newproject.model.state

sealed class NoteState {
    object Idle : NoteState()
    object Loading : NoteState()
    data class Success(
        val title: String,
        val content: String,
        val targetUri: String = "",
        val originalHash: String? = null,
        val distillUnavailableReason: String? = null
    ) : NoteState()
    object Empty : NoteState()
    data class Error(val message: String, val id: Long = System.currentTimeMillis()) : NoteState()
}
