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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

interface SummaryStateWriter {
    val current: SummaryState
    fun update(transform: (SummaryState) -> SummaryState)
}

interface QuizStateWriter {
    val current: QuizState
    fun update(transform: (QuizState) -> QuizState)
}

data class AnnotationSlice(
    val annotationState: AnnotationState,
    val annotationListState: AnnotationListState
)

interface AnnotationStateWriter {
    val current: AnnotationSlice
    fun update(transform: (AnnotationSlice) -> AnnotationSlice)
}

data class SearchSlice(
    val folders: List<NoteFolder>,
    val selectedFolder: NoteFolder?,
    val foldersError: String?,
    val searchState: SearchState
)

interface SearchStateWriter {
    val current: SearchSlice
    fun update(transform: (SearchSlice) -> SearchSlice)
}

interface DistillStateWriter {
    val current: DistillState
    fun update(transform: (DistillState) -> DistillState)
}

data class SectionChatSlice(
    val sectionChat: SectionChatState?,
    val isSectionChatSheetVisible: Boolean
)

interface SectionChatStateWriter {
    val current: SectionChatSlice
    fun update(transform: (SectionChatSlice) -> SectionChatSlice)
}

interface ReadingTraceStateWriter {
    val current: ReadingTraceCard?
    fun update(transform: (ReadingTraceCard?) -> ReadingTraceCard?)
}

/**
 * 画面全体の [NoteUiState] を所有し、各 Controller には担当スライスだけを書ける
 * Writer を渡す。Controller が担当外フィールドを更新する経路を型で閉じる。
 */
internal class NoteUiStateStore(initialState: NoteUiState = NoteUiState()) {
    private val mutableState = MutableStateFlow(initialState)
    val uiState: StateFlow<NoteUiState> = mutableState.asStateFlow()
    val value: NoteUiState get() = mutableState.value

    val summaryWriter: SummaryStateWriter = object : SummaryStateWriter {
        override val current: SummaryState get() = mutableState.value.summaryState
        override fun update(transform: (SummaryState) -> SummaryState) {
            mutableState.update { it.copy(summaryState = transform(it.summaryState)) }
        }
    }

    val quizWriter: QuizStateWriter = object : QuizStateWriter {
        override val current: QuizState get() = mutableState.value.quizState
        override fun update(transform: (QuizState) -> QuizState) {
            mutableState.update { it.copy(quizState = transform(it.quizState)) }
        }
    }

    val annotationWriter: AnnotationStateWriter = object : AnnotationStateWriter {
        override val current: AnnotationSlice
            get() = mutableState.value.let {
                AnnotationSlice(it.annotationState, it.annotationListState)
            }

        override fun update(transform: (AnnotationSlice) -> AnnotationSlice) {
            mutableState.update { state ->
                val next = transform(
                    AnnotationSlice(state.annotationState, state.annotationListState)
                )
                state.copy(
                    annotationState = next.annotationState,
                    annotationListState = next.annotationListState
                )
            }
        }
    }

    val searchWriter: SearchStateWriter = object : SearchStateWriter {
        override val current: SearchSlice
            get() = mutableState.value.let {
                SearchSlice(it.folders, it.selectedFolder, it.foldersError, it.searchState)
            }

        override fun update(transform: (SearchSlice) -> SearchSlice) {
            mutableState.update { state ->
                val next = transform(
                    SearchSlice(
                        state.folders,
                        state.selectedFolder,
                        state.foldersError,
                        state.searchState
                    )
                )
                state.copy(
                    folders = next.folders,
                    selectedFolder = next.selectedFolder,
                    foldersError = next.foldersError,
                    searchState = next.searchState
                )
            }
        }
    }

    val distillWriter: DistillStateWriter = object : DistillStateWriter {
        override val current: DistillState get() = mutableState.value.distillState
        override fun update(transform: (DistillState) -> DistillState) {
            mutableState.update { it.copy(distillState = transform(it.distillState)) }
        }
    }

    val sectionChatWriter: SectionChatStateWriter = object : SectionChatStateWriter {
        override val current: SectionChatSlice
            get() = mutableState.value.let {
                SectionChatSlice(it.sectionChat, it.isSectionChatSheetVisible)
            }

        override fun update(transform: (SectionChatSlice) -> SectionChatSlice) {
            mutableState.update { state ->
                val next = transform(
                    SectionChatSlice(state.sectionChat, state.isSectionChatSheetVisible)
                )
                state.copy(
                    sectionChat = next.sectionChat,
                    isSectionChatSheetVisible = next.isSectionChatSheetVisible
                )
            }
        }
    }

    val readingTraceWriter: ReadingTraceStateWriter = object : ReadingTraceStateWriter {
        override val current: ReadingTraceCard? get() = mutableState.value.readingTraceCard
        override fun update(transform: (ReadingTraceCard?) -> ReadingTraceCard?) {
            mutableState.update { it.copy(readingTraceCard = transform(it.readingTraceCard)) }
        }
    }

    fun restoreVault(todayHistory: List<HistoryEntry>) {
        mutableState.update { it.copy(vaultSelected = true, todayHistory = todayHistory) }
    }

    fun beginNoteLoad() {
        mutableState.update { it.withNoteScopedReset().copy(noteState = NoteState.Loading) }
    }

    /**
     * Vault切替時の一斉初期化。ノート単位の状態に加え、さがすタブのスコープと
     * 当日履歴も破棄する。`noteState` と `wikilinkTitles` は表示の点滅を避けるため残す。
     */
    fun resetVaultScoped() {
        mutableState.update { it.withNoteScopedReset().withVaultScopedReset() }
    }

    fun setNoteState(state: NoteState) {
        mutableState.update { it.copy(noteState = state) }
    }

    fun currentNote(): NoteState.Success? = mutableState.value.noteState as? NoteState.Success

    fun setTodayHistory(history: List<HistoryEntry>) {
        mutableState.update { it.copy(todayHistory = history) }
    }

    fun setRelatedNotesState(state: RelatedNotesState) {
        mutableState.update { it.copy(relatedNotesState = state) }
    }

    fun setWikilinkTitles(titles: Set<String>) {
        mutableState.update { it.copy(wikilinkTitles = titles) }
    }

    fun hasSectionChat(): Boolean = mutableState.value.sectionChat != null

    fun applyReloadedBody(targetUri: String, loaded: NoteState.Success): Boolean {
        while (true) {
            val current = mutableState.value
            val active = current.noteState as? NoteState.Success ?: return false
            if (active.targetUri != targetUri) return false
            if (mutableState.compareAndSet(current, current.withDistillBodyReloaded(loaded))) {
                return true
            }
        }
    }
}

/**
 * ノートを開き直す・Vaultを切り替える際に、ノート単位の状態をまとめて初期化する。
 * [NoteUiStateStore.beginNoteLoad] と [NoteUiStateStore.resetVaultScoped] が共有する
 * 唯一の登録点で、前者では `Loading` への遷移まで1回の更新にまとめる。
 *
 * **ノート単位の状態を [NoteUiState] へ足したら、ここへ必ず登録する。**
 * 対になるジョブ停止側の契約は
 * [com.example.newproject.controller.NoteSessionCoordinator.cancelNoteScopedJobs]。
 */
private fun NoteUiState.withNoteScopedReset(): NoteUiState = copy(
    summaryState = SummaryState.Idle,
    relatedNotesState = RelatedNotesState.Idle,
    quizState = QuizState.Idle,
    annotationState = AnnotationState.Idle,
    sectionChat = null,
    isSectionChatSheetVisible = false,
    // ここで必ず消えることが「カードは Rediscover でしか出ない」の担保。
    readingTraceCard = null
)

private fun NoteUiState.withVaultScopedReset(): NoteUiState = copy(
    vaultSelected = true,
    folders = emptyList(),
    selectedFolder = null,
    foldersError = null,
    searchState = SearchState.Idle,
    todayHistory = emptyList()
)
