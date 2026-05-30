package com.example.newproject.domain

import com.example.newproject.ai.AiAvailability
import com.example.newproject.ai.AiClient
import com.example.newproject.ai.PromptBuilder

sealed class SummaryResult {
    data class Success(val summary: String) : SummaryResult()
    object AiUnavailable : SummaryResult()
    object AiNeedsDownload : SummaryResult()
    data class Error(val message: String) : SummaryResult()
}

class SummarizeUseCase(private val aiClient: AiClient) {

    suspend fun summarize(title: String, content: String): SummaryResult {
        return when (aiClient.checkAvailability()) {
            AiAvailability.Unavailable   -> SummaryResult.AiUnavailable
            AiAvailability.NeedsDownload -> SummaryResult.AiNeedsDownload
            AiAvailability.Available     -> try {
                val prompt = PromptBuilder.buildSummarizePrompt(title, content)
                val summary = aiClient.generate(prompt)
                SummaryResult.Success(summary.trim())
            } catch (e: Exception) {
                SummaryResult.Error(e.message ?: "Unknown error")
            }
        }
    }
}
