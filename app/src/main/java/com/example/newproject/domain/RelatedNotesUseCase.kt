package com.example.newproject.domain

import android.net.Uri
import com.example.newproject.NoteFile
import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.example.newproject.ai.PromptBuilder
import com.example.newproject.ai.RelatedCandidateLine
import com.example.newproject.toNormalizedObsidianTitle
import kotlinx.coroutines.CancellationException

data class RelatedNote(
    val title: String,
    val uri: Uri,
    val isWikilinked: Boolean,
    val lastModified: Long? = null
)

enum class AiRecommendationStatus {
    Ready,
    Unavailable,
    NeedsDownload,
    Error
}

sealed class RelatedNotesResult {
    data class Success(
        val relatedNotes: List<RelatedNote>,
        val aiNotes: List<RelatedNote>,
        val aiStatus: AiRecommendationStatus = AiRecommendationStatus.Ready,
        val aiErrorMessage: String? = null
    ) : RelatedNotesResult()
    data class Error(val message: String) : RelatedNotesResult()
}

class RelatedNotesUseCase(private val aiClient: AiClient) {

    suspend fun findRelated(
        currentTitle: String,
        currentContent: String,
        allNotes: List<NoteFile>,
        wikilinkTitles: Set<String>
    ): RelatedNotesResult {
        return try {
            val candidateNotes = allNotes.filterNot { it.name.isSameTitleAs(currentTitle) }
            val wikilinkTitleSet = wikilinkTitles.map { it.toNormalizedObsidianTitle() }.toSet()

            val relatedNotes = buildDeterministicRelatedNotes(
                currentTitle = currentTitle,
                candidateNotes = candidateNotes,
                wikilinkTitleSet = wikilinkTitleSet
            )

            when (aiClient.checkAvailability()) {
                AiAvailability.Unavailable -> RelatedNotesResult.Success(
                    relatedNotes = relatedNotes,
                    aiNotes = emptyList(),
                    aiStatus = AiRecommendationStatus.Unavailable
                )
                AiAvailability.NeedsDownload -> RelatedNotesResult.Success(
                    relatedNotes = relatedNotes,
                    aiNotes = emptyList(),
                    aiStatus = AiRecommendationStatus.NeedsDownload
                )
                AiAvailability.Available -> {
                    // 決定的チャンネルに出したタイトルをAI候補から除外し（上限適用の前に落とす）、
                    // AIチャンネルを「未表示ノートの補完」に純化する。並べ替え・上限は純ロジックへ委譲。
                    val orderedCandidates = orderRelatedCandidates(
                        currentTitle = currentTitle,
                        candidates = candidateNotes,
                        titleOf = { it.name },
                        excludedTitles = relatedNotes.map { it.title }.toSet(),
                        limit = AI_CANDIDATE_LIMIT
                    )
                    // 各候補に一時ID（C01..）を採番。同名・別Uriも別IDになり確実に解決できる。
                    val idToNote = orderedCandidates
                        .mapIndexed { index, note -> relatedCandidateId(index) to note }
                        .toMap()
                    val prompt = PromptBuilder.buildRelatedNotesPrompt(
                        currentTitle = currentTitle,
                        currentContent = currentContent,
                        candidates = idToNote.map { (id, note) -> RelatedCandidateLine(id, note.name) }
                    )
                    val response = aiClient.generate(prompt)

                    // 応答からIDを抽出→ノートへ解決。候補は既に決定的枠を除外済みだが、
                    // モデルが既出を混ぜても拾わないようUri単位でも念のため落とす（防御）。
                    val relatedUris = relatedNotes.map { it.uri }.toSet()
                    val aiNotes = parseCandidateIds(response, idToNote.keys, AI_RECOMMENDATION_LIMIT)
                        .mapNotNull { id -> idToNote[id] }
                        .filterNot { it.uri in relatedUris }
                        .distinctBy { it.uri }
                        .take(AI_RECOMMENDATION_LIMIT)
                        .map { note ->
                            RelatedNote(
                                title = note.name,
                                uri = note.uri,
                                isWikilinked = note.name.toNormalizedObsidianTitle() in wikilinkTitleSet,
                                lastModified = note.lastModified
                            )
                        }

                    RelatedNotesResult.Success(
                        relatedNotes = relatedNotes,
                        aiNotes = aiNotes
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e   // ジョブキャンセルはエラー扱いせず伝播させる
        } catch (e: Exception) {
            RelatedNotesResult.Success(
                relatedNotes = buildDeterministicRelatedNotes(
                    currentTitle = currentTitle,
                    candidateNotes = allNotes.filterNot { it.name.isSameTitleAs(currentTitle) },
                    wikilinkTitleSet = wikilinkTitles.map { it.toNormalizedObsidianTitle() }.toSet()
                ),
                aiNotes = emptyList(),
                aiStatus = AiRecommendationStatus.Error,
                aiErrorMessage = e.message ?: "Unknown error"
            )
        }
    }

    private fun buildDeterministicRelatedNotes(
        currentTitle: String,
        candidateNotes: List<NoteFile>,
        wikilinkTitleSet: Set<String>
    ): List<RelatedNote> {
        val wikilinkedNotes = candidateNotes.filter {
            it.name.toNormalizedObsidianTitle() in wikilinkTitleSet
        }
        val sameGroupNotes = extractSameGroup(currentTitle, candidateNotes)

        return (wikilinkedNotes + sameGroupNotes)
            .distinctBy { it.uri }
            .take(RELATED_NOTE_LIMIT)
            .map { note ->
                RelatedNote(
                    title = note.name,
                    uri = note.uri,
                    isWikilinked = note.name.toNormalizedObsidianTitle() in wikilinkTitleSet,
                    lastModified = note.lastModified
                )
            }
    }

    // 上2桁が一致するノートのみ返す（決定的チャンネル表示用）。
    // プレフィックス抽出は純ロジックの extractHexPrefix を共用する。
    private fun extractSameGroup(currentTitle: String, candidates: List<NoteFile>): List<NoteFile> {
        val prefix = extractHexPrefix(currentTitle) ?: return emptyList()
        val twoDigit = prefix.take(2)
        return candidates.filter { extractHexPrefix(it.name)?.take(2) == twoDigit }
    }

    private fun String.isSameTitleAs(other: String): Boolean =
        toNormalizedObsidianTitle() == other.toNormalizedObsidianTitle()

    companion object {
        private const val RELATED_NOTE_LIMIT = 5
        private const val AI_RECOMMENDATION_LIMIT = 5
        // AIへ渡す候補タイトルの上限。制限箇所はここ1か所に統一（旧: プロンプト側80と二重）。
        private const val AI_CANDIDATE_LIMIT = 40
    }
}
