package com.example.newproject.ai

import com.example.newproject.model.DistillCandidate
import com.example.newproject.model.DistillLimits
import com.example.newproject.model.NoteExcerpt
import com.example.newproject.model.NoteExcerptLimits
import com.example.newproject.model.PromptLimits
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
 * **タイトルだけでは中身に踏み込んだ接続理由を作れない**（2026-08-09 の実機確認の指摘）。
 * そこで件数を絞るかわりに本文冒頭の [snippet] を添える。件数×情報量の合計は増やさない。
 * スニペットは関連ノートAIが再ランクのために既に読んだ値を通しているだけで、
 * ここで新しいI/Oは発生しない。
 */
data class RemarkCandidateLine(val id: String, val title: String, val snippet: String? = null) {
    fun renderForPrompt(): String =
        if (snippet.isNullOrBlank()) "$id | $title" else "$id | $title — $snippet"
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

    private const val PICKER_TITLE_LIMIT = 40
    // 訪問は最大30件溜まるが、傾向を掴むには直近だけで足り、入力も短く保てる
    private const val READING_TRACE_VISIT_LINES = 10
    private const val NO_CHAT_HISTORY = "（なし / none）"

    fun buildSummarizePrompt(title: String, excerpt: NoteExcerpt): String {
        val instructions = """
            You are a note-taking assistant. Summarize the following Obsidian note concisely in 2–4 sentences in the same language as the note content.
            Focus on the key ideas. Do not include phrases like "This note is about" — just write the summary directly.
        """.trimIndent()

        return PromptBudget.assemble(
            instructions = instructions,
            body = buildString {
                append("\n\nNote title: ").append(label(title))
                append("\nNote content:\n").append(excerpt.renderForPrompt())
            }
        )
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
        val instructions = """
            The user has opened the following note several times. Below is where they stopped reading each time, oldest first.
            In 1–2 short sentences, in Japanese, describe the pattern in how they have been reading it: how many times they opened it, and where they tend to stop.
            Address the user as 「あなた」. Base every statement only on the data below — do not invent note content. Do not add advice, questions, or encouragement.
        """.trimIndent()

        return PromptBudget.assemble(
            instructions = instructions,
            body = buildString {
                append("\n\nNote title: ").append(label(noteTitle))
                append("\nTimes opened: ").append(totalVisitCount)
                append("\nReading history:\n").append(history)
            }
        )
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
        val instructions = """
            You are a note-taking assistant. Find the notes most related to the current Obsidian note.
            Each candidate is listed as "ID | title", optionally followed by "— context".
            Return only the IDs of up to 5 related notes, one ID per line (for example: C01).
            Do not include the title, numbers, bullets, explanations, or any other text.
        """.trimIndent()

        return PromptBudget.assemble(
            instructions = instructions,
            body = buildString {
                append("\n\nCurrent note title: ").append(label(currentTitle))
                append("\nCurrent note content snippet:\n").append(currentExcerpt.renderForPrompt())
                append("\n\nCandidates:\n").append(candidateList)
            }
        )
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
        val instructions = """
            You are a careful editor selecting the most important original sentences from an Obsidian note.
            Choose up to ${DistillLimits.FINAL_SELECTION_LIMIT} candidates that best preserve the note's central claims, conclusions, or uniquely useful details.
            Prefer specific conclusions over repeated general statements. Do not rewrite, summarize, or invent text.
            Return only candidate IDs in descending order of importance, one ID per line (for example: S001).
            Do not include bullets, explanations, titles, or IDs not present in the candidate list.
        """.trimIndent()
        val prompt = PromptBudget.assemble(
            instructions = instructions,
            body = buildString {
                append("\n\nNote title: ").append(label(title))
                append("\n\nCandidates:\n").append(candidateBlock)
            }
        )
        return DistillPrompt(prompt, fitted, candidateBlock)
    }

    // AIピッカー: 自然文クエリに合うノートを候補タイトルから3件選ばせる。
    // 出力は関連ノートと同型（タイトルのみ・1行1件・説明なし）で、既存パーサを流用できる。
    fun buildPickerPrompt(query: String, candidateTitles: List<String>): String {
        // **タイトルは途中で切らない。** 受け側はタイトルで照合するので、切ると
        // `notesByTitle` が黙って落とし、「3件選ばせたのに1件しか出ない」になる。
        // 予算を超えるぶんは行ごと落とす。
        val titleList = buildString {
            var used = 0
            for (title in candidateTitles.take(PICKER_TITLE_LIMIT)) {
                val line = "- $title"
                val cost = line.length + if (isEmpty()) 0 else 1
                if (used + cost > PromptLimits.PICKER_CANDIDATES_CHARACTERS) break
                if (isNotEmpty()) append('\n')
                append(line)
                used += cost
            }
        }

        val instructions = """
            You are a note-finding assistant. From the candidate list, pick the 3 notes
            that best match the user's request. Answer in the same language as the request.
            Return only note titles from the candidate list, one title per line.
            Do not add numbers, bullets, explanations, or extra text.
        """.trimIndent()

        return PromptBudget.assemble(
            instructions = instructions,
            body = buildString {
                append("\n\nUser request: ").append(query.take(PromptLimits.QUERY_CHARACTERS))
                append("\n\nCandidate note titles:\n").append(titleList)
            }
        )
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
        val instructions = """
            You are a study assistant. Read the following excerpt from an Obsidian note and create a compact quiz that helps the user recall its key ideas.
            Answer in the same language as the excerpt content.
            Use only information supported by the excerpt. Return only the requested fields, with a blank line between questions.
        """.trimIndent()

        return PromptBudget.assemble(
            // 書式契約は指示文の一部。**削れる側に置かない** — 欠けると出力の形が崩れる。
            instructions = instructions + "\n\n" + formatContract,
            body = buildString {
                append("\n\nSource: ").append(label(sourceLabel))
                append("\n--- BEGIN EXCERPT ---\n").append(excerpt.renderForPrompt())
            },
            closing = "\n--- END EXCERPT ---"
        )
    }

    /**
     * ノートへのひとこと（旧「AI補記メモ」）。**出力枠のすべてを1文へ使う。**
     *
     * 旧プロンプトは4つの分類ラベルと3行の補記を同時に出させていたが、
     * 出力枠（256トークン）はゼロサムなので、行動を変えないラベルが
     * 価値のある側を圧迫していた（→ features/reflect_remark.md §0）。
     *
     * **候補ノートは「ID | タイトル」で提示し、本文中でもIDで参照させる。**
     * 生のタイトルを書かせると言い換え・装飾で解決できなくなるため
     * （蒸留・関連ノートと同じ契約。AIピッカーだけがこの契約から外れている）。
     *
     * **出力は日本語で固定する。** 当初は要約と同じ「ノート本文と同じ言語で」に
     * していたが、**ソースコードだけのノートで英語の問いが返ってきた**（2026-08-09 実機）。
     * 要約はノートの内容を写すものなので本文の言語に従うのが正しいが、
     * ひとことは**アプリがユーザーへ話しかける文**なので、従うべきは読み手の言語である。
     * [buildReadingTraceSummaryPrompt]（痕跡の俯瞰要約）が先に同じ判断をしており、
     * こちらはその category を取り違えて旧補記の文言を引き継いでいた。
     *
     * **ただし「あなた」まで一緒に持ってきたのは行き過ぎだった**（2026-08-09 実機）。
     * 俯瞰要約は「あなたは3回開いています」と**読み手自身の行動を述べる**文なので
     * 二人称が要るが、ひとことは**ノートについて話す**文なので主語に読み手を置く必要がない。
     * 日本語は主語を落とせるうえ、二人称を名指しすると採点者の口調になる。
     */
    fun buildRemarkPrompt(
        title: String,
        excerpt: NoteExcerpt,
        candidates: List<RemarkCandidateLine>
    ): String {
        val candidateBlock = candidates
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n") { it.renderForPrompt() }
            ?: "(none)"

        // **複数行の値をテンプレートへ補間しない。** `trimIndent()` は補間"後"の
        // 文字列に効くため、埋めた値の2行目以降（インデント0）が混ざると共通インデントが
        // 0と判定され、テンプレート側の字下げが全行に残る。本文抜粋も候補一覧も
        // 複数行になり得るので、静的な部分だけを trimIndent して後から連結する。
        val instructions = """
            You are a reading companion for a private Obsidian vault. You are not the author, and not a reviewer.
            Say ONE short thing that helps the user think further about the note below.
            Write in Japanese, whatever language the note itself is written in.
            Technical identifiers, code symbols, and proper nouns stay as they appear in the note.

            Write EITHER a question that opens up the user's own thinking,
            OR a suggestion to connect this note with one of the candidate notes. Never both.

            Rules:
            - One sentence. Two at most. Around 80–120 characters.
            - Do NOT start with or use 「あなた」 as the subject. Japanese drops the subject naturally;
              talking about the reader in the second person sounds like a grader, not a companion.
            - It MUST contain a word, term, or claim that literally appears in the note.
            - Do NOT summarize, evaluate, praise, grade, or greet.
            - Do NOT tell the user to add, write, fix, or complete anything.
              Avoid 「不足」「必要」「べき」. Open the thought instead of assigning work.
            - To refer to a candidate note, write its ID in double brackets, exactly like [[C03]].
              Never write a note title in brackets. Only IDs from the candidate list are allowed.
            - A sentence with a link must be a declarative suggestion, not a question.
              Never append a link after a question.
            - Output the sentence alone. No heading, no bullet, no quotes, no preamble.
            - If you have nothing worth saying, output exactly: $REMARK_NONE_TOKEN
        """.trimIndent()

        return PromptBudget.assemble(
            instructions = instructions,
            body = buildString {
                append("\n\nNote title: ").append(label(title))
                append("\nNote content:\n").append(excerpt.renderForPrompt())
                append("\n\nCandidate notes:\n").append(candidateBlock)
            }
        )
    }

    /**
     * 返事を受けて返す1文（映し返し）。**問いを書かせない。**
     *
     * ひとことが問いを投げるのに対し、こちらは**受け取ったことを示して閉じる**役。
     * ここに問いを書かせると次の返事を誘発し、無限会話の入口になる
     * （「AIは相手役／本質はノートを読む」から外れる）。
     * **1往復で終わる**という制約は、出力の内容ではなく**形**で守る。
     *
     * 助言・称賛・要約も禁じる。称賛は相手役ではなく採点者の口調になり、
     * 要約は返事をなぞるだけで新しいものを返さない。
     */
    fun buildRemarkMirrorPrompt(
        title: String,
        excerpt: NoteExcerpt,
        remark: String,
        reply: String
    ): String {
        val instructions = """
            You are a reading companion for a private Obsidian vault.
            The user read a note, you asked them one thing, and they answered.
            Reflect back what their answer adds to the note in ONE sentence.

            Write in Japanese.

            Rules:
            - One sentence. Around 60–100 characters.
            - Do NOT start with or use 「あなた」 as the subject. Japanese drops the subject naturally;
              naming the reader in the second person sounds like a grader, not a companion.
            - Name the new angle or the tension their answer brings to the note.
            - Do NOT ask a question. This is the end of the exchange, not a turn in a chat.
            - Do NOT give advice, praise, greet, or summarize what they wrote.
            - Output the sentence alone. No heading, no bullet, no quotes, no preamble.
            - If their answer adds nothing you can name, output exactly: $REMARK_NONE_TOKEN
        """.trimIndent()

        return PromptBudget.assemble(
            instructions = instructions,
            body = buildString {
                append("\n\nNote title: ").append(label(title))
                append("\nNote content:\n").append(excerpt.renderForPrompt())
                append("\n\nWhat you asked:\n").append(remark)
            },
            // **返事は削らない。** 映し返すべき当のものなので、欠けると答えるものが消える。
            // 呼び出し側が `excerptReplyForPrompt` で先に切っているが、
            // **上限は呼び出し側に委ねない**（ここが完成プロンプトを閉じる唯一の場所）。
            closing = "\n\nTheir answer:\n" + reply.take(PromptLimits.REPLY_CHARACTERS)
        )
    }

    // ── セクション単位のAIチャット ─────────────────────────────────────────────

    fun buildSectionSummaryPrompt(sectionTitle: String, sectionExcerpt: NoteExcerpt): String {
        val instructions = """
            You are a note-taking assistant. Summarize ONLY the following section of an Obsidian note, concisely in 2–4 sentences, in the same language as the section content.
            Focus on the key ideas of this section. Do not include phrases like "This section is about" — just write the summary directly.
        """.trimIndent()

        return PromptBudget.assemble(
            instructions = instructions,
            body = buildString {
                append("\n\nSection heading: ").append(label(sectionTitle))
                append("\nSection content:\n").append(sectionExcerpt.renderForPrompt())
            }
        )
    }

    fun buildSectionSuggestionsPrompt(sectionTitle: String, sectionExcerpt: NoteExcerpt): String {
        val instructions = """
            You are a note-taking assistant. Based ONLY on the following section, propose up to 3 short questions a reader might want to ask about this section.
            Answer in the same language as the section content.
            Return only the questions, one per line. Do not add numbers, bullets, or extra text.
        """.trimIndent()

        return PromptBudget.assemble(
            instructions = instructions,
            body = buildString {
                append("\n\nSection heading: ").append(label(sectionTitle))
                append("\nSection content:\n").append(sectionExcerpt.renderForPrompt())
            }
        )
    }

    /**
     * **答える言語はセクションではなくユーザーの質問に従う。**
     *
     * 「セクションの言語で」にしていたため、日本語で質問しても
     * コードや英語のセクションでは英語で返っていた。ノートの内容を写す要約と違い、
     * **これはユーザーの問いに答える文**なので、従うべきは質問の言語である。
     * [buildPickerPrompt] が先に「リクエストの言語で」と正しく書けており、
     * こちらが category を取り違えていた（ひとことでの同じ取り違えと同時に発見）。
     */
    fun buildSectionChatPrompt(
        sectionTitle: String,
        sectionExcerpt: NoteExcerpt,
        history: List<Pair<String, String>>,
        question: String
    ): String {
        val historyText = renderChatHistory(history)
        val instructions = """
            You are a note-taking assistant answering questions about ONE section of an Obsidian note.
            Answer using ONLY the information in the section below. If the answer is not contained in this section, reply that it is not written in this section ("このセクションには記載がありません").
            Answer concisely in the same language as the user's question, not the language of the section. Do not invent facts.
        """.trimIndent()

        return PromptBudget.assemble(
            instructions = instructions,
            body = buildString {
                append("\n\nSection heading: ").append(label(sectionTitle))
                append("\nSection content:\n").append(sectionExcerpt.renderForPrompt())
                append("\n\nConversation so far:\n").append(historyText)
            },
            // **質問は削らない。** ここを欠くと答えるものが消える。
            closing = "\n\nNew question:\n" + question.take(PromptLimits.QUESTION_CHARACTERS)
        )
    }

    /**
     * 会話履歴を [PromptLimits.SECTION_CHAT_HISTORY_CHARACTERS] へ収める。
     *
     * **落とすのは古い発言のほうである。** 往復のたびに伸びる一方だったので、
     * 何もしないと会話が続くほど入力が上限へ近づく。直近ほど文脈として効くため、
     * 新しい側から詰めて、入らなくなった時点で打ち切る。
     *
     * **直近の1件だけは必ず載せる**（入らなければ切って載せる）。
     * 落とすと「直前に何を聞かれたか」が消え、会話として成立しなくなる。
     */
    private fun renderChatHistory(history: List<Pair<String, String>>): String {
        if (history.isEmpty()) return NO_CHAT_HISTORY
        val budget = PromptLimits.SECTION_CHAT_HISTORY_CHARACTERS
        val newest = history.last().render()
        val lines = ArrayDeque<String>()
        var used: Int
        if (newest.length <= budget) {
            lines.addFirst(newest)
            used = newest.length
        } else {
            val keep = (budget - PromptBudget.TRUNCATION_MARKER.length).coerceAtLeast(0)
            lines.addFirst(newest.take(keep) + PromptBudget.TRUNCATION_MARKER)
            used = budget
        }
        for (turn in history.dropLast(1).asReversed()) {
            val line = turn.render()
            if (used + 1 + line.length > budget) break
            lines.addFirst(line)
            used += 1 + line.length
        }
        return lines.joinToString("\n")
    }

    private fun Pair<String, String>.render(): String = "$first: $second"

    /**
     * タイトル・見出し・出典ラベルを [PromptLimits.LABEL_CHARACTERS] へ収める。
     *
     * ノート名はファイル名なので実質短いが、**セクション見出しはMarkdownの1行**で
     * 長さの保証が無い。ラベル1つが抜粋を押し出すのを防ぐ。
     */
    private fun label(value: String): String = value.take(PromptLimits.LABEL_CHARACTERS)

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
