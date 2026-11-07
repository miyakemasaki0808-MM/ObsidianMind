package com.example.newproject.domain

import com.example.newproject.fakes.FakeAiClient
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteFile
import com.example.newproject.model.PromptLimits
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI のモードで同じ名前のノートを見分けられること（→ features/ai_picker.md §5）。
 * 結果の行はタイトルのモードと同じ形で場所を添え、プロンプトは同じ名前の候補にだけ場所を添える。
 */
class SearchPickerSameNameTest {

    private val query = "探して"

    @Test
    fun `同じ名前のノートが結果に並んだら、タイトルのモードと同じ形で場所を添える`() = runBlocking {
        val notes = listOf(note("ideas/メモ.md"), note("メモ.md"), note("料理.md"))

        val rows = pick(notes, "P01\nP02\nP03").notes

        assertEquals(listOf("メモ.md", "メモ.md", "料理.md"), rows.map { it.note.title })
        assertEquals(listOf("ideas", "", null), rows.map { it.folderPath })
    }

    @Test
    fun `同じ名前でも結果に1件だけなら場所を添えない`() = runBlocking {
        val notes = listOf(note("ideas/メモ.md"), note("日記/メモ.md"), note("料理.md"))

        val rows = pick(notes, "P01\nP03").notes

        assertEquals(listOf(null, null), rows.map { it.folderPath })
    }

    @Test
    fun `プロンプトでは同じ名前の候補にだけ場所を添える`() = runBlocking {
        val notes = listOf(note("ideas/メモ.md"), note("メモ.md"), note("日記/料理.md"))
        val ai = FakeAiClient(onGenerate = { "" })

        SearchPickerUseCase(ai).pick(query, notes)

        val prompt = ai.lastPrompt.orEmpty()
        assertTrue(prompt, "P01 | メモ.md (in ideas)" in prompt)
        assertTrue(prompt, "P02 | メモ.md (in root)" in prompt)
        assertTrue(prompt, "P03 | 料理.md\n" in prompt || prompt.endsWith("P03 | 料理.md"))
    }

    @Test
    fun `場所を足しても、予算で提示から落ちる候補は増えない`() = runBlocking {
        // 場所なしでちょうど予算に収まる10行にする。場所を先に足すと、10行目が押し出される。
        val long = PromptLimits.LABEL_CHARACTERS
        val lines = listOf(note("a/" + "D".repeat(long)), note("b/" + "D".repeat(long))) +
            ('E'..'K').map { note(it.toString().repeat(long)) }
        val used = lines.size * ("P01 | ".length + long) + (lines.size - 1)
        val lastTitle = "L".repeat(PromptLimits.PICKER_CANDIDATES_CHARACTERS - used - 1 - "P10 | ".length)
        val ai = FakeAiClient(onGenerate = { "" })

        SearchPickerUseCase(ai).pick(query, lines + note(lastTitle))

        val prompt = ai.lastPrompt.orEmpty()
        assertTrue("場所を足したせいで10行目が提示から落ちた", "P10 | $lastTitle" in prompt)
        assertFalse("予算を超えて場所を足した", "(in a)" in prompt)
    }

    private suspend fun pick(notes: List<NoteFile>, response: String): PickerResult.Success =
        SearchPickerUseCase(FakeAiClient(onGenerate = { response })).pick(query, notes) as PickerResult.Success

    private fun note(path: String) =
        NoteFile(name = path.substringAfterLast('/'), ref = DocumentRef("doc://$path"), vaultRelativePath = path)
}
