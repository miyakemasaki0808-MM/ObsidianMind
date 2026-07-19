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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
            val snackbarHostState = remember { SnackbarHostState() }

            val openQuizResult = {
                snackbarHostState.currentSnackbarData?.dismiss()
                viewModel.markQuizViewed()
                navController.navigate("quiz") { launchSingleTop = true }
            }
            val startQuiz = {
                val noteState = uiState.noteState
                if (noteState is NoteState.Success) {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    viewModel.generateQuiz(noteState.title, noteState.content)
                    // Q&Aも補記と同様、同じノートを読みながら生成を待てるようにする。
                    navController.navigateToTab(AppDestination.Note)
                }
            }
            val openAnnotationResult = {
                snackbarHostState.currentSnackbarData?.dismiss()
                viewModel.markAnnotationViewed()
                navController.navigate("annotation") { launchSingleTop = true }
            }
            val startAnnotation = {
                val noteState = uiState.noteState
                if (noteState is NoteState.Success) {
                    snackbarHostState.currentSnackbarData?.dismiss()
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
                    // 待機画面へ遷移せず、同じノートを読みながら生成を待てるようにする。
                    navController.navigateToTab(AppDestination.Note)
                }
            }

            val quizEventKey = uiState.quizState.toEventKey()
            // 画面回転でActivityが再生成されてもSnackbarを再表示しないよう、
            // 表示済みキーをrememberSaveableで保持する。Idleでリセットし、
            // 同じノートの再生成では再び通知できるようにする。
            var lastShownQuizEvent by rememberSaveable { mutableStateOf<String?>(null) }
            LaunchedEffect(quizEventKey) {
                if (quizEventKey == null) {
                    lastShownQuizEvent = null
                    return@LaunchedEffect
                }
                if (quizEventKey == lastShownQuizEvent) return@LaunchedEffect
                lastShownQuizEvent = quizEventKey
                when (val state = uiState.quizState) {
                    is QuizState.Loading -> snackbarHostState.showSnackbar(
                        message = "Q&Aを作成中…",
                        duration = SnackbarDuration.Short
                    )
                    is QuizState.Success -> if (!state.isViewed) {
                        val result = snackbarHostState.showSnackbar(
                            message = "Q&Aを作成しました",
                            actionLabel = "始める",
                            duration = SnackbarDuration.Long
                        )
                        if (result == SnackbarResult.ActionPerformed) openQuizResult()
                    }
                    is QuizState.Error -> if (!state.isViewed) {
                        val result = snackbarHostState.showSnackbar(
                            message = "Q&Aを作成できませんでした",
                            actionLabel = "詳細",
                            duration = SnackbarDuration.Long
                        )
                        if (result == SnackbarResult.ActionPerformed) openQuizResult()
                    }
                    is QuizState.Idle -> Unit
                }
            }

            val annotationEventKey = uiState.annotationState.toEventKey()
            var lastShownAnnotationEvent by rememberSaveable { mutableStateOf<String?>(null) }
            LaunchedEffect(annotationEventKey) {
                if (annotationEventKey == null) {
                    lastShownAnnotationEvent = null
                    return@LaunchedEffect
                }
                if (annotationEventKey == lastShownAnnotationEvent) return@LaunchedEffect
                lastShownAnnotationEvent = annotationEventKey
                when (val state = uiState.annotationState) {
                    is AnnotationState.Loading -> snackbarHostState.showSnackbar(
                        message = "AI補記メモを作成中…",
                        duration = SnackbarDuration.Short
                    )
                    is AnnotationState.Success -> if (!state.isViewed) {
                        val result = snackbarHostState.showSnackbar(
                            message = "AI補記メモを保存しました",
                            actionLabel = "見る",
                            duration = SnackbarDuration.Long
                        )
                        if (result == SnackbarResult.ActionPerformed) openAnnotationResult()
                    }
                    is AnnotationState.Error -> if (!state.isViewed) {
                        val result = snackbarHostState.showSnackbar(
                            message = "AI補記メモを作成できませんでした",
                            actionLabel = "詳細",
                            duration = SnackbarDuration.Long
                        )
                        if (result == SnackbarResult.ActionPerformed) openAnnotationResult()
                    }
                    is AnnotationState.Idle -> Unit
                }
            }

            AppScaffold(
                windowSizeClass = windowSizeClass,
                navController = navController,
                quizState = uiState.quizState,
                annotationState = uiState.annotationState,
                snackbarHostState = snackbarHostState
            ) { modifier ->
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
                            onGenerateQuiz = startQuiz,
                            onOpenQuiz = openQuizResult,
                            onCreateAnnotation = startAnnotation,
                            onOpenAnnotation = openAnnotationResult
                        )
                    }

                    composable("options") {
                        OptionsScreen(
                            vaultSelected = uiState.vaultSelected,
                            onSelectVault = { openVault.launch(null) },
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
                        val noteTitle = when (val state = uiState.quizState) {
                            is QuizState.Loading -> state.sourceTitle
                            is QuizState.Success -> state.sourceTitle
                            is QuizState.Error -> state.sourceTitle
                            is QuizState.Idle ->
                                (uiState.noteState as? NoteState.Success)?.title.orEmpty()
                        }
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
