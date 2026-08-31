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
import com.example.newproject.ui.component.ReadingProgressReporter
import com.example.newproject.ui.component.ReadingTraceCardPanel
import com.example.newproject.ui.vigilith.VigilithNoteAction
import com.example.newproject.ui.vigilith.sectionChatStatus
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.model.state.NoteState
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.state.QuizState
import com.example.newproject.model.state.SectionChatState
import com.example.newproject.domain.markdown.NoteSection
import com.example.newproject.domain.markdown.NoteSectionModel
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
    onSuggestionTap: (String) -> Unit,
    onRetrySectionSummary: () -> Unit,
    onRetrySectionAnswer: () -> Unit,
    onDismissSectionChat: () -> Unit,
    onEndSectionChat: () -> Unit,
    onGenerateQuiz: (sourceLabel: String, context: String) -> Unit,
    onOpenQuizResult: () -> Unit,
    noteListState: LazyListState,
    onEnterFullscreen: () -> Unit,
    onReadingProgress: (blockIndex: Int, blockFraction: Float, totalBlocks: Int, sectionTitle: String?) -> Unit,
    onDismissReadingTrace: () -> Unit,
    onToggleReadingTraceMark: () -> Unit,
    onOpenReflection: () -> Unit,
    onVigilithActionChanged: (VigilithNoteAction?) -> Unit
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
    val currentSection by remember(sectionModel) {
        derivedStateOf { sectionModel?.sectionForBlockIndex(listState.firstVisibleItemIndex) }
    }

    ReadingProgressReporter(sectionModel, listState, imageMeasurements, onReadingProgress)

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
    val fabSectionLabel = activeChat?.sectionTitle ?: currentSection?.title ?: "ノート全体"
    val vigilithAction = successState?.let { note ->
        VigilithNoteAction(
            section = currentSection ?: NoteSection(note.title, 0, note.content),
            sectionLabel = fabSectionLabel,
            status = sectionChatStatus(activeChat),
            isAnswerGenerating = activeChat?.isGenerating == true
        )
    }
    val currentVigilithActionChanged by rememberUpdatedState(onVigilithActionChanged)
    LaunchedEffect(vigilithAction) {
        currentVigilithActionChanged(vigilithAction)
    }
    DisposableEffect(Unit) {
        onDispose { currentVigilithActionChanged(null) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ReadingGradient)
                .safeDrawingPadding()
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp)
        ) {
            // 未選択時はVault案内、通常時はコンセプト文を出す。
            GradientHeader(
                title = "Rediscover",
                subtitle = if (!uiState.vaultSelected) "Vaultフォルダが未選択です"
                else "過去のノートから、思考をひとつ。",
                trailing = if (hasNote) {
                    { IconPill(symbol = "⛶", contentDescription = "全画面表示") { onEnterFullscreen() } }
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

            // ノートが出ていないときは、案内カードを内容なりの高さに留める。
            // 本文パネルと同じく `weight(1f)` で伸ばすと、中身の無い白が画面の大半を占める。
            if (!hasNote) {
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
                // 余りをカードではなく余白へ逃がす。
                Spacer(modifier = Modifier.weight(1f))
                return@Column
            }

            // 「前回のあなた」カード。NoteContentPanel の外側に置くので全画面には出ない
            // （NoteContentPanel は全画面と共用。LazyColumn の index もずらさないので
            //  セクション判定とスクロール継承を壊さない）。
            // ここへ来る時点で hasNote は true。
            val visibleTraceCard = uiState.readingTraceCard?.takeIf { !it.isDismissed }
            val showTraceCard = visibleTraceCard != null
            if (visibleTraceCard != null) {
                ReadingTraceCardPanel(
                    card = visibleTraceCard,
                    modifier = Modifier.padding(top = 20.dp),
                    onDismiss = onDismissReadingTrace,
                    onOpenReflection = onOpenReflection,
                    onToggleMark = onToggleReadingTraceMark
                )
            }

            NoteContentPanel(
                uiState = uiState,
                modifier = Modifier
                    .weight(1f)
                    .padding(top = if (isLoading || showTraceCard) 8.dp else 20.dp)
                    .graphicsLayer {
                        alpha = noteAppear.value
                        val scale = 0.95f + 0.05f * noteAppear.value
                        scaleX = scale
                        scaleY = scale
                    },
                listState = listState,
                precomputedBlocks = sectionModel?.blocks,
                imageLoader = imageLoader,
                imageMeasurements = imageMeasurements
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
                    // 非対応ならボタン自体が無効なので、ここへは取得失敗のときだけ来る。
                    is QuizState.AiNotice -> startQuizFromChat(chat)
                    is QuizState.Idle -> startQuizFromChat(chat)
                }
            },
            onRetrySummary = onRetrySectionSummary,
            onRetryAnswer = onRetrySectionAnswer,
            onDismiss = onDismissSectionChat,
            onEndSession = onEndSectionChat
        )
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
