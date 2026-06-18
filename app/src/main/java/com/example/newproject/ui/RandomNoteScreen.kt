package com.example.newproject.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

@Composable
fun RandomNoteScreen(
    uiState: NoteUiState,
    isExpanded: Boolean,
    onSelectVault: () -> Unit,
    onRandomNote: () -> Unit,
    onOpenNote: (RelatedNote) -> Unit,
    onGenerateQuiz: () -> Unit,
    onCreateAnnotation: () -> Unit
) {
    val context = LocalContext.current
    var isNoteExpanded by remember { mutableStateOf(false) }

    LaunchedEffect((uiState.noteState as? NoteState.Error)?.id) {
        if (uiState.noteState is NoteState.Error) {
            Toast.makeText(context, uiState.noteState.message, Toast.LENGTH_SHORT).show()
        }
    }

    val gradient = Brush.linearGradient(
        colors = listOf(Indigo, Aqua, Coral),
        start = Offset(0f, Float.POSITIVE_INFINITY),
        end = Offset(Float.POSITIVE_INFINITY, 0f)
    )
    val isLoading = uiState.noteState is NoteState.Loading
    val isAnnotationLoading = uiState.annotationState is AnnotationState.Loading

    Box(modifier = Modifier.fillMaxSize()) {
    if (isExpanded) {
        // フォルダブル展開時: 左ペイン（操作パネル） | 右ペイン（ノート＋サジェスト）
        Row(
            modifier = Modifier
                .fillMaxSize()
                .background(gradient)
                .safeDrawingPadding()
                .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 左ペイン
            Column(
                modifier = Modifier
                    .width(280.dp)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.random_note_title),
                    color = OnVibrant,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(
                        if (uiState.vaultSelected) R.string.vault_selected
                        else R.string.vault_not_selected
                    ),
                    color = OnVibrantMuted,
                    fontSize = 13.sp
                )
                Button(
                    onClick = onSelectVault,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ButtonSecondary),
                    shape = RoundedCornerShape(24.dp)
                ) { Text(stringResource(R.string.select_vault), color = OnVibrant) }
                Button(
                    onClick = onRandomNote,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ButtonPrimary),
                    shape = RoundedCornerShape(24.dp)
                ) { Text(stringResource(R.string.show_random_note), color = OnVibrant) }
                if (uiState.noteState is NoteState.Success) {
                    Button(
                        onClick = onGenerateQuiz,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Coral),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Text("📝 Q&Aを作る", color = OnVibrant)
                    }
                    Button(
                        onClick = onCreateAnnotation,
                        enabled = !isAnnotationLoading,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Text("AI補記メモ", color = OnVibrant)
                    }
                    if (isAnnotationLoading) {
                        Text("AI補記メモを生成中…", color = OnVibrantMuted, fontSize = 12.sp)
                    }
                }
                if (isLoading) {
                    CircularProgressIndicator(
                        color = OnVibrant,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }
                RelatedNotesPanel(
                    state = uiState.relatedNotesState,
                    onNoteClick = onOpenNote,
                    modifier = Modifier.weight(1f)
                )
            }

            // 右ペイン
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                NoteContentPanel(
                    uiState = uiState,
                    modifier = Modifier.weight(1f),
                    onDoubleTap = if (uiState.noteState is NoteState.Success) {
                        { isNoteExpanded = true }
                    } else null
                )
                SummaryPanel(summaryState = uiState.summaryState)
            }
        }
    } else {
        // 通常・折りたたみ時: 縦積みレイアウト
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(gradient)
                .safeDrawingPadding()
                .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 24.dp)
        ) {
            Text(
                text = stringResource(R.string.random_note_title),
                color = OnVibrant,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(
                    if (uiState.vaultSelected) R.string.vault_selected
                    else R.string.vault_not_selected
                ),
                color = OnVibrantMuted,
                fontSize = 14.sp,
                modifier = Modifier.padding(top = 10.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
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
            if (uiState.noteState is NoteState.Success) {
                Button(
                    onClick = onGenerateQuiz,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Coral)
                ) {
                    Text("📝 Q&Aを作る", color = OnVibrant)
                }
                Button(
                    onClick = onCreateAnnotation,
                    enabled = !isAnnotationLoading,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Indigo)
                ) {
                    Text("AI補記メモ", color = OnVibrant)
                }
                if (isAnnotationLoading) {
                    Text(
                        "AI補記メモを生成中…",
                        color = OnVibrantMuted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
            NoteContentPanel(
                uiState = uiState,
                modifier = Modifier
                    .weight(1f)
                    .padding(top = if (isLoading) 8.dp else 24.dp),
                onDoubleTap = if (uiState.noteState is NoteState.Success) {
                    { isNoteExpanded = true }
                } else null
            )
            SummaryPanel(
                summaryState = uiState.summaryState,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }

    // 全画面オーバーレイ（ダブルタップで展開）
    AnimatedVisibility(
        visible = isNoteExpanded,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.zIndex(1f)
    ) {
        NoteContentPanel(
            uiState = uiState,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(pass = PointerEventPass.Initial)
                        waitForUpOrCancellation(pass = PointerEventPass.Initial) ?: return@awaitEachGesture
                        val secondDown = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
                            awaitFirstDown(pass = PointerEventPass.Initial)
                        } ?: return@awaitEachGesture
                        secondDown.consume()
                        waitForUpOrCancellation(pass = PointerEventPass.Initial)?.consume()
                        isNoteExpanded = false
                    }
                }
        )
    }
    } // Box end
}

@Composable
internal fun NoteContentPanel(
    uiState: NoteUiState,
    modifier: Modifier = Modifier,
    onDoubleTap: (() -> Unit)? = null
) {
    val tapModifier = if (onDoubleTap != null) {
        Modifier.pointerInput(onDoubleTap) {
            awaitEachGesture {
                // Initial パスで子（SelectionContainer）より先にイベントを観測
                awaitFirstDown(pass = PointerEventPass.Initial)
                waitForUpOrCancellation(pass = PointerEventPass.Initial) ?: return@awaitEachGesture
                val secondDown = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
                    awaitFirstDown(pass = PointerEventPass.Initial)
                } ?: return@awaitEachGesture
                secondDown.consume()
                waitForUpOrCancellation(pass = PointerEventPass.Initial)?.consume()
                onDoubleTap()
            }
        }
    } else Modifier

    Surface(
        modifier = modifier.fillMaxWidth().then(tapModifier),
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
            Text(text = noteTitle, color = OnSurface, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            MarkdownNoteContent(
                content = noteContent,
                modifier = Modifier.padding(top = 12.dp).weight(1f)
            )
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
            .defaultMinSize(minHeight = 36.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = note.title,
            fontSize = 13.sp,
            lineHeight = 16.sp,
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
