package com.example.newproject.ui.screen

import com.example.newproject.ui.theme.PanelChip
import com.example.newproject.ui.theme.OnSurfaceFaint
import com.example.newproject.ui.theme.OnSurface
import androidx.compose.foundation.layout.Spacer
import com.example.newproject.ui.theme.Panel
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import com.example.newproject.ui.theme.AccentText
import com.example.newproject.ui.component.GradientHeader
import com.example.newproject.ui.component.IconPill
import com.example.newproject.ui.component.NoteContentPanel
import com.example.newproject.ui.markdown.NoteImageLoader
import com.example.newproject.ui.markdown.NoteImageMeasurements
import com.example.newproject.ui.markdown.SkippedImageMeasurement
import com.example.newproject.ui.component.ReadingProgressReporter
import com.example.newproject.ui.component.ReadingTraceCardPanel
import com.example.newproject.domain.sectionSummaryEntryDescription
import com.example.newproject.domain.sectionSummaryEntrySymbol
import com.example.newproject.domain.sectionSummaryStatus
import com.example.newproject.domain.summaryOf
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.model.state.NoteState
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.MarginMemo
import com.example.newproject.model.SectionRef
import com.example.newproject.model.state.MarginMemoDraft
import com.example.newproject.model.state.MarginMemoState
import com.example.newproject.domain.markdown.NoteSectionModel
import com.example.newproject.domain.reunionSlot
import kotlinx.coroutines.launch
import com.example.newproject.ui.theme.OnButtonPrimary
import com.example.newproject.ui.theme.OnButtonSecondary
import com.example.newproject.ui.theme.ButtonOutlineOnGradient
import com.example.newproject.ui.theme.ButtonPrimary
import com.example.newproject.ui.theme.ButtonSecondary
import com.example.newproject.ui.theme.OnVibrant
import com.example.newproject.ui.theme.OnVibrantMuted
import com.example.newproject.ui.theme.ReadingGradient

// ---------------------------------------------------------------------------
// タブ1: ノート（本文リーダー）
//
// 全画面読書は FullscreenNoteScreen.kt、タブと全画面で共用する部品は
// NoteComponents.kt にある。
// ---------------------------------------------------------------------------

@Composable
internal fun NoteReaderTab(
    uiState: NoteUiState,
    /**
     * 本文のパース結果。Main の外で1回だけ作られ全画面表示と共有する（→ NoteSectionController）。
     * 解析中は null で、その間は本文を描かない（描くと描画側がMainで解析し直してしまう）。
     */
    sectionModel: NoteSectionModel?,
    imageLoader: NoteImageLoader?,
    /** 画像の寸法を通常表示と全画面で共有する（→ NoteImageMeasurements）。 */
    imageMeasurements: NoteImageMeasurements?,
    onSelectVault: () -> Unit,
    onRandomNote: () -> Unit,
    /** 10枚を引いて冊子ルートへ入る。**ここでは記録もAIも始まらない**（→ booklet_mode 判断3）。 */
    onOpenBooklet: () -> Unit,
    /**
     * その節の部分要約を頼む（→ features/margin_pane.md §5.8）。持っていれば作り直さない。
     * 見出しの要約ボタンと面の「この節を要約」が呼ぶ。
     */
    onRequestSectionSummary: (SectionRef) -> Unit,
    onRetrySectionSummary: (SectionRef) -> Unit,
    onCancelSectionSummary: (SectionRef) -> Unit,
    noteListState: LazyListState,
    onEnterFullscreen: () -> Unit,
    /** シートを出す。読み込み済みなら読み直さない（ペインの書きかけをシートへ移すときにも使う）。 */
    onOpenMarginMemo: () -> Unit,
    /** 余白ペインが出ている間、このノートのメモを持たせる。読み込み済みなら何もしない。 */
    onLoadMarginMemoForPane: () -> Unit,
    /** 余白ペインの開閉の設定。端末に残り、再起動をまたいで保つ。 */
    marginPaneOpen: Boolean,
    onSetMarginPaneOpen: (Boolean) -> Unit,
    /** 窓の横幅が Expanded か。折り目の無い広い窓でペインを出すかを決める。 */
    expandedWidth: Boolean,
    /** このノートの余白メモの書きかけ。**ViewModel 側の Compose の状態**を直接渡す（→ features/margin_pane.md §6.1）。 */
    memoDraft: MarginMemoDraft,
    /** 書きかけを変える。今の本文の節を添える（書き始めた瞬間だけ書き込み先になる）。 */
    onEditMarginMemo: (text: String, bodySection: SectionRef?) -> Unit,
    /** 置くボタン。新しく置くか、未確定の送信を確かめるかは書きかけで決まる。 */
    onSubmitMarginMemo: (bodySection: SectionRef?) -> Unit,
    onDeleteMarginMemo: (MarginMemo) -> Unit,
    onDismissMarginMemo: () -> Unit,
    /**
     * 入力欄で書いているつもりか。**画面の作り直しと全画面との往復をまたいで保つ** — 面が組み替わっても、
     * 新しい面の入力欄へフォーカスを戻す（→ features/margin_pane.md §5.4）。全画面と同じ値を共有する。
     */
    memoFocusIntent: Boolean,
    onMemoFocusIntentChange: (Boolean) -> Unit,
    onReadingProgress: (blockIndex: Int, blockFraction: Float, totalBlocks: Int, sectionTitle: String?) -> Unit,
    onDismissReadingTrace: () -> Unit
) {
    val context = LocalContext.current

    LaunchedEffect((uiState.noteState as? NoteState.Error)?.id) {
        if (uiState.noteState is NoteState.Error) {
            Toast.makeText(context, uiState.noteState.message, Toast.LENGTH_SHORT).show()
        }
    }

    val isLoading = uiState.noteState is NoteState.Loading
    val successState = uiState.noteState as? NoteState.Success
    val hasNote = successState != null

    val listState = noteListState
    val coroutineScope = rememberCoroutineScope()
    val face = rememberMarginFaceInputs(sectionModel, listState, uiState.marginMemoState, imageMeasurements)
    val bodySection = face.bodySection
    val arrangedMemos = face.arranged
    val previousReadingAt = face.previousReadingAt
    val jumpToSection = face.jumpToSection

    ReadingProgressReporter(sectionModel, listState, imageMeasurements, onReadingProgress)
    SkippedImageMeasurement(sectionModel, imageLoader, imageMeasurements)

    // ノートを引くたびに本文パネルをふわっと出す（フェード＋0.95→1.0のスケール）。
    // AnimatedContent だと新旧リストが1つの listState を共有してしまうため graphicsLayer で行う。
    val noteAppear = remember { Animatable(1f) }
    LaunchedEffect(successState) {
        if (successState != null) {
            noteAppear.snapTo(0f)
            noteAppear.animateTo(1f, animationSpec = tween(300))
        }
    }

    // 面の節の要約。**見出しの要約ボタンの記号も面の節で決める**（要約は節ごとに持つ）。
    val faceSummary = uiState.sectionChat.summaryOf(bodySection)
    val summaryStatus = sectionSummaryStatus(faceSummary)
    // 要約の行。**本文の節が分かるまでは出さない**（どの節の要約かが決まらない）。
    val summaryRow = bodySection?.let { section ->
        SummaryRowInputs(
            summary = faceSummary,
            onRequest = { onRequestSectionSummary(section) },
            onRetry = { onRetrySectionSummary(section) },
            onCancel = { onCancelSectionSummary(section) }
        )
    }
    // 面を要約の行まで送る依頼。**押すたびに新しい番号を振る** — 前の依頼が残っていても、次の押下で送り直せるように。
    // 面が送り終えたら（止められても）その番号の依頼を消す。
    var summaryReveal by remember { mutableStateOf<Long?>(null) }
    var summaryRevealCount by remember { mutableLongStateOf(0L) }

    // **書きかけは ViewModel 側が持つ**ので、シートとペインのどちらから書いても同じ一組になる。
    // 節は押した・打った時点のものを読む（ラムダの中で読むので、組み立て直しを待たない）。
    val onEditMemo: (String) -> Unit = { text -> onEditMarginMemo(text, bodySection) }
    val onSubmitMemo: () -> Unit = { onSubmitMarginMemo(bodySection) }
    // 利用者が面を閉じたときは、書いているつもりも落とす。次に面を出したときに、頼んでいないキーボードを出さない。
    val dismissMemoSheet: () -> Unit = {
        onMemoFocusIntentChange(false)
        onDismissMarginMemo()
    }
    // 見出しの要約ボタンで、このノートの間だけ出したペイン（→ features/margin_pane.md §5.4）。**設定は変えない。**
    // **出したノートを値として持つ。** 保存した画面は別のノートを開いた状態で復元されることがあり、
    // 保存の鍵では照合されない（→ paneForNoteShown）。Fold の開閉と全画面との往復では同じノートなので残る。
    var paneForNote by rememberSaveable { mutableStateOf<String?>(null) }
    val currentNoteUri = successState?.targetUri
    // ノートを離れたら（読み込み中を含む）持ち主を捨てる。戻ってきても、頼み直すまで出さない。
    LaunchedEffect(currentNoteUri) { if (paneForNote != currentNoteUri) paneForNote = null }
    val paneShown = marginPaneOpen || paneForNoteShown(paneForNote, currentNoteUri)
    val foldInfo = rememberReaderFold()
    // 本文領域の左端（窓の座標）。折り目を本文領域の座標へ直すのに使う。最初の配置までは測れていない。
    var regionStartDp by remember { mutableStateOf<Float?>(null) }
    val density = LocalDensity.current
    val onMemoToggle: (MarginToggle) -> Unit = { toggle ->
        when (toggle) {
            MarginToggle.HideSheet -> dismissMemoSheet()
            MarginToggle.ClosePane -> {
                onMemoFocusIntentChange(false)
                paneForNote = null
                onSetMarginPaneOpen(false)
            }
            MarginToggle.OpenPane -> onSetMarginPaneOpen(true)
            MarginToggle.ShowSheet -> onOpenMarginMemo()
        }
    }

    // 並べ方ごとに置き場所だけを変える。**中身は1か所で組み立てる**（2列と縦積みで食い違わせない）。
    val controls: @Composable ColumnScope.(memoToggle: MarginToggle, onSummaryEntry: () -> Unit) -> Unit = { memoToggle, onSummaryEntry ->
        // 未選択時はVault案内、通常時はコンセプト文を出す。
        GradientHeader(
            title = "Rediscover",
            subtitle = if (!uiState.vaultSelected) "Vaultフォルダが未選択です"
            else "過去のノートから、思考をひとつ。",
            // **✎ を ⛶ の隣へ置く。** 本文のどこを読んでいても同じ位置にあり、
            // 本文スクロール・蒸留のつまみ・全画面の右下の丸と縁を取り合わない
            // （→ features/reflect_margin_memo.md 判断7）。ペインを出せる窓では、✎ がペインの開閉を兼ねる。
            trailing = if (hasNote) {
                {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // **要約の入口は ✎ と分ける。** ✎ は書く入口、💬 は AI の入口。
                        // 状態は記号で静かに示し、浮く通知は作らない（→ features/section_ai_chat.md）。
                        IconPill(
                            symbol = sectionSummaryEntrySymbol(summaryStatus),
                            contentDescription = sectionSummaryEntryDescription(summaryStatus)
                        ) { onSummaryEntry() }
                        IconPill(symbol = "✎", contentDescription = marginToggleDescription(memoToggle)) {
                            onMemoToggle(memoToggle)
                        }
                        // **出ていたシートはしまってから入る。** 全画面は本文だけで始める面である
                        // （→ features/note_fullscreen.md）。
                        IconPill(symbol = "⛶", contentDescription = "全画面表示") {
                            dismissMemoSheet()
                            onEnterFullscreen()
                        }
                    }
                }
            } else null
        )

        // ボタンは画面の操作であってノートの操作ではないので、**状態によらず常にここ**。
        // ノートの有無で位置が動くと、同じボタンを毎回探し直すことになる。
        NoteActionButtons(
            vaultSelected = uiState.vaultSelected,
            isLoading = isLoading,
            onSelectVault = onSelectVault,
            onRandomNote = onRandomNote,
            onOpenBooklet = onOpenBooklet,
            modifier = Modifier.padding(top = 16.dp)
        )

        if (isLoading) {
            Surface(
                color = Panel,
                shape = CircleShape,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp)
            ) {
                CircularProgressIndicator(
                    color = AccentText,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
    // ノートが出ていないときの案内。**内容なりの高さに留める** — 本文パネルと同じく `weight(1f)` で伸ばすと、
    // 中身の無い白が画面の大半を占める。
    val emptyNote: @Composable () -> Unit = {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
            color = Panel,
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "ノート未表示",
                    color = OnSurface,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = when (uiState.noteState) {
                        is NoteState.Empty -> "このVaultにMarkdownノートが見つかりませんでした。"
                        is NoteState.Error -> "Vaultを読み込めませんでした。"
                        else -> "Vaultフォルダを選択して「別のノートをひらく」をタップしてください。"
                    },
                    color = OnSurfaceFaint,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
    // 「前回のあなた」カード。NoteContentPanel の外側に置くので全画面には出ない
    // （NoteContentPanel は全画面と共用。LazyColumn の index もずらさないので
    //  セクション判定とスクロール継承を壊さない）。
    val visibleTraceCard = uiState.readingTraceCard?.takeIf { hasNote && !it.isDismissed }
    val traceCard: @Composable (Modifier, onOpenMemos: () -> Unit) -> Unit = { modifier, onOpenMemos ->
        if (visibleTraceCard != null) {
            ReadingTraceCardPanel(
                card = visibleTraceCard,
                // 枠の中身は純関数で決める（→ features/reunion_card.md 判断6）。
                slot = reunionSlot(visibleTraceCard, uiState.summaryState),
                modifier = modifier,
                onDismiss = onDismissReadingTrace,
                onOpenMemos = onOpenMemos,
                onResume = {
                    // **畳むのは即時、送るのは別に走らせる。** 送りは suspend で完了の保証が無いので、
                    // その後ろに畳む操作を置かない（→ lessons L35）。
                    onDismissReadingTrace()
                    val target = visibleTraceCard.resumeBlockIndex
                    val blocks = sectionModel?.blocks
                    if (target != null && !blocks.isNullOrEmpty()) {
                        val clamped = target.coerceAtMost(blocks.lastIndex)
                        coroutineScope.launch { listState.scrollToItem(clamped) }
                        // **飛び越した画像は描画されないので測られない。** 測らないと、その先の読書の
                        // 報告が止まり続ける。止める検査は緩めず、測り終えれば報告が再開する。
                        // **ここで測らず、ノート単位の入れ物へ依頼する** — この画面のスコープで測ると、
                        // 測定待ちの間に全画面へ移っただけでキャンセルされる。
                        imageMeasurements?.requestSkippedMeasurement(clamped)
                    }
                }
            )
        }
    }
    // 面の中で目的のメモまで送る依頼。面が組み立てられて送り終えたら消す。
    var memoReveal by remember { mutableStateOf<MemoReveal?>(null) }
    val notePanel: @Composable (Modifier, (@Composable (Int) -> Unit)?) -> Unit = { modifier, headingMark ->
        NoteContentPanel(
            uiState = uiState,
            modifier = modifier
                .graphicsLayer {
                    alpha = noteAppear.value
                    val scale = 0.95f + 0.05f * noteAppear.value
                    scaleX = scale
                    scaleY = scale
                },
            listState = listState,
            precomputedBlocks = sectionModel?.blocks,
            imageLoader = imageLoader,
            imageMeasurements = imageMeasurements,
            headingAccessory = headingMark
        )
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(ReadingGradient)
            // **並べ方はキーボードを含めない大きさで決める**（→ readerLayoutFor）。キーボードの分は内側で避ける。
            .windowInsetsPadding(WindowInsets.safeDrawing.exclude(WindowInsets.ime))
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp)
            .onGloballyPositioned { coordinates ->
                regionStartDp = with(density) { coordinates.positionInWindow().x.toDp().value }
            }
    ) {
        val sideColumnWidth = sideColumnWidthDp(maxWidth.value).dp
        val canShowPane = canShowMarginPane(
            widthDp = maxWidth.value,
            heightDp = maxHeight.value,
            expandedWidth = expandedWidth,
            fold = foldInfo.fold,
            regionStartDp = regionStartDp ?: 0f
        )
        val layout = readerLayoutFor(
            widthDp = maxWidth.value,
            heightDp = maxHeight.value,
            // ノートが無いときはペインに置くものが無い。読み込み中は枠を保ち、ノートを引くたびに並びを揺らさない。
            paneOpen = paneShown && (hasNote || isLoading),
            expandedWidth = expandedWidth,
            fold = foldInfo.fold,
            regionStartDp = regionStartDp ?: 0f
        )
        val paneVisible = layout is ReaderLayout.MarginPane
        val memoToggle = marginToggleFor(canShowPane, paneShown, uiState.isMarginMemoSheetVisible)
        // 書いている間は、本文の列の上の操作を隠して本文に高さを回す（→ features/margin_pane.md §5.5）。
        // キーボードが出ると、残る高さを操作と再会カードが使い切って本文が消える。キーボードを閉じるか置けば戻る。
        val hideControls = hidesReaderControls(
            layout = layout,
            sheetVisible = uiState.isMarginMemoSheetVisible,
            writingWithKeyboard = memoFocusIntent && WindowInsets.ime.getBottom(density) > 0
        )
        // 目的のメモまで送る。**出せる面を出す** — ペインが出ていればシートを重ねない（→ features/margin_pane.md §5.4）。
        val revealMemos: (MemoReveal) -> Unit = { reveal ->
            if (!paneVisible) onOpenMarginMemo()
            memoReveal = reveal
        }
        val openMemosFromCard: () -> Unit = { revealMemos(MemoReveal.AllMemos) }
        // 見出しの要約ボタン。**出せる面を出し、要約を始めて、要約の行まで送る**（→ features/margin_pane.md §5.4）。
        // 持っている節なら作り直さず、その要約を見せる。
        val openSummary: () -> Unit = {
            when (summaryEntryFor(canShowPane, paneVisible, uiState.isMarginMemoSheetVisible)) {
                SummaryEntry.Pane -> Unit
                SummaryEntry.PaneForNote -> paneForNote = currentNoteUri
                SummaryEntry.Sheet -> onOpenMarginMemo()
            }
            bodySection?.let(onRequestSectionSummary)
            summaryReveal = ++summaryRevealCount
        }
        // 見出しの脇の印。**件数と飛ぶ先は面と同じ照合から作る**ので、メモを消せば印も同時に変わる。
        // 押すと、面をその節のメモまで送る。**本文は動かさない** — 見出しは押した指の下に見えているうえ、
        // 短い節は上端まで送れないので、面の節が本文についてくることを当てにできない。
        val headingMark: (@Composable (Int) -> Unit)? = arrangedMemos?.let { arranged ->
            { block ->
                val ref = sectionModel?.sectionRefAt(block)
                val count = ref?.let { arranged.countsBySection[it] } ?: 0
                if (ref != null && count > 0) {
                    HeadingMemoMark(count) { revealMemos(MemoReveal.Section(ref)) }
                }
            }
        }

        val windowKnown = foldInfo.isKnown && regionStartDp != null
        // 全画面のシートで書いたまま戻ると、シートが出ている扱いのままペインの窓へ来る。
        // そのままだと ✎ の1回目が見えないシートをしまうだけになるので、ペインへ移したことにする。
        LaunchedEffect(windowKnown, paneVisible, uiState.isMarginMemoSheetVisible) {
            if (dropsHiddenSheet(windowKnown, paneVisible, uiState.isMarginMemoSheetVisible)) onDismissMarginMemo()
        }
        MarginWindowShiftEffect(
            windowKnown = windowKnown,
            canShowPane = canShowPane,
            paneOpen = paneShown,
            sheetVisible = uiState.isMarginMemoSheetVisible,
            // **書きかけがあるか入力中なら**シートへ移す（→ features/margin_pane.md §5.4 の2つ目の表）。
            writing = memoDraft.text.isNotEmpty() || memoFocusIntent,
            onHideSheet = onDismissMarginMemo,
            onShowSheet = onOpenMarginMemo
        )
        if (paneVisible && hasNote) {
            // ペインが出たときと、ノート切替で読み込み前へ戻ったときに頼む。読み込み済みなら何もしない。
            // **鍵に状態そのものを使わない** — 読めなかったときに読み直しが止まらなくなる。
            val memoNotLoaded = uiState.marginMemoState is MarginMemoState.Idle
            LaunchedEffect(successState?.targetUri, memoNotLoaded) { onLoadMarginMemoForPane() }
        }

        Box(modifier = Modifier.fillMaxSize().imePadding()) {
            // スマホのシートは本文と併存させる。**キーボードを避けた領域に置く**ので、半分はこの領域の半分になる。
            @OptIn(ExperimentalMaterial3Api::class)
            MarginMemoSheetHost(
                // **ペインが出ている間はシートを出さない**（ペインとシートを同時に出さない → §5.4）。
                // Fold を開いた直後は窓の切り替わりの判定より先にペインが組まれるので、その間もシートを重ねない。
                visible = uiState.isMarginMemoSheetVisible && !paneVisible,
                // ペインへ送る依頼をシートに残さない。残すと、後でシートを出したときに古い依頼で広がる。
                expandRequested = memoReveal != null && !paneVisible,
                onDismiss = dismissMemoSheet,
                sheet = {
                    // **書いた場所は書き始めた時点の本文の節**（紐づけではなく当時の記録 → reflect_margin_memo 判断2）。
                    MarginMemoSheetContent(
                        state = uiState.marginMemoState,
                        draft = memoDraft,
                        section = bodySection,
                        hasHeadings = sectionModel?.hasHeadings ?: false,
                        onJumpToSection = jumpToSection,
                        arranged = arrangedMemos,
                        reveal = memoReveal,
                        onRevealHandled = { handled -> memoReveal = pendingAfterReveal(memoReveal, handled) },
                        onEdit = onEditMemo,
                        onSubmit = onSubmitMemo,
                        onDelete = onDeleteMarginMemo,
                        asSheet = true,
                        onClose = dismissMemoSheet,
                        focusIntent = memoFocusIntent,
                        onFocusIntentChange = onMemoFocusIntentChange,
                        active = uiState.isMarginMemoSheetVisible && !paneVisible,
                        previousReadingAt = previousReadingAt,
                        summaryRow = summaryRow,
                        revealSummary = summaryReveal.takeIf { !paneVisible },
                        onSummaryRevealHandled = { handled -> summaryReveal = pendingAfterReveal(summaryReveal, handled) }
                    )
                }
            ) {
                when (layout) {
                    ReaderLayout.Stacked -> Column(modifier = Modifier.fillMaxSize()) {
                        if (!hideControls) controls(memoToggle, openSummary)
                        if (!hasNote) {
                            emptyNote()
                            // 余りをカードではなく余白へ逃がす。
                            Spacer(modifier = Modifier.weight(1f))
                        } else {
                            if (!hideControls) traceCard(Modifier.padding(top = 20.dp), openMemosFromCard)
                            notePanel(
                                Modifier
                                    .weight(1f)
                                    .padding(
                                        top = when {
                                            hideControls -> 0.dp
                                            isLoading || visibleTraceCard != null -> 8.dp
                                            else -> 20.dp
                                        }
                                    ),
                                headingMark
                            )
                        }
                    }
                    // 左に操作とカード、右に本文。**左列だけをスクロールさせる** — カードが長くても本文の高さは削らない。
                    ReaderLayout.SideBySide -> Row(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .width(sideColumnWidth)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                        ) {
                            controls(memoToggle, openSummary)
                            traceCard(Modifier.padding(top = 16.dp), openMemosFromCard)
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            if (!hasNote) {
                                emptyNote()
                                Spacer(modifier = Modifier.weight(1f))
                            } else {
                                notePanel(Modifier.weight(1f), headingMark)
                            }
                        }
                    }
                    // 左に操作と本文、右に余白ペイン。**溝に折り目が入る**ので、文字と操作が折り目に重ならない。
                    // 操作は本文の上に残す — 左右2列で操作をわきへ寄せたのは高さが足りなかったからで、ここには当たらない。
                    is ReaderLayout.MarginPane -> Row(modifier = Modifier.fillMaxSize()) {
                        Column(modifier = Modifier.width(layout.bodyWidthDp.dp).fillMaxHeight()) {
                            if (!hideControls) controls(memoToggle, openSummary)
                            if (!hasNote) {
                                emptyNote()
                                Spacer(modifier = Modifier.weight(1f))
                            } else {
                                if (!hideControls) traceCard(Modifier.padding(top = 20.dp), openMemosFromCard)
                                notePanel(
                                    Modifier
                                        .weight(1f)
                                        .padding(
                                            top = when {
                                                hideControls -> 0.dp
                                                isLoading || visibleTraceCard != null -> 8.dp
                                                else -> 20.dp
                                            }
                                        ),
                                    headingMark
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(layout.gutterDp.dp))
                        MarginPanePanel(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            MarginMemoSheetContent(
                                state = uiState.marginMemoState,
                                draft = memoDraft,
                                section = bodySection,
                                hasHeadings = sectionModel?.hasHeadings ?: false,
                                onJumpToSection = jumpToSection,
                                arranged = arrangedMemos,
                                reveal = memoReveal,
                                onRevealHandled = { handled -> memoReveal = pendingAfterReveal(memoReveal, handled) },
                                onEdit = onEditMemo,
                                onSubmit = onSubmitMemo,
                                onDelete = onDeleteMarginMemo,
                                modifier = Modifier.padding(top = 16.dp),
                                focusIntent = memoFocusIntent,
                                onFocusIntentChange = onMemoFocusIntentChange,
                                active = true,
                                previousReadingAt = previousReadingAt,
                                summaryRow = summaryRow,
                                revealSummary = summaryReveal,
                                onSummaryRevealHandled = { handled -> summaryReveal = pendingAfterReveal(summaryReveal, handled) }
                            )
                        }
                    }
                }
            }
        }
    }

}

/**
 * 窓が切り替わったとき、出ている余白をもう一方へ移す（→ [marginWindowShiftFor]）。
 *
 * **前に見た窓の判定は画面の作り直しをまたいで保つ。** 折りたたみの開閉で Activity は作り直されるので、
 * `remember` では前の窓を覚えていられず、移すべき切り替わりを見落とす。
 * **窓の情報が揃う前は判定しない** — 折り目の報告と本文領域の位置が届く前の判定は仮のもので、
 * それを前の窓と比べると、切り替わってもいないのに移してしまう。
 */
@Composable
private fun MarginWindowShiftEffect(
    windowKnown: Boolean,
    canShowPane: Boolean,
    paneOpen: Boolean,
    sheetVisible: Boolean,
    writing: Boolean,
    onHideSheet: () -> Unit,
    onShowSheet: () -> Unit
) {
    var previousCanShowPane by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val currentPaneOpen by rememberUpdatedState(paneOpen)
    val currentSheetVisible by rememberUpdatedState(sheetVisible)
    val currentWriting by rememberUpdatedState(writing)
    val currentOnHideSheet by rememberUpdatedState(onHideSheet)
    val currentOnShowSheet by rememberUpdatedState(onShowSheet)
    LaunchedEffect(windowKnown, canShowPane) {
        if (!windowKnown) return@LaunchedEffect
        val shift = marginWindowShiftFor(
            previousCanShowPane = previousCanShowPane,
            canShowPane = canShowPane,
            paneOpen = currentPaneOpen,
            sheetVisible = currentSheetVisible,
            writing = currentWriting
        )
        previousCanShowPane = canShowPane
        when (shift) {
            MarginWindowShift.SheetToPane -> currentOnHideSheet()
            MarginWindowShift.PaneToSheet -> currentOnShowSheet()
            MarginWindowShift.None -> Unit
        }
    }
}

/**
 * 見出しの脇の印（→ features/margin_pane.md §5.6）。**件数と読み上げ名を持ち、色だけにしない。**
 * 押せる範囲は広く取る — 見出しの脇の小さな印は、狭いと隣の本文を選んでしまう。
 */
@Composable
private fun HeadingMemoMark(count: Int, onClick: () -> Unit) {
    val description = headingMemoMarkDescription(count)
    Box(
        modifier = Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        Surface(color = PanelChip, shape = RoundedCornerShape(10.dp)) {
            Text(
                text = "✎$count",
                color = AccentText,
                fontSize = 12.sp,
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .clearAndSetSemantics {}
            )
        }
    }
}

/** 余白ペインの面。本文パネルと同じ地に載せ、右の列として区切る。 */
@Composable
private fun MarginPanePanel(modifier: Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        color = Panel,
        shape = RoundedCornerShape(8.dp)
    ) {
        content()
    }
}

/**
 * ノート画面の操作ボタン。
 *
 * **Vault未選択のときは「Vaultを選択」が主役になる。** その状態で唯一意味のある操作が
 * これで、「別のノートをひらく」は押しても開くノートが無い（無効にする）。
 * 以前は逆で、目立つピンクが機能しないほうに付いていた。
 *
 * このボタンは常にグラデーション直上に置かれるので、輪郭線を必ず描く。
 * 塗りの色をどう選んでも停止色との3:1は満たせない（→ ButtonOutlineOnGradient）。
 */
@Composable
private fun NoteActionButtons(
    vaultSelected: Boolean,
    isLoading: Boolean,
    onSelectVault: () -> Unit,
    onRandomNote: () -> Unit,
    onOpenBooklet: () -> Unit,
    modifier: Modifier = Modifier
) {
    val outline = BorderStroke(1.dp, ButtonOutlineOnGradient)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Vault切替はオプションへ移動。初回セットアップ時だけここにも出す。
        if (!vaultSelected) {
            Button(
                onClick = onSelectVault,
                modifier = Modifier.weight(1f).height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ButtonPrimary, contentColor = OnButtonPrimary),
                border = outline,
                shape = RoundedCornerShape(24.dp)
            ) { Text("Vaultを選択", color = OnButtonPrimary) }
        }
        Button(
            onClick = onRandomNote,
            enabled = !isLoading && vaultSelected,
            modifier = Modifier.weight(1f).height(48.dp),
            colors = if (vaultSelected) {
                ButtonDefaults.buttonColors(containerColor = ButtonPrimary, contentColor = OnButtonPrimary)
            } else {
                ButtonDefaults.buttonColors(
                    containerColor = ButtonSecondary,
                    contentColor = OnButtonSecondary,
                    // 無効時の既定はテーマ由来のαなので、明示して不透明に保つ。
                    disabledContainerColor = PanelChip,
                    disabledContentColor = OnSurfaceFaint
                )
            },
            border = outline,
            shape = RoundedCornerShape(24.dp)
        ) {
            Text(
                "別のノートをひらく",
                color = if (vaultSelected) OnButtonPrimary else OnSurfaceFaint
            )
        }
        // 副。**幅を weight で分けない** — 3つ並ぶ初回セットアップ時に主が痩せる。
        // 同格（どちらもピンク）に並べないのは、「同色ボタンが並ぶと区別できない」という
        // 実機フィードバックの形そのものになるため（→ features/booklet_mode.md §8 の落とし穴）。
        Button(
            onClick = onOpenBooklet,
            enabled = !isLoading && vaultSelected,
            modifier = Modifier
                .width(64.dp)
                .height(48.dp)
                .semantics { contentDescription = "冊子をひらく" },
            contentPadding = PaddingValues(0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ButtonSecondary,
                contentColor = OnButtonSecondary,
                disabledContainerColor = PanelChip,
                disabledContentColor = OnSurfaceFaint
            ),
            border = outline,
            shape = RoundedCornerShape(24.dp)
        ) {
            Text("📖", fontSize = 18.sp, modifier = Modifier.clearAndSetSemantics {})
        }
    }
}
