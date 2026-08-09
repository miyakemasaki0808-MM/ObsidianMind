package com.example.newproject.ai

import com.example.newproject.model.NoteExcerpt
import com.example.newproject.model.REMARK_NONE_TOKEN
import com.example.newproject.model.NoteExcerptLimits
import com.example.newproject.model.state.QuizFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NoteExcerpt 導入時点の7プロンプトを文字列単位で固定する。
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
    fun `クイズプロンプトは移行前の文字列を保つ`() {
        assertEquals(
            listOf(
                "            You are a study assistant. Read the following excerpt from an Obsidian note and create a compact quiz that helps the user recall its key ideas.",
                "            Answer in the same language as the excerpt content.",
                "            Use only information supported by the excerpt. Return only the requested fields, with a blank line between questions.",
                "",
                "            Generate exactly 2 true-or-false statements about what the excerpt says.",
                "Keep each statement within 50 characters when writing Japanese, or 20 words otherwise.",
                "Do not add explanations or choices. Use exactly this format:",
                "Q: <statement>",
                "ANSWER: <TRUE or FALSE>",
                "",
                "            Source: 題名",
                "            --- BEGIN EXCERPT ---",
                "            本文",
                "            --- END EXCERPT ---"
            ).joinToString("\n"),
            PromptBuilder.buildQuizPrompt("題名", excerpt, QuizFormat.TrueFalse)
        )
    }

    @Test
    fun `ひとことプロンプトは1文だけを要求しIDで参照させる`() {
        val prompt = PromptBuilder.buildRemarkPrompt(
            title = "題名",
            excerpt = excerpt,
            candidates = listOf(RemarkCandidateLine("C01", "候補ノート"))
        )

        // 出力枠はゼロサムなので、分類ラベルを1つでも足すと1文の余地が削られる。
        // 旧補記の4項目が復活していないことを固定する。
        listOf("粒度評価", "種別", "補記方針", "補記すべき内容").forEach { removed ->
            assertFalse("旧補記の項目が復活している: $removed", prompt.contains(removed))
        }
        assertTrue(prompt.contains("One sentence. Two at most."))
        // **出力は日本語で固定する。** 本文の言語に従わせると、ソースコードだけの
        // ノートで英語の問いが返る（2026-08-09 実機）。ひとことはノートを写す文ではなく
        // アプリがユーザーへ話しかける文なので、従うべきは読み手の言語。
        assertTrue(prompt.contains("Write in Japanese"))
        // 二人称で名指しさせない。指示を戻すと採点者の口調になる。
        assertTrue(prompt.contains("Do NOT start with or use 「あなた」"))
        assertFalse(
            "二人称で呼ばせる指示が残っている",
            prompt.contains("address the user as 「あなた」")
        )
        assertFalse(
            "本文の言語に従わせる指示が残っている",
            prompt.contains("same language as the note content")
        )
        // 生タイトルではなくIDを返させる契約（蒸留・関連ノートと同じ）。
        assertTrue(prompt.contains("[[C03]]"))
        assertTrue(prompt.contains("C01 | 候補ノート"))
        // 「出すものが無い」の表明語は model の定数と一致していること
        // （ずれると NONE を検査が拾えず、定型文がそのまま保存される）。
        assertTrue(prompt.contains(REMARK_NONE_TOKEN))
        assertTrue(prompt.contains("本文"))
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
    fun `セクション提案プロンプトは移行前の文字列を保つ`() {
        assertEquals(
            """
                You are a note-taking assistant. Based ONLY on the following section, propose up to 3 short questions a reader might want to ask about this section.
                Answer in the same language as the section content.
                Return only the questions, one per line. Do not add numbers, bullets, or extra text.

                Section heading: 節
                Section content:
                本文
            """.trimIndent(),
            PromptBuilder.buildSectionSuggestionsPrompt("節", excerpt)
        )
    }

    @Test
    fun `セクションチャットプロンプトは移行前の文字列を保つ`() {
        assertEquals(
            listOf(
                "            You are a note-taking assistant answering questions about ONE section of an Obsidian note.",
                "            Answer using ONLY the information in the section below. If the answer is not contained in this section, reply that it is not written in this section (\"このセクションには記載がありません\").",
                "            Answer concisely in the same language as the user's question, not the language of the section. Do not invent facts.",
                "",
                "            Section heading: 節",
                "            Section content:",
                "            本文",
                "",
                "            Conversation so far:",
                "            User: 前の質問",
                "AI: 前の回答",
                "",
                "            New question:",
                "            新しい質問"
            ).joinToString("\n"),
            PromptBuilder.buildSectionChatPrompt(
                sectionTitle = "節",
                sectionExcerpt = excerpt,
                history = listOf("User" to "前の質問", "AI" to "前の回答"),
                question = "新しい質問"
            )
        )
    }

    @Test
    fun `7プロンプトは抜粋時だけ注意書きを出す`() {
        assertTrue(NoteExcerptLimits.ABRIDGED_NOTICE.contains("when present"))
        val abridgedPrompts = buildAllExcerptPrompts(NoteExcerpt("本文", isAbridged = true))
        val completePrompts = buildAllExcerptPrompts(NoteExcerpt("本文", isAbridged = false))

        assertEquals(7, abridgedPrompts.size)
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
        PromptBuilder.buildQuizPrompt("題名", value, QuizFormat.TrueFalse),
        PromptBuilder.buildRemarkPrompt(
            title = "題名",
            excerpt = value,
            candidates = listOf(RemarkCandidateLine("C01", "候補"))
        ),
        PromptBuilder.buildSectionSummaryPrompt("節", value),
        PromptBuilder.buildSectionSuggestionsPrompt("節", value),
        PromptBuilder.buildSectionChatPrompt(
            sectionTitle = "節",
            sectionExcerpt = value,
            history = emptyList(),
            question = "質問"
        )
    )
}
