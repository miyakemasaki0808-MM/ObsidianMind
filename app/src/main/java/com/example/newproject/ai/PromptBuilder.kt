package com.example.newproject.ai

import com.example.newproject.toNormalizedObsidianTitle

object PromptBuilder {

    private const val CONTENT_SNIPPET_LENGTH = 1200
    private const val ANNOTATION_CONTENT_SNIPPET_LENGTH = 2000
    private const val RELATED_CONTENT_SNIPPET_LENGTH = 600
    private const val RELATED_TITLE_LIMIT = 80

    fun buildSummarizePrompt(title: String, content: String): String {
        val snippet = content.take(CONTENT_SNIPPET_LENGTH)
        return """
            You are a note-taking assistant. Summarize the following Obsidian note concisely in 2–4 sentences in the same language as the note content.
            Focus on the key ideas. Do not include phrases like "This note is about" — just write the summary directly.

            Note title: $title
            Note content:
            $snippet
        """.trimIndent()
    }

    fun buildRelatedNotesPrompt(
        currentTitle: String,
        currentContent: String,
        allTitles: List<String>,
        wikilinkTitles: Set<String>
    ): String {
        val snippet = currentContent.take(RELATED_CONTENT_SNIPPET_LENGTH)
        val linkedTitleSet = wikilinkTitles.map { it.toNormalizedObsidianTitle() }.toSet()
        val titleList = allTitles
            .take(RELATED_TITLE_LIMIT)
            .joinToString("\n") { title ->
                val marker = if (title.toNormalizedObsidianTitle() in linkedTitleSet) " [linked]" else ""
                "- $title$marker"
            }

        return """
            You are a note-taking assistant. Find the 5 notes most related to the current Obsidian note.
            Answer in the same language as the note content.
            Return only note titles from the candidate list, one title per line.
            Do not add numbers, bullets, explanations, or extra text.
            Prefer candidates marked [linked] when they are relevant.

            Current note title: $currentTitle
            Current note content snippet:
            $snippet

            Candidate note titles:
            $titleList
        """.trimIndent()
    }

    fun buildQuizPrompt(title: String, content: String): String {
        val snippet = content.take(CONTENT_SNIPPET_LENGTH)
        return """
            You are a study assistant. Read the following Obsidian note and generate exactly 5 multiple-choice questions to help the user memorize the key concepts. If the note is short, use related general knowledge to create additional questions.
            Answer in the same language as the note content.
            Format each question EXACTLY like this (blank line between questions):
            Q: <question>
            A: <correct answer>
            B: <wrong answer>
            C: <wrong answer>
            D: <wrong answer>
            ANSWER: <A or B or C or D>
            EXPLANATION: <1-2 sentence explanation of why the correct answer is right>

            Note title: $title
            Note content:
            $snippet
        """.trimIndent()
    }

    fun buildAnnotationPrompt(
        title: String,
        content: String,
        summary: String?,
        relatedTitles: List<String>,
        aiRecommendedTitles: List<String>,
        wikilinkTitles: Set<String>,
        createdAt: String
    ): String {
        val snippet = content.take(ANNOTATION_CONTENT_SNIPPET_LENGTH)
        val summaryText = summary?.takeIf { it.isNotBlank() } ?: "なし"
        val relatedText = relatedTitles.asBulletList("関連ノートなし")
        val aiRecommendedText = aiRecommendedTitles.asBulletList("AI推薦ノートなし")
        val wikilinkText = wikilinkTitles.toList().asBulletList("wikilinkなし")

        return """
            You are an editor and reader for a private Obsidian vault. You are not the author.
            Create an annotation memo for the current note so the user can grow the note later.
            Respect the source note. Do not rewrite or replace it. Write suggestions, not final truth.
            Use the same language as the note content.

            Choose values only from these fixed choices:
            種別: 概念メモ / 読書メモ / 日記・ログ / アイデア断片 / 技術メモ / タスク・計画 / 長文記事 / その他
            粒度: 断片 / 原子メモ / 中粒度 / 長文
            状態: 十分 / 書きかけ / 具体例不足 / 背景不足 / 関連リンク不足 / 自分の解釈不足 / 次アクション不足 / 論点過多
            補記方針: 具体例を足す / 背景を補う / 関連リンクを足す / 自分の解釈を書く / 次の問いを作る / 構成を整理する / 反論・別視点を足す

            Output Markdown only. Include exactly these sections and headings:
            ## 粒度評価
            - 種別: <one fixed choice>
            - 粒度: <one fixed choice>
            - 状態: <one fixed choice>
            - 補記方針: <one fixed choice>

            ## 補記すべき内容
            - <bullet suggestions>

            ## 補記案
            <short annotation text. Use tentative phrasing.>

            ## 関連リンク候補
            - [[title]]: <why it may connect>

            ## 次の問い
            - <question>

            Current note title: $title
            Created at: $createdAt

            AI summary:
            $summaryText

            Related notes:
            $relatedText

            AI recommended notes:
            $aiRecommendedText

            Existing wikilinks:
            $wikilinkText

            Current note content snippet:
            $snippet
        """.trimIndent()
    }

    private fun List<String>.asBulletList(emptyText: String): String =
        takeIf { it.isNotEmpty() }
            ?.joinToString("\n") { "- $it" }
            ?: emptyText

}
