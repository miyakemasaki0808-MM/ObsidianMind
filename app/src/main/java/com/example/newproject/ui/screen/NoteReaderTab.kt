package com.example.newproject.ui.screen

import com.example.newproject.ui.theme.PanelChip
import com.example.newproject.ui.theme.OnSurfaceFaint
import com.example.newproject.ui.theme.OnSurface
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.geometry.Rect
import androidx.compose.foundation.layout.Spacer
import com.example.newproject.ui.theme.Panel
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import com.example.newproject.ui.theme.AccentText
import com.example.newproject.ui.theme.notePaperColor
import com.example.newproject.ui.component.GradientHeader
import com.example.newproject.ui.component.bleedScreenPadding
import com.example.newproject.ui.component.screenContentPadding
import com.example.newproject.ui.component.screenHorizontalPadding
import com.example.newproject.ui.component.IconPill
import com.example.newproject.ui.component.NoteContentPanel
import com.example.newproject.ui.markdown.QuoteOffers
import com.example.newproject.ui.markdown.QuoteSelection
import com.example.newproject.ui.markdown.HeadingAccessoryHeight
import com.example.newproject.ui.markdown.HeadingAccessoryWidth
import com.example.newproject.ui.markdown.NoteImageLoader
import com.example.newproject.ui.markdown.NoteImageMeasurements
import com.example.newproject.ui.markdown.SkippedImageMeasurement
import com.example.newproject.ui.component.ReadingProgressReporter
import com.example.newproject.ui.component.ReadingTraceCardPanel
import com.example.newproject.domain.isQuotable
import com.example.newproject.domain.sectionSummaryEntryDescription
import com.example.newproject.domain.sectionSummaryEntrySymbol
import com.example.newproject.domain.sectionSummaryStatus
import com.example.newproject.domain.summaryOf
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
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
import androidx.activity.compose.BackHandler
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.model.state.NoteState
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.MarginMemo
import com.example.newproject.model.RelatedNote
import com.example.newproject.model.MemoPassage
import com.example.newproject.model.SectionRef
import com.example.newproject.model.state.MarginMemoDraft
import com.example.newproject.model.state.MarginMemoState
import com.example.newproject.model.state.SideReadingState
import com.example.newproject.model.state.note
import com.example.newproject.domain.NoteLink
import com.example.newproject.model.DocumentRef
import com.example.newproject.domain.NoteLinkResolution
import com.example.newproject.domain.markdown.MarkdownBlock
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
     * 見出しの要約ボタンと面の「この節を要約」が呼ぶ。`quietly` は面を出さずに頼んだか（→ [opensFaceForNotice]）。
     */
    onRequestSectionSummary: (section: SectionRef, quietly: Boolean) -> Unit,
    /** 面を出さずに頼んだ要約の、端末AIが使えない理由を見せた。要求の番号で伝える。 */
    onAcknowledgeSectionSummaryNotice: (requestId: Long) -> Unit,
    onRetrySectionSummary: (SectionRef) -> Unit,
    onCancelSectionSummary: (SectionRef) -> Unit,
    noteListState: LazyListState,
    /** 本文の位置を書く口。Activity で [noteListState] と一緒に1つだけ作って渡す（→ [BodyPosition]）。 */
    bodyPosition: BodyPosition = remember(noteListState) { BodyPosition(noteListState) },
    onEnterFullscreen: () -> Unit,
    /** シートを出す。読み込み済みなら読み直さない（ペインの書きかけをシートへ移すときにも使う）。 */
    onOpenMarginMemo: () -> Unit,
    /** 余白ペインが出ている間、このノートのメモを持たせる。読み込み済みなら何もしない。 */
    onLoadMarginMemoForPane: () -> Unit,
    /**
     * 余白ペインの開閉。**端末に保存しない** — 開くのは ✎ と別のノートへのリンクだけで、出せない窓へ移ったら外殻が閉じる
     * （→ features/margin_pane.md §5.4）。外殻が閉じても並べ読みは終えない。
     */
    marginPaneOpen: Boolean,
    onSetMarginPaneOpen: (Boolean) -> Unit,
    /** 窓の横幅が Expanded か。折り目の無い広い窓でペインを出すかを決める。 */
    expandedWidth: Boolean,
    /**
     * 窓に報告された折り目（→ [rememberReaderFold]）。**ここで読まずに外から受け取る** — 机の画面のテストは、
     * 端末の持ち方によらず自分で決めた折り目で机を組む（→ system/instrumentation_testing.md 判断12）。
     */
    foldInfo: ReaderFoldInfo,
    /**
     * 外殻が観測した窓の値（→ [readerWindowFor]）。**ペインを出せるかはこの値で決め、この画面で測り直した可否を使わない** —
     * 閉じる判定と同じ値を読まないと、片方だけが出せると決めて机が残る（→ features/margin_pane.md §5.4）。
     * 外殻の無い組み立て（画面だけのテスト）と、値が揃う前は、この画面の本文領域で決める。
     */
    readerWindow: ReaderWindow? = null,
    /**
     * 外殻が測ったレールの右端（窓の座標、dp）。机と見開きで重ねて出すレールが帯の中身を隠さないよう、帯の中身をここから始める
     * （→ [deskBarRailInsetDp]）。外殻の無い組み立てと測る前は null。
     */
    railEndDp: Float? = null,
    /** 読む形が見開きか（レールの見開きのタブ → features/note_spread.md §5「見開きのタブ」）。選んでいなければ見開きにしない。 */
    spreadSelected: Boolean = false,
    /** 読む形を替える。見開きの戻る操作でノートのタブへ戻すのに使う。 */
    onSelectSpread: (Boolean) -> Unit = {},
    /** 机の形（余白ペイン・本文の見開き）が出ているかを外殻へ知らせる。出ている間、外殻はタブのレールを畳む（→ `showsRail`）。 */
    onMarginPaneVisibleChange: (Boolean) -> Unit,
    /** 机の形の帯が出ているかを外殻へ知らせる。出ている間、外殻はレールを重ねて出す（→ `overlaysRail`）。 */
    onDeskBarShownChange: (Boolean) -> Unit = {},
    /** 重ねて出したレールでタブが押されるたびに進む番号。進んだら帯をしまう。 */
    deskBarHideRequest: Long = 0L,
    /** このノートの余白メモの書きかけ。**ViewModel 側の Compose の状態**を直接渡す（→ features/margin_pane.md §6.1）。 */
    memoDraft: MarginMemoDraft,
    /** 書きかけを変える。今の本文の節を添える（書き始めた瞬間だけ書き込み先になる）。 */
    onEditMarginMemo: (text: String, bodySection: SectionRef?, seenAtStart: MemoPassage.Seen?) -> Unit,
    /** 本文の選択メニューの「引用して書く」。[model] は選んだ本文の解析結果（→ features/margin_quote.md §5.2）。 */
    onQuoteMarginMemo: (selection: QuoteSelection, bodySection: SectionRef?, model: NoteSectionModel) -> Unit,
    /** 書きかけに付けた引用の欄の操作（✕・付ける場で候補を選ぶ）。 */
    quoteActions: DraftQuoteActions = DraftQuoteActions.None,
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
    onDismissReadingTrace: () -> Unit,
    /** 並べ読みで右に出す本文の解析結果（→ features/margin_pane.md §5.9）。読める前と、並べ読みをしていない間は null。 */
    sideReadingBlocks: List<MarkdownBlock>?,
    /** 関連ノートを右で開く。**今のノートにはしない。** */
    onOpenSideReading: (RelatedNote) -> Unit,
    onCloseSideReading: () -> Unit,
    /** 関連ノートを普通に開く（関連タブと同じ経路）。右で読んでいた位置へ送るのはこの画面が行う。 */
    onOpenNote: (RelatedNote) -> Unit,
    /** 本文のリンクを今の走査結果から引く（→ features/note_links.md）。押したときに同期で呼ぶ。 */
    onResolveNoteLink: (NoteLink) -> NoteLinkResolution,
    /** 右のノートの中のリンクを、右のノートを出発点に引く（→ features/desk_foundation.md §5）。押したときに同期で呼ぶ。 */
    onResolveSideNoteLink: (link: NoteLink, sideNote: DocumentRef) -> NoteLinkResolution
) {
    val context = LocalContext.current
    val showNotice: (String) -> Unit = { text -> Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }

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
    // 見開きが出ているか。並べ方は下の窓の中で決まるので、そこで書く（節へ飛ぶ送り方と、本文の記録の入れ替えに使う）。
    var spreadShown by remember { mutableStateOf(false) }
    val face = rememberMarginFaceInputs(sectionModel, bodyPosition, uiState.marginMemoState, imageMeasurements) { !spreadShown }
    val bodySection = face.bodySection
    val arrangedMemos = face.arranged
    val previousReadingAt = face.previousReadingAt
    val jumpToSection = face.jumpToSection

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
            onRequest = { onRequestSectionSummary(section, false) },
            onRetry = { onRetrySectionSummary(section) },
            onCancel = { onCancelSectionSummary(section) },
            onNoticeShown = onAcknowledgeSectionSummaryNotice
        )
    }
    // 面を要約の行まで送る依頼。**押すたびに新しい番号を振る** — 前の依頼が残っていても、次の押下で送り直せるように。
    // 面が送り終えたら（止められても）その番号の依頼を消す。
    var summaryReveal by remember { mutableStateOf<Long?>(null) }
    var summaryRevealCount by remember { mutableLongStateOf(0L) }

    // **書きかけは ViewModel 側が持つ**ので、シートとペインのどちらから書いても同じ一組になる。
    // 節は押した・打った時点のものを読む（ラムダの中で読むので、組み立て直しを待たない）。
    // 控える位置も渡す（直前にスクロールが止まっていた最上端から。本文の節と同じ基準）。控えるのは書き込み先を決める瞬間だけで、そこは規則が決める（→ features/margin_quote.md §5.4）。
    val seenAtStart = face.seenAtStart
    val onEditMemo: (String) -> Unit = { text -> onEditMarginMemo(text, bodySection, seenAtStart) }
    val onSubmitMemo: () -> Unit = { onSubmitMarginMemo(bodySection) }
    // 利用者が面を閉じたときは、書いているつもりも落とす。次に面を出したときに、頼んでいないキーボードを出さない。
    val dismissMemoSheet: () -> Unit = {
        onMemoFocusIntentChange(false)
        onDismissMarginMemo()
    }
    val currentNoteUri = successState?.targetUri
    val currentOnOpenMarginMemo by rememberUpdatedState(onOpenMarginMemo)
    val currentOnMarginPaneVisibleChange by rememberUpdatedState(onMarginPaneVisibleChange)
    // ペインの「このノートの関連」の並びと開閉。後から届いた候補は下へ足す（→ paneRelatedCandidates）。
    // **画面の保存値に置き、持ち主のノートを値として持つ。** 右で読んでいる間に画面が組み直されても、戻る先の一覧を失わない。
    var relatedList by rememberSaveable(stateSaver = PaneRelatedListSaver) { mutableStateOf<PaneRelatedList?>(null) }
    LaunchedEffect(currentNoteUri, uiState.relatedNotesState) {
        relatedList = paneRelatedListFor(relatedList, currentNoteUri, uiState.relatedNotesState)
    }
    val shownRelated = relatedList?.takeIf { it.owner == currentNoteUri }
    // 並べ読みから戻ったときに、面をこの一覧まで送る依頼。戻るたびに新しい番号を振る。
    var relatedReveal by remember { mutableStateOf<Long?>(null) }
    var relatedRevealCount by remember { mutableLongStateOf(0L) }
    val sideReading = uiState.sideReading
    val reading = sideReading != SideReadingState.Idle
    // リンクから右で開いたノートと、その見出し（→ features/note_links.md）。関連の候補から開いたときは null。
    var sideLinkStart by rememberSaveable { mutableStateOf<SideLinkStart?>(null) }
    var sideLinkCount by rememberSaveable { mutableLongStateOf(0L) }
    // 右で辿り始めた入口が本文のリンクだったか。**最後に辿ったリンクではなく入口で決める** — 余白へ戻るとき、
    // 関連の候補から始めたなら一覧まで送る（→ features/desk_foundation.md §4）。
    var sideFromLink by rememberSaveable { mutableStateOf(false) }
    // 右の本文の位置と、送り終えたリンクの要求。**右の面ではなくここに置く** — 右の面はペインを出せない窓では組まれず、
    // 面に置くと、開き直したときに位置を失い、リンクの見出しへ送り直してしまう（→ features/desk_foundation.md 判断3）。
    // 位置は右のノートごとに先頭から始め、画面の作り直しでは保つ。
    val sideListState = rememberSaveable(sideReading.note?.ref?.value, saver = LazyListState.Saver) { LazyListState() }
    var sideLinkHandled by rememberSaveable { mutableLongStateOf(0L) }
    // 右で開く。**書いているつもりは落とす** — 入力欄は面ごと隠れるので、戻ったときに頼んでいないキーボードを出さない。
    val openSide: (RelatedNote) -> Unit = { note ->
        onMemoFocusIntentChange(false)
        sideLinkStart = null
        onOpenSideReading(note)
    }
    // 「← 余白へ戻る」と戻る操作。**元の場所へ戻す** — 関連の候補から開いたなら、選んだ一覧まで面を送る。
    // 本文のリンクから開いたなら、余白は開く前の位置のままでよい。
    val returnFromSide: () -> Unit = {
        onCloseSideReading()
        sideLinkStart = null
        if (!sideFromLink) relatedReveal = ++relatedRevealCount
    }
    // 余白から右で開く（関連の候補と、反証の一文の出どころ）。
    val openSideFromMargin: (RelatedNote) -> Unit = { note ->
        sideFromLink = false
        openSide(note)
    }
    // 開いたノートの始まりの位置。「このノートへ移る」は右で読んでいたブロック、リンクは見出しから（→ rememberNoteStart）。
    val startNote = rememberNoteStart(bodyPosition, currentNoteUri, sectionModel, imageMeasurements) { heading ->
        showNotice(noteLinkHeadingMissingText(heading, opened = true))
    }
    val moveToNote: (RelatedNote, Int) -> Unit = { note, block ->
        onOpenNote(note)
        startNote(PendingNoteStart(note.ref.value, block))
    }
    // 本文領域の左端（窓の座標）。折り目を本文領域の座標へ直すのに使う。最初の配置までは測れていない。
    var regionStartDp by remember { mutableStateOf<Float?>(null) }
    // 本文領域の上端（窓の座標）。机の画面で、つまみの上の余白まで払い始める場所に含めるのに使う（→ DeskGripReach）。
    var regionTopDp by remember { mutableStateOf<Float?>(null) }
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val deskBackdrop = remember { DeskBackdrop() }
    // 利用者が机をしまう。**並べ読みを終えるのはこの操作だけ** — 外殻が窓のせいでペインを閉じたときは終えず、✎ で開けば同じ配置で戻る
    // （→ features/margin_pane.md §5.4・features/desk_foundation.md §5）。重ねたレールのタブで机をしまうときは外殻が同じことをする。
    val closeDesk: () -> Unit = {
        onMemoFocusIntentChange(false)
        onSetMarginPaneOpen(false)
        if (reading) onCloseSideReading()
    }
    val onMemoToggle: (MarginToggle) -> Unit = { toggle ->
        when (toggle) {
            MarginToggle.HideSheet -> dismissMemoSheet()
            MarginToggle.ClosePane -> closeDesk()
            MarginToggle.OpenPane -> onSetMarginPaneOpen(true)
            MarginToggle.ShowSheet -> onOpenMarginMemo()
        }
    }

    // 並べ方ごとに置き場所だけを変える。**中身は1か所で組み立てる**（2列と縦積みと机の画面の帯で食い違わせない）。
    // afterAction はどのボタンを押した後にも呼ぶ。机の画面の帯は、押したら帯をしまう。
    // place は置き場所。机の画面の帯の中では見出しの霞の下のフェードを描かない（→ DeskBar）。
    // 左右2列の左の列では、霞を右の溝へ広げない（右は画面の端ではなく列の間）。
    val controls: @Composable ColumnScope.(
        memoToggle: MarginToggle,
        onSummaryEntry: () -> Unit,
        place: ControlsPlace,
        afterAction: () -> Unit
    ) -> Unit = { memoToggle, onSummaryEntry, place, afterAction ->
        // 未選択時はVault案内、通常時はコンセプト文を出す。
        GradientHeader(
            fade = place != ControlsPlace.DeskBar,
            bleedEnd = place != ControlsPlace.SideColumn,
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
                        ) {
                            onSummaryEntry()
                            afterAction()
                        }
                        IconPill(symbol = "✎", contentDescription = marginToggleDescription(memoToggle)) {
                            onMemoToggle(memoToggle)
                            afterAction()
                        }
                        // **出ていたシートはしまってから入る。** 全画面は本文だけで始める面である
                        // （→ features/note_fullscreen.md）。
                        IconPill(symbol = "⛶", contentDescription = "全画面表示") {
                            dismissMemoSheet()
                            onEnterFullscreen()
                            afterAction()
                        }
                    }
                }
            } else null
        )

        // ボタンは画面の操作であってノートの操作ではないので、**状態によらず常にここ**。
        // ノートの有無で位置が動くと、同じボタンを毎回探し直すことになる。机の画面では帯に入るが、並びは同じ。
        NoteActionButtons(
            vaultSelected = uiState.vaultSelected,
            isLoading = isLoading,
            onSelectVault = {
                onSelectVault()
                afterAction()
            },
            onRandomNote = {
                onRandomNote()
                afterAction()
            },
            onOpenBooklet = {
                onOpenBooklet()
                afterAction()
            },
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
                        bodyPosition.moveTo(clamped)
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
    // 机の札（→ QuoteChipLayer）。様子は入れ物ごと覚え、長く走る見張りからも同じ入れ物へ書く。面の位置は札を置く・落とす判定にだけ使う。
    val quoteOffers = remember { QuoteOffers() }
    val quoteChip = remember { mutableStateOf<QuoteChip>(QuoteChip.Hidden) }
    var deskMainBounds by remember { mutableStateOf<Rect?>(null) }
    var deskSupportingBounds by remember { mutableStateOf<Rect?>(null) }
    val notePanel: @Composable (Modifier, (@Composable (Int) -> Unit)?, (NoteLink) -> Unit, ((QuoteSelection) -> Unit)?, QuoteOffers?) -> Unit = { modifier, headingMark, onLink, onQuote, quoteOffers ->
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
            headingAccessory = headingMark,
            onLink = onLink,
            onQuote = onQuote,
            quoteOffers = quoteOffers
        )
    }

    // 見開きに入れるかの、本文の側の入力（巨大なブロックが無いか → spreadAllowed）。解析結果から1回だけ数える。
    val spreadBlockChars = remember(sectionModel) { sectionModel?.let { maxBlockChars(it.blocks) } }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            // 机の画面の帯は、この地と同じグラデーションをここに合わせて描く（→ DeskBar）。
            .reportDeskBackdrop(deskBackdrop)
            .background(ReadingGradient)
            // **並べ方はキーボードを含めない大きさで決める**（→ readerLayoutFor）。キーボードの分は内側で避ける。
            .windowInsetsPadding(WindowInsets.safeDrawing.exclude(WindowInsets.ime))
            .screenContentPadding()
            .onGloballyPositioned { coordinates ->
                regionStartDp = with(density) { coordinates.positionInWindow().x.toDp().value }
                regionTopDp = with(density) { coordinates.positionInWindow().y.toDp().value }
            }
    ) {
        val sideColumnWidth = sideColumnWidthDp(maxWidth.value).dp
        val sharedWindow = readerWindow?.takeIf { it.known }
        val canShowPane = sharedWindow?.canShowPane ?: canShowMarginPane(
            widthDp = maxWidth.value,
            heightDp = maxHeight.value,
            expandedWidth = expandedWidth,
            fold = foldInfo.fold,
            regionStartDp = regionStartDp ?: 0f
        )
        val spreadFit = spreadFitFor(spreadSelected, hasNote, spreadBlockChars, density.fontScale)
        val layout = readerLayoutFor(
            widthDp = maxWidth.value,
            heightDp = maxHeight.value,
            // ノートが無いときはペインに置くものが無い。読み込み中は枠を保ち、ノートを引くたびに並びを揺らさない。
            paneOpen = marginPaneOpen && (hasNote || isLoading),
            expandedWidth = expandedWidth,
            fold = foldInfo.fold,
            regionStartDp = regionStartDp ?: 0f,
            spread = spreadFit
        )
        // 見開きを選んでいるのに、本文の側の条件を欠いて縦の流しにしたとき、その理由（→ features/note_spread.md §5「見開きにできないノート」）。
        val spreadRefusal = if (layout == ReaderLayout.Stacked) {
            spreadRefusalFor(maxWidth.value, maxHeight.value, foldInfo.fold, regionStartDp ?: 0f, spreadFit)
        } else {
            null
        }
        val paneVisible = layout is ReaderLayout.MarginPane
        // 本文の見開き（→ features/note_spread.md）。机と同じく、つまみで帯を呼び、外殻はレールを畳む。
        val spreadVisible = layout is ReaderLayout.Spread
        SideEffect { spreadShown = spreadVisible }
        // 見開きの間は本文の一覧が組まれない。縦の流しで走っていた送りは取り消す（遅れて終わって新しい位置を上書きしない）。
        LaunchedEffect(spreadVisible) { if (spreadVisible) bodyPosition.cancelAnimation() }
        // **見開きの間は、縦の流しの本文の記録を組まない。** 一覧が最後に組まれたときの見えている範囲を読み、古い範囲を記録へ混ぜうる。
        // 見開きは自分で記録を送る（→ NoteSpread）。
        if (!spreadVisible) ReadingProgressReporter(sectionModel, listState, imageMeasurements, onReadingProgress)
        // 机の画面の操作の帯と広げ方（→ DeskSpread）。**しまった状態と2面から始める** — ペインを出し直したときも、ノートを替えたときも。
        var deskBarShown by remember(paneVisible || spreadVisible, currentNoteUri) { mutableStateOf(false) }
        // 帯が出ているかを外殻へ知らせる（レールを重ねて出す）。画面が組まれなくなったら、出ていない。
        val currentOnDeskBarShownChange by rememberUpdatedState(onDeskBarShownChange)
        LaunchedEffect(deskBarShown) { currentOnDeskBarShownChange(deskBarShown) }
        DisposableEffect(Unit) { onDispose { currentOnDeskBarShownChange(false) } }
        // 重ねて出したレールでタブが押されたら、帯ごとしまう。
        var seenHideRequest by remember { mutableLongStateOf(deskBarHideRequest) }
        LaunchedEffect(deskBarHideRequest) {
            if (deskBarHideRequest != seenHideRequest) {
                seenHideRequest = deskBarHideRequest
                deskBarShown = false
            }
        }
        // 重ねて出すレールが帯の中身を隠さないよう、帯の中身をレールの右端から始める。
        val railInset = deskBarRailInsetDp(expandedWidth, railEndDp, regionStartDp).dp
        var deskSpread by remember(paneVisible, currentNoteUri) { mutableStateOf(DeskSpread.Both) }
        // ペインの余白の面のスクロール。余白を1画面に広げて戻したときに位置を戻すため外で持つ（→ RestoreSupportingScroll）。
        // ペインを出し直したら先頭から始める（面ごと組み直していたときと同じ）。
        val paneScroll = rememberSaveable(paneVisible, saver = ScrollState.Saver) { ScrollState(0) }
        val paneScrollMemory = remember(paneVisible, currentNoteUri) { SpreadScrollMemory<Int>(DeskSpread.SupportingOnly) }
        // 本文と並べ読みの読む位置も、片側を1画面に広げて戻したときに戻す（→ RestoreLazyFacePosition）。
        // 並べ読みは右のノートごとに位置を持つので、覚えも右のノートごとに替える。
        val mainScrollMemory = remember(paneVisible, currentNoteUri) { SpreadScrollMemory<LazyFacePosition>(DeskSpread.MainOnly) }
        val sideScrollMemory = remember(paneVisible, currentNoteUri, sideListState) {
            SpreadScrollMemory<LazyFacePosition>(DeskSpread.SupportingOnly)
        }
        // 広げ方を替える。**本文だけにするときは書いているつもりを落とす** — 入力欄は組んだまま隠れるので、見えない欄へ打ち込ませない。
        val spreadTo: (DeskSpread) -> Unit = { next ->
            if (!next.shows(DeskFace.Supporting) && memoFocusIntent) {
                onMemoFocusIntentChange(false)
                focusManager.clearFocus()
            }
            deskBarShown = false
            paneScrollMemory.beforeSpread(deskSpread, next, paneScroll.value)
            mainScrollMemory.beforeSpread(deskSpread, next, listState)
            sideScrollMemory.beforeSpread(deskSpread, next, sideListState)
            deskSpread = next
        }
        // 面の中身へ送る依頼の前に、その面が隠れていれば2面へ戻す（→ deskSpreadShowing）。
        val showDeskFace: (DeskFace) -> Unit = { face -> spreadTo(deskSpreadShowing(deskSpread, face)) }
        // 今の本文を見出しへ送る。見出しが見つからなければ動かさずに知らせる。
        val scrollCurrentTo: (String?) -> Unit = { heading ->
            val target = currentNoteScrollTarget(sectionModel?.blocks, heading)
            if (target == null) {
                heading?.let { showNotice(noteLinkHeadingMissingText(it, opened = false)) }
            } else {
                bodyPosition.moveTo(target)
                imageMeasurements?.requestSkippedMeasurement(target)
            }
        }
        // 本文のリンク（→ features/note_links.md）。開く面は窓で決める（→ noteLinkActionFor）。
        val openLink: (NoteLink) -> Unit = { link ->
            when (val action = noteLinkActionFor(onResolveNoteLink(link), canShowSupporting = canShowPane)) {
                is NoteLinkAction.ScrollCurrent -> scrollCurrentTo(action.heading)
                is NoteLinkAction.Open -> if (action.onSupporting) {
                    // ペインを閉じていれば ✎ と同じく開き（端末には残さない）、本文だけにしていれば2面へ戻す。
                    if (!marginPaneOpen) onSetMarginPaneOpen(true)
                    if (paneVisible) showDeskFace(DeskFace.Supporting)
                    sideFromLink = true
                    openSide(action.note)
                    sideLinkStart = SideLinkStart(action.note.ref.value, action.heading, ++sideLinkCount)
                } else {
                    onOpenNote(action.note)
                    startNote(PendingNoteStart(action.note.ref.value, block = 0, heading = action.heading))
                }
                is NoteLinkAction.Notice -> showNotice(action.text)
            }
        }
        // 右のノートの中のリンク（→ features/desk_foundation.md §5）。右のノートを出発点に引き、今のノートにはしない。
        // 辿り始めた入口（sideFromLink）は替えない。
        val openSideLink: (NoteLink) -> Unit = { link ->
            sideReading.note?.let { side ->
                when (val action = sideLinkActionFor(onResolveSideNoteLink(link, side.ref), currentNoteUri?.let(::DocumentRef))) {
                    is SideLinkAction.ScrollSide -> {
                        val target = currentNoteScrollTarget(sideReadingBlocks, action.heading)
                        if (target == null) {
                            action.heading?.let { showNotice(noteLinkHeadingMissingText(it, opened = false)) }
                        } else {
                            coroutineScope.launch { sideListState.scrollToItem(target) }
                        }
                    }
                    is SideLinkAction.ScrollMain -> {
                        showDeskFace(DeskFace.Main)
                        scrollCurrentTo(action.heading)
                    }
                    is SideLinkAction.OpenSide -> {
                        openSide(action.note)
                        sideLinkStart = SideLinkStart(action.note.ref.value, action.heading, ++sideLinkCount)
                    }
                    is SideLinkAction.Notice -> showNotice(action.text)
                }
            }
        }
        // 横の払いで仕切りを1段動かす。**帯が出ている間は受けない** — 帯の操作と、本文に触れて帯をしまう操作が先。
        val onSpreadGesture: (DeskSpreadGesture) -> Unit = { gesture -> spreadTo(deskSpreadAfter(deskSpread, gesture)) }
        val currentOnSpreadGesture by rememberUpdatedState(onSpreadGesture)
        val spreadSwipe = Modifier.pointerInput(deskBarShown) {
            if (!deskBarShown) detectDeskSpread { gesture -> currentOnSpreadGesture(gesture) }
        }
        // ペインが見えているか。本文だけにしている間は、組んだまま隠れている。
        val paneShown = paneVisible && deskSpread.shows(DeskFace.Supporting)
        val memoToggle = marginToggleFor(canShowPane, marginPaneOpen, uiState.isMarginMemoSheetVisible)
        // 書いている間は、本文の列の上の操作を隠して本文に高さを回す（→ features/margin_pane.md §5.5）。
        // キーボードが出ると、残る高さを操作と再会カードが使い切って本文が消える。キーボードを閉じるか置けば戻る。
        val hideControls = hidesReaderControls(
            layout = layout,
            sheetVisible = uiState.isMarginMemoSheetVisible,
            writingWithKeyboard = memoFocusIntent && WindowInsets.ime.getBottom(density) > 0
        )
        // 目的のメモまで送る。**出せる面を出す** — ペインが出ていればシートを重ねない（→ features/margin_pane.md §5.4）。
        val revealMemos: (MemoReveal) -> Unit = { reveal ->
            // 右で眺めている間は、余白へ戻してから送る（メモは余白の面にある）。
            if (reading) onCloseSideReading()
            if (paneVisible) showDeskFace(DeskFace.Supporting) else onOpenMarginMemo()
            memoReveal = reveal
        }
        val openMemosFromCard: () -> Unit = { revealMemos(MemoReveal.AllMemos) }
        // 本文の選択メニューの「引用して書く」（→ features/margin_quote.md §5.1）。**✎ と同じ規則で面を出し、入力欄へフォーカスを送る** —
        // 引用には言葉が要るので、次にすることは書くこと。面が出ていれば面は替えずに引用だけを付ける。空白だけの選択では何も付けない。
        // **並べ読みの間は出さない**（右の面を書きかけへ替える規則は区切り4で入れる）。
        // 机の札（→ features/margin_quote.md §5.1）。**2面が並ぶ机で、帯をしまっているときだけ出す**（片側を1画面・帯・見開き・スマホ・全画面には出さない）。
        // 選択メニューが出ている間の範囲は本文の選択の容器が [quoteOffers] へ書く。窓・机の形が変わったら、運んでいる途中でも消す。
        val chipEnabled = layout is ReaderLayout.MarginPane && deskSpread == DeskSpread.Both && !deskBarShown && !reading && sectionModel != null
        LaunchedEffect(layout, deskSpread, foldInfo.fold, chipEnabled) { quoteChip.value = QuoteChip.Hidden }
        LaunchedEffect(quoteOffers) {
            snapshotFlow { quoteOffers.current }.collect { offer ->
                quoteChip.value = quoteChipAfter(quoteChip.value, if (offer != null) QuoteChipEvent.MenuShown(offer) else QuoteChipEvent.MenuHidden)
            }
        }
        // 本文がスクロールしたら消す。
        LaunchedEffect(listState) {
            snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
                if (scrolling) quoteChip.value = quoteChipAfter(quoteChip.value, QuoteChipEvent.Dismissed)
            }
        }
        val quoteFromBody: ((QuoteSelection) -> Unit)? = sectionModel?.takeIf { !reading }?.let { model ->
            { selection ->
                if (isQuotable(selection.text)) {
                    if (canShowPane) {
                        if (!marginPaneOpen) onSetMarginPaneOpen(true)
                        if (paneVisible) showDeskFace(DeskFace.Supporting)
                    } else if (!uiState.isMarginMemoSheetVisible) {
                        onOpenMarginMemo()
                    }
                    onMemoFocusIntentChange(true)
                    onQuoteMarginMemo(selection, bodySection, model)
                }
            }
        }
        // 見出しの要約ボタン（→ features/margin_pane.md §5.4）。面が出ていれば、要約を始めてその面の要約の行まで送る。
        // **面が出ていなければ、まだ頼んでいない節は始めるだけ**で、頼んだ節はシートで見せに行く（→ summaryEntryFor）。
        // 持っている節なら作り直さず、その要約を見せる。
        // 本文だけにしている机の画面は、面が出ていないときと同じに扱い、見に行くときはシートに代えてペインを戻す。
        // 並べ読みを閉じるのは要約の行を見せる入口だけ（→ closesSideReading）。
        val openSummary: () -> Unit = {
            val entry = summaryEntryFor(paneShown, uiState.isMarginMemoSheetVisible, requested = faceSummary != null)
            if (reading && entry.closesSideReading()) onCloseSideReading()
            if (entry == SummaryEntry.Sheet) {
                if (paneVisible) showDeskFace(DeskFace.Supporting) else onOpenMarginMemo()
            }
            bodySection?.let { onRequestSectionSummary(it, entry == SummaryEntry.Background) }
            if (entry != SummaryEntry.Background) summaryReveal = ++summaryRevealCount
        }
        // 面を出さずに頼んだ要約に、端末AIが使えない理由が届いていて、どの面も出ていなければ開く（→ opensFaceForNotice）。
        // **見るのは今の本文の節の要約だけ。** 見せたことは理由の行が組まれたときに行が伝えるので、ここでは開くだけにする。
        val opensNotice = opensFaceForNotice(
            faceSummary,
            faceVisible = (paneVisible && deskPaneCountsAsFace(deskSpread, reading)) || uiState.isMarginMemoSheetVisible
        )
        val currentPaneVisible by rememberUpdatedState(paneVisible)
        val currentShowDeskFace by rememberUpdatedState(showDeskFace)
        LaunchedEffect(faceSummary?.requestId, opensNotice) {
            if (opensNotice) {
                if (currentPaneVisible) currentShowDeskFace(DeskFace.Supporting) else currentOnOpenMarginMemo()
                summaryReveal = ++summaryRevealCount
            }
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
        // 余白ペインが出ているかを外殻へ知らせる。**窓の情報が揃う前の仮の並べ方は知らせない** — 折り目が届く前に
        // 仮にペインが組まれると、レールが一瞬畳まれてから戻る。
        // **畳んだせいでペインが出せなくなってはいけない**（出せなくなるとレールが戻り、また畳む、を繰り返す）。
        // 折り目で割る窓では、畳んでも本文が左へ広がるだけでペインの幅は変わらない（ReaderLayoutTest）。
        // 折り目がレールの下に来ると割り方が替わるが、レールを出す Expanded の窓では折り目は窓の中ほどにある。
        LaunchedEffect(windowKnown, paneVisible, spreadVisible) {
            if (windowKnown) currentOnMarginPaneVisibleChange(paneVisible || spreadVisible)
        }
        // 見開きのタブでは、戻る操作でノートのタブへ戻る（→ features/note_spread.md §5「見開きのタブ」）。内側（帯・1画面・並べ読み）が先に効くよう、
        // それらより前に置く。見開きから入った机では、内側を閉じた後に机をしまってノートのタブへ戻る（レールの「ノート」と同じ）。
        BackHandler(enabled = spreadSelected) {
            if (paneVisible) closeDesk()
            onSelectSpread(false)
        }
        // 戻る操作は内側から — 右で眺めている間は余白へ戻る（→ features/margin_pane.md §5.4）。
        BackHandler(enabled = reading && paneVisible) { returnFromSide() }
        // 戻る操作は内側から — 帯が出ていればまず帯をしまい、片側を1画面にしていれば次に2面へ戻す
        // （後に置いた BackHandler ほど先に効く）。
        BackHandler(enabled = deskSpread != DeskSpread.Both && paneVisible) { spreadTo(DeskSpread.Both) }
        RestoreSupportingScroll(deskSpread, paneScroll, paneScrollMemory)
        RestoreLazyFacePosition(deskSpread, listState, mainScrollMemory)
        RestoreLazyFacePosition(deskSpread, sideListState, sideScrollMemory)
        BackHandler(enabled = deskBarShown && (paneVisible || spreadVisible)) { deskBarShown = false }
        // 全画面のシートで書いたまま戻ると、シートが出ている扱いのままペインの窓へ来る。
        // そのままだと ✎ の1回目が見えないシートをしまうだけになるので、ペインへ移したことにする。
        LaunchedEffect(windowKnown, paneVisible, uiState.isMarginMemoSheetVisible) {
            if (dropsHiddenSheet(windowKnown, paneVisible, uiState.isMarginMemoSheetVisible)) onDismissMarginMemo()
        }
        MarginWindowShiftEffect(
            // 外殻の値を使うときは、その値も揃ってから判定する。
            windowKnown = windowKnown && readerWindow?.known != false,
            canShowPane = canShowPane,
            paneOpen = marginPaneOpen,
            sheetVisible = uiState.isMarginMemoSheetVisible,
            // **書きかけがあるか入力中なら**シートへ移す（→ features/margin_pane.md §5.4 の2つ目の表）。並べ読み中は入力中だけ。
            writing = writingForWindowShift(hasDraft = memoDraft.text.isNotEmpty(), focusIntent = memoFocusIntent, sideReading = reading),
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
                // **器を画面の端まで広げ、中身は同じ幅だけ戻す。** 器（Material3 のシート）は中身を自分の枠で切るので、
                // 余白の内側に置いたままだと見出しの霞が本文の列の幅で止まり、題名が霞の左端に付く。シートも幅の上限（640dp）までは端まで広がる。
                modifier = Modifier.bleedScreenPadding(),
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
                        onJumpToBlock = face.jumpToBlock,
                        quoteCandidates = face.quoteCandidates,
                        quoteActions = quoteActions,
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
                Box(modifier = Modifier.fillMaxSize().screenHorizontalPadding()) {
                    when (layout) {
                        ReaderLayout.Stacked -> Column(modifier = Modifier.fillMaxSize()) {
                            if (!hideControls) controls(memoToggle, openSummary, ControlsPlace.Top) {}
                            if (spreadRefusal != null) SpreadRefusalLine(spreadRefusal)
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
                                    headingMark,
                                    openLink,
                                    quoteFromBody,
                                    null
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
                                controls(memoToggle, openSummary, ControlsPlace.SideColumn) {}
                                traceCard(Modifier.padding(top = 16.dp), openMemosFromCard)
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                if (!hasNote) {
                                    emptyNote()
                                    Spacer(modifier = Modifier.weight(1f))
                                } else {
                                    notePanel(Modifier.weight(1f), headingMark, openLink, quoteFromBody, null)
                                }
                            }
                        }
                        // 本文の見開き（→ features/note_spread.md）。本文を左右2ページに組み、横に払ってめくる。
                        // 操作は机と同じく、左ページの上のつまみで呼ぶ帯に入れる（→ 同 §8 判断3）。
                        is ReaderLayout.Spread -> Box(modifier = Modifier.fillMaxSize().padding(start = layout.pageStartDp.dp)) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                DeskGripStrip(
                                    loading = isLoading,
                                    onShow = { deskBarShown = true },
                                    spread = null,
                                    onSpread = {},
                                    modifier = Modifier.width(layout.pageWidthDp.dp)
                                )
                                if (successState != null && sectionModel != null) {
                                    NoteSpread(
                                        noteKey = successState.targetUri,
                                        title = successState.title,
                                        sectionModel = sectionModel,
                                        position = bodyPosition,
                                        paperColor = notePaperColor(uiState.notePaperTone),
                                        imageLoader = imageLoader,
                                        imageMeasurements = imageMeasurements,
                                        headingAccessory = headingMark,
                                        onLink = openLink,
                                        onReadingProgress = onReadingProgress,
                                        pageWidth = layout.pageWidthDp.dp,
                                        gutter = layout.gutterDp.dp,
                                        leftPageTop = if (visibleTraceCard != null && !hideControls) {
                                            { traceCard(Modifier, openMemosFromCard) }
                                        } else {
                                            null
                                        },
                                        modifier = Modifier.weight(1f),
                                        onQuote = quoteFromBody
                                    )
                                }
                            }
                            if (deskBarShown) {
                                DeskTouchCatcher(onTouch = { deskBarShown = false }, modifier = Modifier.matchParentSize())
                            }
                            AnimatedVisibility(
                                visible = deskBarShown,
                                modifier = Modifier.clipToBounds(),
                                enter = slideInVertically(
                                    initialOffsetY = { -it },
                                    animationSpec = tween(DESK_MOTION_MILLIS, easing = FastOutSlowInEasing)
                                ),
                                exit = slideOutVertically(
                                    targetOffsetY = { -it },
                                    animationSpec = tween(DESK_BAR_HIDE_MILLIS, easing = FastOutLinearInEasing)
                                )
                            ) {
                                DeskBar(onHide = { deskBarShown = false }, backdrop = deskBackdrop, modifier = Modifier.width(layout.pageWidthDp.dp).padding(start = railInset)) {
                                    controls(memoToggle, openSummary, ControlsPlace.DeskBar) { deskBarShown = false }
                                }
                            }
                        }
                        // 机の画面（→ features/margin_pane.md §5.1）。本文とペインを**同じ大きさ**で並べ、見出しと操作の行は出さない。
                        // 上に同じ高さの余白を両方へ置き（左はつまみ）、操作はつまみで呼ぶ帯に入れる。帯の出し入れで大きさは変えない。
                        // **溝に折り目が入る**ので、文字と操作が折り目に重ならない。横に払うと片側を1画面にする（→ DeskFaces）。
                        // 札の層は面の上に重ねる兄弟 — 札から始まった指は、面の中の横の払いと本文のスクロールに届かない（→ QuoteChipLayer）。
                        is ReaderLayout.MarginPane -> Box(modifier = Modifier.fillMaxSize()) {
                            DeskFaces(
                                spread = deskSpread,
                                mainWidth = layout.bodyWidthDp.dp,
                                gutter = layout.gutterDp.dp,
                                modifier = Modifier.fillMaxSize(),
                                main = {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .then(spreadSwipe)
                                            .onGloballyPositioned { deskMainBounds = it.boundsInRoot() }
                                            // 本文のほかの場所に触れたら札を消す。指は使わずに見るだけ（選択と払いへそのまま渡す）。
                                            .pointerInput(Unit) {
                                                awaitEachGesture {
                                                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                                    quoteChip.value = quoteChipAfter(quoteChip.value, QuoteChipEvent.Dismissed)
                                                }
                                            }
                                    ) {
                                        Column(modifier = Modifier.fillMaxSize()) {
                                            // つまみと帯は2面のときの本文の幅に留める — 本文だけにしても、折り目にかからないように。
                                            DeskGripStrip(
                                                loading = isLoading,
                                                onShow = { deskBarShown = true },
                                                spread = deskSpread,
                                                onSpread = spreadTo,
                                                modifier = Modifier.width(layout.bodyWidthDp.dp)
                                            )
                                            if (!hasNote) {
                                                emptyNote()
                                                Spacer(modifier = Modifier.weight(1f))
                                            } else {
                                                // 再会カードは引いた直後だけ出る。閉じるまで左右の大きさが少しずれるのは受け入れる（一時的なため）。
                                                val cardShown = !hideControls && visibleTraceCard != null
                                                if (cardShown) traceCard(Modifier, openMemosFromCard)
                                                notePanel(Modifier.weight(1f).padding(top = if (cardShown) 8.dp else 0.dp), headingMark, openLink, quoteFromBody, quoteOffers)
                                            }
                                        }
                                        // 本文に触れて帯をしまう受け口は、帯と一緒に動かさない — 帯の中に入れると、滑る距離が帯の高さではなく面の高さになる。
                                        if (deskBarShown) {
                                            DeskTouchCatcher(onTouch = { deskBarShown = false }, modifier = Modifier.matchParentSize())
                                        }
                                        // 帯は面の上の縁からシャッターのように降り、しまうときは巻き上がる。縁で切るので、窓の上の余白へははみ出さない。
                                        AnimatedVisibility(
                                            visible = deskBarShown,
                                            modifier = Modifier.clipToBounds(),
                                            enter = slideInVertically(
                                                initialOffsetY = { -it },
                                                animationSpec = tween(DESK_MOTION_MILLIS, easing = FastOutSlowInEasing)
                                            ),
                                            exit = slideOutVertically(
                                                targetOffsetY = { -it },
                                                animationSpec = tween(DESK_BAR_HIDE_MILLIS, easing = FastOutLinearInEasing)
                                            )
                                        ) {
                                            DeskBar(onHide = { deskBarShown = false }, backdrop = deskBackdrop, modifier = Modifier.width(layout.bodyWidthDp.dp).padding(start = railInset)) {
                                                controls(memoToggle, openSummary, ControlsPlace.DeskBar) { deskBarShown = false }
                                            }
                                        }
                                    }
                                },
                                supporting = {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .then(spreadSwipe)
                                            .onGloballyPositioned { deskSupportingBounds = it.boundsInRoot() }
                                    ) {
                                        Spacer(modifier = Modifier.height(DeskStripHeight))
                                        MarginPanePanel(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                            // 右で眺めている間は、余白の面に代えてそのノートを出す。
                                            if (reading) {
                                                SideReadingPane(
                                                    state = sideReading,
                                                    blocks = sideReadingBlocks,
                                                    imageLoader = imageLoader,
                                                    onBack = returnFromSide,
                                                    onMove = moveToNote,
                                                    onRetry = onOpenSideReading,
                                                    listState = sideListState,
                                                    linkStart = sideLinkStart?.takeIf { it.noteUri == sideReading.note?.ref?.value },
                                                    handledLinkRequest = sideLinkHandled,
                                                    onLinkRequestHandled = { sideLinkHandled = it },
                                                    onHeadingMissing = { heading -> showNotice(noteLinkHeadingMissingText(heading, opened = true)) },
                                                    onLink = openSideLink
                                                )
                                            } else MarginMemoSheetContent(
                                                state = uiState.marginMemoState,
                                                draft = memoDraft,
                                                section = bodySection,
                                                hasHeadings = sectionModel?.hasHeadings ?: false,
                                                // 本文へ飛ぶので、本文が隠れていれば2面へ戻す。
                                                onJumpToSection = { ref ->
                                                    showDeskFace(DeskFace.Main)
                                                    jumpToSection(ref)
                                                },
                                                arranged = arrangedMemos,
                                                onJumpToBlock = { block ->
                                                    showDeskFace(DeskFace.Main)
                                                    face.jumpToBlock(block)
                                                },
                                                quoteCandidates = face.quoteCandidates,
                                                quoteActions = quoteActions,
                                                reveal = memoReveal,
                                                onRevealHandled = { handled -> memoReveal = pendingAfterReveal(memoReveal, handled) },
                                                onEdit = onEditMemo,
                                                onSubmit = onSubmitMemo,
                                                onDelete = onDeleteMarginMemo,
                                                modifier = Modifier.padding(top = 16.dp),
                                                scrollState = paneScroll,
                                                focusIntent = memoFocusIntent,
                                                onFocusIntentChange = onMemoFocusIntentChange,
                                                active = deskSpread.shows(DeskFace.Supporting),
                                                previousReadingAt = previousReadingAt,
                                                summaryRow = summaryRow,
                                                revealSummary = summaryReveal,
                                                onSummaryRevealHandled = { handled -> summaryReveal = pendingAfterReveal(summaryReveal, handled) },
                                                related = PaneRelatedInputs(
                                                    candidates = shownRelated?.candidates,
                                                    expanded = shownRelated?.expanded ?: false,
                                                    onToggle = { shownRelated?.let { relatedList = it.copy(expanded = !it.expanded) } },
                                                    onOpen = openSideFromMargin,
                                                    reveal = relatedReveal,
                                                    onRevealHandled = { handled -> relatedReveal = pendingAfterReveal(relatedReveal, handled) },
                                                    rebuttal = uiState.rebuttalSentence
                                                )
                                            )
                                        }
                                    }
                                }
                            )
                            if (chipEnabled) {
                                QuoteChipLayer(
                                    chip = quoteChip.value,
                                    onEvent = { event -> quoteChip.value = quoteChipAfter(quoteChip.value, event) },
                                    main = deskMainBounds,
                                    supporting = deskSupportingBounds,
                                    onAttach = { selection -> quoteFromBody?.invoke(selection) }
                                )
                            }
                        }
                    }
                }
            }
            // 机の画面で帯がしまわれている間は、つまみの上の余白も払い始める場所にする（→ DeskGripReach）。
            // シートの器の外（ここ）に置く — 器の内側では、はみ出した分が指を受け取らない。
            // 本文が隠れている間は置かない（つまみも本文と一緒に隠れている）。
            if (layout is ReaderLayout.MarginPane && !deskBarShown && deskSpread.shows(DeskFace.Main)) {
                DeskGripReach(
                    reachAbove = (regionTopDp ?: 0f).dp,
                    width = layout.bodyWidthDp.dp,
                    onShow = { deskBarShown = true },
                    onSpread = onSpreadGesture
                )
            }
            if (layout is ReaderLayout.Spread && !deskBarShown) {
                DeskGripReach(
                    reachAbove = (regionTopDp ?: 0f).dp,
                    width = layout.pageWidthDp.dp,
                    onShow = { deskBarShown = true },
                    onSpread = {}
                )
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
    // **出せる窓で最後に見たペインの開閉。** 出せない窓へ移ると外殻がペインを閉じるので、移った時点の開閉ではなく移る前の開閉で決める。
    // 作り直しとほかのタブとの往復をまたいで保つ（ほかのタブにいる間に窓が替わっても、戻ったときに書きかけをシートへ移す）。
    var paneOpenWhenShowable by rememberSaveable { mutableStateOf(false) }
    if (windowKnown && canShowPane && paneOpenWhenShowable != paneOpen) {
        SideEffect { paneOpenWhenShowable = paneOpen }
    }
    val currentPaneOpen by rememberUpdatedState(if (canShowPane) paneOpen else paneOpenWhenShowable)
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
            .sizeIn(minWidth = HeadingAccessoryWidth, minHeight = HeadingAccessoryHeight)
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
            Text("📚", fontSize = 18.sp, modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

/**
 * 見開きを選んでいるのに縦の流しで出すときの1行（→ features/note_spread.md §5「見開きにできないノート」）。
 * タブは選んだままなので、なぜ見開きでないかをここで言う。次のノートが条件を満たせば見開きに戻る。
 */
@Composable
private fun SpreadRefusalLine(refusal: SpreadRefusal) {
    Surface(
        color = PanelChip,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
    ) {
        Text(
            text = spreadRefusalText(refusal),
            color = OnSurface,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

/** ノート画面の見出しと操作の置き場所。縦積みの上・左右2列の左の列・机の画面の帯。 */
private enum class ControlsPlace { Top, SideColumn, DeskBar }
