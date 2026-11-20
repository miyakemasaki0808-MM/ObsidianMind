package com.example.newproject

import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteFolder
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.NotePaperTone
import com.example.newproject.model.NoteUiStateStore
import com.example.newproject.model.RelatedNote
import com.example.newproject.model.SearchSlice
import com.example.newproject.model.SectionRef
import com.example.newproject.model.state.DistillState
import com.example.newproject.model.state.NoteState
import com.example.newproject.model.state.ReadingTraceCard
import com.example.newproject.model.state.RebuttalSentence
import com.example.newproject.model.state.SearchState
import com.example.newproject.model.state.TitleSearchState
import com.example.newproject.model.state.SectionChatState
import com.example.newproject.model.state.SectionSummary
import com.example.newproject.model.state.SummaryState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
            searchState = SearchState.Loading,
            titleSearch = TitleSearchState.Scanning
        )
        store.searchWriter.update { searchSlice }
        expected = expected.copy(
            folders = searchSlice.folders,
            selectedFolder = searchSlice.selectedFolder,
            foldersError = searchSlice.foldersError,
            searchState = searchSlice.searchState,
            titleSearch = searchSlice.titleSearch
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

        val rebuttal = RebuttalSentence(
            text = "食い違う一文。",
            source = RelatedNote(title = "別のノート", ref = DocumentRef("content://vault/other.md"), isWikilinked = false)
        )
        store.rebuttalWriter.update { rebuttal }
        expected = expected.copy(rebuttalSentence = rebuttal)
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

    /** ノートを開くたびに表示の番号を進める。A→B→A でも番号が違うので、画面の一時的な状態（節の開閉）が前の表示のものだと見分けられる。 */
    @Test
    fun `ノートの読込を始めるたびに表示の番号が進み、本文の差し替えでは進まない`() {
        val store = NoteUiStateStore()
        store.beginNoteLoad()
        store.setNoteState(NoteState.Success(title = "A", content = "本文", targetUri = "ref:A"))
        val first = store.value.noteLoadId

        store.applyReloadedBody("ref:A", NoteState.Success(title = "A", content = "太字の本文", targetUri = "ref:A"))
        assertEquals("蒸留の差し替えは同じ表示", first, store.value.noteLoadId)

        store.beginNoteLoad()
        store.beginNoteLoad()
        assertEquals(first.copy(count = first.count + 2), store.value.noteLoadId)
    }

    /**
     * 番号は発行した Store の印を持つ。プロセスを作り直すと Store も作り直されて数え直すので、
     * 印が同じだと画面の保存値に残った前のプロセスの番号と一致する（→ features/backlinks.md §6）。
     */
    @Test
    fun `作り直した Store は前の Store と違う印で番号を出す`() {
        val issued = List(2) { NoteUiStateStore().apply { beginNoteLoad() }.value.noteLoadId }

        assertEquals(issued[0].count, issued[1].count)
        assertNotEquals(issued[0].issuer, issued[1].issuer)
        assertEquals("印は与えたものを使う", 9L, NoteUiStateStore(loadIssuer = 9L).apply { beginNoteLoad() }.value.noteLoadId.issuer)
    }
}
