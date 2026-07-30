package com.example.newproject.ui.component

import com.example.newproject.model.ReadingTrace
import com.example.newproject.ui.quantizeReadingFraction
import com.example.newproject.ui.READING_FRACTION_STEPS
import com.example.newproject.ui.screen.FullscreenNoteScreen
import com.example.newproject.ui.screen.NoteReaderTab
import com.example.newproject.ui.visibleFractionOfBlock
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.model.state.NoteState
import com.example.newproject.model.NoteUiState
import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.ui.markdown.MarkdownNoteContent
import com.example.newproject.domain.markdown.NoteSectionModel
import com.example.newproject.ui.theme.AccentGlass
import com.example.newproject.ui.theme.OnSurface
import com.example.newproject.ui.theme.OnVibrant
import com.example.newproject.ui.theme.Panel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull

// ---------------------------------------------------------------------------
// ノート表示の共用部品。
// タブ（[NoteReaderTab]）と全画面（[FullscreenNoteScreen]）の両方から使うものだけを置く。
// 片方でしか使わないものはそれぞれのファイルに private で置く。
// ---------------------------------------------------------------------------

/**
 * 読書痕跡（ReadingTrace）へ「どこまで読んだか」を報告する。
 * 通常タブと全画面は別の [LazyListState] を持つため、両方から呼ぶ。
 *
 * 先頭可視ではなく**最終可視**ブロックを見るのは、先頭基準だと1画面に収まる分だけ
 * 末尾に届かず「読み切った」を表現できないため。
 *
 * ブロックの index だけでなく、そのブロックがどこまで見えているか（可視割合）も送る。
 * 長大な段落・コードブロックは1ブロックとして描画されるため、index だけでは冒頭しか
 * 見ていなくても最終ブロック＝到達率100%になってしまう（→ [visibleFractionOfBlock]）。
 */
@Composable
internal fun ReadingProgressReporter(
    sectionModel: NoteSectionModel?,
    listState: LazyListState,
    onReadingProgress: (blockIndex: Int, blockFraction: Float, totalBlocks: Int, sectionTitle: String?) -> Unit
) {
    // 長寿命ブロックから外部のラムダを呼ぶので rememberUpdatedState を通す
    // （pointerInput/LaunchedEffect が初回のクロージャを固定する問題への定石）。
    val report by rememberUpdatedState(onReadingProgress)
    LaunchedEffect(sectionModel) {
        val model = sectionModel ?: return@LaunchedEffect
        snapshotFlow {
            val layout = listState.layoutInfo
            val last = layout.visibleItemsInfo.lastOrNull() ?: return@snapshotFlow null
            val fraction = visibleFractionOfBlock(
                itemOffset = last.offset,
                itemSize = last.size,
                // 下端の contentPadding は本文が表示されない余白なので実表示域から外す。
                viewportEndOffset = layout.viewportEndOffset - layout.afterContentPadding
            )
            // 割合はスクロール中ずっと変わり続けるので、粗い段階へ落としてから
            // distinctUntilChanged に掛け、報告を必要な回数だけに絞る。
            last.index to quantizeReadingFraction(fraction)
        }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { (index, step) ->
                report(
                    index,
                    step.toFloat() / READING_FRACTION_STEPS,
                    model.blocks.size,
                    model.sectionForBlockIndex(index)?.title
                )
            }
    }
}

/**
 * 丸いアイコンボタン（material-icons 依存を避けるため絵文字/記号を使用）。
 * 既定色はグラデーション背景に置く前提。明色パネルの上に置く場合は
 * containerColor で暗めの下地を指定しないと視認できない。
 *
 * 補記管理画面にも同等の実装が別途あったが、そちらは contentDescription を
 * Semantics へ設定しておらずTalkBackで読み上げられなかったため、本関数へ統合した。
 */
@Composable
internal fun IconPill(
    symbol: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    containerColor: Color = AccentGlass,
    symbolSize: TextUnit = 18.sp,
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
            Text(symbol, color = OnVibrant, fontSize = symbolSize, fontWeight = FontWeight.Bold)
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
