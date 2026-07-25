package com.example.newproject.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.example.newproject.NoteUiState
import com.example.newproject.domain.markdown.NoteSection

/**
 * 画面に出す常駐Vigilithの、表示・対象・タップ挙動をひとまとめにしたもの。
 *
 * `MainActivity` はこれを1つ受け取るだけでよく、Vigilithの導出は関知しない。
 */
internal class VigilithUiState(
    val presentation: VigilithPresentation,
    val noteAction: VigilithNoteAction?,
    val onTap: (() -> Unit)?,
    val onNoteActionChanged: (VigilithNoteAction?) -> Unit
)

/**
 * Vigilithの表示状態を組み立てる。
 *
 * 判断そのものは純粋関数 [resolveVigilithPresentation] が持ち、本関数は
 * 「Noteタブから届く読書位置の保持」と「タップ時の行き先の決定」だけを担う薄い配線層。
 * 判断ロジックをここへ足すとJVMテストの外へ出てしまうので、増やさないこと。
 */
@Composable
internal fun rememberVigilithState(
    uiState: NoteUiState,
    currentRoute: String?,
    onOpenSection: (NoteSection) -> Unit,
    onShowSectionChat: () -> Unit
): VigilithUiState {
    // Noteタブが現在の読書位置をここへ渡す。
    // キャラクター専用の永続状態ではなく、画面内だけの操作文脈。
    var noteAction by remember { mutableStateOf<VigilithNoteAction?>(null) }

    val presentation = resolveVigilithPresentation(
        currentRoute = currentRoute,
        distillState = uiState.distillState,
        readingTraceCard = uiState.readingTraceCard,
        summaryState = uiState.summaryState,
        isSectionSummaryLoading = uiState.sectionChat?.isSummaryLoading == true,
        isBlockingOverlayVisible = uiState.isSectionChatSheetVisible
    )
    val activeAction = noteAction.takeIf { currentRoute == AppDestination.Note.route }

    // タップ先は「チャットが既にあるか」で変わる。ここを remember で固めると、
    // チャット生成後も古い分岐が残って「押しても何も起きない」既知の型を踏むため、
    // 参照する状態・コールバックは rememberUpdatedState 経由で毎回最新を読む。
    val hasSectionChat = uiState.sectionChat != null
    val currentOpenSection by rememberUpdatedState(onOpenSection)
    val currentShowSectionChat by rememberUpdatedState(onShowSectionChat)
    val onTap = activeAction?.let { action ->
        {
            if (hasSectionChat) currentShowSectionChat() else currentOpenSection(action.section)
        }
    }

    return VigilithUiState(
        presentation = presentation,
        noteAction = activeAction,
        onTap = onTap,
        onNoteActionChanged = { noteAction = it }
    )
}
