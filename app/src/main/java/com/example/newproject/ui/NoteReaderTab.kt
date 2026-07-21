package com.example.newproject.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.example.newproject.NoteState
import com.example.newproject.NoteUiState
import com.example.newproject.QuizState
import com.example.newproject.SectionChatState
import com.example.newproject.ui.markdown.MarkdownBlock
import com.example.newproject.ui.markdown.MarkdownNoteContent
import com.example.newproject.ui.markdown.NoteSection
import com.example.newproject.ui.markdown.buildNoteSectionModel
import com.example.newproject.ui.theme.Aqua
import com.example.newproject.ui.theme.ButtonPrimary
import com.example.newproject.ui.theme.ButtonSecondary
import com.example.newproject.ui.theme.Coral
import com.example.newproject.ui.theme.Indigo
import com.example.newproject.ui.theme.OnSurface
import com.example.newproject.ui.theme.OnVibrant
import com.example.newproject.ui.theme.OnVibrantMuted
import com.example.newproject.ui.theme.Panel
import com.example.newproject.ui.theme.ReadingGradient
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// タブ1: ノート（本文リーダー）
// ---------------------------------------------------------------------------

@Composable
fun NoteReaderTab(
    uiState: NoteUiState,
    onSelectVault: () -> Unit,
    onRandomNote: () -> Unit,
    onOpenSection: (NoteSection) -> Unit,
    onShowSectionChat: () -> Unit,
    onSuggestionTap: (String) -> Unit,
    onDismissSectionChat: () -> Unit,
    onEndSectionChat: () -> Unit,
    onGenerateQuiz: (sourceLabel: String, context: String) -> Unit,
    onOpenQuizResult: () -> Unit,
    noteListState: LazyListState,
    onEnterFullscreen: () -> Unit
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
    val sectionModel = remember(successState?.content) {
        successState?.content?.let { buildNoteSectionModel(it) }
    }
    val currentSection by remember(sectionModel) {
        derivedStateOf { sectionModel?.sectionForBlockIndex(listState.firstVisibleItemIndex) }
    }

    // ノートを引くたびに本文パネルをふわっと出す（フェード＋0.95→1.0のスケール）。
    // AnimatedContent だと新旧リストが1つの listState を共有してしまうため graphicsLayer で行う。
    val noteAppear = remember { Animatable(1f) }
    LaunchedEffect(successState) {
        if (successState != null) {
            noteAppear.snapTo(0f)
            noteAppear.animateTo(1f, animationSpec = tween(300))
        }
    }

    val activeChat = uiState.sectionChat
    val fabStatus = when {
        activeChat == null -> SectionFabStatus.Idle
        activeChat.error != null -> SectionFabStatus.Error
        activeChat.isSummaryLoading || activeChat.isGenerating -> SectionFabStatus.Loading
        activeChat.summary != null -> SectionFabStatus.Ready
        else -> SectionFabStatus.Loading
    }
    val fabSectionLabel = activeChat?.sectionTitle ?: currentSection?.title ?: "ノート全体"
    val onFabTap = {
        if (activeChat != null) {
            onShowSectionChat()
        } else if (successState != null) {
            onOpenSection(currentSection ?: NoteSection(successState.title, 0, successState.content))
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ReadingGradient)
                .safeDrawingPadding()
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Rediscover",
                        color = OnVibrant,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold
                    )
                    // 未選択時はVault案内、通常時はコンセプト文を出す。
                    Text(
                        text = if (!uiState.vaultSelected) "Vaultフォルダが未選択です"
                        else "過去のノートから、思考をひとつ。",
                        color = OnVibrantMuted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                if (hasNote) {
                    IconPill(symbol = "⛶", contentDescription = "全画面表示") { onEnterFullscreen() }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Vault切替はオプションへ移動。初回セットアップ時だけここにも出す。
                if (!uiState.vaultSelected) {
                    Button(
                        onClick = onSelectVault,
                        modifier = Modifier.weight(1f).height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ButtonSecondary),
                        shape = RoundedCornerShape(24.dp)
                    ) { Text("Vaultを選択", color = OnVibrant) }
                }
                Button(
                    onClick = onRandomNote,
                    enabled = !isLoading,
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ButtonPrimary),
                    shape = RoundedCornerShape(24.dp)
                ) { Text("別のノートをひらく", color = OnVibrant) }
            }

            if (isLoading) {
                CircularProgressIndicator(
                    color = OnVibrant,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp)
                )
            }

            NoteContentPanel(
                uiState = uiState,
                modifier = Modifier
                    .weight(1f)
                    .padding(top = if (isLoading) 8.dp else 20.dp)
                    .graphicsLayer {
                        alpha = noteAppear.value
                        val scale = 0.95f + 0.05f * noteAppear.value
                        scaleX = scale
                        scaleY = scale
                    },
                listState = if (hasNote) listState else null,
                precomputedBlocks = sectionModel?.blocks
            )
        }

        // 浮遊吹き出し（今見ているセクションを対象に。タップで要約＋質問シート）
        // 全画面は独立ルート（note_fullscreen）へ移したため、ここではタブ表示時のみ出す。
        if (successState != null) {
            SectionFab(
                sectionLabel = fabSectionLabel,
                status = fabStatus,
                isAnswerGenerating = activeChat?.isGenerating == true,
                onTap = onFabTap
            )
        }
    }

    // セクションチャットのボトムシート
    if (uiState.isSectionChatSheetVisible) uiState.sectionChat?.let { chat ->
        // クイズ生成の入力: シートが対象にしているセクションを sectionModel から
        // 同定し、その周辺テキストを渡す。擬似セクション（ノート全体）は
        // surroundingContext 側でノート先頭フォールバックになる。
        val startQuizFromChat: (SectionChatState) -> Unit = { target ->
            val matched = sectionModel?.sections?.firstOrNull {
                it.title == target.sectionTitle && it.text == target.sectionContext
            }
            val quizContext = sectionModel?.surroundingContext(matched) ?: target.sectionContext
            onGenerateQuiz(target.sectionTitle, quizContext)
        }
        SectionChatSheet(
            state = chat,
            quizState = uiState.quizState,
            onSuggestionTap = onSuggestionTap,
            onQuizTap = {
                when (val qs = uiState.quizState) {
                    is QuizState.Loading -> Unit
                    is QuizState.Success -> onOpenQuizResult()
                    is QuizState.Error ->
                        if (qs.isViewed) startQuizFromChat(chat) else onOpenQuizResult()
                    is QuizState.Idle -> startQuizFromChat(chat)
                }
            },
            onDismiss = onDismissSectionChat,
            onEndSession = onEndSectionChat
        )
    }
}

private enum class SectionFabStatus { Idle, Loading, Ready, Error }

/** 画面に浮かぶ半透明・立体的な吹き出しボタン。ドラッグで移動、タップで要約＋質問シート。 */
@Composable
private fun BoxScope.SectionFab(
    sectionLabel: String,
    status: SectionFabStatus,
    isAnswerGenerating: Boolean,
    onTap: () -> Unit
) {
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    // pointerInput(Unit) は初回コンポーズ時のクロージャを固定するため、直接 onTap を
    // 参照すると古いセッション状態を抱き込んだ処理が呼ばれ続ける（クイズ画面から
    // 戻った後に「確認終了→吹き出しタップ」が無反応になる不具合の原因）。
    // rememberUpdatedState 経由で常に最新の onTap を呼ぶ。
    val currentOnTap by rememberUpdatedState(onTap)

    Box(
        modifier = Modifier
            .align(Alignment.BottomEnd)
            .offset { IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) }
            .safeDrawingPadding()
            .padding(end = 20.dp, bottom = 20.dp)
    ) {
        Column(horizontalAlignment = Alignment.End) {
            // 対象セクションラベル（半透明・アクセント色）
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Indigo.copy(alpha = 0.55f))
                    .widthIn(max = 260.dp)
                    .padding(horizontal = 11.dp, vertical = 5.dp)
            ) {
                Text(
                    text = when (status) {
                        SectionFabStatus.Idle -> "📌 $sectionLabel"
                        SectionFabStatus.Loading -> if (isAnswerGenerating) {
                            "⏳ AI回答中 · $sectionLabel"
                        } else {
                            "⏳ AI要約中 · $sectionLabel"
                        }
                        SectionFabStatus.Ready -> "✓ 要約完了 · $sectionLabel"
                        SectionFabStatus.Error -> "! 要約を確認 · $sectionLabel"
                    },
                    color = OnVibrant,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            // 吹き出し本体：半透明のアクセント色ガラス＋立体感（影・上部ハイライト・色リム）
            Box(
                modifier = Modifier
                    .size(62.dp)
                    .shadow(elevation = 18.dp, shape = CircleShape, clip = false)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                Indigo.copy(alpha = 0.62f),
                                Coral.copy(alpha = 0.55f)
                            )
                        )
                    )
                    .border(1.5.dp, Indigo.copy(alpha = 0.55f), CircleShape)
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            dragOffset += dragAmount
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { currentOnTap() })
                    },
                contentAlignment = Alignment.Center
            ) {
                // 上部スペキュラハイライト（ガラスの艶・アクセント色）
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                        .size(width = 26.dp, height = 12.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Aqua.copy(alpha = 0.40f))
                )
                when (status) {
                    SectionFabStatus.Loading -> CircularProgressIndicator(
                        modifier = Modifier.size(25.dp),
                        color = OnVibrant,
                        strokeWidth = 2.5.dp
                    )
                    SectionFabStatus.Ready -> Text(
                        "✓",
                        color = OnVibrant,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold
                    )
                    SectionFabStatus.Error -> Text(
                        "!",
                        color = OnVibrant,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold
                    )
                    SectionFabStatus.Idle -> Text("💬", fontSize = 26.sp)
                }
            }
        }
    }
}

/**
 * タブ内の丸いアイコンボタン（material-icons 依存を避けるため絵文字/記号を使用）。
 * 既定色はグラデーション背景に置く前提。明色パネルの上に置く場合は
 * containerColor で暗めの下地を指定しないと視認できない。
 */
@Composable
private fun IconPill(
    symbol: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    containerColor: Color = Panel.copy(alpha = 0.22f),
    onClick: () -> Unit
) {
    // contentDescription を実際にSemanticsへ設定し、絵文字記号は読み上げ対象から外す。
    val description = contentDescription
    Surface(
        modifier = modifier
            .size(40.dp)
            .clickable(onClick = onClick)
            .semantics {
                this.contentDescription = description
                role = Role.Button
            },
        shape = CircleShape,
        color = containerColor
    ) {
        Box(
            modifier = Modifier.clearAndSetSemantics {},
            contentAlignment = Alignment.Center
        ) {
            Text(symbol, color = OnVibrant, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

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
    onOpenSummary: () -> Unit
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
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState(
        tabListState.firstVisibleItemIndex,
        tabListState.firstVisibleItemScrollOffset
    )
    val leaveWith: (() -> Unit) -> Unit = { action ->
        // action() でルートが破棄されると scope もキャンセルされるため、書き戻しを
        // 起動しっぱなしにすると（特にフリング中）完了前に消える。scrollToItem 完了後に閉じる。
        // scrollToItem は未アタッチのstateでも即座に保留位置をセットして返るためハングしない。
        scope.launch {
            tabListState.scrollToItem(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset
            )
            action()
        }
    }
    // システムバックでもスクロール位置を書き戻してから閉じる。
    BackHandler { leaveWith(onExit) }

    val successState = uiState.noteState as? NoteState.Success
    val sectionModel = remember(successState?.content) {
        successState?.content?.let { buildNoteSectionModel(it) }
    }
    val activeChat = uiState.sectionChat

    // 要約/回答の状態（通常FABと同じ導出）に、クイズ状態を合成した最小インジケータ用ステータス。
    val summaryStatus = when {
        activeChat == null -> SectionFabStatus.Idle
        activeChat.error != null -> SectionFabStatus.Error
        activeChat.isSummaryLoading || activeChat.isGenerating -> SectionFabStatus.Loading
        activeChat.summary != null -> SectionFabStatus.Ready
        else -> SectionFabStatus.Loading
    }
    val quiz = uiState.quizState
    val combinedStatus = when {
        summaryStatus == SectionFabStatus.Loading || quiz is QuizState.Loading -> SectionFabStatus.Loading
        summaryStatus == SectionFabStatus.Error ||
            (quiz is QuizState.Error && !quiz.isViewed) -> SectionFabStatus.Error
        summaryStatus == SectionFabStatus.Ready ||
            (quiz is QuizState.Success && !quiz.isViewed) -> SectionFabStatus.Ready
        else -> SectionFabStatus.Idle
    }

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
    status: SectionFabStatus,
    onTap: () -> Unit
) {
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    val currentOnTap by rememberUpdatedState(onTap)
    val fabDescription = when (status) {
        SectionFabStatus.Loading -> "AI生成中"
        SectionFabStatus.Ready -> "AI生成完了。タップで開く"
        SectionFabStatus.Error -> "AIエラー。タップで確認"
        SectionFabStatus.Idle -> "AIメニュー。タップで開く"
    }
    var showLabel by remember { mutableStateOf(false) }
    LaunchedEffect(status) {
        showLabel = status == SectionFabStatus.Ready || status == SectionFabStatus.Error
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
                    .background(Indigo.copy(alpha = 0.55f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (status == SectionFabStatus.Error) "! 確認して" else "✓ 完了",
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
                .background(Indigo.copy(alpha = 0.55f))
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
                SectionFabStatus.Loading -> CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = OnVibrant,
                    strokeWidth = 2.dp
                )
                SectionFabStatus.Ready -> Text("✓", color = OnVibrant, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                SectionFabStatus.Error -> Text("!", color = OnVibrant, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                SectionFabStatus.Idle -> Text("💬", fontSize = 18.sp)
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

/** ノート本文パネル（通常表示・全画面で共用）。 */
@Composable
internal fun NoteContentPanel(
    uiState: NoteUiState,
    modifier: Modifier = Modifier,
    listState: LazyListState? = null,
    precomputedBlocks: List<MarkdownBlock>? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Panel,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            val (noteTitle, noteContent) = when (val state = uiState.noteState) {
                is NoteState.Success -> state.title to state.content
                is NoteState.Empty   -> "ノート未表示" to "このVaultにMarkdownノートが見つかりませんでした。"
                is NoteState.Error   -> "ノート未表示" to "Vaultを読み込めませんでした。"
                else                 -> "ノート未表示" to "Vaultフォルダを選択して「ランダム表示」をタップしてください。"
            }
            // precomputedBlocks はノート本文（Success時）のパース結果。
            // プレースホルダ表示時は内容と一致しないため渡さない。
            val blocksForContent = if (uiState.noteState is NoteState.Success) precomputedBlocks else null
            Text(text = noteTitle, color = OnSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            if (listState != null) {
                MarkdownNoteContent(
                    content = noteContent,
                    modifier = Modifier.padding(top = 12.dp).weight(1f),
                    listState = listState,
                    precomputedBlocks = blocksForContent
                )
            } else {
                MarkdownNoteContent(
                    content = noteContent,
                    modifier = Modifier.padding(top = 12.dp).weight(1f),
                    precomputedBlocks = blocksForContent
                )
            }
        }
    }
}
