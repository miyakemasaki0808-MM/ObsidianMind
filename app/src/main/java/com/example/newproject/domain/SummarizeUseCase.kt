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
            AiAvailability.Unsupported -> SummaryResult.AiUnavailable
            // 要約は**ノートを開くと自動で走る**ので、状態を取れなかったことを見せない。
            // 押していない機能が理由を語り出すと、読書中ずっと騒がしくなる。
            is AiAvailability.TemporarilyUnavailable -> SummaryResult.AiUnavailable
            // **DL中はDLを始めない**（走行中のDLへ合流できないため → AiAvailability.Downloading）。
            // 自動機能なので黙って諦め、次にノートを開いたときに取り直す。
            AiAvailability.Downloading -> SummaryResult.AiUnavailable
            // 自動DL方式。`downloadModel()` を呼んでよいのはここだけ。
            AiAvailability.NeedsDownload -> SummaryResult.AiNeedsDownload
            AiAvailability.Ready -> try {
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
