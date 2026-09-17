package com.example.newproject.domain

import com.example.newproject.fakes.FakeAiClient
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteFile
import com.example.newproject.model.NoteMeta
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 関連ノートのAI推薦が、**生成の直前で門番を待つ**ことを固定する（→ `docs/dev/system/background_ai_ux.md` §7）。
 *
 * 他の自動生成は `NoteSessionCoordinatorTest` が本番と同じ入口から見るが、
 * 関連ノートのジョブは ViewModel にあって素のJVMで組み立てられないので、ここで見る。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RelatedNotesDwellTest {

    @Test
    fun `AI推薦は門番が開くまで生成を呼ばない`() = runTest {
        val ai = FakeAiClient.returning("C01")
        val dwell = CompletableDeferred<Unit>()
        val useCase = RelatedNotesUseCase(ai, excerptDispatcher = StandardTestDispatcher(testScheduler))

        val result = async {
            useCase.findRelated(
                currentTitle = "読書の問い",
                currentContent = "読書は著者との対話である。",
                allNotes = listOf(NoteFile(name = "読書の問いと対話", ref = DocumentRef("content://candidate"))),
                wikilinkTitles = emptySet(),
                readContent = { "問いを持ち込む。" },
                parseMeta = { NoteMeta() },
                awaitDwell = { dwell.await() }
            )
        }
        runCurrent()

        assertEquals("門番が開く前に Nano を呼んでいる", 0, ai.generateCalls)
        assertFalse(result.isCompleted)

        dwell.complete(Unit)
        runCurrent()

        assertEquals(1, ai.generateCalls)
        val success = result.await() as RelatedNotesResult.Success
        assertEquals(listOf("読書の問いと対話"), success.aiNotes.map { it.title })
    }
}
