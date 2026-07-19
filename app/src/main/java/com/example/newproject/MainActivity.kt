package com.example.newproject

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.viewModels
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.newproject.RelatedNotesState
import com.example.newproject.NoteState
import com.example.newproject.QuizState
import com.example.newproject.ui.AiTab
import com.example.newproject.ui.AnnotationManagerScreen
import com.example.newproject.ui.AnnotationResultScreen
import com.example.newproject.ui.AppDestination
import com.example.newproject.ui.AppScaffold
import com.example.newproject.ui.navigateToTab
import com.example.newproject.ui.NoteReaderTab
import com.example.newproject.ui.OptionsScreen
import com.example.newproject.ui.QuizScreen
import com.example.newproject.ui.RelatedTab
import com.example.newproject.ui.SearchTab

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
        enableEdgeToEdge()
        hideStatusBar()
        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val windowSizeClass = calculateWindowSizeClass(this)
            val navController = rememberNavController()

            val goGenerateQuiz = {
                val state = uiState.noteState
                if (state is NoteState.Success) {
                    if (uiState.quizState !is QuizState.Success) {
                        viewModel.generateQuiz(state.title, state.content)
                    }
                    navController.navigate("quiz")
                }
            }
            val goCreateAnnotation = {
                val noteState = uiState.noteState
                if (noteState is NoteState.Success) {
                    val summary = (uiState.summaryState as? SummaryState.Success)?.summary
                    val relatedState = uiState.relatedNotesState as? RelatedNotesState.Success
                    viewModel.createAnnotation(
                        contentResolver = contentResolver,
                        title = noteState.title,
                        content = noteState.content,
                        summary = summary,
                        relatedNotes = relatedState?.relatedNotes.orEmpty(),
                        aiNotes = relatedState?.aiNotes.orEmpty(),
                        wikilinkTitles = uiState.wikilinkTitles
                    )
                    navController.navigate("annotation")
                }
            }

            AppScaffold(windowSizeClass = windowSizeClass, navController = navController) { modifier ->
                NavHost(
                    navController = navController,
                    startDestination = "note",
                    modifier = modifier
                ) {
                    composable("note") {
                        NoteReaderTab(
                            uiState = uiState,
                            onSelectVault = { openVault.launch(null) },
                            onRandomNote = {
                                if (viewModel.vaultUri != null) viewModel.loadRandomNote(contentResolver)
                                else openVault.launch(null)
                            },
                            onOpenSection = { section -> viewModel.openSection(section) },
                            onShowSectionChat = { viewModel.showSectionChat() },
                            onSuggestionTap = { text -> viewModel.sendSectionMessage(text) },
                            onDismissSectionChat = { viewModel.dismissSectionChatSheet() },
                            onEndSectionChat = { viewModel.endSectionChat() }
                        )
                    }

                    composable("search") {
                        LaunchedEffect(uiState.vaultSelected) {
                            if (uiState.vaultSelected) viewModel.loadFolders(contentResolver)
                        }
                        SearchTab(
                            uiState = uiState,
                            onSelectFolder = { folder -> viewModel.selectSearchFolder(folder) },
                            onSearch = { q -> viewModel.searchByKeyword(contentResolver, q) },
                            onRandom = { viewModel.pickRandomInScope(contentResolver) },
                            onOpenNote = { note ->
                                viewModel.openNote(contentResolver, note)
                                navController.navigateToTab(AppDestination.Note)
                            }
                        )
                    }

                    composable("related") {
                        RelatedTab(
                            uiState = uiState,
                            onOpenNote = { note ->
                                viewModel.openNote(contentResolver, note)
                                navController.navigateToTab(AppDestination.Note)
                            }
                        )
                    }

                    composable("ai") {
                        AiTab(
                            uiState = uiState,
                            onGenerateQuiz = goGenerateQuiz,
                            onCreateAnnotation = goCreateAnnotation
                        )
                    }

                    composable("options") {
                        OptionsScreen(
                            onManageAnnotations = { navController.navigate("annotation_manager") }
                        )
                    }

                    composable("annotation_manager") {
                        AnnotationManagerScreen(
                            state = uiState.annotationListState,
                            onLoad = { viewModel.loadAnnotations(contentResolver) },
                            onDelete = { uri -> viewModel.deleteAnnotation(contentResolver, uri) },
                            onDeleteAll = { viewModel.deleteAllAnnotations(contentResolver) },
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable("annotation") {
                        AnnotationResultScreen(
                            annotationState = uiState.annotationState,
                            onBack = { navController.popBackStack() }
                        )
                    }

                    composable("quiz") {
                        val noteTitle = (uiState.noteState as? NoteState.Success)?.title ?: ""
                        QuizScreen(
                            noteTitle = noteTitle,
                            quizState = uiState.quizState,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    private fun hideStatusBar() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
