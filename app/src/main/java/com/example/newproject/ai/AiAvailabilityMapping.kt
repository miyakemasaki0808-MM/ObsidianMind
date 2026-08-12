package com.example.newproject.ai

import com.google.mlkit.genai.common.FeatureStatus
import kotlinx.coroutines.CancellationException

/**
 * 端末の状態問い合わせを [AiAvailability] へ変換する。
 *
 * **[AICoreClient] の中に置かない。** あのクラスは `Generation.getClient()` を抱えていて
 * 素のJVMでは組み立てられないため、「非対応／一時失敗／未知の値」の割り振りを
 * **一度も検証できなかった**。それが4値を1つへ畳む誤りが長く残った理由でもある。
 * 状態の読み取りをラムダで受ければ、投げる側も返す側もJVMテストから作れる。
 *
 * @param readStatus 生の `FeatureStatus` を返す suspend 関数（本番は `model.checkStatus()`）
 */
internal suspend fun readAvailability(readStatus: suspend () -> Int): AiAvailability =
    try {
        featureStatusToAvailability(readStatus())
    } catch (e: CancellationException) {
        // ノート切替のたびに偽の「非対応」を出さない。`CancellationException` は
        // `Exception` の子なので、下の catch に任せると切替が失敗として画面に出る。
        throw e
    } catch (e: Exception) {
        AiAvailability.CheckFailed(e)
    }

/** 既知の [FeatureStatus] を [AiAvailability] へ割り振る。 */
internal fun featureStatusToAvailability(status: Int): AiAvailability = when (status) {
    FeatureStatus.AVAILABLE -> AiAvailability.Ready
    FeatureStatus.DOWNLOADABLE -> AiAvailability.NeedsDownload
    FeatureStatus.DOWNLOADING -> AiAvailability.Downloading
    FeatureStatus.UNAVAILABLE -> AiAvailability.Unsupported
    // 未知の値を Unsupported にしない。SDKが定数を増やしただけで全端末が「非対応」に化け、
    // しかも再試行が出ないので異常だと誰も気づけない。再試行可能側へ寄せる。
    else -> AiAvailability.CheckFailed(IllegalStateException("未知の FeatureStatus: $status"))
}
