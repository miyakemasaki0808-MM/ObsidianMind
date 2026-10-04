package com.example.newproject

import com.example.newproject.model.NoteFolder
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.NotePaperTone
import com.example.newproject.model.NoteUiStateStore
import com.example.newproject.model.SearchSlice
import com.example.newproject.model.SectionRef
import com.example.newproject.model.state.DistillState
import com.example.newproject.model.state.NoteState
import com.example.newproject.model.state.ReadingTraceCard
import com.example.newproject.model.state.SearchState
import com.example.newproject.model.state.SectionChatState
import com.example.newproject.model.state.SectionSummary
import com.example.newproject.model.state.SummaryState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NoteUiStateStoreTest {

    @Test
    fun `各Writerは担当スライスだけを更新する`() {
        val store = NoteUiStateStore()
        var expected = NoteUiState()

        store.summaryWriter.update { SummaryState.Success("要約") }
        expected = expected.copy(summaryState = SummaryState.Success("要約"))
        assertEquals(expected, store.value)

        val folder = NoteFolder("下書き", "folder-id")
        val searchSlice = SearchSlice(
            folders = listOf(folder),
            selectedFolder = folder,
            foldersError = "列挙失敗",
            searchState = SearchState.Loading
        )
        store.searchWriter.update { searchSlice }
        expected = expected.copy(
            folders = searchSlice.folders,
            selectedFolder = searchSlice.selectedFolder,
            foldersError = searchSlice.foldersError,
            searchState = searchSlice.searchState
        )
        assertEquals(expected, store.value)

        store.distillWriter.update { DistillState.Analyzing("蒸留対象") }
        expected = expected.copy(distillState = DistillState.Analyzing("蒸留対象"))
        assertEquals(expected, store.value)

        val chat = SectionChatState(listOf(SectionSummary(SectionRef("節"), requestId = 1L, sectionTitle = "節", sectionContext = "本文")))
        store.sectionChatWriter.update { chat }
        expected = expected.copy(sectionChat = chat)
        assertEquals(expected, store.value)

        val card = ReadingTraceCard(
            visitCount = 2,
            lastVisitAtMillis = 100L,
            lastSectionTitle = "節",
            lastProgressPercent = 50
        )
        store.readingTraceWriter.update { card }
        expected = expected.copy(readingTraceCard = card)
        assertEquals(expected, store.value)
    }

    @Test
    fun `ノート読込開始はリセット済みLoadingを一度だけ通知する`() = runTest {
        val store = NoteUiStateStore(
            NoteUiState(
                summaryState = SummaryState.Success("旧要約"),
                notePaperTone = NotePaperTone.Weathered
            )
        )
        val emissions = mutableListOf<NoteUiState>()
        val collectJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            store.uiState.drop(1).collect(emissions::add)
        }

        store.beginNoteLoad()

        assertEquals(1, emissions.size)
        assertTrue(emissions.single().noteState is NoteState.Loading)
        assertTrue(emissions.single().summaryState is SummaryState.Idle)
        // 紙の地色は前のノートの放置期間で決まっているので、必ず現行のパネル色へ戻る。
        // 残ると、新しいノートを開いた瞬間だけ旧ノートの色で本文が出る。
        assertEquals(NotePaperTone.Fresh, emissions.single().notePaperTone)
        collectJob.cancel()
    }
}
