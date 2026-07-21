package com.example.newproject.ai

import com.example.newproject.QuizFormat
import com.example.newproject.domain.DistillCandidate
import com.example.newproject.domain.DistillLimits

private const val DISTILL_HEADING_LENGTH = 80

/**
 * 関連ノートAIプロンプトに渡す候補行。ID→ノートの解決はUseCase側で確実に行う。
 * [detail] は本文冒頭スニペットやタグ等の補助情報（無ければ null）。
 * プロンプト整形はここに集約し、文字数計算（入力バジェット）と一致させる。
 */
data class RelatedCandidateLine(val id: String, val title: String, val detail: String? = null) {
    fun renderForPrompt(): String =
        if (detail.isNullOrBlank()) "$id | $title" else "$id | $title — $detail"
}

/** AIへ実際に渡した候補集合も保持し、応答IDの許可集合とプロンプトをずらさない。 */
internal data class DistillPrompt(
    val text: String,
    val candidates: List<DistillCandidate>,
    val candidateBlock: String
) {
    val validIds: Set<String> get() = candidates.mapTo(linkedSetOf()) { it.id }
}

object PromptBuilder {

    private const val CONTENT_SNIPPET_LENGTH = 1200
    // 補記は入力が最大のプロンプト。入力を絞って生成時間とコンテキスト圧迫を抑える
    private const val ANNOTATION_CONTENT_SNIPPET_LENGTH = 1500
    private const val RELATED_CONTENT_SNIPPET_LENGTH = 600
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

    // 候補は「ID | タイトル」で提示し、モデルにはIDだけ返させる。ID→ノートの解決は
    // UseCase側で確実に行うため、言い換え・翻訳・装飾・同名衝突に強い。
    // 絞り込み・並べ替え・上限はUseCase側が担い、ここでは整形のみ（上限で切らない）。
    fun buildRelatedNotesPrompt(
        currentTitle: String,
        currentContent: String,
        candidates: List<RelatedCandidateLine>
    ): String {
        val snippet = currentContent.take(RELATED_CONTENT_SNIPPET_LENGTH)
        val candidateList = candidates.joinToString("\n") { it.renderForPrompt() }

        return """
            You are a note-taking assistant. Find the notes most related to the current Obsidian note.
            Each candidate is listed as "ID | title", optionally followed by "— context".
            Return only the IDs of up to 5 related notes, one ID per line (for example: C01).
            Do not include the title, numbers, bullets, explanations, or any other text.

            Current note title: $currentTitle
            Current note content snippet:
            $snippet

            Candidates:
            $candidateList
        """.trimIndent()
    }

    /**
     * 蒸留ではAIに文章を生成させず、原文候補のIDだけを選ばせる。
     * 候補文そのものは途中で切らず、件数と候補ブロックの双方を上限内に収める。
     */
    internal fun buildDistillPrompt(
        title: String,
        candidates: List<DistillCandidate>,
        candidateLimit: Int = DistillLimits.MAX_AI_CANDIDATES,
        candidateCharacterBudget: Int = DistillLimits.AI_CANDIDATE_CHAR_BUDGET
    ): DistillPrompt {
        require(candidateLimit >= 0)
        require(candidateCharacterBudget >= 0)
        val fitted = mutableListOf<DistillCandidate>()
        val rendered = mutableListOf<String>()
        var usedCharacters = 0

        for (candidate in candidates) {
            if (fitted.size >= candidateLimit) break
            val line = candidate.renderForDistillPrompt()
            val separatorLength = if (rendered.isEmpty()) 0 else 1
            if (usedCharacters + separatorLength + line.length <= candidateCharacterBudget) {
                fitted += candidate
                rendered += line
                usedCharacters += separatorLength + line.length
            }
        }
        val candidateBlock = rendered.joinToString("\n")
        val prompt = """
            You are a careful editor selecting the most important original sentences from an Obsidian note.
            Choose up to ${DistillLimits.FINAL_SELECTION_LIMIT} candidates that best preserve the note's central claims, conclusions, or uniquely useful details.
            Prefer specific conclusions over repeated general statements. Do not rewrite, summarize, or invent text.
            Return only candidate IDs in descending order of importance, one ID per line (for example: S001).
            Do not include bullets, explanations, titles, or IDs not present in the candidate list.

            Note title: $title

            Candidates:
            $candidateBlock
        """.trimIndent()
        return DistillPrompt(prompt, fitted, candidateBlock)
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

    // フォーカス周辺クイズ: 本文構造に応じて問題数と選択肢数を抑え、
    // オンデバイスモデルの出力上限内へ収める。
    fun buildQuizPrompt(sourceLabel: String, content: String, format: QuizFormat): String {
        val snippet = content.take(CONTENT_SNIPPET_LENGTH)
        val formatContract = when (format) {
            QuizFormat.TrueFalse -> """
                Generate exactly 2 true-or-false statements about what the excerpt says.
                Keep each statement within 50 characters when writing Japanese, or 20 words otherwise.
                Do not add explanations or choices. Use exactly this format:
                Q: <statement>
                ANSWER: <TRUE or FALSE>
            """.trimIndent()
            QuizFormat.ThreeChoice -> """
                Generate exactly 2 three-choice questions.
                Keep each question within 50 characters when writing Japanese, or 20 words otherwise.
                Keep each choice within 24 characters when writing Japanese, or 10 words otherwise.
                Do not add explanations. Use exactly this format:
                Q: <question>
                A: <choice>
                B: <choice>
                C: <choice>
                ANSWER: <A or B or C>
            """.trimIndent()
            QuizFormat.FourChoice -> """
                Generate exactly 1 four-choice question.
                Keep the question within 60 characters when writing Japanese, or 24 words otherwise.
                Keep each choice within 24 characters when writing Japanese, or 10 words otherwise.
                Add only one short explanatory sentence. Use exactly this format:
                Q: <question>
                A: <choice>
                B: <choice>
                C: <choice>
                D: <choice>
                ANSWER: <A or B or C or D>
                EXPLANATION: <one short sentence>
            """.trimIndent()
        }
        return """
            You are a study assistant. Read the following excerpt from an Obsidian note and create a compact quiz that helps the user recall its key ideas.
            Answer in the same language as the excerpt content.
            Use only information supported by the excerpt. Return only the requested fields, with a blank line between questions.

            $formatContract

            Source: $sourceLabel
            --- BEGIN EXCERPT ---
            $snippet
            --- END EXCERPT ---
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

private fun DistillCandidate.renderForDistillPrompt(): String {
    val headingPrefix = sentence.heading
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.take(DISTILL_HEADING_LENGTH)
        ?.let { "[$it] " }
        .orEmpty()
    return "$id | $headingPrefix${sentence.text}"
}
