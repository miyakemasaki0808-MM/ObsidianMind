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
 * **この経路は、分類が [AICoreClient] の中にあったあいだ1つも書けなかった**
 * （`Generation.getClient()` を抱えていて素のJVMでは組み立てられない）。
 * 4つの別々の事象を `Unavailable` へ畳む誤りが長く残ったのはそれが理由なので、
 * 分類をラムダ受けの純関数へ出したことと、この検査は対になっている。
 */
class AiAvailabilityMappingTest {

    @Test
    fun `AVAILABLE は生成できる`() {
        assertEquals(AiAvailability.Ready, map(FeatureStatus.AVAILABLE))
    }

    @Test
    fun `DOWNLOADABLE はDLの提案`() {
        assertEquals(AiAvailability.NeedsDownload, map(FeatureStatus.DOWNLOADABLE))
    }

    /**
     * **DL中を未取得へ畳まない。** 畳むと蒸留が走行中のDLに対して
     * 「通信量を確認してから開始してください」という新しいCTAを出す。
     * さらに `download()` を呼んでよい状態は `DOWNLOADABLE` だけなので、
     * ここを分けていないと**呼び出し側が禁じ手を踏む**。
     */
    @Test
    fun `DOWNLOADING は未取得とは別の値になる`() {
        assertEquals(AiAvailability.Downloading, map(FeatureStatus.DOWNLOADING))
    }

    /**
     * **恒久非対応と言えるのは、AICoreが無い（古い）ときだけ。**
     *
     * beta2 の逆アセンブルによれば `checkFeatureStatusInternal` は
     * `isAiCoreCompatible` が false のとき AICore へ問い合わせずに `UNAVAILABLE` を返す。
     * つまり `UNAVAILABLE` は端末非対応の**十分条件ではない**。
     */
    @Test
    fun `UNAVAILABLE は AICore が無いときだけ恒久非対応になる`() {
        assertEquals(
            AiAvailability.Unsupported,
            map(FeatureStatus.UNAVAILABLE, isDeviceCapable = false)
        )
    }

    /**
     * **対応端末の `UNAVAILABLE` を恒久扱いしない。** 構成の取得待ちでも同じ値が返るので、
     * ここを `Unsupported` にすると**あとで使えるようになる端末から再試行導線を奪う**。
     */
    @Test
    fun `AICore がある端末の UNAVAILABLE は再試行できる扱いになる`() {
        val result = map(FeatureStatus.UNAVAILABLE, isDeviceCapable = true)
        assertTrue(
            "対応端末の UNAVAILABLE は一時扱いにすること: $result",
            result is AiAvailability.TemporarilyUnavailable
        )
    }

    /**
     * **未知の値を非対応へ寄せない。** SDKが定数を増やしただけで全端末が「非対応」に化け、
     * しかも再試行導線が出ないので、異常が起きていることに誰も気づけなくなる。
     */
    @Test
    fun `未知の FeatureStatus は非対応ではなく一時扱いになる`() {
        val result = map(UNKNOWN_FEATURE_STATUS, isDeviceCapable = false)
        assertTrue(
            "未知の値は TemporarilyUnavailable であること: $result",
            result is AiAvailability.TemporarilyUnavailable
        )
        assertTrue(
            "診断できるよう生の値を残すこと",
            (result as AiAvailability.TemporarilyUnavailable)
                .cause.message?.contains("$UNKNOWN_FEATURE_STATUS") == true
        )
    }

    @Test
    fun `状態を読めなかったら原因を運ぶ`() = runTest {
        val boom = IllegalStateException("AICore not bound")
        val result = readAvailability({ true }) { throw boom }
        assertEquals(AiAvailability.TemporarilyUnavailable(boom), result)
        assertSame(boom, (result as AiAvailability.TemporarilyUnavailable).cause)
    }

    /**
     * **キャンセルを値へ変えない。** `CancellationException` は `Exception` の子なので、
     * 広い catch に任せると一時失敗へ化け、**ノートを切り替えた瞬間に
     * 「利用できません」が一瞬出る**。ここが再throwを固定している唯一の場所。
     */
    @Test
    fun `キャンセルは値へ畳まず再throwする`() = runTest {
        val cancel = CancellationException("note changed")
        try {
            val result = readAvailability({ true }) { throw cancel }
            fail("キャンセルが $result へ畳まれた")
        } catch (e: CancellationException) {
            assertSame(cancel, e)
        }
    }

    @Test
    fun `読めた値はそのまま分類へ渡る`() = runTest {
        assertEquals(AiAvailability.Ready, readAvailability({ true }) { FeatureStatus.AVAILABLE })
    }

    /**
     * **端末能力の判定は `UNAVAILABLE` のときにしか効かない。**
     * 他の状態まで巻き込むと、AICoreの版が古い端末でDLの提案まで消える。
     */
    @Test
    fun `端末能力は UNAVAILABLE 以外の分類を変えない`() {
        for (status in listOf(
            FeatureStatus.AVAILABLE,
            FeatureStatus.DOWNLOADABLE,
            FeatureStatus.DOWNLOADING
        )) {
            assertEquals(
                "status=$status で端末能力に影響されている",
                map(status, isDeviceCapable = true),
                map(status, isDeviceCapable = false)
            )
        }
    }

    private fun map(status: Int, isDeviceCapable: Boolean = true): AiAvailability =
        featureStatusToAvailability(status, isDeviceCapable)

    private companion object {
        /** どの `FeatureStatus` 定数とも重ならない値（SDKが将来増やす定数の代役）。 */
        const val UNKNOWN_FEATURE_STATUS = 9999
    }
}
