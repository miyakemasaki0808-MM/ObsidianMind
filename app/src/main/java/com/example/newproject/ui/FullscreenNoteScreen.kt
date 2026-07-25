package com.example.newproject.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.example.newproject.NoteState
import com.example.newproject.NoteUiState
import com.example.newproject.ui.markdown.buildNoteSectionModel
import com.example.newproject.ui.theme.AccentGlass
import com.example.newproject.ui.theme.OnSurface
import com.example.newproject.ui.theme.OnVibrant
import com.example.newproject.ui.theme.Panel
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

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
    tabListState: LazyListState,
    onExit: () -> Unit,
    onOpenSummary: () -> Unit,
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
    val leaveWith: (() -> Unit) -> Unit = { action ->
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
    // システムバックでもスクロール位置を書き戻してから閉じる。
    BackHandler { leaveWith(onExit) }

    val successState = uiState.noteState as? NoteState.Success
    val sectionModel = remember(successState?.content) {
        successState?.content?.let { buildNoteSectionModel(it) }
    }
    val activeChat = uiState.sectionChat

    // 全画面でも読んだ位置を報告する。全画面は専用の listState を持つため、
    // ここで報告しないと「全画面で読み進めてそのままアプリを離れた」分が記録から漏れる。
    ReadingProgressReporter(sectionModel, listState, onReadingProgress)

    // 要約/回答の状態（通常FABと同じ導出）に、クイズ状態を合成した最小インジケータ用ステータス。
    val combinedStatus = fullscreenAiStatus(activeChat, uiState.quizState)

    Box(modifier = Modifier.fillMaxSize().background(Panel)) {
        NoteContentPanel(
            uiState = uiState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 720.dp)
                .fillMaxSize()
                .safeDrawingPadding(),
            listState = listState,
            precomputedBlocks = sectionModel?.blocks
        )
        IconPill(
            symbol = "✕",
            contentDescription = "全画面表示を閉じる",
            modifier = Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(8.dp),
            // 白いノートパネルの上に重なるため、暗い半透明の下地を敷く。
            containerColor = OnSurface.copy(alpha = 0.45f)
        ) { leaveWith(onExit) }
        // 読書中もAIの状態（要約・クイズ）が分かるよう最小インジケータを残す。
        if (activeChat != null) {
            FullscreenAiFab(status = combinedStatus, onTap = { leaveWith(onOpenSummary) })
        }
    }
}

/**
 * 全画面用の最小AIインジケータ。通常FABの立体グラスは使わず小さなフラット円で、
 * 状態が完了/エラーへ変わったときだけ短くラベルをフラッシュする。タップで要約シートへ。
 */
@Composable
private fun BoxScope.FullscreenAiFab(
    status: VigilithActionStatus,
    onTap: () -> Unit
) {
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    val currentOnTap by rememberUpdatedState(onTap)
    val fabDescription = when (status) {
        VigilithActionStatus.Working -> "AI生成中"
        VigilithActionStatus.Ready -> "AI生成完了。タップで開く"
        VigilithActionStatus.Error -> "AIエラー。タップで確認"
        VigilithActionStatus.Idle -> "AIメニュー。タップで開く"
    }
    var showLabel by remember { mutableStateOf(false) }
    LaunchedEffect(status) {
        showLabel = status == VigilithActionStatus.Ready || status == VigilithActionStatus.Error
        if (showLabel) {
            delay(3000)
            showLabel = false
        }
    }
    Column(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .offset { IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) }
            .safeDrawingPadding()
            .padding(end = 20.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.End
    ) {
        if (showLabel) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(AccentGlass)
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (status == VigilithActionStatus.Error) "! 確認して" else "✓ 完了",
                    color = OnVibrant,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        Box(
            modifier = Modifier
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
                    contentDescription = fabDescription
                    role = Role.Button
                    onClick { currentOnTap(); true }
                },
            contentAlignment = Alignment.Center
        ) {
            when (status) {
                VigilithActionStatus.Working -> CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = OnVibrant,
                    strokeWidth = 2.dp
                )
                VigilithActionStatus.Ready -> Text("✓", color = OnVibrant, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                VigilithActionStatus.Error -> Text("!", color = OnVibrant, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                VigilithActionStatus.Idle -> Text("💬", fontSize = 18.sp)
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
