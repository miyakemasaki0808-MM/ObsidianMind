package com.example.newproject.domain

import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.example.newproject.ai.PromptBuilder
import com.example.newproject.model.NoteExcerptLimits
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class SummaryResult {
    data class Success(val summary: String) : SummaryResult()
    object AiUnavailable : SummaryResult()
    object AiNeedsDownload : SummaryResult()
    data class Error(val message: String) : SummaryResult()
}

class SummarizeUseCase(
    private val aiClient: AiClient,
    private val excerptDispatcher: CoroutineDispatcher = Dispatchers.Default
) {

    suspend fun summarize(title: String, content: String): SummaryResult {
        return when (aiClient.checkAvailability()) {
            AiAvailability.Unavailable   -> SummaryResult.AiUnavailable
            AiAvailability.NeedsDownload -> SummaryResult.AiNeedsDownload
            AiAvailability.Available     -> try {
                val excerpt = withContext(excerptDispatcher) {
                    buildNoteExcerpt(content, NoteExcerptLimits.SUMMARY)
                }
                val prompt = PromptBuilder.buildSummarizePrompt(
                    title,
                    excerpt
                )
                val summary = aiClient.generate(prompt)
                SummaryResult.Success(summary.trim())
            } catch (e: CancellationException) {
                throw e   // ジョブキャンセルはエラー扱いせず伝播させる
            } catch (e: Exception) {
                SummaryResult.Error(e.message ?: "Unknown error")
            }
        }
    }
}
