package com.example.newproject.domain

import android.net.Uri
import com.example.newproject.NoteFile
import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.example.newproject.ai.PromptBuilder

data class RelatedNote(
    val title: String,
    val uri: Uri,
    val isWikilinked: Boolean
)

sealed class RelatedNotesResult {
    data class Success(val notes: List<RelatedNote>) : RelatedNotesResult()
    object AiUnavailable : RelatedNotesResult()
    object AiNeedsDownload : RelatedNotesResult()
    data class Error(val message: String) : RelatedNotesResult()
}

class RelatedNotesUseCase(private val aiClient: AiClient) {

    suspend fun findRelated(
        currentTitle: String,
        currentContent: String,
        allNotes: List<NoteFile>,
        wikilinkTitles: Set<String>
    ): RelatedNotesResult {
        return when (aiClient.checkAvailability()) {
            AiAvailability.Unavailable -> RelatedNotesResult.AiUnavailable
            AiAvailability.NeedsDownload -> RelatedNotesResult.AiNeedsDownload
            AiAvailability.Available -> findRelatedWithAi(
                currentTitle = currentTitle,
                currentContent = currentContent,
                allNotes = allNotes,
                wikilinkTitles = wikilinkTitles
            )
        }
    }

    private suspend fun findRelatedWithAi(
        currentTitle: String,
        currentContent: String,
        allNotes: List<NoteFile>,
        wikilinkTitles: Set<String>
    ): RelatedNotesResult {
        return try {
            val candidateNotes = allNotes.filterNot { it.name.isSameTitleAs(currentTitle) }
            val prompt = PromptBuilder.buildRelatedNotesPrompt(
                currentTitle = currentTitle,
                currentContent = currentContent,
                allTitles = candidateNotes.map { it.name },
                wikilinkTitles = wikilinkTitles
            )
            val response = aiClient.generate(prompt)
            val notesByTitle = candidateNotes.associateBy { it.name.normalizedTitle() }
            val wikilinkTitleSet = wikilinkTitles.map { it.normalizedTitle() }.toSet()

            val relatedNotes = response.lineSequence()
                .map { it.cleanAiTitle() }
                .filter { it.isNotBlank() }
                .mapNotNull { title -> notesByTitle[title.normalizedTitle()] }
                .distinctBy { it.uri }
                .take(RELATED_NOTE_LIMIT)
                .map { note ->
                    RelatedNote(
                        title = note.name,
                        uri = note.uri,
                        isWikilinked = note.name.normalizedTitle() in wikilinkTitleSet
                    )
                }
                .toList()

            RelatedNotesResult.Success(relatedNotes)
        } catch (e: Exception) {
            RelatedNotesResult.Error(e.message ?: "Unknown error")
        }
    }

    private fun String.cleanAiTitle(): String =
        trim()
            .removePrefix("-")
            .removeSuffix("[linked]")
            .replace(Regex("^\\d+[.)]\\s*"), "")
            .trim('"')
            .trim()

    private fun String.isSameTitleAs(other: String): Boolean =
        normalizedTitle() == other.normalizedTitle()

    private fun String.normalizedTitle(): String =
        trim().removeMdExtension().lowercase()

    private fun String.removeMdExtension(): String =
        if (endsWith(".md", ignoreCase = true)) dropLast(3) else this

    companion object {
        private const val RELATED_NOTE_LIMIT = 5
    }
}
