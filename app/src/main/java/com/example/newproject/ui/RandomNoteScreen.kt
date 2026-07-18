package com.example.newproject.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.ui.markdown.MarkdownBlock
import com.example.newproject.ui.markdown.NoteSection
import com.example.newproject.ui.markdown.buildNoteSectionModel
import kotlin.math.roundToInt
import com.example.newproject.AnnotationState
import com.example.newproject.NoteState
import com.example.newproject.NoteUiState
import com.example.newproject.R
import com.example.newproject.RelatedNotesState
import com.example.newproject.SummaryState
import com.example.newproject.domain.AiRecommendationStatus
import com.example.newproject.domain.RelatedNote
import com.example.newproject.ui.markdown.MarkdownNoteContent
import com.example.newproject.ui.theme.Aqua
import com.example.newproject.ui.theme.ButtonPrimary
import com.example.newproject.ui.theme.ButtonSecondary
import com.example.newproject.ui.theme.Coral
import com.example.newproject.ui.theme.Indigo
import com.example.newproject.ui.theme.OnSurface
import com.example.newproject.ui.theme.OnVibrant
import com.example.newproject.ui.theme.OnVibrantMuted
import com.example.newproject.ui.theme.Panel

/** 3タブで共有する背景グラデーション。 */
private fun noteGradient(): Brush = Brush.linearGradient(
    colors = listOf(Indigo, Aqua, Coral),
    start = Offset(0f, Float.POSITIVE_INFINITY),
    end = Offset(Float.POSITIVE_INFINITY, 0f)
)

// ---------------------------------------------------------------------------
// タブ1: ノート（本文リーダー）
// ---------------------------------------------------------------------------

@Composable
fun NoteReaderTab(
    uiState: NoteUiState,
    onSelectVault: () -> Unit,
    onRandomNote: () -> Unit,
    onOpenSection: (NoteSection) -> Unit,
    onSuggestionTap: (String) -> Unit,
    onCloseSectionChat: () -> Unit
) {
    val context = LocalContext.current
    var isFullscreen by remember { mutableStateOf(false) }

    LaunchedEffect((uiState.noteState as? NoteState.Error)?.id) {
        if (uiState.noteState is NoteState.Error) {
            Toast.makeText(context, uiState.noteState.message, Toast.LENGTH_SHORT).show()
        }
    }

    val isLoading = uiState.noteState is NoteState.Loading
    val successState = uiState.noteState as? NoteState.Success
    val hasNote = successState != null

    val listState = rememberLazyListState()
    val sectionModel = remember(successState?.content) {
        successState?.content?.let { buildNoteSectionModel(it) }
    }
    val currentSection by remember(sectionModel) {
        derivedStateOf { sectionModel?.sectionForBlockIndex(listState.firstVisibleItemIndex) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(noteGradient())
                .safeDrawingPadding()
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.random_note_title),
                        color = OnVibrant,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(
                            if (uiState.vaultSelected) R.string.vault_selected
                            else R.string.vault_not_selected
                        ),
                        color = OnVibrantMuted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                if (hasNote) {
                    IconPill(symbol = "⛶", contentDescription = "全画面表示") { isFullscreen = true }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onSelectVault,
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ButtonSecondary),
                    shape = RoundedCornerShape(24.dp)
                ) { Text(stringResource(R.string.select_vault), color = OnVibrant) }
                Button(
                    onClick = onRandomNote,
                    enabled = !isLoading,
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ButtonPrimary),
                    shape = RoundedCornerShape(24.dp)
                ) { Text(stringResource(R.string.show_random_note), color = OnVibrant) }
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
                    .padding(top = if (isLoading) 8.dp else 20.dp),
                listState = if (hasNote) listState else null,
                precomputedBlocks = sectionModel?.blocks
            )
        }

        // 浮遊吹き出し（今見ているセクションを対象に。タップで要約＋質問シート）
        if (successState != null && !isFullscreen) {
            val note = successState
            SectionFab(
                sectionLabel = currentSection?.title ?: "ノート全体",
                onTap = {
                    onOpenSection(currentSection ?: NoteSection(note.title, 0, note.content))
                }
            )
        }

        // 全画面オーバーレイ（明示ボタンで開閉。ダブルタップは廃止）
        AnimatedVisibility(
            visible = isFullscreen,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(noteGradient())
                    .safeDrawingPadding()
                    .padding(12.dp)
            ) {
                NoteContentPanel(
                    uiState = uiState,
                    modifier = Modifier.fillMaxSize(),
                    precomputedBlocks = sectionModel?.blocks
                )
                IconPill(
                    symbol = "✕",
                    contentDescription = "全画面表示を閉じる",
                    modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                ) { isFullscreen = false }
            }
        }
    }

    // セクションチャットのボトムシート
    uiState.sectionChat?.let { chat ->
        SectionChatSheet(
            state = chat,
            onSuggestionTap = onSuggestionTap,
            onDismiss = onCloseSectionChat
        )
    }
}

/** 画面に浮かぶ半透明・立体的な吹き出しボタン。ドラッグで移動、タップで要約＋質問シート。 */
@Composable
private fun BoxScope.SectionFab(
    sectionLabel: String,
    onTap: () -> Unit
) {
    var dragOffset by remember { mutableStateOf(Offset.Zero) }

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
                    .padding(horizontal = 11.dp, vertical = 5.dp)
            ) {
                Text(
                    text = "📌 $sectionLabel",
                    color = OnVibrant,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
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
                        detectTapGestures(onTap = { onTap() })
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
                Text("💬", fontSize = 26.sp)
            }
        }
    }
}

/** タブ内の丸いアイコンボタン（material-icons 依存を避けるため絵文字/記号を使用）。 */
@Composable
private fun IconPill(
    symbol: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.size(40.dp).clickable(onClick = onClick),
        shape = CircleShape,
        color = Panel.copy(alpha = 0.22f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(symbol, color = OnVibrant, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// ---------------------------------------------------------------------------
// タブ2: 関連ノート
// ---------------------------------------------------------------------------

@Composable
fun RelatedTab(
    uiState: NoteUiState,
    onOpenNote: (RelatedNote) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(noteGradient())
            .safeDrawingPadding()
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp)
    ) {
        Text(
            text = "関連ノート",
            color = OnVibrant,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            if (uiState.relatedNotesState is RelatedNotesState.Idle) {
                Text(
                    text = "ノートを表示すると、リンクとAIによる関連ノートがここに並びます。",
                    color = OnVibrantMuted,
                    fontSize = 14.sp
                )
            } else {
                RelatedNotesPanel(
                    state = uiState.relatedNotesState,
                    onNoteClick = onOpenNote
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// タブ3: AI（要約・Q&A・補記メモ）
// ---------------------------------------------------------------------------

@Composable
fun AiTab(
    uiState: NoteUiState,
    onGenerateQuiz: () -> Unit,
    onCreateAnnotation: () -> Unit
) {
    val hasNote = uiState.noteState is NoteState.Success
    val isAnnotationLoading = uiState.annotationState is AnnotationState.Loading

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(noteGradient())
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp)
    ) {
        Text(
            text = "AIアシスト",
            color = OnVibrant,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))

        if (!hasNote) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Panel.copy(alpha = 0.22f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "先に「ノート」タブでノートを表示してください。",
                    color = OnVibrant,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(16.dp)
                )
            }
            return@Column
        }

        SummaryPanel(summaryState = uiState.summaryState)

        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onGenerateQuiz,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Coral),
            shape = RoundedCornerShape(24.dp)
        ) { Text("📝 Q&Aを作る", color = OnVibrant) }

        Spacer(modifier = Modifier.height(10.dp))
        Button(
            onClick = onCreateAnnotation,
            enabled = !isAnnotationLoading,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Indigo),
            shape = RoundedCornerShape(24.dp)
        ) { Text("✨ AI補記メモ", color = OnVibrant) }

        if (isAnnotationLoading) {
            Text(
                "AI補記メモを生成中…",
                color = OnVibrantMuted,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 共有パネル（ロジックは従来どおり）
// ---------------------------------------------------------------------------

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
                is NoteState.Empty   -> stringResource(R.string.no_note_loaded) to stringResource(R.string.no_markdown_notes)
                is NoteState.Error   -> stringResource(R.string.no_note_loaded) to stringResource(R.string.vault_read_error)
                else                 -> stringResource(R.string.no_note_loaded) to stringResource(R.string.random_note_empty_state)
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

@Composable
internal fun SummaryPanel(summaryState: SummaryState, modifier: Modifier = Modifier) {
    when (summaryState) {
        is SummaryState.Idle,
        is SummaryState.AiUnavailable -> return
        is SummaryState.Loading,
        is SummaryState.Downloading,
        is SummaryState.Success,
        is SummaryState.Error -> Unit
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color(0xFFF0F4FF),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "📝 AI 要約",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Indigo
            )
            Spacer(modifier = Modifier.height(8.dp))
            when (summaryState) {
                is SummaryState.Loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Indigo)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("要約を生成中…", fontSize = 13.sp, color = Color(0xFF555555))
                    }
                }
                is SummaryState.Downloading -> {
                    val downloaded = summaryState.downloaded
                    val total = summaryState.total
                    val progress = if (total > 0) downloaded.toFloat() / total else -1f
                    val label = when {
                        downloaded < 0   -> "Gemini Nano をダウンロード中…"
                        total <= 0       -> "Gemini Nano をダウンロード中…"
                        else -> {
                            val dlMb = downloaded / 1_048_576f
                            val totalMb = total / 1_048_576f
                            "Gemini Nano をダウンロード中… %.0f / %.0f MB".format(dlMb, totalMb)
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(label, fontSize = 13.sp, color = Color(0xFF555555))
                        if (progress >= 0) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                                color = Indigo
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = Indigo
                            )
                        }
                    }
                }
                is SummaryState.Success -> {
                    Text(text = summaryState.summary, fontSize = 14.sp, lineHeight = 22.sp, color = OnSurface)
                }
                is SummaryState.AiUnavailable -> {
                    Text("この端末はGemini Nanoに対応していません。", fontSize = 13.sp, color = Color(0xFF888888))
                }
                is SummaryState.Error -> {
                    Text("要約の取得に失敗しました: ${summaryState.message}", fontSize = 13.sp, color = Color(0xFFCC0000))
                }
                else -> {}
            }
        }
    }
}

@Composable
internal fun RelatedNotesPanel(
    state: RelatedNotesState,
    onNoteClick: (RelatedNote) -> Unit,
    modifier: Modifier = Modifier
) {
    when (state) {
        is RelatedNotesState.Idle -> return
        is RelatedNotesState.Loading,
        is RelatedNotesState.Success,
        is RelatedNotesState.Error -> Unit
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Color(0xFFF0F4FF),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = "🔗 関連ノート",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Indigo
            )
            Spacer(modifier = Modifier.height(8.dp))
            when (state) {
                is RelatedNotesState.Loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Indigo
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("関連ノートを検索中…", fontSize = 13.sp, color = Color(0xFF555555))
                    }
                }
                is RelatedNotesState.Success -> {
                    val hasRelated = state.relatedNotes.isNotEmpty()
                    val hasAi = state.aiNotes.isNotEmpty()
                    if (!hasRelated && !hasAi && state.aiStatus == AiRecommendationStatus.Ready) {
                        Text("関連ノートは見つかりませんでした。", fontSize = 13.sp, color = Color(0xFF777777))
                    } else {
                        if (hasRelated) {
                            Text(
                                text = "関連ノート",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF7B6FFF)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            state.relatedNotes.forEachIndexed { index, note ->
                                RelatedNoteItem(note = note, onClick = { onNoteClick(note) })
                                if (index < state.relatedNotes.lastIndex) {
                                    Divider(color = Color(0xFFD6DDF5), thickness = 0.5.dp)
                                }
                            }
                        }
                        val showAiSection = hasAi || state.aiStatus != AiRecommendationStatus.Ready
                        if (showAiSection) {
                            if (hasRelated) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Divider(color = Color(0xFFB0BBEE), thickness = 1.dp)
                                Spacer(modifier = Modifier.height(8.dp))
                            }
                            Text(
                                text = "AI推薦",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF16B8A6)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            if (hasAi) {
                                state.aiNotes.forEachIndexed { index, note ->
                                    RelatedNoteItem(note = note, onClick = { onNoteClick(note) })
                                    if (index < state.aiNotes.lastIndex) {
                                        Divider(color = Color(0xFFD6DDF5), thickness = 0.5.dp)
                                    }
                                }
                            } else {
                                AiRecommendationStatusText(
                                    status = state.aiStatus,
                                    errorMessage = state.aiErrorMessage
                                )
                            }
                        }
                    }
                }
                is RelatedNotesState.Error -> {
                    Text("関連ノートの取得に失敗しました: ${state.message}", fontSize = 13.sp, color = Color(0xFFCC0000))
                }
                else -> {}
            }
        }
    }
}

@Composable
internal fun AiRecommendationStatusText(
    status: AiRecommendationStatus,
    errorMessage: String?,
) {
    val message = when (status) {
        AiRecommendationStatus.Ready -> "AI推薦は見つかりませんでした。"
        AiRecommendationStatus.Unavailable -> "この端末ではAI推薦を利用できません。"
        AiRecommendationStatus.NeedsDownload -> "AI推薦に必要なモデルを準備中です。"
        AiRecommendationStatus.Error -> "AI推薦の取得に失敗しました: ${errorMessage ?: "Unknown error"}"
    }
    val color = when (status) {
        AiRecommendationStatus.Ready -> Color(0xFF777777)
        AiRecommendationStatus.NeedsDownload -> Color(0xFF555555)
        AiRecommendationStatus.Unavailable,
        AiRecommendationStatus.Error -> Color(0xFFCC0000)
    }
    Text(message, fontSize = 13.sp, color = color)
}

@Composable
internal fun RelatedNoteItem(note: RelatedNote, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = note.title,
            fontSize = 14.sp,
            lineHeight = 18.sp,
            color = OnSurface,
            modifier = Modifier.weight(1f)
        )
        if (note.isWikilinked) {
            Surface(
                color = Indigo,
                shape = RoundedCornerShape(4.dp)
            ) {
                Text(
                    text = "linked",
                    color = OnVibrant,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}
