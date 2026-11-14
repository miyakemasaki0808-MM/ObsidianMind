package com.example.newproject.domain

import com.example.newproject.ai.AiAvailability
import com.example.newproject.fakes.FakeAiClient
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI のモードのキーワード一致（絞り込みとフォールバック）は、タイトルのモードと同じく拡張子を照らさない
 * （→ features/ai_picker.md §5）。ファイル名は `.md` を持つので、照らすと `m`・`d`・`md` が全ノートに当たる。
 */
class SearchPickerExtensionTest {

    @Test
    fun `拡張子の文字だけを打っても、一致しないノートを出さない`() = runBlocking {
        val notes = listOf(note("習慣.md"), note("読書.md"), note("旅行.MD"))

        listOf("m", "d", ".", "md", "cmd", ".MD").forEach { query ->
            assertEquals(query, emptyList<String>(), fallback(query, notes).notes.map { it.note.title })
            // Nano が空を返したときのフォールバックも同じ照合を通る。
            assertEquals(query, emptyList<String>(), pick(query, notes, response = "").notes.map { it.note.title })
        }
    }

    @Test
    fun `1文字の絞り込みで、本当にその文字を含むノートを候補から落とさない`() = runBlocking {
        val notes = (1..44).map { note("ノート%02d.md".format(it)) } + note("memo.md")
        val ai = FakeAiClient(onGenerate = { "" })

        SearchPickerUseCase(ai).pick("m", notes)

        val prompt = ai.lastPrompt.orEmpty()
        assertTrue("m を含む唯一のノートが40件の候補から落ちた:\n$prompt", "memo.md" in prompt)
    }

    @Test
    fun `拡張子の文字で、名前の近くないノートを同点にしない`() = runBlocking {
        val notes = listOf(note("cm1.md"), note("cmd.md"))

        assertEquals(listOf("cmd.md", "cm1.md"), fallback("cmd", notes).notes.map { it.note.title })
    }

    private suspend fun fallback(query: String, notes: List<NoteFile>): PickerResult.Success {
        val ai = FakeAiClient(availability = AiAvailability.Unsupported)
        return SearchPickerUseCase(ai).pick(query, notes) as PickerResult.Success
    }

    private suspend fun pick(query: String, notes: List<NoteFile>, response: String): PickerResult.Success =
        SearchPickerUseCase(FakeAiClient(onGenerate = { response })).pick(query, notes) as PickerResult.Success

    private fun note(name: String) = NoteFile(name = name, ref = DocumentRef("doc://$name"))
}
