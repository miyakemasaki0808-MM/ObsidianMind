package com.example.newproject

import com.example.newproject.model.state.RemarkState
import com.example.newproject.model.state.SummaryState
import com.example.newproject.model.state.toEventKey
import com.example.newproject.ui.theme.Indigo
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.viewModels
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.example.newproject.ui.markdown.NoteImageMeasurements
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.newproject.model.state.RelatedNotesState
import com.example.newproject.model.state.NoteState
import com.example.newproject.model.state.QuizState
import com.example.newproject.ui.screen.AiTab
import com.example.newproject.ui.screen.AnnotationManagerScreen
import com.example.newproject.ui.screen.ReadingTraceCleanupScreen
import com.example.newproject.ui.AppDestination
import com.example.newproject.ui.AppScaffold
import com.example.newproject.ui.screen.FullscreenNoteScreen
import com.example.newproject.ui.navigateToTab
import com.example.newproject.ui.screen.NoteReaderTab
import com.example.newproject.ui.screen.OpeningScreen
import com.example.newproject.ui.screen.OptionsScreen
import com.example.newproject.ui.screen.QuizScreen
import com.example.newproject.ui.screen.RemarkScreen
import com.example.newproject.ui.screen.RelatedTab
import com.example.newproject.ui.screen.SearchTab
import com.example.newproject.ui.vigilith.rememberVigilithState
import com.example.newproject.ui.theme.AppTheme

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

    private val exportDistillOriginal = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        uri ?: return@registerForActivityResult
        viewModel.exportDistillOriginal(contentResolver, uri)
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideStatusBar()
        setContent {
            // テーマ判定に必要な1値だけをAppThemeの外で購読する。
            val darkTheme by viewModel.darkTheme.collectAsStateWithLifecycle()
            val notePaperAging by viewModel.notePaperAging.collectAsStateWithLifecycle()
            AppTheme(darkTheme = darkTheme, notePaperAging = notePaperAging) {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                // 本文のパース結果は Main の外で1回だけ作られる。noteListState と同じく
                // ここで受けて通常表示と全画面表示へ配り、進入のたびの再解析をなくす。
                val sectionModel by viewModel.sectionModel.collectAsStateWithLifecycle()
                // 新規Activity起動時だけ再生する。回転・Fold開閉・プロセス復元では
                // savedInstanceStateが非nullになるため、OPを再生し直さない。
                var showOpening by remember { mutableStateOf(savedInstanceState == null) }
                if (showOpening) {
                    OpeningScreen(onFinished = { showOpening = false })
                    return@AppTheme
                }

                val windowSizeClass = calculateWindowSizeClass(this)
                val navController = rememberNavController()
                val snackbarHostState = remember { SnackbarHostState() }
                // 通常表示と全画面表示でスクロール位置を継承するため、listStateを共通スコープで持つ。
                val noteListState = rememberLazyListState()
                // 画像の寸法も同じスコープで持つ。**位置だけ引き継いで寸法を捨てると、
                // 全画面へ入った瞬間に未計測へ戻り、後続ブロックが可視になって到達率が
                // 水増しされる**（→ NoteImageMeasurements）。sectionModel を鍵にして
                // ノートが変われば捨てる。
                val noteImageMeasurements = remember(sectionModel) { NoteImageMeasurements() }
                // 全画面ルート表示中はSnackbarを抑制する（状態は全画面のAIインジケータが担う）。
                val currentRoute = navController
                    .currentBackStackEntryAsState().value?.destination?.route
                val isFullscreenRoute = currentRoute == "note_fullscreen"
                val vigilith = rememberVigilithState(
                    uiState = uiState,
                    currentRoute = currentRoute,
                    onOpenSection = { section -> viewModel.openSection(section) },
                    onShowSectionChat = { viewModel.showSectionChat() }
                )

                val openQuizResult = {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    viewModel.markQuizViewed()
                    navController.navigate("quiz") { launchSingleTop = true }
                }
                val openRemark = {
                    snackbarHostState.currentSnackbarData?.dismiss()
                    navController.navigate("remark") { launchSingleTop = true }
                }
                val startRemark = {
                    val noteState = uiState.noteState
                    if (noteState is NoteState.Success) {
                        snackbarHostState.currentSnackbarData?.dismiss()
                        val relatedState = uiState.relatedNotesState as? RelatedNotesState.Success
                        viewModel.createRemark(
                            title = noteState.title,
                            content = noteState.content,
                            relatedNotes = relatedState?.relatedNotes.orEmpty(),
                            aiNotes = relatedState?.aiNotes.orEmpty()
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
                    if (isFullscreenRoute) return@LaunchedEffect
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

                val remarkEventKey = uiState.remarkState.toEventKey()
                var lastShownRemarkEvent by rememberSaveable { mutableStateOf<String?>(null) }
                LaunchedEffect(remarkEventKey) {
                    if (remarkEventKey == null) {
                        lastShownRemarkEvent = null
                        return@LaunchedEffect
                    }
                    if (remarkEventKey == lastShownRemarkEvent) return@LaunchedEffect
                    lastShownRemarkEvent = remarkEventKey
                    if (isFullscreenRoute) return@LaunchedEffect
                    when (uiState.remarkState) {
                        is RemarkState.Loading -> snackbarHostState.showSnackbar(
                            message = "ひとことを考えています…",
                            duration = SnackbarDuration.Short
                        )
                        is RemarkState.Ready -> {
                            val result = snackbarHostState.showSnackbar(
                                message = "ひとことが届きました",
                                actionLabel = "見る",
                                duration = SnackbarDuration.Long
                            )
                            if (result == SnackbarResult.ActionPerformed) openRemark()
                        }
                        // 空振りは失敗ではないので、行き先を示さず短く伝えるだけ。
                        // 再試行しても同じなので「見る」も出さない。
                        is RemarkState.Empty -> snackbarHostState.showSnackbar(
                            message = "今は新しい問いは見つかりませんでした",
                            duration = SnackbarDuration.Short
                        )
                        // 書式失敗は空振りと分ける。もう一度きけば変わりうる。
                        is RemarkState.Unusable -> {
                            val result = snackbarHostState.showSnackbar(
                                message = "うまく言葉にできませんでした",
                                actionLabel = "もう一度",
                                duration = SnackbarDuration.Long
                            )
                            if (result == SnackbarResult.ActionPerformed) startRemark()
                        }
                        is RemarkState.Error -> {
                            val result = snackbarHostState.showSnackbar(
                                message = "ひとことをもらえませんでした",
                                actionLabel = "詳細",
                                duration = SnackbarDuration.Long
                            )
                            if (result == SnackbarResult.ActionPerformed) openRemark()
                        }
                        is RemarkState.Idle -> Unit
                    }
                }

                AppScaffold(
                    windowSizeClass = windowSizeClass,
                    navController = navController,
                    remarkState = uiState.remarkState,
                    snackbarHostState = snackbarHostState,
                    vigilithPresentation = vigilith.presentation,
                    vigilithNoteAction = vigilith.noteAction,
                    onVigilithTap = vigilith.onTap
                ) { modifier ->
                    NavHost(
                        navController = navController,
                        startDestination = "note",
                        modifier = modifier
                    ) {
                        composable("note") {
                            NoteReaderTab(
                                uiState = uiState,
                                sectionModel = sectionModel,
                                imageLoader = viewModel.imageLoader,
                                imageMeasurements = noteImageMeasurements,
                                onSelectVault = { openVault.launch(null) },
                                onRandomNote = {
                                    if (viewModel.vaultUri != null) viewModel.loadRandomNote(contentResolver)
                                    else openVault.launch(null)
                                },
                                onSuggestionTap = { text -> viewModel.sendSectionMessage(text) },
                                onDismissSectionChat = { viewModel.dismissSectionChatSheet() },
                                onEndSectionChat = { viewModel.endSectionChat() },
                                onGenerateQuiz = { sourceLabel, context ->
                                    snackbarHostState.currentSnackbarData?.dismiss()
                                    viewModel.generateQuiz(sourceLabel, context)
                                },
                                onOpenQuizResult = openQuizResult,
                                noteListState = noteListState,
                                onEnterFullscreen = {
                                    // 進入前から表示中のSnackbarはHostが全画面でも描画し続けるため消す。
                                    snackbarHostState.currentSnackbarData?.dismiss()
                                    // ⛶連打での多重pushを防ぐ。
                                    navController.navigate("note_fullscreen") { launchSingleTop = true }
                                },
                                onReadingProgress = { blockIndex, blockFraction, totalBlocks, sectionTitle ->
                                    viewModel.reportReadingProgress(blockIndex, blockFraction, totalBlocks, sectionTitle)
                                },
                                onDismissReadingTrace = { viewModel.dismissReadingTraceCard() },
                                onOpenReflection = openRemark,
                                onVigilithActionChanged = vigilith.onNoteActionChanged
                            )
                        }

                        composable("note_fullscreen") {
                            FullscreenNoteScreen(
                                uiState = uiState,
                                sectionModel = sectionModel,
                                imageLoader = viewModel.imageLoader,
                                imageMeasurements = noteImageMeasurements,
                                tabListState = noteListState,
                                onExit = { navController.popBackStack() },
                                // 要約シートは通常表示（noteルート）で描画されるため、
                                // 全画面を閉じてからシートを開く。
                                onOpenSummary = {
                                    navController.popBackStack()
                                    viewModel.showSectionChat()
                                },
                                onReadingProgress = { blockIndex, blockFraction, totalBlocks, sectionTitle ->
                                    viewModel.reportReadingProgress(blockIndex, blockFraction, totalBlocks, sectionTitle)
                                }
                            )
                        }

                        composable("search") {
                            LaunchedEffect(uiState.vaultSelected) {
                                if (uiState.vaultSelected) viewModel.loadFolders()
                            }
                            SearchTab(
                                uiState = uiState,
                                onSelectFolder = { folder -> viewModel.selectSearchFolder(folder) },
                                onSearch = { q -> viewModel.searchByKeyword(q) },
                                onRandom = { viewModel.pickRandomInScope() },
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
                                onOpenRemark = openRemark,
                                onStartDistill = { viewModel.startDistill() },
                                onDownloadDistillModel = { viewModel.downloadDistillModel() },
                                onToggleDistillCandidate = { id -> viewModel.toggleDistillCandidate(id) },
                                onSaveDistill = { viewModel.saveDistillSelection() },
                                onRetryDistill = { viewModel.retryDistill() },
                                onDismissDistill = { viewModel.dismissDistillResult() },
                                onKeepCurrentRecovery = { viewModel.keepCurrentAfterDistillRecovery() },
                                onRestoreOriginal = { viewModel.restoreDistillOriginal() },
                                onExportOriginal = { exportDistillOriginal.launch("distill_original.md") }
                            )
                        }

                        composable("options") {
                            OptionsScreen(
                                vaultSelected = uiState.vaultSelected,
                                darkTheme = darkTheme,
                                notePaperAging = notePaperAging,
                                onSelectVault = { openVault.launch(null) },
                                onManageAnnotations = { navController.navigate("annotation_manager") },
                                onManageReadingTraces = { navController.navigate("reading_trace_cleanup") },
                                onToggleDarkTheme = { enabled -> viewModel.setDarkTheme(enabled) },
                                onToggleNotePaperAging = { enabled ->
                                    viewModel.setNotePaperAging(enabled)
                                }
                            )
                        }

                        composable("annotation_manager") {
                            AnnotationManagerScreen(
                                state = uiState.annotationListState,
                                onLoad = { viewModel.loadAnnotations() },
                                onDelete = { ref -> viewModel.deleteAnnotation(ref) },
                                onDeleteAll = { viewModel.deleteAllAnnotations() },
                                onBack = { navController.popBackStack() }
                            )
                        }

                        composable("reading_trace_cleanup") {
                            ReadingTraceCleanupScreen(
                                state = uiState.readingTraceCleanupState,
                                onLoad = { viewModel.assessReadingTraceOrphans() },
                                onDelete = { key -> viewModel.deleteReadingTrace(key) },
                                onBack = { navController.popBackStack() }
                            )
                        }

                        composable("remark") {
                            // 画面を開いたときにだけ保存済みの組を読む。
                            // ノート表示の経路には置かない（開くたびのSAF読みを増やさない）。
                            val noteTitle = (uiState.noteState as? NoteState.Success)?.title.orEmpty()
                            LaunchedEffect(noteTitle) {
                                viewModel.restoreSavedRemark(noteTitle)
                            }
                            RemarkScreen(
                                state = uiState.remarkState,
                                onRegenerate = startRemark,
                                onSaveReply = { viewModel.saveRemarkReply(it) },
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
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideStatusBar()
    }

    // 背面から戻ったら読書時間の計測を再開する。背面にいた時間を積算しないことで、
    // 「少し読んで放置し、戻ってすぐ離れた」が訪問条件（10秒）を満たさないようにする。
    override fun onStart() {
        super.onStart()
        viewModel.resumeReadingTrace()
    }

    // ノートを表示したままホームへ戻った読書を取りこぼさないため、背面に回る時点で
    // 読書痕跡を確定させる。セッションは残るので、復帰して読み進めれば同じ訪問が
    // 更新される（背面化のたびに閲覧回数が増えない）。
    // ノート切替時の確定は ViewModel 側（cancelNoteScopedJobs）が担う。
    override fun onStop() {
        super.onStop()
        viewModel.pauseReadingTrace()
    }

    private fun hideStatusBar() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            // 引数なしの enableEdgeToEdge() は、システムバーのアイコン明暗を **OSの uiMode**
            // から決める。アプリのテーマはOS設定と独立しているため、「OSライト＋アプリダーク」
            // では暗い背景に暗いナビゲーションアイコンが描かれて見えなくなる。
            //
            // ここで明示的に「常に明るいアイコン（＝暗い背景を前提）」へ固定する。
            // ライトでもダークでもナビゲーションバーの下地は暗い側だからで
            // （ライト＝Indigo `#4D3DFF`、ダーク＝`#232640`、いずれも白アイコンで十分な差がある）、
            // テーマ切替のたびに切り替える必要はない。下地の明度を変えるときは
            // この前提も見直すこと。
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }
}
