package com.example.newproject.ai

import com.example.newproject.model.NoteExcerpt
import com.example.newproject.model.state.QuizFormat
import org.junit.Assert.assertEquals
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
    fun `補記プロンプトは移行前の文字列を保つ`() {
        assertEquals(
            """
                You are an editor and reader for a private Obsidian vault. You are not the author.
                Create an annotation memo for the current note so the user can grow the note later.
                Respect the source note. Do not rewrite or replace it. Write suggestions, not final truth.
                Use the same language as the note content.

                Choose values only from these fixed choices:
                種別: 概念メモ / 読書メモ / 日記・ログ / アイデア断片 / 技術メモ / タスク・計画 / 長文記事 / その他
                粒度: 断片 / 原子メモ / 中粒度 / 長文
                状態: 十分 / 書きかけ / 具体例不足 / 背景不足 / 自分の解釈不足 / 次アクション不足 / 論点過多
                補記方針: 具体例を足す / 背景を補う / 自分の解釈を書く / 構成を整理する / 反論・別視点を足す

                Output Markdown only. Include exactly these sections and headings:
                ## 粒度評価
                - 種別: <one fixed choice>
                - 粒度: <one fixed choice>
                - 状態: <one fixed choice>
                - 補記方針: <one fixed choice>

                ## 補記すべき内容
                Exactly 3 bullets, one line each. Each MUST reference a specific concept, claim, or term that actually appears in this note — no generic advice.
                Keep each bullet short: name the term, then state in one sentence what concrete information should be added.
                - <exact term or claim from this note>: <what specific information should be added>

                Keep the entire output compact. Do not add sections, preambles, or closing remarks beyond the format above.

                Current note title: 題名
                Created at: 2026-07-27

                AI summary:
                要約

                Related notes:
                - 関連

                AI recommended notes:
                - 推薦

                Existing wikilinks:
                - 既存

                Current note content snippet:
                本文
            """.trimIndent(),
            PromptBuilder.buildAnnotationPrompt(
                title = "題名",
                excerpt = excerpt,
                summary = "要約",
                relatedTitles = listOf("関連"),
                aiRecommendedTitles = listOf("推薦"),
                wikilinkTitles = linkedSetOf("既存"),
                createdAt = "2026-07-27"
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
                "            Answer concisely in the same language as the section content. Do not invent facts.",
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
}
