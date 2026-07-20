package com.example.newproject.ai

import com.example.newproject.toNormalizedObsidianTitle

object PromptBuilder {

    private const val CONTENT_SNIPPET_LENGTH = 1200
    // 補記は入力が最大のプロンプト。入力を絞って生成時間とコンテキスト圧迫を抑える
    private const val ANNOTATION_CONTENT_SNIPPET_LENGTH = 1500
    private const val RELATED_CONTENT_SNIPPET_LENGTH = 600
    private const val RELATED_TITLE_LIMIT = 80
    private const val SECTION_SNIPPET_LENGTH = 1500
    private const val PICKER_TITLE_LIMIT = 40

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

    // AIピッカー: 自然文クエリに合うノートを候補タイトルから3件選ばせる。
    // 出力は関連ノートと同型（タイトルのみ・1行1件・説明なし）で、既存パーサを流用できる。
    fun buildPickerPrompt(query: String, candidateTitles: List<String>): String {
        val titleList = candidateTitles
            .take(PICKER_TITLE_LIMIT)
            .joinToString("\n") { "- $it" }

        return """
            You are a note-finding assistant. From the candidate list, pick the 3 notes
            that best match the user's request. Answer in the same language as the request.
            Return only note titles from the candidate list, one title per line.
            Do not add numbers, bullets, explanations, or extra text.

            User request: $query

            Candidate note titles:
            $titleList
        """.trimIndent()
    }

    // フォーカス周辺クイズ: 出力上限（オンデバイスは256トークン程度）に確実に収まる
    // 2問固定で生成する。追い生成では excludeQuestions で既出問題の重複を避けさせる。
    fun buildQuizPrompt(
        sourceLabel: String,
        content: String,
        excludeQuestions: List<String> = emptyList()
    ): String {
        val snippet = content.take(CONTENT_SNIPPET_LENGTH)
        val exclusionText = excludeQuestions
            .takeIf { it.isNotEmpty() }
            ?.joinToString(
                separator = "\n",
                prefix = "Do NOT repeat or closely paraphrase any of these existing questions:\n"
            ) { "- $it" }
            ?: ""

        return """
            You are a study assistant. Read the following excerpt from an Obsidian note and generate exactly 2 multiple-choice questions to help the user memorize the key concepts of this excerpt. If the excerpt is short, use closely related general knowledge to complete the questions.
            Answer in the same language as the excerpt content.
            Format each question EXACTLY like this (blank line between questions):
            Q: <question>
            A: <correct answer>
            B: <wrong answer>
            C: <wrong answer>
            D: <wrong answer>
            ANSWER: <A or B or C or D>
            EXPLANATION: <1-2 sentence explanation of why the correct answer is right>

            $exclusionText

            Source: $sourceLabel
            Excerpt:
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

    // ── セクション単位のAIチャット ─────────────────────────────────────────────

    fun buildSectionSummaryPrompt(sectionTitle: String, sectionText: String): String {
        val snippet = sectionText.take(SECTION_SNIPPET_LENGTH)
        return """
            You are a note-taking assistant. Summarize ONLY the following section of an Obsidian note, concisely in 2–4 sentences, in the same language as the section content.
            Focus on the key ideas of this section. Do not include phrases like "This section is about" — just write the summary directly.

            Section heading: $sectionTitle
            Section content:
            $snippet
        """.trimIndent()
    }

    fun buildSectionSuggestionsPrompt(sectionTitle: String, sectionText: String): String {
        val snippet = sectionText.take(SECTION_SNIPPET_LENGTH)
        return """
            You are a note-taking assistant. Based ONLY on the following section, propose up to 3 short questions a reader might want to ask about this section.
            Answer in the same language as the section content.
            Return only the questions, one per line. Do not add numbers, bullets, or extra text.

            Section heading: $sectionTitle
            Section content:
            $snippet
        """.trimIndent()
    }

    fun buildSectionChatPrompt(
        sectionTitle: String,
        sectionText: String,
        history: List<Pair<String, String>>,
        question: String
    ): String {
        val snippet = sectionText.take(SECTION_SNIPPET_LENGTH)
        val historyText = history
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n") { (role, text) -> "$role: $text" }
            ?: "（なし / none）"
        return """
            You are a note-taking assistant answering questions about ONE section of an Obsidian note.
            Answer using ONLY the information in the section below. If the answer is not contained in this section, reply that it is not written in this section ("このセクションには記載がありません").
            Answer concisely in the same language as the section content. Do not invent facts.

            Section heading: $sectionTitle
            Section content:
            $snippet

            Conversation so far:
            $historyText

            New question:
            $question
        """.trimIndent()
    }

    private fun List<String>.asBulletList(emptyText: String): String =
        takeIf { it.isNotEmpty() }
            ?.joinToString("\n") { "- $it" }
            ?: emptyText

}
