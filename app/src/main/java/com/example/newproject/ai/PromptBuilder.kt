package com.example.newproject.ai

object PromptBuilder {

    private const val CONTENT_SNIPPET_LENGTH = 1200

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
}
