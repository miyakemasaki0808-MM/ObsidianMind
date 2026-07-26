package com.example.newproject

import com.example.newproject.data.NoteFolder
import com.example.newproject.model.AnnotationSlice
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.NoteUiStateStore
import com.example.newproject.model.SearchSlice
import com.example.newproject.model.SectionChatSlice
import com.example.newproject.model.state.AnnotationListState
import com.example.newproject.model.state.AnnotationState
import com.example.newproject.model.state.DistillState
import com.example.newproject.model.state.NoteState
import com.example.newproject.model.state.QuizState
import com.example.newproject.model.state.ReadingTraceCard
import com.example.newproject.model.state.SearchState
import com.example.newproject.model.state.SectionChatState
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

        store.quizWriter.update { QuizState.Loading("クイズ対象") }
        expected = expected.copy(quizState = QuizState.Loading("クイズ対象"))
        assertEquals(expected, store.value)

        val annotationSlice = AnnotationSlice(
            annotationState = AnnotationState.Error("生成失敗"),
            annotationListState = AnnotationListState.Error("一覧失敗")
        )
        store.annotationWriter.update { annotationSlice }
        expected = expected.copy(
            annotationState = annotationSlice.annotationState,
            annotationListState = annotationSlice.annotationListState
        )
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

        val chat = SectionChatState("節", "本文")
        val chatSlice = SectionChatSlice(chat, isSectionChatSheetVisible = true)
        store.sectionChatWriter.update { chatSlice }
        expected = expected.copy(sectionChat = chat, isSectionChatSheetVisible = true)
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
                quizState = QuizState.Loading("旧ノート")
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
        assertTrue(emissions.single().quizState is QuizState.Idle)
        collectJob.cancel()
    }
}
