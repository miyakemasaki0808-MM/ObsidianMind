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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.NoteState
import com.example.newproject.NoteUiState
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
                        text = "ランダムAIノート",
                        color = OnVibrant,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (uiState.vaultSelected) "Vaultフォルダ選択済み" else "Vaultフォルダが未選択です",
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
                ) { Text("Vaultを選択", color = OnVibrant) }
                Button(
                    onClick = onRandomNote,
                    enabled = !isLoading,
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ButtonPrimary),
                    shape = RoundedCornerShape(24.dp)
                ) { Text("ランダム表示", color = OnVibrant) }
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
                    .background(ReadingGradient)
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
