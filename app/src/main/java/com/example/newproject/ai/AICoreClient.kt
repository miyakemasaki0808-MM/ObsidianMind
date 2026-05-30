package com.example.newproject.ai

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerationConfig
import com.google.mlkit.genai.prompt.ModelConfig
import com.google.mlkit.genai.prompt.ModelPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext

sealed class AiAvailability {
    object Available : AiAvailability()
    object Unavailable : AiAvailability()
    object NeedsDownload : AiAvailability()
}

interface AiClient {
    suspend fun checkAvailability(): AiAvailability
    suspend fun generate(prompt: String): String
    fun downloadModel(): Flow<DownloadStatus>
}

// ─────────────────────────────────────────────────────────────────────────────
// AICoreClient — Gemini Nano 4 Full (E4B) via ML Kit GenAI Prompt API
// ─────────────────────────────────────────────────────────────────────────────
class AICoreClient : AiClient {

    private val model by lazy {
        val modelConfig = ModelConfig.Builder().apply {
            preference = ModelPreference.FULL
        }.build()
        val config = GenerationConfig.Builder().apply {
            this.modelConfig = modelConfig
        }.build()
        Generation.getClient(config)
    }

    override suspend fun checkAvailability(): AiAvailability = withContext(Dispatchers.IO) {
        try {
            when (model.checkStatus()) {
                FeatureStatus.AVAILABLE    -> AiAvailability.Available
                FeatureStatus.DOWNLOADABLE,
                FeatureStatus.DOWNLOADING  -> AiAvailability.NeedsDownload
                else                       -> AiAvailability.Unavailable
            }
        } catch (e: Exception) {
            AiAvailability.Unavailable
        }
    }

    override suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        val response = model.generateContent(prompt)
        response.candidates.firstOrNull()?.text ?: ""
    }

    // DOWNLOADABLE 時に呼ぶ。Flow が DownloadCompleted を emit したら generate() が使える
    override fun downloadModel(): Flow<DownloadStatus> = model.download()
}

// ─────────────────────────────────────────────────────────────────────────────
// StubAiClient — 開発・テスト用スタブ
// ─────────────────────────────────────────────────────────────────────────────
class StubAiClient : AiClient {
    override suspend fun checkAvailability() = AiAvailability.Available
    override suspend fun generate(prompt: String) =
        "（スタブ）AICoreClient に差し替えると Gemini Nano 4 が要約を生成します。"
    override fun downloadModel(): Flow<DownloadStatus> = emptyFlow()
}
