package com.example.newproject.ai

import com.example.newproject.model.NoteExcerpt
import com.example.newproject.model.NoteExcerptLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NoteExcerpt を渡す6プロンプトを文字列単位で固定する。
 * 抜粋アルゴリズムを差し替えても、PromptBuilder 自体の既存文面を意図せず変えないための安全網。
 */
class PromptBuilderExcerptRegressionTest {

    private val excerpt = NoteExcerpt("本文", isAbridged = false)

    @Test
    fun `要約プロンプトは移行前の文字列を保つ`() {
        assertEquals(
            """
                You are a note-taking assistant. Summarize the following Obsidian note concisely in 2–4 sentences in the same language as the note content.
                Focus on the key ideas. Do not include phrases like "This note is about" — just write the summary directly.

                Note title: 題名
                Note content:
                本文
            """.trimIndent(),
            PromptBuilder.buildSummarizePrompt("題名", excerpt)
        )
    }

    @Test
    fun `関連ノートプロンプトは移行前の文字列を保つ`() {
        assertEquals(
            """
                You are a note-taking assistant. Find the notes most related to the current Obsidian note.
                Each candidate is listed as "ID | title", optionally followed by "— context".
                Return only the IDs of up to 5 related notes, one ID per line (for example: C01).
                Do not include the title, numbers, bullets, explanations, or any other text.

                Current note title: 題名
                Current note content snippet:
                本文

                Candidates:
                C01 | 候補 — 文脈
            """.trimIndent(),
            PromptBuilder.buildRelatedNotesPrompt(
                currentTitle = "題名",
                currentExcerpt = excerpt,
                candidates = listOf(RelatedCandidateLine("C01", "候補", "文脈"))
            )
        )
    }

    @Test
    fun `セクション要約プロンプトは移行前の文字列を保つ`() {
        assertEquals(
            """
                You are a note-taking assistant. Summarize ONLY the following section of an Obsidian note, concisely in 2–4 sentences, in the same language as the section content.
                Focus on the key ideas of this section. Do not include phrases like "This section is about" — just write the summary directly.

                Section heading: 節
                Section content:
                本文
            """.trimIndent(),
            PromptBuilder.buildSectionSummaryPrompt("節", excerpt)
        )
    }

    @Test
    fun `3プロンプトは抜粋時だけ注意書きを出す`() {
        assertTrue(NoteExcerptLimits.ABRIDGED_NOTICE.contains("when present"))
        val abridgedPrompts = buildAllExcerptPrompts(NoteExcerpt("本文", isAbridged = true))
        val completePrompts = buildAllExcerptPrompts(NoteExcerpt("本文", isAbridged = false))

        assertEquals(3, abridgedPrompts.size)
        abridgedPrompts.forEach { prompt ->
            assertTrue(prompt.contains(NoteExcerptLimits.ABRIDGED_NOTICE_PREFIX + "本文"))
        }
        completePrompts.forEach { prompt ->
            assertFalse(prompt.contains(NoteExcerptLimits.ABRIDGED_NOTICE))
        }
    }

    private fun buildAllExcerptPrompts(value: NoteExcerpt): List<String> = listOf(
        PromptBuilder.buildSummarizePrompt("題名", value),
        PromptBuilder.buildRelatedNotesPrompt(
            currentTitle = "題名",
            currentExcerpt = value,
            candidates = listOf(RelatedCandidateLine("C01", "候補"))
        ),
        PromptBuilder.buildSectionSummaryPrompt("節", value)
    )
}
