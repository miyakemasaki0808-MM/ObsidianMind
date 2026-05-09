package com.example.newproject

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class MainActivity : androidx.activity.ComponentActivity() {

    private val viewModel: NoteViewModel by viewModels()

    private lateinit var vaultStatusText: TextView
    private lateinit var noteTitleText: TextView
    private lateinit var noteContentText: TextView
    private lateinit var randomNoteButton: Button
    private lateinit var loadingIndicator: ProgressBar

    private val openVault = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri ?: return@registerForActivityResult
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        contentResolver.takePersistableUriPermission(uri, flags)
        viewModel.saveVault(uri)
        viewModel.loadRandomNote(contentResolver)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        vaultStatusText = findViewById(R.id.vaultStatusText)
        noteTitleText = findViewById(R.id.noteTitleText)
        noteContentText = findViewById(R.id.noteContentText)
        randomNoteButton = findViewById(R.id.randomNoteButton)
        loadingIndicator = findViewById(R.id.loadingIndicator)

        findViewById<Button>(R.id.selectVaultButton).setOnClickListener {
            openVault.launch(null)
        }
        randomNoteButton.setOnClickListener {
            if (viewModel.vaultUri != null) {
                viewModel.loadRandomNote(contentResolver)
            } else {
                openVault.launch(null)
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    renderState(state)
                }
            }
        }
    }

    private fun renderState(state: NoteUiState) {
        vaultStatusText.setText(
            if (state.vaultSelected) R.string.vault_selected else R.string.vault_not_selected
        )

        val isLoading = state.noteState is NoteState.Loading
        loadingIndicator.visibility = if (isLoading) View.VISIBLE else View.GONE
        randomNoteButton.isEnabled = !isLoading

        when (val noteState = state.noteState) {
            is NoteState.Idle -> {
                noteTitleText.setText(R.string.no_note_loaded)
                noteContentText.setText(R.string.random_note_empty_state)
            }
            is NoteState.Loading -> {
                noteTitleText.setText(R.string.no_note_loaded)
                noteContentText.text = ""
            }
            is NoteState.Success -> {
                noteTitleText.text = noteState.title
                noteContentText.text = noteState.content
            }
            is NoteState.Empty -> {
                noteTitleText.setText(R.string.no_note_loaded)
                noteContentText.setText(R.string.no_markdown_notes)
            }
            is NoteState.Error -> {
                noteTitleText.setText(R.string.no_note_loaded)
                noteContentText.setText(R.string.vault_read_error)
                Toast.makeText(this, noteState.message, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
