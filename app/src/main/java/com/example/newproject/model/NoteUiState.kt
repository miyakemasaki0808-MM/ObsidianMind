package com.example.newproject.model

import com.example.newproject.data.HistoryEntry
import com.example.newproject.data.NoteFolder
import com.example.newproject.model.state.AnnotationListState
import com.example.newproject.model.state.AnnotationState
import com.example.newproject.model.state.DistillState
import com.example.newproject.model.state.NoteState
import com.example.newproject.model.state.QuizState
import com.example.newproject.model.state.ReadingTraceCard
import com.example.newproject.model.state.RelatedNotesState
import com.example.newproject.model.state.SearchState
import com.example.newproject.model.state.SectionChatState
import com.example.newproject.model.state.SummaryState

data class NoteUiState(
    val vaultSelected: Boolean = false,
    val noteState: NoteState = NoteState.Idle,
    val summaryState: SummaryState = SummaryState.Idle,
    val relatedNotesState: RelatedNotesState = RelatedNotesState.Idle,
    val quizState: QuizState = QuizState.Idle,
    val wikilinkTitles: Set<String> = emptySet(),
    val annotationState: AnnotationState = AnnotationState.Idle,
    val distillState: DistillState = DistillState.Idle,
    val annotationListState: AnnotationListState = AnnotationListState.Idle,
    val sectionChat: SectionChatState? = null,
    // セッションの有無とシート表示を分離する。シートを閉じても同じノート内では
    // AI生成と結果を保持し、吹き出しから再表示できる。
    val isSectionChatSheetVisible: Boolean = false,
    // Rediscover で引いた時だけ入る「前回のあなた」カード
    val readingTraceCard: ReadingTraceCard? = null,
    // さがすタブ
    val folders: List<NoteFolder> = emptyList(),
    val selectedFolder: NoteFolder? = null,   // null = ルート直下スコープ
    // フォルダ列挙に失敗したときだけ入る。ルート直下スコープは使えるので致命的ではないが、
    // 黙って chips が出ないと「フォルダが無い」のか「取れなかった」のか区別できない。
    // Vault単位の状態なので、リセットは NoteUiStateStore.resetVaultScoped が持つ。
    val foldersError: String? = null,
    val searchState: SearchState = SearchState.Idle,
    // 当日分のみの閲覧履歴（`NoteHistoryStore` が日付判定を担当）
    val todayHistory: List<HistoryEntry> = emptyList()
)

/**
 * 蒸留は意味を変えずMarkdown装飾だけを更新するため、ノート全体から得たAI結果は維持する。
 * 一方、生Markdownのセクション本文に結び付くチャットとクイズは照合不能になるため破棄する。
 *
 * 再会カード（[NoteUiState.readingTraceCard]）も維持する。同じノートのままで、痕跡は
 * vault相対パスをキーにしているため有効なまま（維持しないと Rediscover→蒸留保存で
 * カードだけが消える）。
 */
internal fun NoteUiState.withDistillBodyReloaded(loaded: NoteState.Success): NoteUiState = copy(
    noteState = loaded,
    quizState = QuizState.Idle,
    sectionChat = null,
    isSectionChatSheetVisible = false
)
