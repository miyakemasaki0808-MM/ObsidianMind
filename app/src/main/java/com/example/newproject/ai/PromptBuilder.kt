package com.example.newproject.ai

object PromptBuilder {

    private const val CONTENT_SNIPPET_LENGTH = 1200
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
        val linkedTitleSet = wikilinkTitles.map { it.normalizeTitle() }.toSet()
        val titleList = allTitles
            .take(RELATED_TITLE_LIMIT)
            .joinToString("\n") { title ->
                val marker = if (title.normalizeTitle() in linkedTitleSet) " [linked]" else ""
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

    private fun String.normalizeTitle(): String =
        trim().removeMdExtension().lowercase()

    private fun String.removeMdExtension(): String =
        if (endsWith(".md", ignoreCase = true)) dropLast(3) else this
}
