package com.example.newproject

import android.net.Uri
import com.example.newproject.domain.AiRecommendationStatus
import com.example.newproject.domain.RelatedNote

// ---------------------------------------------------------------------------
// 画面状態の定義。ロジックは持たない（NoteViewModel と各 Controller が更新する）。
// ---------------------------------------------------------------------------

sealed class NoteState {
    object Idle : NoteState()
    object Loading : NoteState()
    data class Success(val title: String, val content: String) : NoteState()
    object Empty : NoteState()
    data class Error(val message: String, val id: Long = System.currentTimeMillis()) : NoteState()
}

sealed class SummaryState {
    object Idle : SummaryState()
    object Loading : SummaryState()
    data class Success(val summary: String) : SummaryState()
    // DL進捗: downloaded=-1 は「開始待ち」、total=0 はサイズ不明
    data class Downloading(val downloaded: Long, val total: Long) : SummaryState()
    object AiUnavailable : SummaryState()
    data class Error(val message: String) : SummaryState()
}

sealed class RelatedNotesState {
    object Idle : RelatedNotesState()
    object Loading : RelatedNotesState()
    data class Success(
        val relatedNotes: List<RelatedNote>,
        val aiNotes: List<RelatedNote>,
        val aiStatus: AiRecommendationStatus = AiRecommendationStatus.Ready,
        val aiErrorMessage: String? = null
    ) : RelatedNotesState()
    data class Error(val message: String) : RelatedNotesState()
}

// AIピッカー（さがすタブ）の検索状態。キーワード/ランダム両モードで共有する。
sealed class SearchState {
    object Idle : SearchState()
    object Loading : SearchState()
    data class Success(
        val results: List<RelatedNote>,
        val aiStatus: AiRecommendationStatus = AiRecommendationStatus.Ready
    ) : SearchState()
    data class Error(val message: String) : SearchState()
}

data class QuizCard(
    val question: String,
    val choices: List<String>,
    val correctIndex: Int,
    val explanation: String = ""
)

sealed class QuizState {
    object Idle : QuizState()
    data class Loading(val sourceTitle: String) : QuizState()
    data class Success(
        val sourceTitle: String,
        val cards: List<QuizCard>,
        val isViewed: Boolean = false
    ) : QuizState()
    data class Error(
        val message: String,
        val sourceTitle: String,
        val isViewed: Boolean = false
    ) : QuizState()
}

sealed class AnnotationState {
    object Idle : AnnotationState()
    data class Loading(val sourceTitle: String) : AnnotationState()
    data class Success(
        val sourceTitle: String,
        val savedUri: Uri,
        val fileName: String,
        val content: String,
        val isViewed: Boolean = false
    ) : AnnotationState()
    data class Error(
        val message: String,
        val sourceTitle: String? = null,
        val isViewed: Boolean = false
    ) : AnnotationState()
}

// Snackbar通知の発火判定キー。値が変わったときだけ通知を出し直す。
// nullはIdle（通知対象なし）を表す。
internal fun QuizState.toEventKey(): String? = when (this) {
    is QuizState.Idle -> null
    is QuizState.Loading -> "loading:$sourceTitle"
    is QuizState.Success -> "success:$sourceTitle:${cards.hashCode()}:$isViewed"
    is QuizState.Error -> "error:$sourceTitle:$message:$isViewed"
}

internal fun AnnotationState.toEventKey(): String? = when (this) {
    is AnnotationState.Idle -> null
    is AnnotationState.Loading -> "loading:$sourceTitle"
    is AnnotationState.Success -> "success:$savedUri:$isViewed"
    is AnnotationState.Error -> "error:$sourceTitle:$message:$isViewed"
}

sealed class AnnotationListState {
    object Idle : AnnotationListState()
    object Loading : AnnotationListState()
    data class Success(val files: List<NoteFile>) : AnnotationListState()
    data class Error(val message: String) : AnnotationListState()
}

enum class ChatRole { User, Ai }

data class ChatMessage(val role: ChatRole, val text: String)

// セクション単位のAIチャット。null のときシートは閉じている。
data class SectionChatState(
    val sectionTitle: String,
    val sectionContext: String,     // LLM に渡す本文（表示はしない）
    val summary: String? = null,
    val isSummaryLoading: Boolean = false,
    val suggestions: List<String> = emptyList(),
    val messages: List<ChatMessage> = emptyList(),  // 質問タップによる Q&A ログ
    val isGenerating: Boolean = false,              // 質問の回答生成中
    val error: String? = null
)

data class NoteUiState(
    val vaultSelected: Boolean = false,
    val noteState: NoteState = NoteState.Idle,
    val summaryState: SummaryState = SummaryState.Idle,
    val relatedNotesState: RelatedNotesState = RelatedNotesState.Idle,
    val quizState: QuizState = QuizState.Idle,
    val wikilinkTitles: Set<String> = emptySet(),
    val annotationState: AnnotationState = AnnotationState.Idle,
    val annotationListState: AnnotationListState = AnnotationListState.Idle,
    val sectionChat: SectionChatState? = null,
    // セッションの有無とシート表示を分離する。シートを閉じても同じノート内では
    // AI生成と結果を保持し、吹き出しから再表示できる。
    val isSectionChatSheetVisible: Boolean = false,
    // さがすタブ
    val folders: List<NoteFolder> = emptyList(),
    val selectedFolder: NoteFolder? = null,   // null = ルート直下スコープ
    val searchState: SearchState = SearchState.Idle
)
