package com.example.newproject.model

import com.example.newproject.model.HistoryEntry
import com.example.newproject.model.NoteFolder
import com.example.newproject.model.NotePaperTone
import com.example.newproject.model.state.AnnotationListState
import com.example.newproject.model.state.ReadingTraceBackupState
import com.example.newproject.model.state.ReadingTraceCleanupState
import com.example.newproject.model.state.RemarkState
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

interface RemarkStateWriter {
    val current: RemarkState
    fun update(transform: (RemarkState) -> RemarkState)
}

/**
 * 補記ファイル一覧（Vault単位）。生成側と寿命が違うので Writer を分ける
 * — 一覧はノート切替で消してはいけない（補記管理画面はノートと無関係）。
 */
interface AnnotationListStateWriter {
    val current: AnnotationListState
    fun update(transform: (AnnotationListState) -> AnnotationListState)
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

interface ReadingTraceCleanupStateWriter {
    val current: ReadingTraceCleanupState
    fun set(state: ReadingTraceCleanupState)
}

/**
 * 痕跡の退避（Vault単位）。整理と別のWriterにするのは、**同じ画面から使われても
 * 状態は独立している**ため（書き出しの結果表示が孤児の洗い出しで消えては困る）。
 */
interface ReadingTraceBackupStateWriter {
    val current: ReadingTraceBackupState
    fun set(state: ReadingTraceBackupState)
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

    val remarkWriter: RemarkStateWriter = object : RemarkStateWriter {
        override val current: RemarkState get() = mutableState.value.remarkState
        override fun update(transform: (RemarkState) -> RemarkState) {
            mutableState.update { it.copy(remarkState = transform(it.remarkState)) }
        }
    }

    val annotationListWriter: AnnotationListStateWriter = object : AnnotationListStateWriter {
        override val current: AnnotationListState get() = mutableState.value.annotationListState
        override fun update(transform: (AnnotationListState) -> AnnotationListState) {
            mutableState.update { it.copy(annotationListState = transform(it.annotationListState)) }
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

    val readingTraceCleanupWriter: ReadingTraceCleanupStateWriter =
        object : ReadingTraceCleanupStateWriter {
            override val current: ReadingTraceCleanupState
                get() = mutableState.value.readingTraceCleanupState

            override fun set(state: ReadingTraceCleanupState) {
                mutableState.update { it.copy(readingTraceCleanupState = state) }
            }
        }

    val readingTraceBackupWriter: ReadingTraceBackupStateWriter =
        object : ReadingTraceBackupStateWriter {
            override val current: ReadingTraceBackupState
                get() = mutableState.value.readingTraceBackupState

            override fun set(state: ReadingTraceBackupState) {
                mutableState.update { it.copy(readingTraceBackupState = state) }
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

    /**
     * 紙の地色の段階を決める。**本文を出す前に呼ぶ**（[setNoteState] より先）。
     * 後から呼ぶと、本文が現行のパネル色で1フレーム描かれてから色が変わる。
     */
    fun setNotePaperTone(tone: NotePaperTone) {
        mutableState.update { it.copy(notePaperTone = tone) }
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
    // 紙の地色は前のノートの放置期間で決まっているので、必ず現行のパネル色へ戻す。
    // 残すと、新しいノートを開いた瞬間だけ旧ノートの色で本文が出る。
    notePaperTone = NotePaperTone.Fresh,
    summaryState = SummaryState.Idle,
    relatedNotesState = RelatedNotesState.Idle,
    quizState = QuizState.Idle,
    remarkState = RemarkState.Idle,
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
    todayHistory = emptyList(),
    // 旧Vaultの候補を新Vaultの画面へ残さない。Controller 側ではなく状態変換で落とすのは、
    // 「別Vaultのノートを消しませんか」と尋ねる状態を作らないことを構造的に保証するため
    // （補記一覧は Controller が落とすが、あちらは誤って消す危険が無い）。
    readingTraceCleanupState = ReadingTraceCleanupState.Idle,
    // 退避も同じ理由で落とす。旧Vaultの下見を残すと、**確定を押した瞬間に
    // 別Vaultの痕跡へ書き込む**ことになる（Controller側も下見した時点のVaultと
    // 照合して弾くが、「確定できる状態」を画面に残さないことも状態変換で保証する）。
    readingTraceBackupState = ReadingTraceBackupState.Idle
)
