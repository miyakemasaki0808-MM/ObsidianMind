package com.example.newproject

import com.example.newproject.controller.ReadingPauseReason
import com.example.newproject.domain.isDuplicateLauncherLaunch
import com.example.newproject.model.state.BookletMode
import com.example.newproject.model.state.BookletState
import com.example.newproject.model.state.NoteState
import com.example.newproject.ui.screen.BookletScreen
import com.example.newproject.ui.screen.openFromBooklet
import com.example.newproject.model.state.SummaryState
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
import androidx.compose.material3.SnackbarHostState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.DisposableEffect
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
import androidx.navigation.compose.rememberNavController
import com.example.newproject.domain.readingTraceBackupFileName
import com.example.newproject.model.state.RelatedNotesState
import com.example.newproject.ui.screen.AiTab
import com.example.newproject.ui.screen.AnnotationManagerScreen
import com.example.newproject.ui.screen.CrystalListScreen
import com.example.newproject.ui.screen.DataManagementScreen
import com.example.newproject.ui.screen.ReadingTraceCleanupScreen
import com.example.newproject.ui.AppDestination
import com.example.newproject.ui.AppScaffold
import com.example.newproject.ui.screen.FullscreenNoteScreen
import com.example.newproject.ui.navigateToTab
import com.example.newproject.ui.screen.NoteReaderTab
import com.example.newproject.ui.screen.OpeningScreen
import com.example.newproject.ui.screen.OptionsScreen
import com.example.newproject.ui.screen.RelatedTab
import com.example.newproject.ui.screen.SearchTab
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

    // 保存先は**都度ユーザーが選ぶ**。初期フォルダを指定しないので、SAF は既定で
    // Vault の外（前回の保存先／ダウンロード）を開く（→ reading_trace_backup 判断2）。
    private val exportReadingTraces = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri ?: return@registerForActivityResult
        viewModel.exportReadingTraces(contentResolver, uri)
    }

    // MIME で絞らない。退避ファイルは拡張子が `.json` でも、プロバイダによっては
    // `application/octet-stream` を名乗る。**絞ると自分が書き出したファイルが
    // 選べない端末が出る**ので、中身の `format` と版で受け付けるかを決める。
    private val importReadingTraces = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@registerForActivityResult
        viewModel.prepareReadingTraceImport(contentResolver, uri)
    }

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        // ランチャーの再タップで既存タスクの上へ積まれた1枚なら、何も組み立てずに畳んで
        // 既存タスクへ委ねる（→ domain/LauncherEntry.kt）。`launchMode` は変えない —
        // `singleTask` はタスク親和性ごと挙動が変わり、この1点に対して代償が大きい。
        //
        // installSplashScreen() より**前**に判定する。すぐ閉じるActivityにスプラッシュを
        // 掛けると、畳むまでの一瞬だけ起動画面が見える。
        val duplicateLaunch = isDuplicateLauncherLaunch(
            isTaskRoot = isTaskRoot,
            action = intent?.action,
            categories = intent?.categories,
            launcherAction = Intent.ACTION_MAIN,
            launcherCategory = Intent.CATEGORY_LAUNCHER
        )
        if (duplicateLaunch) {
            super.onCreate(savedInstanceState)
            finish()
            return
        }
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
                val marginPaneOpen by viewModel.marginPaneOpen.collectAsStateWithLifecycle()
                val navController = rememberNavController()
                val snackbarHostState = remember { SnackbarHostState() }
                // 通常表示と全画面表示でスクロール位置を継承するため、listStateを共通スコープで持つ。
                val noteListState = rememberLazyListState()
                // 画像の寸法も同じスコープで持つ。**位置だけ引き継いで寸法を捨てると、
                // 全画面へ入った瞬間に未計測へ戻り、後続ブロックが可視になって到達率が
                // 水増しされる**（→ NoteImageMeasurements）。sectionModel を鍵にして
                // ノートが変われば捨てる。
                val noteImageMeasurements = remember(sectionModel) { NoteImageMeasurements() }
                // 余白メモの入力欄で書いているつもりか。**通常表示と全画面で共有する** — 全画面のシートで書いたまま戻ると、
                // 戻った先の面へフォーカスを引き継ぐ（→ features/margin_pane.md §5.4）。画面の作り直しもまたいで保つ。
                var memoFocusIntent by rememberSaveable { mutableStateOf(false) }

                AppScaffold(
                    windowSizeClass = windowSizeClass,
                    navController = navController,
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
                                sectionModel = sectionModel,
                                imageLoader = viewModel.imageLoader,
                                imageMeasurements = noteImageMeasurements,
                                onSelectVault = { openVault.launch(null) },
                                onRandomNote = {
                                    if (viewModel.vaultUri != null) viewModel.loadRandomNote(contentResolver)
                                    else openVault.launch(null)
                                },
                                onRetrySectionSummary = { viewModel.retrySectionSummary() },
                                onDismissSectionChat = { viewModel.dismissSectionChatSheet() },
                                onEndSectionChat = { viewModel.endSectionChat() },
                                noteListState = noteListState,
                                onOpenBooklet = {
                                    // 冊子へ入る前に束を作り始める。ここでは記録もAIも動かない。
                                    viewModel.openBooklet(contentResolver)
                                    navController.navigate("booklet") {
                                        // 冊子から開いたノートで押し直した場合に冊子が二重に積まれる
                                        // のを防ぐ。スタックに無ければ何も起きない。
                                        popUpTo("booklet") { inclusive = true }
                                        launchSingleTop = true
                                    }
                                },
                                onEnterFullscreen = {
                                    // 進入前から表示中のSnackbarはHostが全画面でも描画し続けるため消す。
                                    snackbarHostState.currentSnackbarData?.dismiss()
                                    // ⛶連打での多重pushを防ぐ。
                                    navController.navigate("note_fullscreen") { launchSingleTop = true }
                                },
                                onReadingProgress = { blockIndex, blockFraction, totalBlocks, sectionTitle ->
                                    viewModel.reportReadingProgress(blockIndex, blockFraction, totalBlocks, sectionTitle)
                                },
                                onOpenMarginMemo = { viewModel.openMarginMemoSheet() },
                                onLoadMarginMemoForPane = { viewModel.loadMarginMemoForPane() },
                                marginPaneOpen = marginPaneOpen,
                                onSetMarginPaneOpen = { open -> viewModel.setMarginPaneOpen(open) },
                                expandedWidth = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded,
                                memoDraft = viewModel.marginMemoDraft((uiState.noteState as? NoteState.Success)?.targetUri),
                                onEditMarginMemo = { text, section -> viewModel.editMarginMemo(text, section) },
                                onSubmitMarginMemo = { section -> viewModel.submitMarginMemo(section) },
                                onDeleteMarginMemo = { viewModel.deleteMarginMemo(it) },
                                onDismissMarginMemo = { viewModel.dismissMarginMemoSheet() },
                                memoFocusIntent = memoFocusIntent,
                                onMemoFocusIntentChange = { memoFocusIntent = it },
                                onDismissReadingTrace = { viewModel.dismissReadingTraceCard() },
                                onOpenSection = { section -> viewModel.openSection(section) }
                            )
                        }

                        composable("booklet") {
                            // プロセス復元で冊子ルートだけ戻り、束（メモリ上）が消えている場合は
                            // 空の冊子を見せずにノートタブへ返す（→ booklet_mode §10 の境界条件）。
                            val hasBundle = uiState.bookletState !is BookletState.Idle
                            LaunchedEffect(hasBundle) {
                                if (!hasBundle) navController.popBackStack()
                            }
                            DisposableEffect(Unit) {
                                // **冊子を眺めている時間は読書時間に入れない。** ルート遷移では
                                // Activity が onStop しないので、ここで止めないと直前のノートの
                                // 読書時間が冊子の滞在分だけ伸びる（→ booklet_mode 判断3）。
                                viewModel.pauseReadingTrace(ReadingPauseReason.Booklet)
                                // 読込中にバックで戻ってきた場合、その要求をここで捨てる。
                                // 冊子が前面のまま痕跡・履歴・AIが始まるのを防ぐ。
                                viewModel.cancelBookletRead()
                                onDispose { viewModel.resumeReadingTrace(ReadingPauseReason.Booklet) }
                            }
                            BookletScreen(
                                state = uiState.bookletState,
                                // 索引Aは状態から**そのまま**渡す。冊子側で引くのは `ref` だけで、
                                // 走査も保存ストアの読み出しも起こさない（→ 判断17）。
                                noteFields = uiState.noteFields,
                                onPageSettled = { page -> viewModel.onBookletPageSettled(page) },
                                onRead = { entry ->
                                    // 先頭から開くことは openFromBooklet が保証する。
                                    // **navigateToTab を使わない** — popUpTo(startDestination) が
                                    // 冊子ルートごと畳んでしまい、戻れなくなる
                                    // （→ features/booklet_mode.md 判断8）。
                                    openFromBooklet(
                                        noteListState = noteListState,
                                        open = { viewModel.openBookletEntry(contentResolver, entry) },
                                        navigateToNote = { navController.navigate("note") }
                                    )
                                },
                                // **引く束だけを作り直す。** `openBooklet` を使うと種を取り直し、
                                // 冊子の中で読んだノートへ黙ってすり替わる（→ 判断12）。
                                onDrawAgain = { viewModel.drawBookletAgain(contentResolver) },
                                onModeChange = { mode -> viewModel.setBookletMode(mode) },
                                onExit = { navController.popBackStack() }
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
                                onOpenMarginMemo = { viewModel.openMarginMemoSheet() },
                                onDismissMarginMemo = { viewModel.dismissMarginMemoSheet() },
                                memoDraft = viewModel.marginMemoDraft((uiState.noteState as? NoteState.Success)?.targetUri),
                                onEditMarginMemo = { text, section -> viewModel.editMarginMemo(text, section) },
                                onSubmitMarginMemo = { section -> viewModel.submitMarginMemo(section) },
                                onDeleteMarginMemo = { viewModel.deleteMarginMemo(it) },
                                memoFocusIntent = memoFocusIntent,
                                onMemoFocusIntentChange = { memoFocusIntent = it },
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
                                onStartDistill = { viewModel.startDistill() },
                                onDownloadDistillModel = { viewModel.downloadDistillModel() },
                                onToggleDistillCandidate = { id -> viewModel.toggleDistillCandidate(id) },
                                onOpenDistillRangeSheet = { id -> viewModel.openDistillRangeSheet(id) },
                                onCloseDistillRangeSheet = { viewModel.closeDistillRangeSheet() },
                                onSelectDistillRange = { id, preset -> viewModel.applyDistillRange(id, preset) },
                                onDragDistillRangeEdge = { id, edge, offset, from ->
                                    viewModel.dragDistillRangeEdge(id, edge, offset, from)
                                },
                                onNudgeDistillRangeEdge = { id, move -> viewModel.nudgeDistillRangeEdge(id, move) },
                                onResetDistillRange = { id -> viewModel.resetDistillRange(id) },
                                onSaveDistill = { viewModel.saveDistillSelection() },
                                onRetryDistill = { viewModel.retryDistill() },
                                onDismissDistill = { viewModel.dismissDistillResult() },
                                onKeepCurrentRecovery = { viewModel.keepCurrentAfterDistillRecovery() },
                                onRestoreOriginal = { viewModel.restoreDistillOriginal() },
                                onExportOriginal = { exportDistillOriginal.launch("distill_original.md") },
                                onLoadCrystals = { viewModel.loadCrystals() },
                                onOpenCrystalList = { navController.navigate("crystal_list") }
                            )
                        }

                        composable("crystal_list") {
                            val knownNotePaths by viewModel.knownNotePaths.collectAsStateWithLifecycle()
                            CrystalListScreen(
                                state = uiState.crystalLog,
                                knownNotePaths = knownNotePaths,
                                onLoad = { viewModel.openCrystalList(contentResolver) },
                                onOpenSource = { path ->
                                    viewModel.openCrystalSource(contentResolver, path)
                                    navController.navigateToTab(AppDestination.Note)
                                },
                                onBack = { navController.popBackStack() }
                            )
                        }

                        composable("options") {
                            OptionsScreen(
                                vaultSelected = uiState.vaultSelected,
                                darkTheme = darkTheme,
                                notePaperAging = notePaperAging,
                                onSelectVault = { openVault.launch(null) },
                                onManageData = { navController.navigate("data_management") },
                                onToggleDarkTheme = { enabled -> viewModel.setDarkTheme(enabled) },
                                onToggleNotePaperAging = { enabled ->
                                    viewModel.setNotePaperAging(enabled)
                                }
                            )
                        }

                        composable("data_management") {
                            DataManagementScreen(
                                state = uiState.readingTraceBackupState,
                                vaultSelected = uiState.vaultSelected,
                                onExport = {
                                    exportReadingTraces.launch(
                                        readingTraceBackupFileName(System.currentTimeMillis())
                                    )
                                },
                                onImport = { importReadingTraces.launch(arrayOf("*/*")) },
                                onApplyImport = { viewModel.applyReadingTraceImport() },
                                onCancel = { viewModel.cancelReadingTraceBackup() },
                                onDismiss = { viewModel.dismissReadingTraceBackup() },
                                onManageReadingTraces = {
                                    navController.navigate("reading_trace_cleanup")
                                },
                                onManageAnnotations = {
                                    navController.navigate("annotation_manager")
                                },
                                onBack = { navController.popBackStack() }
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
        viewModel.resumeReadingTrace(ReadingPauseReason.AppBackground)
    }

    // ノートを表示したままホームへ戻った読書を取りこぼさないため、背面に回る時点で
    // 読書痕跡を確定させる。セッションは残るので、復帰して読み進めれば同じ訪問が
    // 更新される（背面化のたびに閲覧回数が増えない）。
    // ノート切替時の確定は ViewModel 側（cancelNoteScopedJobs）が担う。
    override fun onStop() {
        super.onStop()
        viewModel.pauseReadingTrace(ReadingPauseReason.AppBackground)
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
