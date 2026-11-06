package com.example.newproject.ai

import com.google.mlkit.genai.common.GenAiException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** AICore の回数制限の判定。**本物の `GenAiException` で組み立てる**（SDKの読み替えごと確かめるため）。 */
class AiGenerationFailureTest {

    @Test
    fun `BUSYは回数制限として扱う`() {
        assertTrue(isAiCoreBusy(GenAiException(RuntimeException(), GenAiException.ErrorCode.BUSY)))
    }

    /** SDK は内部コード28を `getErrorCode()` で9へ読み替える。SDKを上げてこれが崩れたら判定を見直す。 */
    @Test
    fun `SDKが内部コード28をBUSYへ読み替えるので同じく扱う`() {
        assertTrue(isAiCoreBusy(GenAiException(RuntimeException(), 28)))
    }

    @Test
    fun `長期の利用枠やほかの例外は回数制限として扱わない`() {
        assertFalse(
            isAiCoreBusy(
                GenAiException(RuntimeException(), GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED)
            )
        )
        assertFalse(isAiCoreBusy(IllegalStateException("BUSY")))
    }

    /** **断られたかどうかだけを見る。** 長期の利用枠は待っても通らないので、節の失敗として数える。 */
    @Test
    fun `回数制限と背面の拒否は断られた扱いで、長期の利用枠とほかの例外は違う`() {
        assertTrue(isAiCoreRefusal(GenAiException(RuntimeException(), GenAiException.ErrorCode.BUSY)))
        assertTrue(isAiCoreRefusal(GenAiException(RuntimeException(), GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED)))
        assertFalse(isAiCoreRefusal(GenAiException(RuntimeException(), GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED)))
        assertFalse(isAiCoreRefusal(IllegalStateException("BUSY")))
    }
}
