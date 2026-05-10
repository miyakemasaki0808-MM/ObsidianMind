package com.example.newproject

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val Indigo = Color(0xFF4D3DFF)
private val Aqua = Color(0xFF00C2FF)
private val Coral = Color(0xFFFF6B8A)
private val OnVibrant = Color.White
private val OnVibrantMuted = Color(0xFFEAF7FF)
private val OnSurface = Color(0xFF202124)
private val Panel = Color(0xFFFDFEFF)
private val ButtonPrimary = Color(0xFFFF3D71)
private val ButtonSecondary = Color(0xFF16B8A6)

class MainActivity : ComponentActivity() {

    private val viewModel: NoteViewModel by viewModels()

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
        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            RandomNoteScreen(
                uiState = uiState,
                onSelectVault = { openVault.launch(null) },
                onRandomNote = {
                    if (viewModel.vaultUri != null) viewModel.loadRandomNote(contentResolver)
                    else openVault.launch(null)
                }
            )
        }
    }
}

@Composable
fun RandomNoteScreen(
    uiState: NoteUiState,
    onSelectVault: () -> Unit,
    onRandomNote: () -> Unit
) {
    val context = LocalContext.current

    LaunchedEffect((uiState.noteState as? NoteState.Error)?.id) {
        if (uiState.noteState is NoteState.Error) {
            Toast.makeText(context, uiState.noteState.message, Toast.LENGTH_SHORT).show()
        }
    }

    val gradient = Brush.linearGradient(
        colors = listOf(Indigo, Aqua, Coral),
        start = Offset(0f, Float.POSITIVE_INFINITY),
        end = Offset(Float.POSITIVE_INFINITY, 0f)
    )

    val isLoading = uiState.noteState is NoteState.Loading

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(gradient)
            .padding(24.dp)
    ) {
        Text(
            text = stringResource(R.string.random_note_title),
            color = OnVibrant,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = stringResource(
                if (uiState.vaultSelected) R.string.vault_selected
                else R.string.vault_not_selected
            ),
            color = OnVibrantMuted,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 10.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onSelectVault,
                modifier = Modifier.weight(1f).height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ButtonSecondary),
                shape = RoundedCornerShape(24.dp)
            ) {
                Text(stringResource(R.string.select_vault), color = OnVibrant)
            }
            Button(
                onClick = onRandomNote,
                enabled = !isLoading,
                modifier = Modifier.weight(1f).height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ButtonPrimary),
                shape = RoundedCornerShape(24.dp)
            ) {
                Text(stringResource(R.string.show_random_note), color = OnVibrant)
            }
        }

        if (isLoading) {
            CircularProgressIndicator(
                color = OnVibrant,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 16.dp)
            )
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = if (isLoading) 8.dp else 24.dp),
            color = Panel,
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                val (noteTitle, noteContent) = when (val state = uiState.noteState) {
                    is NoteState.Success -> state.title to state.content
                    is NoteState.Empty   -> stringResource(R.string.no_note_loaded) to stringResource(R.string.no_markdown_notes)
                    is NoteState.Error   -> stringResource(R.string.no_note_loaded) to stringResource(R.string.vault_read_error)
                    else                 -> stringResource(R.string.no_note_loaded) to stringResource(R.string.random_note_empty_state)
                }

                Text(
                    text = noteTitle,
                    color = OnSurface,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = noteContent,
                    color = OnSurface,
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        }
    }
}
