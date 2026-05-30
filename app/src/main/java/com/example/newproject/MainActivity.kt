package com.example.newproject

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.newproject.GraphState
import com.example.newproject.NoteState
import com.example.newproject.QuizState
import com.example.newproject.ui.GraphViewScreen
import com.example.newproject.ui.QuizScreen
import com.example.newproject.ui.RandomNoteScreen

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

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val windowSizeClass = calculateWindowSizeClass(this)
            val navController = rememberNavController()

            NavHost(navController = navController, startDestination = "random_note") {
                composable("random_note") {
                    RandomNoteScreen(
                        uiState = uiState,
                        isExpanded = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded,
                        onSelectVault = { openVault.launch(null) },
                        onRandomNote = {
                            if (viewModel.vaultUri != null) viewModel.loadRandomNote(contentResolver)
                            else openVault.launch(null)
                        },
                        onOpenNote = { note -> viewModel.openNote(contentResolver, note) },
                        onGenerateQuiz = {
                            val state = uiState.noteState
                            if (state is NoteState.Success) {
                                if (uiState.quizState !is QuizState.Success) {
                                    viewModel.generateQuiz(state.title, state.content)
                                }
                                navController.navigate("quiz")
                            }
                        },
                        onGraphView = {
                            viewModel.buildVaultGraph(contentResolver)
                            navController.navigate("graph")
                        }
                    )
                }

                composable("graph") {
                    val noteTitle = (uiState.noteState as? NoteState.Success)?.title ?: ""
                    GraphViewScreen(
                        currentNoteTitle = noteTitle,
                        graphState = uiState.graphState,
                        onNodeTap = { title ->
                            viewModel.openNoteByTitle(contentResolver, title)
                            navController.popBackStack()
                        },
                        onBack = { navController.popBackStack() }
                    )
                }

                composable("quiz") {
                    val noteTitle = (uiState.noteState as? NoteState.Success)?.title ?: ""
                    QuizScreen(
                        noteTitle = noteTitle,
                        quizState = uiState.quizState,
                        onBack = {
                            navController.popBackStack()
                        }
                    )
                }
            }
        }
    }
}
