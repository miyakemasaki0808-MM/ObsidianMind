package com.example.newproject.ai

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Candidate
import com.google.mlkit.genai.prompt.GenerateContentRequest
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

/**
 * 端末AIが「いま使えるか」。**各値は呼び出し側が次に何をするかで割ってある。**
 *
 * 旧版は3値で、[Unsupported]・[CheckFailed]・未知の `FeatureStatus`・
 * **ノート切替によるキャンセル**の4つを `Unavailable` へ畳んでいた。
 * 下流はどれが起きたのか区別できないまま各自で見せ方を作り、
 * 同じ原因に対して互いに矛盾する文言が並んでいた。
 */
sealed class AiAvailability {
    /** 生成できる。 */
    object Ready : AiAvailability()
    /** モデル未取得。DLを提案する（自動DL方式なら黙って開始する）。 */
    object NeedsDownload : AiAvailability()
    /** DL実行中。走行中のDLへ合流して待つ。**新しいCTAは出さない。** */
    object Downloading : AiAvailability()
    /** この端末では動かない。**再試行を出さない**（何度押しても同じ答えが返る）。 */
    object Unsupported : AiAvailability()
    /**
     * 状態を取得できなかった。**再試行を出す**（次は取れるかもしれない）。
     *
     * [cause] は**画面へ出さない。** SDKの `message` は英語か null で
     * ユーザーの次の行動を1文字も助けないため、診断のためだけに運ぶ。
     */
    data class CheckFailed(val cause: Throwable) : AiAvailability()
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

/**
 * プロンプト1本ぶんのトークン計測結果。
 *
 * **[tokenLimit] は入力と出力の合計上限**で、[inputTokens] は入力ぶんだけを数えた値。
 * したがって「まだ入るか」は入力だけを見ても分からず、生成のために予約される
 * [maxOutputTokens] も引いた [headroom] で判断する。
 */
internal data class PromptTokenMeasurement(
    val inputTokens: Int,
    val maxOutputTokens: Int,
    val tokenLimit: Int
) {
    val headroom: Int get() = tokenLimit - inputTokens - maxOutputTokens
}

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

    // 分類そのものは [readAvailability] が持つ。このクラスは `Generation.getClient()` を
    // 抱えていて素のJVMでは組み立てられず、ここに分類を書くとテストが1本も書けない。
    override suspend fun checkAvailability(): AiAvailability = withContext(Dispatchers.IO) {
        readAvailability { model.checkStatus() }
    }

    /**
     * 生成と計測で同じリクエストを組む。別々に作ると「測ったもの」と「実際に投げたもの」がずれ、
     * 計測値が予算判断の根拠として使えなくなる。
     *
     * **`maxOutputTokens` は明示設定しない。** genai-prompt 1.0.0-beta2 は「1〜256」しか
     * 受け付けず（超過は `IllegalArgumentException` で全生成が失敗する）、かつ
     * **未指定時の既定値もちょうど256**である（`GenerateContentRequest.Builder.build()` の
     * null 分岐を逆アセンブルして確認済み）。つまり未指定と256明示は同義なので、
     * 明示して「選んだ値」だと読み手に誤解させるより未指定のままにする。
     * 途切れは [generate] の finishReason 検知で拾う。
     *
     * beta4 では許容範囲・既定値とも 4096 へ上がる。**ソース互換だが動作互換ではない**ので、
     * 上げる際は応答長・推論時間・[GENERATE_TIMEOUT_MS]・Mutex の占有時間が同時に動く。
     */
    private fun buildRequest(prompt: String): GenerateContentRequest =
        generateContentRequest(TextPart(prompt)) {}

    // 複数機能（要約・関連・セクションチャット等）からの同時呼び出しを直列化する。
    // タイムアウトは待ち時間を含めないよう、ロック取得後に計測する。
    override suspend fun generate(prompt: String): String = generateMutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                withTimeout(GENERATE_TIMEOUT_MS) {
                    val request = buildRequest(prompt)
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

    // ─────────────────────────────────────────────────────────────────────────
    // 計測用（開発専用）— 本番の生成経路からは呼ばない。
    //
    // `AiClient` インターフェースへ載せないのは、計測が端末AI固有の関心事であり、
    // `StubAiClient` に実装義務を負わせる筋合いが無いため。ここに置く限り、
    // 本番の呼び出し経路は1つも増えない。
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * プロンプト1本の入力トークン数を数える。
     *
     * [generate] の Mutex には相乗りさせない。計測は生成を伴わないので、直列化の待ち行列へ
     * 並べると「計測のために生成が待たされる」だけになる。
     */
    internal suspend fun countPromptTokens(prompt: String): Int = withContext(Dispatchers.IO) {
        model.countTokens(buildRequest(prompt)).totalTokens
    }

    /** 入力と出力を合わせたトークン上限。 */
    internal suspend fun tokenLimit(): Int = withContext(Dispatchers.IO) {
        model.getTokenLimit()
    }

    /**
     * モデルを事前に立ち上げる。
     *
     * 本番の生成経路では呼んでいない（初回の遅さは許容している）。計測で使うのは、
     * **トークン系APIがセッション確立を前提にしている可能性**を切り分けるため。
     */
    internal suspend fun warmup() = withContext(Dispatchers.IO) {
        model.warmup()
    }

    /**
     * 生成時に予約される出力トークン数。
     *
     * SDKが埋めた実値をリクエストから読むので、SDK版を上げれば既定値の変化に自動で追随する。
     * 端末へ問い合わせないため、AICoreの対応状況に関係なく必ず取れる。
     */
    internal fun reservedOutputTokens(): Int = buildRequest("").maxOutputTokens

    /**
     * [countPromptTokens] と [tokenLimit] を1つにまとめる。
     *
     * **どちらが落ちたかを切り分けたい場合は個別に呼ぶこと。** 端末AIの能力は
     * 「生成できる」「トークンを数えられる」が別々に決まり得るため、まとめて呼ぶと
     * 欠けている能力を特定できない。
     */
    internal suspend fun measurePrompt(prompt: String): PromptTokenMeasurement =
        PromptTokenMeasurement(
            inputTokens = countPromptTokens(prompt),
            maxOutputTokens = reservedOutputTokens(),
            tokenLimit = tokenLimit()
        )

    /** 計測ログへ載せるモデル識別子。端末やモデル世代が違う数値を後から比較するために要る。 */
    internal suspend fun baseModelName(): String = withContext(Dispatchers.IO) {
        model.getBaseModelName()
    }

    /**
     * skip 判定に使う生の [FeatureStatus]。
     *
     * [checkAvailability] は例外を [AiAvailability.CheckFailed] という**値**へ変える。
     * 本番の見せ方としてはそれが正しいが、計測テストの skip 判定に使うと
     * **SDKの回帰が「取得できなかったので skip」に化けて見逃される**。
     * skip 判定は既知の [FeatureStatus] だけで行い、例外は skip せず失敗させたいので、
     * ここでは投げるまま渡す。
     */
    internal suspend fun featureStatus(): Int = withContext(Dispatchers.IO) {
        model.checkStatus()
    }

    companion object {
        private val generateMutex = Mutex()
        private const val GENERATE_TIMEOUT_MS = 60_000L
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
    override suspend fun checkAvailability() = AiAvailability.Ready
    override suspend fun generate(prompt: String) =
        "（スタブ）AICoreClient に差し替えると Gemini Nano 4 が要約を生成します。"
    override fun downloadModel(): Flow<DownloadStatus> = emptyFlow()
}
