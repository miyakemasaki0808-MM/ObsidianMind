package com.example.newproject.ai

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerationConfig
import com.google.mlkit.genai.prompt.ModelConfig
import com.google.mlkit.genai.prompt.ModelPreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

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

// 生成がタイムアウトしたことを呼び出し側の通常エラー処理に乗せるための例外。
// （TimeoutCancellationException のままだと CancellationException として扱われ、
//   エラー表示ではなくジョブのキャンセルとして黙殺されてしまう）
class AiTimeoutException(message: String) : Exception(message)

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

    // 複数機能（要約・関連・セクションチャット等）からの同時呼び出しを直列化する。
    // タイムアウトは待ち時間を含めないよう、ロック取得後に計測する。
    override suspend fun generate(prompt: String): String = generateMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                withTimeout(GENERATE_TIMEOUT_MS) {
                    val response = model.generateContent(prompt)
                    response.candidates.firstOrNull()?.text ?: ""
                }
            } catch (e: TimeoutCancellationException) {
                throw AiTimeoutException("AI応答がタイムアウトしました（${GENERATE_TIMEOUT_MS / 1000}秒）")
            }
        }
    }

    // DOWNLOADABLE 時に呼ぶ。Flow が DownloadCompleted を emit したら generate() が使える
    override fun downloadModel(): Flow<DownloadStatus> = model.download()

    companion object {
        private val generateMutex = Mutex()
        private const val GENERATE_TIMEOUT_MS = 60_000L
    }
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
