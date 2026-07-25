package com.example.newproject.ui

import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.example.newproject.NoteState
import com.example.newproject.NoteUiState
import com.example.newproject.QuizState
import com.example.newproject.SectionChatState
import com.example.newproject.domain.markdown.NoteSection
import com.example.newproject.domain.markdown.buildNoteSectionModel
import com.example.newproject.ui.theme.OnButtonPrimary
import com.example.newproject.ui.theme.OnButtonSecondary
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
    onSelectVault: () -> Unit,
    onRandomNote: () -> Unit,
    onSuggestionTap: (String) -> Unit,
    onDismissSectionChat: () -> Unit,
    onEndSectionChat: () -> Unit,
    onGenerateQuiz: (sourceLabel: String, context: String) -> Unit,
    onOpenQuizResult: () -> Unit,
    noteListState: LazyListState,
    onEnterFullscreen: () -> Unit,
    onReadingProgress: (blockIndex: Int, blockFraction: Float, totalBlocks: Int, sectionTitle: String?) -> Unit,
    onDismissReadingTrace: () -> Unit,
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
    val sectionModel = remember(successState?.content) {
        successState?.content?.let { buildNoteSectionModel(it) }
    }
    val currentSection by remember(sectionModel) {
        derivedStateOf { sectionModel?.sectionForBlockIndex(listState.firstVisibleItemIndex) }
    }

    ReadingProgressReporter(sectionModel, listState, onReadingProgress)

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
                        colors = ButtonDefaults.buttonColors(containerColor = ButtonSecondary, contentColor = OnButtonSecondary),
                        shape = RoundedCornerShape(24.dp)
                    ) { Text("Vaultを選択", color = OnButtonSecondary) }
                }
                Button(
                    onClick = onRandomNote,
                    enabled = !isLoading,
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ButtonPrimary, contentColor = OnButtonPrimary),
                    shape = RoundedCornerShape(24.dp)
                ) { Text("別のノートをひらく", color = OnButtonPrimary) }
            }

            if (isLoading) {
                CircularProgressIndicator(
                    color = OnVibrant,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp)
                )
            }

            // 「前回のあなた」カード。NoteContentPanel の外側に置くので全画面には出ない
            // （NoteContentPanel は全画面と共用。LazyColumn の index もずらさないので
            //  セクション判定とスクロール継承を壊さない）。
            val visibleTraceCard = uiState.readingTraceCard?.takeIf {
                successState != null && !it.isDismissed
            }
            val showTraceCard = visibleTraceCard != null
            if (visibleTraceCard != null) {
                ReadingTraceCardPanel(
                    card = visibleTraceCard,
                    modifier = Modifier.padding(top = 20.dp),
                    onDismiss = onDismissReadingTrace
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
                listState = if (hasNote) listState else null,
                precomputedBlocks = sectionModel?.blocks
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
