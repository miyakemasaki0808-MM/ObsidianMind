package com.example.newproject.ai

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Candidate
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerationConfig
import com.google.mlkit.genai.prompt.ModelConfig
import com.google.mlkit.genai.prompt.ModelPreference
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
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

// 出力トークン上限に達して応答が途中で切れたことを示す例外。
// 途切れた文章をそのまま保存・表示するより、エラーとして再試行を促す方が安全。
class AiTruncatedException(message: String) : Exception(message)

// ─────────────────────────────────────────────────────────────────────────────
// AICoreClient — Gemini Nano via ML Kit GenAI Prompt API
// 実際に動くモデル世代（nano-v2 / v3）は端末のAICoreが決める。
// ModelPreference.FULL は世代指定ではなく「速度より精度を優先」の指定。
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
                    val request = generateContentRequest(TextPart(prompt)) {
                        maxOutputTokens = MAX_OUTPUT_TOKENS
                    }
                    val candidate = model.generateContent(request).candidates.firstOrNull()
                    if (candidate?.finishReason == Candidate.FinishReason.MAX_TOKENS) {
                        throw AiTruncatedException(
                            "AI応答が長すぎて途中で打ち切られました。もう一度お試しください。"
                        )
                    }
                    candidate?.text ?: ""
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

        // 全機能共通の出力上限。上限自体に処理負荷はなく（負荷は実際に生成された
        // トークン数で決まる）、未設定時のSDKデフォルトで応答が黙って切れるのを防ぐ。
        // 最長出力のクイズ（5問×選択肢4つ＋解説、日本語）が収まる値にしている。
        // Gemini Nano のコンテキスト約4096トークン（入力＋出力）に対し、
        // 最大入力の補記プロンプト（約2000〜2500トークン）と併せても枠内に収まる。
        private const val MAX_OUTPUT_TOKENS = 1024
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// StubAiClient — 開発・テスト用スタブ
// 本番コードからの参照はないが意図的に残している。Nano非対応のエミュレータ等で
// UIフローを確認したいとき、NoteViewModel の `aiClient = AICoreClient()` を
// `StubAiClient()` に差し替えて使う。
// ─────────────────────────────────────────────────────────────────────────────
@Suppress("unused")
class StubAiClient : AiClient {
    override suspend fun checkAvailability() = AiAvailability.Available
    override suspend fun generate(prompt: String) =
        "（スタブ）AICoreClient に差し替えると Gemini Nano 4 が要約を生成します。"
    override fun downloadModel(): Flow<DownloadStatus> = emptyFlow()
}
