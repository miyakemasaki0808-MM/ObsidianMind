package com.example.newproject.domain

import com.example.newproject.data.NoteFile
import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.example.newproject.ai.PromptBuilder
import kotlinx.coroutines.CancellationException

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
                recallCutByKeyword(query, scopeNotes, CANDIDATE_LIMIT) { it.name }
            } else {
                scopeNotes
            }
            val notesByTitle = candidates.associateBy { it.name.toNormalizedObsidianTitle() }

            when (aiClient.checkAvailability()) {
                AiAvailability.Unavailable -> fallback(query, candidates, AiRecommendationStatus.Unavailable)
                AiAvailability.NeedsDownload -> fallback(query, candidates, AiRecommendationStatus.NeedsDownload)
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

                    // Nano が候補外/空を返したら、キーワード一致でフォールバック。
                    if (picked.isEmpty()) fallback(query, candidates, AiRecommendationStatus.Ready)
                    else PickerResult.Success(picked, AiRecommendationStatus.Ready)
                }
            }
        } catch (e: CancellationException) {
            // キャンセルを Error へ畳むと、呼び出し側は「正常に失敗が返った」と
            // 区別できず、追い越された古い要求がエラー表示になる。素通しする。
            throw e
        } catch (e: Exception) {
            PickerResult.Error(e.message ?: "Unknown error")
        }
    }

    // AI が使えない/失敗したときは、キーワード一致の強い順に返す。
    //
    // 以前は絞り込み結果の先頭をそのまま返していたため、候補が上限以下だと
    // 並べ替えを通らず、SAF の列挙順のまま「キーワード一致で表示しています」と
    // 表示していた（UI文言と実装の不一致）。候補数に関係なくスコア順へ統一し、
    // 一致0件は返さない（0件なら画面は「見つかりませんでした。」になる）。
    private fun fallback(
        query: String,
        candidates: List<NoteFile>,
        status: AiRecommendationStatus
    ): PickerResult = PickerResult.Success(
        notes = pickByKeyword(query, candidates, PICK_LIMIT) { it.name }.map { it.toRelatedNote() },
        aiStatus = status
    )

    private fun NoteFile.toRelatedNote(): RelatedNote =
        RelatedNote(title = name, uri = uri, isWikilinked = false, lastModified = lastModified)

    companion object {
        private const val CANDIDATE_LIMIT = 40
        private const val PICK_LIMIT = 3
    }
}
