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
    private val cache: SummaryCache,
    private val excerptDispatcher: CoroutineDispatcher = Dispatchers.Default
) {

    suspend fun summarize(title: String, content: String): SummaryResult {
        // **状態確認の例外も終端へ落とす。** `AiClient` は他実装を許す公開契約なので
        // `checkAvailability()` は投げうる。投げたまま抜けると `SummaryState.Loading` が
        // 残り、要約パネルが永久に回る。自動起動なので黙る側へ倒す。
        val availability = try {
            aiClient.checkAvailability()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return SummaryResult.AiUnavailable
        }
        return when (availability) {
            // 保存済みも引かない。非対応端末でノートを開くたびに抜粋を作らないため
            AiAvailability.Unsupported -> SummaryResult.AiUnavailable
            // 自動DL方式。`downloadModel()` を呼んでよいのはここだけ。
            // DL完了後の再開で保存済みを引くので、ここで引いてDLの契機を変えない。
            AiAvailability.NeedsDownload -> SummaryResult.AiNeedsDownload
            AiAvailability.Ready,
            AiAvailability.Downloading,
            is AiAvailability.TemporarilyUnavailable -> try {
                val excerpt = withContext(excerptDispatcher) {
                    buildNoteExcerpt(content, NoteExcerptLimits.SUMMARY)
                }
                val prompt = PromptBuilder.buildSummarizePrompt(
                    title,
                    excerpt
                )
                // 生成は決定的なので、保存済みは同じモデルで生成し直した結果と同じである
                // （→ note_summary.md 判断6）。だから生成できない状態でも出してよい
                cache.find(prompt)?.let { return SummaryResult.Success(it) }
                when (availability) {
                    AiAvailability.Ready -> generate(prompt)
                    // 要約は**ノートを開くと自動で走る**ので、状態を取れなかったことを見せない。
                    // 押していない機能が理由を語り出すと、読書中ずっと騒がしくなる。
                    // **DL中はDLを始めない**（走行中のDLへ合流できないため → AiAvailability.Downloading）。
                    // 自動機能なので黙って諦め、次にノートを開いたときに取り直す。
                    else -> SummaryResult.AiUnavailable
                }
            } catch (e: CancellationException) {
                throw e   // ジョブキャンセルはエラー扱いせず伝播させる
            } catch (e: Exception) {
                SummaryResult.Error(e.message ?: "Unknown error")
            }
        }
    }

    private suspend fun generate(prompt: String): SummaryResult {
        val summary = aiClient.generate(prompt).trim()
        // 空は保存しない。保存すると、次に開いても空の要約が出続ける
        if (summary.isNotEmpty()) cache.save(prompt, summary)
        return SummaryResult.Success(summary)
    }
}
