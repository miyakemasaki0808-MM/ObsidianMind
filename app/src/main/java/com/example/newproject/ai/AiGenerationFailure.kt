package com.example.newproject.ai

import com.google.mlkit.genai.common.GenAiException

/**
 * AICore が短い時間窓の回数制限で要求を断ったか（→ `docs/dev/system/background_ai_ux.md` §6）。
 *
 * 断られたときモデルは動いておらず、**少し待てば同じ要求が通りうる**。
 * `getErrorCode()` で見るのは、SDK が内部コード28も BUSY(9) へ読み替えて返すため
 * （genai-common 1.0.0-beta3 の逆アセンブルで確認）。
 */
internal fun isAiCoreBusy(error: Throwable): Boolean =
    (error as? GenAiException)?.errorCode == GenAiException.ErrorCode.BUSY
