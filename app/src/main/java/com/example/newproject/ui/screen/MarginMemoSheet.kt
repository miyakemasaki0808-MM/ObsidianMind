package com.example.newproject.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.domain.ArrangedMemos
import com.example.newproject.domain.MemoSectionMatch
import com.example.newproject.domain.MemoSendAction
import com.example.newproject.domain.SOFT_MEMO_CHARS
import com.example.newproject.domain.isMemoOverSoftLimit
import com.example.newproject.domain.sendAction
import com.example.newproject.model.MarginMemo
import com.example.newproject.model.SectionRef
import com.example.newproject.model.state.MarginMemoDraft
import com.example.newproject.model.state.MarginMemoState
import com.example.newproject.model.state.MemoSaveStatus
import com.example.newproject.ui.theme.AccentText
import com.example.newproject.ui.theme.ButtonPrimary
import com.example.newproject.ui.theme.DangerAction
import com.example.newproject.ui.theme.OnButtonPrimary
import com.example.newproject.ui.theme.OnSurface
import com.example.newproject.ui.theme.OnSurfaceFaint
import com.example.newproject.ui.theme.OnSurfaceMuted
import com.example.newproject.ui.theme.Panel
import com.example.newproject.ui.theme.PanelChip
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 余白メモのシートを、本文と**併存させて**出す器（→ features/margin_pane.md §5.5）。[body] は本文側。
 *
 * **暗幕を出さない。** `ModalBottomSheet` は暗幕を透明にしても背後の本文を触らせないので、主画面と併存する
 * `BottomSheetScaffold` を使う。書いていない間は半分の高さで止め、本文をスクロールできるまま残す。
 * 半分は**今の領域の**半分 — キーボードを避けた領域に置くので、キーボードが出ると一緒に低くなる。
 *
 * **ルートを増やさない。** 結果が長くも遅くもないので、専用画面にすると「読む → 書く → 戻る」の往復が重くなる。
 * **置いても閉じない。** 続けて書けることがこの機能の要点。
 *
 * 戻る操作は内側から順に閉じる。キーボード（IME が先に閉じる）、画面いっぱいなら半分へ、半分なら閉じる。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MarginMemoSheetHost(
    visible: Boolean,
    /** 目的のメモまで送る依頼が来ている。メモが半分の外にあることがあるので、出せるなら画面いっぱいにする。 */
    expandRequested: Boolean,
    onDismiss: () -> Unit,
    sheet: @Composable () -> Unit,
    /** シートの状態。テストが広げる途中に割り込むために渡す。 */
    sheetState: SheetState = rememberStandardBottomSheetState(
        initialValue = SheetValue.Hidden,
        skipHiddenState = false
    ),
    body: @Composable () -> Unit
) {
    val scaffoldState = rememberBottomSheetScaffoldState(bottomSheetState = sheetState)
    val scope = rememberCoroutineScope()
    val currentVisible by rememberUpdatedState(visible)
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    // 送る依頼の保留（下）。**閉じたら捨てる** — 次に ✎ で出したとき、古い依頼で画面いっぱいへ広げない。
    var expandPending by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible) {
            sheetState.partialExpand()
        } else {
            expandPending = false
            sheetState.hide()
        }
    }
    // **下へ払って隠したら、閉じたことにする。** 最初の値は見ない — 出す前の Hidden を閉じたと読まない。
    LaunchedEffect(sheetState) {
        snapshotFlow { sheetState.currentValue }
            .drop(1)
            .collect { value -> if (value == SheetValue.Hidden && currentVisible) currentOnDismiss() }
    }
    // **送る依頼は、広げ終えるまで持つ。** 依頼そのものは面が送り終えると消えるので、それに合わせて止めると
    // 広げる途中で止まる。ほかの節を開いて中身が伸び、画面いっぱいの止まりどころが現れるのも待つ。
    // **止まりどころが無いときは頼まない** — 無い止まりどころを頼むと、状態だけが「いっぱい」になってシートは動かない
    // （Material3 1.3.0 の実装）。
    LaunchedEffect(expandRequested) { if (expandRequested) expandPending = true }
    // **見張りは1本で、保留をキーにしない。** キーにすると、閉じた面から印を押したとき（出す依頼と広げる依頼が
    // 同じ再構成で届く）に、保留が立った変化で効果が自分を取り消し、先に始めた partialExpand ごと止まってシートが開かない。
    // **広げるのは子に任せる。** 利用者が払って止めると Material3 は広げている呼び出し元を取り消すので、
    // 見張りが直接呼ぶと見張りごと止まる。子なら止められても見張りは続き、保留は終えたときも止められたときも外れる。
    LaunchedEffect(sheetState) {
        snapshotFlow { expandPending && currentVisible }
            .filter { it }
            .collect {
                withTimeoutOrNull(REVEAL_LAYOUT_TIMEOUT_MILLIS) {
                    snapshotFlow { sheetState.hasExpandedState }.first { it }
                }
                // 待っている間に閉じられたら広げない（閉じると保留は外れている）。
                if (expandPending && currentVisible && sheetState.hasExpandedState) {
                    coroutineScope { launch { sheetState.expand() } }
                }
                expandPending = false
            }
    }
    BackHandler(enabled = visible) {
        scope.launch {
            if (sheetState.currentValue == SheetValue.Expanded) sheetState.partialExpand() else currentOnDismiss()
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // **閉じたシートは描かない。** 閉じたシートは本文の領域のすぐ下に置かれ、面とハンドルは中身と別に常に組まれる
        // （Material3 1.3.0）。描くと上端が下の余白へはみ出し、開いていないのに白い面とハンドルが下端に見える。
        // 出ている間と、出す・しまう動きの間だけ面とハンドルを描く。
        val sheetShown = visible || sheetState.isVisible
        BottomSheetScaffold(
            scaffoldState = scaffoldState,
            sheetPeekHeight = maxHeight / 2,
            sheetContainerColor = if (sheetShown) Panel else Color.Transparent,
            sheetShadowElevation = if (sheetShown) BottomSheetDefaults.Elevation else 0.dp,
            sheetDragHandle = if (sheetShown) {
                { BottomSheetDefaults.DragHandle() }
            } else {
                null
            },
            containerColor = Color.Transparent,
            snackbarHost = {},
            // 隠れている間は中身を組まない — 画面の外に置いたままだと、読み上げが隠れたメモへ移れてしまう。
            sheetContent = { if (sheetShown) sheet() }
        ) { _ ->
            // **本文はシートが覆う分だけ低くして組む**（→ features/margin_pane.md §5.5）。この器は本文を全高で組むので、
            // そのままだと本文の末尾がシートの背後でスクロールの限界に達し、背後のブロックまで「読めた」と報告される。
            // 覆う高さはシートの位置から組むたびに求める — 払う途中も、画面いっぱいも、キーボードが出たときも同じ式で済む。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layout { measurable, constraints ->
                        val covered = sheetCoveredHeight(constraints.maxHeight, sheetOffsetOrNull(sheetState))
                        val placeable = measurable.measure(
                            constraints.copy(minHeight = 0, maxHeight = constraints.maxHeight - covered)
                        )
                        layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
                    }
            ) { body() }
        }
    }
}

/** 開いたほかの節の組が並ぶのを待つ上限。並ばなければメモの並びの始まりへ送り、シートも広げない。 */
private const val REVEAL_LAYOUT_TIMEOUT_MILLIS = 500L

/** シートの上端の位置。最初に組まれる前は無い。 */
@OptIn(ExperimentalMaterial3Api::class)
private fun sheetOffsetOrNull(state: SheetState): Float? = try {
    state.requireOffset()
} catch (e: IllegalStateException) {
    null
}

/**
 * シートの中身。**`ModalBottomSheet` から切り出してあるのは、シートを開かずに
 * 入力の振る舞いを検査するため**（調整シートと同じ切り分け）。
 *
 * **書きかけ [draft] は ViewModel 側が持つ**（→ features/margin_pane.md §6.1）。文字・書き込み先・
 * 未確定の送信を一組で持ち、受理と入力欄を空にする判断も向こうで行う。この部品は描くだけで、
 * 何を送ったかを覚えない — 覚えると、保存中にペインをしまう・シートへ移すと対応が消える。
 *
 * **ボタンの役目は書きかけで決まる**（→ [sendAction]）。未確定の送信と整えた本文が同じなら、
 * 新しく置かずに元の送信を確かめる。
 */
@Composable
internal fun MarginMemoSheetContent(
    state: MarginMemoState,
    draft: MarginMemoDraft,
    /** 今の本文の節（＝この面の節）。解析の前は null。 */
    section: SectionRef?,
    hasHeadings: Boolean,
    /** 本文をその節の始まりへ送る。 */
    onJumpToSection: (SectionRef) -> Unit,
    /** メモを今の見出しと照合して並べたもの（→ [arrangeMemos]）。本文の解析の前は null。 */
    arranged: ArrangedMemos?,
    onEdit: (String) -> Unit,
    onSubmit: () -> Unit,
    onDelete: (MarginMemo) -> Unit,
    modifier: Modifier = Modifier,
    /** 目的のメモまで送る依頼（→ [MemoReveal]）。送り終えたら [onRevealHandled] で消してもらう。 */
    reveal: MemoReveal? = null,
    onRevealHandled: () -> Unit = {},
    /**
     * スマホのシートとして出す。**書いている間は入力を優先して畳む**（→ [compactWhileTyping]）。
     * ペインは本文の横にあり高さが足りるので畳まない。
     */
    asSheet: Boolean = false,
    /** 閉じるボタン。下へ払うほかに閉じる手段を置く（読み上げで払えないため）。null なら出さない。 */
    onClose: (() -> Unit)? = null,
    /**
     * 入力欄で書いているつもりか。**面の外で持つ** — 窓が切り替わって面が組み替わっても、
     * 新しい面の入力欄へフォーカスを戻すため（→ features/margin_pane.md §5.4）。
     */
    focusIntent: Boolean = false,
    onFocusIntentChange: (Boolean) -> Unit = {},
    /**
     * この面が今出ている面か。**フォーカスを取ってよいのは出ている面だけ**（→ features/margin_pane.md §5.4）。
     * Fold を開いた直後は、しまう途中のシートとペインが一瞬だけ両方組まれる。どちらもフォーカスを取りにいくと、
     * 後からしまわれるシートの入力欄がフォーカスを持ち去り、ペインに残らない。
     */
    active: Boolean = true,
    /**
     * この節に前回の読書の跡を出すなら、その訪問の日時（→ `showsPreviousReading`）。出さなければ null。
     * 照合は画面の外で済ませ、ここは行の出し方（→ [previousReadingRowFor]）だけを決める。
     */
    previousReadingAt: Long? = null,
    /** この節の部分要約の行（→ [SummaryRowInputs]）。null なら行を出さない。 */
    summaryRow: SummaryRowInputs? = null,
    /** 要約の行まで送る依頼。AI の入口から来たときに立つ。送り終えたら [onSummaryRevealHandled] で消してもらう。 */
    revealSummary: Boolean = false,
    onSummaryRevealHandled: () -> Unit = {}
) {
    var pendingDelete by remember { mutableStateOf<MarginMemo?>(null) }
    var inputFocused by remember { mutableStateOf(false) }
    // 書き始めたときに前回の跡の行があったか。書いていなければ null（→ [previousReadingRowFor]）。
    var previousRowAtFocus by remember { mutableStateOf<Boolean?>(null) }
    val focusRequester = remember { FocusRequester() }
    // 前の面で書いていたなら、この面の入力欄へフォーカスを戻す。**窓がフォーカスを得てからも頼み直す** —
    // 画面の作り直しの直後は窓がまだフォーカスを持たず、そのときに頼んでも残らないことがある。
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(active, focusIntent, windowFocused) {
        if (active && focusIntent && !inputFocused) focusRequester.requestFocus()
    }
    // **フォーカスが外れたことは、1フレーム後もこの面が出ているときだけ伝える。**
    // 面ごと組み替わって外れたときと、しまう途中の面から外れたときは伝えない — 伝えると、次の面へフォーカスを戻せない。
    var blurPending by remember { mutableStateOf(false) }
    val currentActive by rememberUpdatedState(active)
    LaunchedEffect(blurPending) {
        if (!blurPending) return@LaunchedEffect
        withFrameNanos { }
        blurPending = false
        if (currentActive) onFocusIntentChange(false)
    }
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val compact = compactWhileTyping(asSheet = asSheet, inputFocused = inputFocused, imeVisible = imeVisible)
    // 書いている間は、要約の行を書き始めたときの高さに保つ（→ features/margin_pane.md §5.2）。要約は遅れて届き、
    // 面の節は書いている間も本文についていくので、そのまま描くと入力欄が上下に動く。高さを超える分は行の中でスクロールする。
    // シートは書いている間に畳む側なので保たない。
    var summaryRowHeight by remember { mutableIntStateOf(0) }
    var heldSummaryRowHeight by remember { mutableStateOf<Int?>(null) }
    val holdSummaryRow = !asSheet && inputFocused && imeVisible
    LaunchedEffect(holdSummaryRow) { heldSummaryRowHeight = if (holdSummaryRow) summaryRowHeight else null }
    val previousRow = previousReadingRowFor(previousReadingAt, compact, previousRowAtFocus)
    val focusManager = LocalFocusManager.current
    // ほかの節のメモは畳んでおく。件数だけを見せ、開いたときに節ごとに並べる。
    var othersExpanded by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    // メモの並びの始まり（この面の中の位置）。目的のメモまで送るときの行き先。
    var memosTop by remember { mutableIntStateOf(0) }
    // ほかの節の組の始まり（この面の中の位置）。印から来たときの行き先。
    val groupTops = remember { mutableStateMapOf<SectionRef, Int>() }
    // 要約の行の始まり（この面の中の位置）。AI の入口から来たときの行き先。
    var summaryTop by remember { mutableIntStateOf(0) }
    LaunchedEffect(revealSummary) {
        if (!revealSummary) return@LaunchedEffect
        withFrameNanos { }
        scrollState.animateScrollTo(summaryTop)
        onSummaryRevealHandled()
    }
    LaunchedEffect(reveal) {
        val target = reveal ?: return@LaunchedEffect
        val stop = memoRevealStop(target, section)
        if (stop is MemoRevealStop.OtherGroup) othersExpanded = true
        withFrameNanos { }
        val top = when (stop) {
            MemoRevealStop.CurrentMemos -> memosTop
            is MemoRevealStop.OtherGroup -> stop.section?.let { group ->
                // **開いた組が並ぶのを待つ。** 開いた直後のフレームではまだ位置が無い。来なければ並びの始まりへ。
                withTimeoutOrNull(REVEAL_LAYOUT_TIMEOUT_MILLIS) {
                    snapshotFlow { groupTops[group] }.filterNotNull().first()
                }
            } ?: memosTop
        }
        scrollState.animateScrollTo(top)
        onRevealHandled()
    }

    val ready = state as? MarginMemoState.Ready
    val action = draft.sendAction()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
    ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // **面の節は本文の節に常についていく**（書いている間も動く → features/margin_pane.md §5.3）。
                // **1行に収める。** 書いている間に長い見出しの節へ移って2行になると、入力欄が下がる。
                Text(
                    text = section?.let { sectionLabel(it, hasHeadings) } ?: "このノートのメモ",
                    color = OnSurface,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (onClose != null) {
                    TextButton(onClick = onClose) { Text("閉じる", color = OnSurfaceMuted, fontSize = 13.sp) }
                }
            }
            PreviousReadingLine(previousRow)
            val held = heldSummaryRowHeight
            val rowPlacement = Modifier.onGloballyPositioned { summaryTop = it.positionInParent().y.roundToInt() }
            when {
                compact -> Unit
                held != null -> Box(
                    modifier = rowPlacement
                        .fillMaxWidth()
                        .height(with(density) { held.toDp() })
                        .verticalScroll(rememberScrollState())
                ) {
                    summaryRow?.let { SectionSummaryRow(it.summary, it.onRequest, it.onRetry, it.onCancel) }
                }
                summaryRow != null -> Box(modifier = rowPlacement.onSizeChanged { summaryRowHeight = it.height }) {
                    // 行が消えたら高さは0。書き始めたときに行が無ければ、書き終えるまで足さない。
                    DisposableEffect(Unit) { onDispose { summaryRowHeight = 0 } }
                    SectionSummaryRow(summaryRow.summary, summaryRow.onRequest, summaryRow.onRetry, summaryRow.onCancel)
                }
            }
            // **この1行は高さを変えない。** 書き込み先の知らせが出入りしても、入力欄の画面上の位置を動かさない
            // （→ features/margin_pane.md §5.2）。知らせが無いときは使い方を出す。
            WriteTargetLine(
                notice = writeTargetNotice(draft.target, section),
                hasHeadings = hasHeadings,
                onJumpToSection = onJumpToSection,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = draft.text,
                onValueChange = onEdit,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { focus ->
                        if (focus.isFocused) onFocusIntentChange(true) else if (inputFocused) blurPending = true
                        previousRowAtFocus =
                            if (focus.isFocused) previousRowAtFocus ?: (previousRow != PreviousReadingRow.Hidden) else null
                        inputFocused = focus.isFocused
                    },
                placeholder = { Text("いま思ったこと", color = OnSurfaceFaint) },
                minLines = 2,
                maxLines = 6
            )

            // **合図であって壁ではない。** 超えても切らないし、置けなくもならない。
            if (isMemoOverSoftLimit(draft.text)) {
                Text(
                    text = "少し長めです（${SOFT_MEMO_CHARS}字をめやすに）。このまま置けます。",
                    color = OnSurfaceMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusText(state)
                Spacer(modifier = Modifier.height(0.dp))
                Button(
                    onClick = {
                        onSubmit()
                        // シートでは置いたらキーボードを閉じ、畳んだ要約とメモを戻す（→ features/margin_pane.md §5.5）。
                        // **入力欄は空にしない** — 空にするのは受理が分かったときだけ。
                        if (asSheet) focusManager.clearFocus()
                    },
                    // **受け付けられない状態では押させない。** 押せてしまうと、
                    // Controller が何もしないまま入力だけが宙に浮く。
                    enabled = ready != null &&
                        ready.status != MemoSaveStatus.Saving &&
                        action != MemoSendAction.None,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ButtonPrimary,
                        contentColor = OnButtonPrimary
                    ),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(
                        if (action == MemoSendAction.Verify) "保存を確かめる" else "置く",
                        color = OnButtonPrimary
                    )
                }
            }

            if (compact) {
                // **書いている間は入力を優先する。** 要約とメモは1行に畳み、置くかキーボードを閉じれば戻す。
                Text(
                    text = "${if (summaryRow != null) "要約とメモ" else "メモ"} ${ready?.memos?.size ?: 0}件",
                    color = OnSurfaceFaint,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
                return@Column
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(
                modifier = Modifier.onGloballyPositioned { memosTop = it.positionInParent().y.roundToInt() }
            )
            Spacer(modifier = Modifier.height(12.dp))

            when (state) {
                is MarginMemoState.Loading -> CircularProgressIndicator(
                    modifier = Modifier.height(20.dp),
                    color = AccentText,
                    strokeWidth = 2.dp
                )

                is MarginMemoState.Error -> Text(
                    text = "前のメモを読めませんでした。\n${state.message}",
                    color = OnSurfaceMuted,
                    fontSize = 12.sp
                )

                is MarginMemoState.Ready ->
                    if (arranged == null) {
                        // 本文の解析の前は節が分からない。照合せずに全件を出す。
                        state.memos.forEach { memo ->
                            MemoRow(memo = memo, match = null, onDelete = { pendingDelete = memo })
                        }
                    } else {
                        // **静かにするのは、この節のメモが0件のときだけ**（→ features/margin_pane.md §5.2）。
                        arranged.current.forEach { placed ->
                            MemoRow(memo = placed.memo, match = placed.match, onDelete = { pendingDelete = placed.memo })
                        }
                        OtherSectionMemos(
                            arranged = arranged,
                            hasHeadings = hasHeadings,
                            expanded = othersExpanded,
                            onToggle = {
                                othersExpanded = !othersExpanded
                                // 畳んだ組の位置は古くなるので捨てる（次に開いたときに並び直した位置を待つ）。
                                if (!othersExpanded) groupTops.clear()
                            },
                            onJumpToSection = onJumpToSection,
                            onDelete = { pendingDelete = it },
                            onGroupPlaced = { group, top -> groupTops[group] = top }
                        )
                    }

                MarginMemoState.Idle -> Unit
            }
    }

    // **消すのは不可逆なので確認を挟む。** 置くのは何度でもやり直せるが、消したものは戻らない。
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("このメモを消しますか？") },
            text = { Text("消したメモは戻せません。") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(target)
                    pendingDelete = null
                }) { Text("消す", color = DangerAction) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("やめる") }
            }
        )
    }
}

/**
 * 書き込み先の知らせか、使い方の1行。**1行に収め、高さを変えない。**
 *
 * 書き込み先は書き始めた節で、本文を先へ進めても動かない（→ features/margin_pane.md §5.3）。
 * 「本文を戻す」でその節へ本文を送る。
 */
@Composable
private fun WriteTargetLine(
    notice: SectionRef?,
    hasHeadings: Boolean,
    onJumpToSection: (SectionRef) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth().height(WRITE_TARGET_LINE_HEIGHT),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (notice == null) {
            Text(
                text = "思いついたことを短く。ノート本体は変わりません。",
                color = OnSurfaceFaint,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            Text(
                text = "「${sectionLabel(notice, hasHeadings)}」へ書き込み中",
                color = AccentText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Text(" · ", color = OnSurfaceFaint, fontSize = 12.sp)
            TextButton(
                onClick = { onJumpToSection(notice) },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
            ) { Text("本文を戻す", fontSize = 12.sp, color = AccentText) }
        }
    }
}

/** 書き込み先の1行の高さ。**知らせの有無で変えない**（入力欄を動かさないため）。 */
private val WRITE_TARGET_LINE_HEIGHT = 32.dp

/**
 * 前回の読書の跡（→ features/margin_pane.md §5.7）。**知らせるだけで、押せない。**
 * 自動で出入りする行なので、読み上げの割り込み（ライブ領域）にもしない（→ §9）。
 */
@Composable
private fun PreviousReadingLine(row: PreviousReadingRow) {
    when (row) {
        PreviousReadingRow.Hidden -> Unit
        PreviousReadingRow.Reserved -> Spacer(modifier = Modifier.height(PREVIOUS_READING_LINE_HEIGHT))
        is PreviousReadingRow.Shown -> Box(
            modifier = Modifier.fillMaxWidth().height(PREVIOUS_READING_LINE_HEIGHT),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                text = previousReadingLabel(row.atEpochMillis, System.currentTimeMillis()),
                color = OnSurfaceMuted,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 前回の読書の跡の行の高さ。**取っておくときも同じ高さにする**（入力欄を動かさないため）。 */
private val PREVIOUS_READING_LINE_HEIGHT = 24.dp

/**
 * 保存の進み具合。**[MemoSaveStatus.Held] を「保存済み」と呼ばない** —
 * 預かった時点ではまだファイルに書かれていない。
 */
@Composable
private fun StatusText(state: MarginMemoState) {
    val ready = state as? MarginMemoState.Ready ?: return
    val text = when (ready.status) {
        MemoSaveStatus.None -> if (ready.wasTruncated) "長すぎたので、ここまでを置きました" else ""
        MemoSaveStatus.Saving -> "置いています…"
        MemoSaveStatus.Held -> "このノートを離れるときに保存します"
        MemoSaveStatus.Saved -> if (ready.wasTruncated) "長すぎたので、ここまでを置きました" else "置きました"
        // **入力は消えていない**ことまで言う。消えたと思わせない。
        MemoSaveStatus.Full -> "このノートのメモがいっぱいです。1件消すと置けます"
        MemoSaveStatus.Failed -> "置けませんでした。もう一度お試しください"
        // **未保存と断定しない。** 読めないだけで、置けている場合がある。
        MemoSaveStatus.Unconfirmed -> "保存を確認できません"
    }
    if (text.isEmpty()) return
    Text(
        text = text,
        color = if (ready.status == MemoSaveStatus.Failed) DangerAction else OnSurfaceMuted,
        fontSize = 11.sp,
        modifier = Modifier.padding(end = 12.dp)
    )
}

/**
 * ほかの節のメモ。**畳んで件数だけ**を出し、開くと節ごとに本文の順で並べる（→ features/margin_pane.md §5.2 の8）。
 * 節の名前を押すと本文がその節へ飛ぶ。同名の見出しに共通のメモは候補のどの節にも並ぶので、
 * どの節へ飛ぶかは押した名前で決まる（黙って先頭へ決めない）。見出しが見つからないメモは飛べない。
 */
@Composable
private fun OtherSectionMemos(
    arranged: ArrangedMemos,
    hasHeadings: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onJumpToSection: (SectionRef) -> Unit,
    onDelete: (MarginMemo) -> Unit,
    /** 節の組の見出しが並んだ位置（この面の中）。印から来たときの行き先に使う。 */
    onGroupPlaced: (SectionRef, Int) -> Unit
) {
    // **出すかは、ほかの組があるかで決める。** 件数で決めると、今の節と共通のメモだけを持つ同名の候補が選べなくなる。
    if (arranged.others.isEmpty()) return
    TextButton(
        onClick = onToggle,
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
    ) {
        Text(
            text = "${if (expanded) "▾" else "▸"} ほかの節のメモ ${arranged.otherCount}件",
            color = OnSurfaceMuted,
            fontSize = 13.sp
        )
    }
    if (!expanded) return
    arranged.others.forEach { group ->
        val section = group.section
        if (section == null) {
            Text(
                text = "見出しが見つからないメモ",
                color = OnSurfaceFaint,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
            )
        } else {
            TextButton(
                onClick = { onJumpToSection(section) },
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                modifier = Modifier
                    .semantics { contentDescription = "${sectionLabel(section, hasHeadings)}へ本文を送る" }
                    .onGloballyPositioned { onGroupPlaced(section, it.positionInParent().y.roundToInt()) }
            ) {
                Text(sectionLabel(section, hasHeadings), color = AccentText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        group.memos.forEach { placed ->
            MemoRow(memo = placed.memo, match = placed.match, onDelete = { onDelete(placed.memo) })
        }
    }
}

@Composable
private fun MemoRow(memo: MarginMemo, match: MemoSectionMatch?, onDelete: () -> Unit) {
    Surface(
        color = PanelChip,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(memo.text, color = OnSurface, fontSize = 14.sp, lineHeight = 20.sp)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 書いた場所は**紐づけではなく当時の記録**。見出しが消えても解決し直さない。
                Text(
                    text = memoCaption(memo, match),
                    color = OnSurfaceFaint,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onDelete) { Text("消す", color = OnSurfaceMuted, fontSize = 12.sp) }
            }
        }
    }
}

/**
 * メモの添え書き。節ごとに並べたときは節の名前を繰り返さない。
 * **同名の見出しに共通**なら、どの候補にも並んでいることを添える。見つからないメモは、控えた見出し名を
 * 当時の記録として添える（**紐づけではない**ので、今の見出しへ解決し直さない）。照合の前（[match] が null）も同じ。
 */
internal fun memoCaption(memo: MarginMemo, match: MemoSectionMatch?): String {
    val stamp = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
        .format(Date(memo.writtenAtEpochMillis))
    return when (match) {
        MemoSectionMatch.Opening, is MemoSectionMatch.Unique -> stamp
        is MemoSectionMatch.Shared -> "$stamp ・ 同名の見出しに共通"
        MemoSectionMatch.Missing, null -> memo.sectionTitle?.let { "$stamp ・ $it のあたり" } ?: stamp
    }
}
