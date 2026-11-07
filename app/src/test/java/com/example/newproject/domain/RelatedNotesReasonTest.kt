package com.example.newproject.domain

import com.example.newproject.data.NoteRepository
import com.example.newproject.fakes.FakeAiClient
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteFile
import com.example.newproject.model.NoteMeta
import com.example.newproject.model.RelatedReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 関連ノートのAI推薦の行に、再ランクが既に計算した根拠を1つ通すこと（→ features/related_notes_ai.md §5）。
 * 読み直しも生成も足さず、決定的な関連には付けない。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RelatedNotesReasonTest {

    @Test
    fun `AI推薦の行にだけ根拠を通し、弱い根拠しか無い行と決定的な関連には付けない`() = runTest {
        val contents = mapOf(
            "content://cooking" to "#読書\n献立を考える",
            "content://diary" to "天気がよかった"
        )
        var reads = 0
        val success = RelatedNotesUseCase(FakeAiClient.returning("C01\nC02"), excerptDispatcher = StandardTestDispatcher(testScheduler))
            .findRelated(
                currentTitle = "本の記録",
                currentContent = "#読書\n本を読む",
                allNotes = listOf(
                    NoteFile(name = "料理メモ", ref = DocumentRef("content://cooking")),
                    NoteFile(name = "日記", ref = DocumentRef("content://diary")),
                    NoteFile(name = "リンク先", ref = DocumentRef("content://linked"))
                ),
                wikilinkTitles = setOf("リンク先"),
                readContent = { ref -> reads++; contents.getValue(ref.value) },
                parseMeta = { content -> NoteMeta(tags = if ("#読書" in content) listOf("#読書") else emptyList()) },
                awaitDwell = {}
            ) as RelatedNotesResult.Success

        assertEquals(
            listOf("料理メモ" to RelatedReason.SharedTag("読書"), "日記" to null),
            success.aiNotes.map { it.title to it.reason }
        )
        assertEquals(listOf("リンク先" to null), success.relatedNotes.map { it.title to it.reason })
        assertEquals("根拠のために読み直した", 2, reads)
    }

    // 本番の frontmatter の解析から根拠までを通す。手で作ったタグの一覧だけでは、解析が混ぜる構文の文字を見落とす。
    @Test
    fun `frontmatter のコメントや引用符を共通タグの根拠にしない`() = runTest {
        val current = "---\ntags:\n  - 読書\n  # タグは随時整理\n---\n本を読む"
        val commentOnly = "---\ntags:\n  - 料理\n  # タグは随時整理\n---\n献立を考える"
        assertEquals(null, reasonFor(current, commentOnly))

        val quoted = "---\ntags: [\"読書\"]\n---\n献立を考える"
        assertEquals(RelatedReason.SharedTag("読書"), reasonFor("---\ntags: [\"読書\"]\n---\n本を読む", quoted))
    }

    private suspend fun kotlinx.coroutines.test.TestScope.reasonFor(current: String, candidate: String): RelatedReason? {
        val repository = NoteRepository()
        val success = RelatedNotesUseCase(FakeAiClient.returning("C01"), excerptDispatcher = StandardTestDispatcher(testScheduler))
            .findRelated(
                currentTitle = "本の記録",
                currentContent = current,
                allNotes = listOf(NoteFile(name = "料理メモ", ref = DocumentRef("content://cooking"))),
                wikilinkTitles = emptySet(),
                readContent = { candidate },
                parseMeta = repository::parseMeta,
                awaitDwell = {}
            ) as RelatedNotesResult.Success
        return success.aiNotes.single().reason
    }
}
