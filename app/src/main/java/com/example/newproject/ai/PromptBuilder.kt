package com.example.newproject.ai

import com.example.newproject.model.DistillCandidate
import com.example.newproject.model.DistillLimits
import com.example.newproject.model.NoteExcerpt
import com.example.newproject.model.NoteExcerptLimits
import com.example.newproject.model.REMARK_NONE_TOKEN
import com.example.newproject.model.ReadingVisit
import com.example.newproject.model.state.QuizFormat

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

/**
 * ひとことプロンプトに渡す候補ノート。
 *
 * [RelatedCandidateLine] と形は似ているが `detail` を持たない。ひとことの出力枠は
 * 1文ぶんしかなく、候補の補助情報を読ませても出力へ出す先が無いため
 * （旧補記が3ブロックを渡しながら出力で使わせていなかったのと同じ失敗を作らない）。
 */
data class RemarkCandidateLine(val id: String, val title: String)

/** AIへ実際に渡した候補集合も保持し、応答IDの許可集合とプロンプトをずらさない。 */
internal data class DistillPrompt(
    val text: String,
    val candidates: List<DistillCandidate>,
    val candidateBlock: String
) {
    val validIds: Set<String> get() = candidates.mapTo(linkedSetOf()) { it.id }
}

object PromptBuilder {

    private const val PICKER_TITLE_LIMIT = 40
    // 訪問は最大30件溜まるが、傾向を掴むには直近だけで足り、入力も短く保てる
    private const val READING_TRACE_VISIT_LINES = 10

    fun buildSummarizePrompt(title: String, excerpt: NoteExcerpt): String {
        return """
            You are a note-taking assistant. Summarize the following Obsidian note concisely in 2–4 sentences in the same language as the note content.
            Focus on the key ideas. Do not include phrases like "This note is about" — just write the summary directly.

            Note title: $title
            Note content:
            ${excerpt.renderForPrompt()}
        """.trimIndent()
    }

    /**
     * 読書痕跡の俯瞰要約。
     *
     * AIに新しい内容を作らせるのではなく、**ユーザー自身の読み方を要約させるだけ**。
     * これが「前回の自分からの申し送り」という体験を保つ肝なので、データに無いことを
     * 書かせない・助言や問いを足させない、を明示する。
     * 出力は Nano の256トークン上限に収まるよう1〜2文に絞る。
     */
    internal fun buildReadingTraceSummaryPrompt(
        noteTitle: String,
        visits: List<ReadingVisit>,
        // 保持している訪問は直近30件まで。「何回開いたか」は延べ回数を渡す
        // （visits.size を使うと31回目以降ずっと「30回」と要約される）。
        totalVisitCount: Int
    ): String {
        val history = visits.takeLast(READING_TRACE_VISIT_LINES).joinToString("\n") { visit ->
            val where = visit.deepestSectionTitle
                ?.takeIf { it.isNotBlank() }
                ?.let { "section \"$it\"" }
                ?: "no heading reached"
            "- stopped at $where (${visit.progressPercent}% of the note)"
        }
        return """
            The user has opened the following note several times. Below is where they stopped reading each time, oldest first.
            In 1–2 short sentences, in Japanese, describe the pattern in how they have been reading it: how many times they opened it, and where they tend to stop.
            Address the user as 「あなた」. Base every statement only on the data below — do not invent note content. Do not add advice, questions, or encouragement.

            Note title: $noteTitle
            Times opened: $totalVisitCount
            Reading history:
            $history
        """.trimIndent()
    }

    // 候補は「ID | タイトル」で提示し、モデルにはIDだけ返させる。ID→ノートの解決は
    // UseCase側で確実に行うため、言い換え・翻訳・装飾・同名衝突に強い。
    // 絞り込み・並べ替え・上限はUseCase側が担い、ここでは整形のみ（上限で切らない）。
    fun buildRelatedNotesPrompt(
        currentTitle: String,
        currentExcerpt: NoteExcerpt,
        candidates: List<RelatedCandidateLine>
    ): String {
        val candidateList = candidates.joinToString("\n") { it.renderForPrompt() }

        return """
            You are a note-taking assistant. Find the notes most related to the current Obsidian note.
            Each candidate is listed as "ID | title", optionally followed by "— context".
            Return only the IDs of up to 5 related notes, one ID per line (for example: C01).
            Do not include the title, numbers, bullets, explanations, or any other text.

            Current note title: $currentTitle
            Current note content snippet:
            ${currentExcerpt.renderForPrompt()}

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
    fun buildQuizPrompt(sourceLabel: String, excerpt: NoteExcerpt, format: QuizFormat): String {
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
            ${excerpt.renderForPrompt()}
            --- END EXCERPT ---
        """.trimIndent()
    }

    /**
     * ノートへのひとこと（旧「AI補記メモ」）。**出力枠のすべてを1文へ使う。**
     *
     * 旧プロンプトは4つの分類ラベルと3行の補記を同時に出させていたが、
     * 出力枠（256トークン）はゼロサムなので、行動を変えないラベルが
     * 価値のある側を圧迫していた（→ design/reflect_remark.md §0）。
     *
     * **候補ノートは「ID | タイトル」で提示し、本文中でもIDで参照させる。**
     * 生のタイトルを書かせると言い換え・装飾で解決できなくなるため
     * （蒸留・関連ノートと同じ契約。AIピッカーだけがこの契約から外れている）。
     */
    fun buildRemarkPrompt(
        title: String,
        excerpt: NoteExcerpt,
        candidates: List<RemarkCandidateLine>
    ): String {
        val candidateBlock = candidates
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n") { "${it.id} | ${it.title}" }
            ?: "(none)"

        // **複数行の値をテンプレートへ補間しない。** `trimIndent()` は補間"後"の
        // 文字列に効くため、埋めた値の2行目以降（インデント0）が混ざると共通インデントが
        // 0と判定され、テンプレート側の字下げが全行に残る。本文抜粋も候補一覧も
        // 複数行になり得るので、静的な部分だけを trimIndent して後から連結する。
        val instructions = """
            You are a reading companion for a private Obsidian vault. You are not the author, and not a reviewer.
            Say ONE short thing that helps the user think further about the note below.
            Use the same language as the note content.

            Write EITHER a question that opens up the user's own thinking,
            OR a suggestion to connect this note with one of the candidate notes. Never both.

            Rules:
            - One sentence. Two at most. Around 80–120 characters.
            - It MUST contain a word, term, or claim that literally appears in the note.
            - Do NOT summarize, evaluate, praise, grade, or greet.
            - Do NOT tell the user to add, write, fix, or complete anything.
              Avoid 「不足」「必要」「べき」. Open the thought instead of assigning work.
            - To refer to a candidate note, write its ID in double brackets, exactly like [[C03]].
              Never write a note title in brackets. Only IDs from the candidate list are allowed.
            - Output the sentence alone. No heading, no bullet, no quotes, no preamble.
            - If you have nothing worth saying, output exactly: $REMARK_NONE_TOKEN
        """.trimIndent()

        return buildString {
            append(instructions)
            append("\n\nNote title: ").append(title)
            append("\nNote content:\n").append(excerpt.renderForPrompt())
            append("\n\nCandidate notes:\n").append(candidateBlock)
        }
    }

    // ── セクション単位のAIチャット ─────────────────────────────────────────────

    fun buildSectionSummaryPrompt(sectionTitle: String, sectionExcerpt: NoteExcerpt): String {
        return """
            You are a note-taking assistant. Summarize ONLY the following section of an Obsidian note, concisely in 2–4 sentences, in the same language as the section content.
            Focus on the key ideas of this section. Do not include phrases like "This section is about" — just write the summary directly.

            Section heading: $sectionTitle
            Section content:
            ${sectionExcerpt.renderForPrompt()}
        """.trimIndent()
    }

    fun buildSectionSuggestionsPrompt(sectionTitle: String, sectionExcerpt: NoteExcerpt): String {
        return """
            You are a note-taking assistant. Based ONLY on the following section, propose up to 3 short questions a reader might want to ask about this section.
            Answer in the same language as the section content.
            Return only the questions, one per line. Do not add numbers, bullets, or extra text.

            Section heading: $sectionTitle
            Section content:
            ${sectionExcerpt.renderForPrompt()}
        """.trimIndent()
    }

    fun buildSectionChatPrompt(
        sectionTitle: String,
        sectionExcerpt: NoteExcerpt,
        history: List<Pair<String, String>>,
        question: String
    ): String {
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
            ${sectionExcerpt.renderForPrompt()}

            Conversation so far:
            $historyText

            New question:
            $question
        """.trimIndent()
    }

    private fun NoteExcerpt.renderForPrompt(): String =
        if (isAbridged) {
            NoteExcerptLimits.ABRIDGED_NOTICE_PREFIX + text
        } else {
            text
        }

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
