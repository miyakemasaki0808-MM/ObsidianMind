package com.example.newproject.ui.screen

import com.example.newproject.ui.AppScaffold
import com.example.newproject.ui.component.IconPill
import com.example.newproject.ui.component.NoteContentPanel
import com.example.newproject.ui.markdown.NoteImageLoader
import com.example.newproject.ui.markdown.NoteImageMeasurements
import com.example.newproject.ui.markdown.SkippedImageMeasurement
import com.example.newproject.ui.component.ReadingProgressReporter
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.example.newproject.model.state.NoteState
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.MarginMemo
import com.example.newproject.model.SectionRef
import com.example.newproject.model.state.MarginMemoDraft
import com.example.newproject.domain.markdown.NoteSectionModel
import com.example.newproject.ui.theme.AccentGlass
import com.example.newproject.ui.theme.OnSurface
import com.example.newproject.ui.theme.OnVibrant
import com.example.newproject.ui.theme.notePaperColor
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// 全画面ノート（独立ルート note_fullscreen）
// バー/レールの外側に出すため AppScaffold の非タブルートとして表示する。
// ---------------------------------------------------------------------------

/**
 * 全画面のノート読書画面。
 * - 進入中はシステムバー（ナビ＋ステータス）を隠し、離脱時はナビバーのみ復元する
 *   （ステータスバーはアプリ全体仕様どおり隠したまま）。
 * - 背景はノートページ色で全ブリードし、本文カラムは最大720dpで中央寄せ。
 * - タブ側の [tabListState] から開始位置を継承し、離脱時に書き戻す。
 */
@Composable
internal fun FullscreenNoteScreen(
    uiState: NoteUiState,
    /** タブ側と同じパース結果を受け取る。進入のたびに解析し直さないための共有（→ NoteSectionController）。 */
    sectionModel: NoteSectionModel?,
    imageLoader: NoteImageLoader?,
    /** 画像の寸法を通常表示と全画面で共有する（→ NoteImageMeasurements）。 */
    imageMeasurements: NoteImageMeasurements?,
    tabListState: LazyListState,
    onExit: () -> Unit,
    /** シートを出す。読み込み済みなら読み直さない。 */
    onOpenMarginMemo: () -> Unit,
    onDismissMarginMemo: () -> Unit,
    /** このノートの余白メモの書きかけ。通常画面と同じ一組（→ features/margin_pane.md §6.1）。 */
    memoDraft: MarginMemoDraft,
    onEditMarginMemo: (text: String, bodySection: SectionRef?) -> Unit,
    onSubmitMarginMemo: (bodySection: SectionRef?) -> Unit,
    onDeleteMarginMemo: (MarginMemo) -> Unit,
    /** 入力欄で書いているつもりか。通常画面と共有する（戻った先の面へフォーカスを引き継ぐ）。 */
    memoFocusIntent: Boolean,
    onMemoFocusIntentChange: (Boolean) -> Unit,
    onReadingProgress: (blockIndex: Int, blockFraction: Float, totalBlocks: Int, sectionTitle: String?) -> Unit
) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val activity = context.findActivity()
        val controller = activity?.let {
            WindowCompat.getInsetsController(it.window, it.window.decorView)
        }
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            // ステータスバーは隠したまま、ナビゲーションバーだけ戻す。
            controller?.show(WindowInsetsCompat.Type.navigationBars())
        }
    }

    // 遷移アニメーション中は通常タブと全画面が同時にコンポーズされ、同一 LazyListState を
    // 2つの LazyColumn に装着すると例外になる。全画面は専用stateを持ち、開いた時点で
    // タブ側の位置から開始し、離脱時にタブ側へ書き戻すことでスクロール位置を継承する。
    val listState = rememberLazyListState(
        tabListState.firstVisibleItemIndex,
        tabListState.firstVisibleItemScrollOffset
    )
    // 閉じ始めた。**閉じる遷移の間もこの画面は組まれている**ので、ここからはシートを出さず、フォーカスも取りにいかない。
    // 取りにいくと戻った先の面とフォーカスを奪い合い、外れたことを「書くのをやめた」と読んでしまう。
    var leaving by remember { mutableStateOf(false) }
    val leaveWith: (() -> Unit) -> Unit = { action ->
        leaving = true
        // **書いていなければシートはしまう。** 書いている途中なら、戻った先で出せる面へ引き継ぐ
        // （窓が切り替わったときと同じ規則 → features/margin_pane.md §5.4）。
        if (memoDraft.text.isEmpty() && !memoFocusIntent) onDismissMarginMemo()
        // 閉じる処理(action)は必ず即実行する。以前は suspend の scrollToItem の完了後に
        // action を呼んでいたが（フリング中の書き戻し消失を防ぐ狙い）、Fold開閉による
        // Activity再生成後などに tabListState 側の coroutine が完了せず、✕もバックも
        // 無反応で全画面を解除できなくなった。非suspendの requestScrollToItem で保留位置を
        // 積むだけにし、書き戻しをベストエフォート化して閉じる導線から切り離す
        // （pop でルートが破棄されてもキャンセルの影響を受けない）。
        tabListState.requestScrollToItem(
            listState.firstVisibleItemIndex,
            listState.firstVisibleItemScrollOffset
        )
        action()
    }
    // システムバックでもスクロール位置を書き戻してから閉じる。シートが出ている間はシートの側が先に受ける。
    BackHandler { leaveWith(onExit) }

    // 全画面でも読んだ位置を報告する。全画面は専用の listState を持つため、
    // ここで報告しないと「全画面で読み進めてそのままアプリを離れた」分が記録から漏れる。
    ReadingProgressReporter(sectionModel, listState, imageMeasurements, onReadingProgress)
    // 通常表示で依頼された「飛び越した画像」の測定を、全画面でも引き継いで測る（→ NoteImageMeasurements）。
    SkippedImageMeasurement(sectionModel, imageLoader, imageMeasurements)

    // 面の節は全画面の本文についていく。通常画面と同じ規則で作る。
    val face = rememberMarginFaceInputs(sectionModel, listState, uiState.marginMemoState, imageMeasurements)
    val sheetShown = uiState.isMarginMemoSheetVisible && !leaving
    val dismissSheet: () -> Unit = {
        onMemoFocusIntentChange(false)
        onDismissMarginMemo()
    }

    // 全画面はパネルが画面いっぱいに広がるので、下地も同じ紙の色にする。
    // ここだけ Panel のままだと、縁に現行色の額縁が残る。
    Box(modifier = Modifier.fillMaxSize().background(notePaperColor(uiState.notePaperTone)).imePadding()) {
        // **全画面のシートは書くための面で、要約の行を出さない。** 要約は全画面に入る前のおさらい
        // （→ features/note_fullscreen.md）。見出しの印も出さない — 全画面は本文以外を消す面である。
        @OptIn(ExperimentalMaterial3Api::class)
        MarginMemoSheetHost(
            visible = sheetShown,
            expandRequested = false,
            onDismiss = dismissSheet,
            sheet = {
                MarginMemoSheetContent(
                    state = uiState.marginMemoState,
                    draft = memoDraft,
                    section = face.bodySection,
                    hasHeadings = sectionModel?.hasHeadings ?: false,
                    onJumpToSection = face.jumpToSection,
                    arranged = face.arranged,
                    onEdit = { text -> onEditMarginMemo(text, face.bodySection) },
                    onSubmit = { onSubmitMarginMemo(face.bodySection) },
                    onDelete = onDeleteMarginMemo,
                    asSheet = true,
                    onClose = dismissSheet,
                    focusIntent = memoFocusIntent,
                    onFocusIntentChange = onMemoFocusIntentChange,
                    active = sheetShown,
                    previousReadingAt = face.previousReadingAt
                )
            }
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                NoteContentPanel(
                    uiState = uiState,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = 720.dp)
                        .fillMaxSize()
                        .safeDrawingPadding(),
                    listState = listState,
                    precomputedBlocks = sectionModel?.blocks,
                    imageLoader = imageLoader,
                    imageMeasurements = imageMeasurements
                )
                IconPill(
                    symbol = "✕",
                    contentDescription = "全画面表示を閉じる",
                    modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(8.dp),
                    // 既定の不透明な下地をそのまま使う。半透明にすると、下のノートパネルが
                    // 透けて記号のコントラストが下地の明るさで変わる（白の「✕」で 2.5 前後）。
                ) { leaveWith(onExit) }
                // **全画面に置く入口は書くためのものだけ。** ✎ の規則は通常画面と同じで、出ている面をしまうか、シートを出す。
                FullscreenMemoButton(
                    description = marginToggleDescription(MarginToggle.ShowSheet),
                    onTap = { if (sheetShown) dismissSheet() else onOpenMarginMemo() }
                )
            }
        }
    }
}

/**
 * 全画面の ✎。通常FABの立体グラスは使わず小さなフラット円にする。
 * **指で動かせる** — 本文の右下に固定すると、その位置の文字を隠し続ける。
 */
@Composable
private fun BoxScope.FullscreenMemoButton(
    description: String,
    onTap: () -> Unit
) {
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    val currentOnTap by rememberUpdatedState(onTap)
    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .offset { IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) }
            .safeDrawingPadding()
            .padding(end = 20.dp, bottom = 20.dp)
            .size(48.dp)
            .clip(CircleShape)
            .background(AccentGlass)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    dragOffset += dragAmount
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { currentOnTap() })
            }
            // pointerInput はSemanticsを持たないため、スクリーンリーダー用に明示する。
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick { currentOnTap(); true }
            },
        contentAlignment = Alignment.Center
    ) {
        Text("✎", color = OnVibrant, fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

internal fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
