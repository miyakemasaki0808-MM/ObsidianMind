package com.example.newproject.domain

import com.example.newproject.NoteFile
import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.example.newproject.ai.PromptBuilder
import com.example.newproject.toNormalizedObsidianTitle

sealed class PickerResult {
    data class Success(
        val notes: List<RelatedNote>,
        val aiStatus: AiRecommendationStatus = AiRecommendationStatus.Ready
    ) : PickerResult()
    data class Error(val message: String) : PickerResult()
}

/**
 * AIピッカー（さがすタブ）のキーワードモード。
 * RelatedNotesUseCase と同じ「候補を絞る → Nano に選ばせる → タイトルをノートに戻す」骨格。
 * 起点が「現在ノート」ではなく「自然文クエリ」で、絞り込みはプレフィックスではなく
 * キーワード再現率カット（候補が上限を超えるときのみ発火）に差し替えている。
 */
class SearchPickerUseCase(private val aiClient: AiClient) {

    suspend fun pick(query: String, scopeNotes: List<NoteFile>): PickerResult {
        if (scopeNotes.isEmpty()) {
            return PickerResult.Success(emptyList())
        }
        return try {
            // 候補が上限超のときだけ、取りこぼさない程度に粗く絞る（精度は Nano が担保）。
            val candidates = if (scopeNotes.size > CANDIDATE_LIMIT) {
                keywordRecallCut(query, scopeNotes, CANDIDATE_LIMIT)
            } else {
                scopeNotes
            }
            val notesByTitle = candidates.associateBy { it.name.toNormalizedObsidianTitle() }

            when (aiClient.checkAvailability()) {
                AiAvailability.Unavailable -> fallback(candidates, AiRecommendationStatus.Unavailable)
                AiAvailability.NeedsDownload -> fallback(candidates, AiRecommendationStatus.NeedsDownload)
                AiAvailability.Available -> {
                    val prompt = PromptBuilder.buildPickerPrompt(query, candidates.map { it.name })
                    val response = aiClient.generate(prompt)
                    val picked = response.lineSequence()
                        .map { it.cleanAiTitle() }
                        .filter { it.isNotBlank() }
                        .mapNotNull { title -> notesByTitle[title.toNormalizedObsidianTitle()] }
                        .distinctBy { it.uri }
                        .take(PICK_LIMIT)
                        .map { it.toRelatedNote() }
                        .toList()

                    // Nano が候補外/空を返したら、キーワードカット結果でフォールバック。
                    if (picked.isEmpty()) fallback(candidates, AiRecommendationStatus.Ready)
                    else PickerResult.Success(picked, AiRecommendationStatus.Ready)
                }
            }
        } catch (e: Exception) {
            PickerResult.Error(e.message ?: "Unknown error")
        }
    }

    // AI が使えない/失敗したときは、絞り込み結果の先頭を素の検索結果として返す。
    private fun fallback(candidates: List<NoteFile>, status: AiRecommendationStatus): PickerResult =
        PickerResult.Success(
            notes = candidates.take(PICK_LIMIT).map { it.toRelatedNote() },
            aiStatus = status
        )

    // 文字bigramの重なり数で並べ替えて上位を返す（日本語トークナイザ不要・再現率重視）。
    // スコアは事前に1回だけ計算する。sortedByDescending のセレクタは比較のたびに
    // 呼ばれるため、以前は bigram 集合の構築が O(n log n) 回走っていた（P4）。
    private fun keywordRecallCut(query: String, notes: List<NoteFile>, limit: Int): List<NoteFile> {
        val queryBigrams = query.toBigrams()
        if (queryBigrams.isEmpty()) return notes.take(limit)
        return notes
            .map { note -> note to note.name.toBigrams().count { it in queryBigrams } }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    private fun String.toBigrams(): Set<String> {
        val cleaned = lowercase().filterNot { it.isWhitespace() }
        return when {
            cleaned.isEmpty() -> emptySet()
            cleaned.length == 1 -> setOf(cleaned)
            else -> (0 until cleaned.length - 1).map { cleaned.substring(it, it + 2) }.toSet()
        }
    }

    private fun NoteFile.toRelatedNote(): RelatedNote =
        RelatedNote(title = name, uri = uri, isWikilinked = false, lastModified = lastModified)

    companion object {
        private const val CANDIDATE_LIMIT = 40
        private const val PICK_LIMIT = 3
    }
}
