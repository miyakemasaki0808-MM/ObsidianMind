package com.example.newproject.ai

import com.google.mlkit.genai.common.FeatureStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * `FeatureStatus` → [AiAvailability] の割り振りを固定する。
 *
 * **この7経路は、分類が [AICoreClient] の中にあったあいだ1つも書けなかった**
 * （`Generation.getClient()` を抱えていて素のJVMでは組み立てられない）。
 * 4つの別々の事象を `Unavailable` へ畳む誤りが長く残ったのはそれが理由なので、
 * 分類をラムダ受けの純関数へ出したことと、この検査は対になっている。
 */
class AiAvailabilityMappingTest {

    @Test
    fun `AVAILABLE は生成できる`() {
        assertEquals(AiAvailability.Ready, featureStatusToAvailability(FeatureStatus.AVAILABLE))
    }

    @Test
    fun `DOWNLOADABLE はDLの提案`() {
        assertEquals(
            AiAvailability.NeedsDownload,
            featureStatusToAvailability(FeatureStatus.DOWNLOADABLE)
        )
    }

    /**
     * **DL中を未取得へ畳まない。** 畳むと蒸留が走行中のDLに対して
     * 「通信量を確認してから開始してください」という新しいCTAを出す。
     */
    @Test
    fun `DOWNLOADING は未取得とは別の値になる`() {
        assertEquals(
            AiAvailability.Downloading,
            featureStatusToAvailability(FeatureStatus.DOWNLOADING)
        )
    }

    @Test
    fun `UNAVAILABLE は再試行しても変わらない非対応`() {
        assertEquals(
            AiAvailability.Unsupported,
            featureStatusToAvailability(FeatureStatus.UNAVAILABLE)
        )
    }

    /**
     * **未知の値を非対応へ寄せない。** SDKが定数を増やしただけで全端末が「非対応」に化け、
     * しかも再試行導線が出ないので、異常が起きていることに誰も気づけなくなる。
     */
    @Test
    fun `未知の FeatureStatus は非対応ではなく取得失敗になる`() {
        val result = featureStatusToAvailability(UNKNOWN_FEATURE_STATUS)
        assertTrue("未知の値は CheckFailed であること: $result", result is AiAvailability.CheckFailed)
        assertTrue(
            "診断できるよう生の値を残すこと",
            (result as AiAvailability.CheckFailed).cause.message?.contains("$UNKNOWN_FEATURE_STATUS") == true
        )
    }

    @Test
    fun `状態を読めなかったら CheckFailed へ原因を運ぶ`() = runTest {
        val boom = IllegalStateException("AICore not bound")
        val result = readAvailability { throw boom }
        assertEquals(AiAvailability.CheckFailed(boom), result)
        assertSame(boom, (result as AiAvailability.CheckFailed).cause)
    }

    /**
     * **キャンセルを値へ変えない。** `CancellationException` は `Exception` の子なので、
     * 広い catch に任せると `CheckFailed` へ化け、**ノートを切り替えた瞬間に
     * 「利用できません」が一瞬出る**。ここが再throwを固定している唯一の場所。
     */
    @Test
    fun `キャンセルは値へ畳まず再throwする`() = runTest {
        val cancel = CancellationException("note changed")
        try {
            val result = readAvailability { throw cancel }
            fail("キャンセルが $result へ畳まれた")
        } catch (e: CancellationException) {
            assertSame(cancel, e)
        }
    }

    @Test
    fun `読めた値はそのまま分類へ渡る`() = runTest {
        assertEquals(AiAvailability.Ready, readAvailability { FeatureStatus.AVAILABLE })
    }

    private companion object {
        /** どの `FeatureStatus` 定数とも重ならない値（SDKが将来増やす定数の代役）。 */
        const val UNKNOWN_FEATURE_STATUS = 9999
    }
}
