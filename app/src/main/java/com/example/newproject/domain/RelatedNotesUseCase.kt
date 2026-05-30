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
    data class Success(
        val notes: List<RelatedNote>,
        val prefixNotes: List<RelatedNote> = emptyList()
    ) : RelatedNotesResult()
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
            val wikilinkTitleSet = wikilinkTitles.map { it.normalizedTitle() }.toSet()
            val notesByTitle = candidateNotes.associateBy { it.name.normalizedTitle() }

            // プレフィックス一致ノート（同2桁グループ）を先に確定
            val sameGroupNotes = extractSameGroup(currentTitle, candidateNotes)
                .take(PREFIX_DISPLAY_LIMIT)
                .map { note ->
                    RelatedNote(
                        title = note.name,
                        uri = note.uri,
                        isWikilinked = note.name.normalizedTitle() in wikilinkTitleSet
                    )
                }

            // AIには全プレフィックスフィルタ済み候補を渡す
            val prefixFiltered = prefixFilter(currentTitle, candidateNotes)
            val prompt = PromptBuilder.buildRelatedNotesPrompt(
                currentTitle = currentTitle,
                currentContent = currentContent,
                allTitles = prefixFiltered.map { it.name },
                wikilinkTitles = wikilinkTitles
            )
            val response = aiClient.generate(prompt)

            val aiNotes = response.lineSequence()
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

            RelatedNotesResult.Success(notes = aiNotes, prefixNotes = sameGroupNotes)
        } catch (e: Exception) {
            RelatedNotesResult.Error(e.message ?: "Unknown error")
        }
    }

    // 上2桁が一致するノートのみ返す（表示用）
    private fun extractSameGroup(currentTitle: String, candidates: List<NoteFile>): List<NoteFile> {
        val prefix = extractPrefix(currentTitle) ?: return emptyList()
        val twoDigit = prefix.take(2)
        return candidates.filter { extractPrefix(it.name)?.take(2) == twoDigit }
    }

    // 4桁16進数プレフィックス（例: 0F01）を抽出する
    private fun extractPrefix(filename: String): String? {
        val match = Regex("^([0-9A-Fa-f]{4})").find(filename) ?: return null
        return match.groupValues[1].uppercase()
    }

    // プレフィックス体系で候補を絞る
    // 上2桁一致（兄弟グループ）→ 上1桁一致（大カテゴリ）→ プレフィックスなし全件
    private fun prefixFilter(currentTitle: String, candidates: List<NoteFile>): List<NoteFile> {
        val currentPrefix = extractPrefix(currentTitle) ?: return candidates

        val twoDigit = currentPrefix.take(2)
        val oneDigit = currentPrefix.take(1)

        val sameGroup = candidates.filter { note ->
            extractPrefix(note.name)?.take(2) == twoDigit
        }
        val sameCategory = candidates.filter { note ->
            val p = extractPrefix(note.name) ?: return@filter false
            p.take(1) == oneDigit && p.take(2) != twoDigit
        }
        val noPrefix = candidates.filter { extractPrefix(it.name) == null }

        // 兄弟グループ + 同カテゴリ + プレフィックスなし を合わせてAIに渡す
        return (sameGroup + sameCategory + noPrefix).take(PREFIX_CANDIDATE_LIMIT)
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
        private const val PREFIX_CANDIDATE_LIMIT = 40
        private const val PREFIX_DISPLAY_LIMIT = 5
    }
}
