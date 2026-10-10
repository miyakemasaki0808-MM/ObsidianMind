package com.example.newproject.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.domain.headingBlockIndex
import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.model.RelatedNote
import com.example.newproject.model.state.SideReadingState
import com.example.newproject.model.state.note
import com.example.newproject.ui.markdown.MarkdownNoteContent
import com.example.newproject.ui.markdown.NoteImageLoader
import com.example.newproject.ui.theme.AccentText
import com.example.newproject.ui.theme.ButtonPrimary
import com.example.newproject.ui.theme.OnButtonPrimary
import com.example.newproject.ui.theme.OnSurface
import com.example.newproject.ui.theme.OnSurfaceMuted

/**
 * 並べ読みの右側（→ features/margin_pane.md §5.9）。関連ノートを**眺めるだけ**で、今のノートにはしない。
 *
 * 進捗の報告も見出しの印も置かず、画像の寸法の入れ物も渡さない — 左の読書と、左の画像の測定に右を混ぜない。
 * 「このノートへ移る」は、右で読んでいたブロックを添えて普通に開く。
 */
@Composable
internal fun SideReadingPane(
    /** [SideReadingState.Idle] では呼ばない。 */
    state: SideReadingState,
    /** 読めたノートの本文の解析結果。読める前は null。 */
    blocks: List<MarkdownBlock>?,
    imageLoader: NoteImageLoader?,
    onBack: () -> Unit,
    /** 右で読んでいたブロック [startBlock] から、[RelatedNote] を普通に開く。 */
    onMove: (note: RelatedNote, startBlock: Int) -> Unit,
    onRetry: (RelatedNote) -> Unit,
    /** 本文のリンクから開いたなら、その見出し（→ features/note_links.md）。関連の候補から開いたなら null。 */
    linkStart: SideLinkStart? = null,
    /** リンクの見出しがこのノートに無かった。先頭から始めたことを知らせる。 */
    onHeadingMissing: (heading: String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val note = state.note ?: return
    // **候補ごとに先頭から。** 別の候補を選んだら位置を持ち越さない。画面の作り直しでは保つ。
    val listState = rememberSaveable(note.ref.value, saver = LazyListState.Saver) { LazyListState() }
    // リンクの見出しへ送るのは、読めた後に1度だけ。**送った要求の番号を保存値に置く** — 画面を作り直しても、読み進めた位置を見出しへ戻さない。
    var startedRequest by rememberSaveable { mutableLongStateOf(0L) }
    val currentOnHeadingMissing by rememberUpdatedState(onHeadingMissing)
    val ready = state is SideReadingState.Ready && blocks != null
    LaunchedEffect(linkStart, ready) {
        val start = linkStart ?: return@LaunchedEffect
        if (!ready || start.request == startedRequest) return@LaunchedEffect
        startedRequest = start.request
        val heading = start.heading ?: return@LaunchedEffect
        val block = headingBlockIndex(blocks, heading)
        if (block != null) listState.scrollToItem(block) else currentOnHeadingMissing(heading)
    }
    Column(modifier = modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(
                onClick = onBack,
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                modifier = Modifier.semantics { contentDescription = "余白へ戻る" }
            ) { Text("← 余白へ戻る", color = AccentText, fontSize = 13.sp) }
            Button(
                // 読めていなければ先頭から開く。
                onClick = { onMove(note, if (state is SideReadingState.Ready) listState.firstVisibleItemIndex else 0) },
                colors = ButtonDefaults.buttonColors(containerColor = ButtonPrimary, contentColor = OnButtonPrimary),
                shape = RoundedCornerShape(20.dp)
            ) { Text("このノートへ移る", color = OnButtonPrimary, fontSize = 13.sp) }
        }
        Text(
            text = note.title,
            color = OnSurface,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp)
        )
        when (state) {
            is SideReadingState.Loading -> CircularProgressIndicator(
                modifier = Modifier.padding(top = 16.dp).height(20.dp),
                color = AccentText,
                strokeWidth = 2.dp
            )

            // **失敗は右側だけに出す。** 左の読書と書きかけは止めない。
            is SideReadingState.Failed -> Column(modifier = Modifier.padding(top = 12.dp)) {
                Text(
                    text = "このノートを読み込めませんでした。\n${state.message}",
                    color = OnSurfaceMuted,
                    fontSize = 12.sp
                )
                TextButton(onClick = { onRetry(note) }) { Text("もう一度", color = AccentText, fontSize = 13.sp) }
            }

            // **解析が届くまで本文を描かない。** 描くと描画側の予備の解析が Main で走る（→ NoteContentPanel と同じ）。
            is SideReadingState.Ready -> if (blocks != null) {
                MarkdownNoteContent(
                    content = "",
                    modifier = Modifier.padding(top = 12.dp).weight(1f),
                    listState = listState,
                    precomputedBlocks = blocks,
                    imageLoader = imageLoader,
                    imageMeasurements = null
                )
            }

            SideReadingState.Idle -> Unit
        }
    }
}
