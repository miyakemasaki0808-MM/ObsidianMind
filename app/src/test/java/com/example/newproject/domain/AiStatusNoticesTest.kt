package com.example.newproject.domain

import com.example.newproject.ai.AiAvailability
import com.example.newproject.model.state.AiNoticeAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI状態の見せ方を1箇所へ寄せた純関数を固定する。
 *
 * **導線（[AiNoticeAction]）が本体である。** 文言だけ揃えても、非対応に再試行ボタンが
 * 付いたままなら「再試行しても直らないものを押させる」問題は残る。
 */
class AiStatusNoticesTest {

    @Test
    fun `使えるときは説明することが無い`() {
        assertNull(aiStatusNotice(AiAvailability.Ready, LABEL))
    }

    @Test
    fun `未取得はDLを促す`() {
        val notice = requireNotNull(aiStatusNotice(AiAvailability.NeedsDownload, LABEL))
        assertEquals(AiNoticeAction.Download, notice.action)
        assertTrue(notice.message, notice.message.contains(LABEL))
    }

    /**
     * **DL中に新しいCTAを重ねない。** ここが `Download` に戻ると、走行中のDLに対して
     * 「通信量を確認してから開始してください」と出る（分離前の症状そのもの）。
     */
    @Test
    fun `DL中は合流するだけで導線を出さない`() {
        val notice = requireNotNull(aiStatusNotice(AiAvailability.Downloading, LABEL))
        assertEquals(AiNoticeAction.None, notice.action)
        assertTrue(notice.message, notice.message.contains(LABEL))
    }

    /** **非対応に再試行を出さない。** 何度押しても同じ答えが返る。 */
    @Test
    fun `非対応は再試行導線を持たない`() {
        val notice = requireNotNull(aiStatusNotice(AiAvailability.Unsupported, LABEL))
        assertEquals(AiNoticeAction.None, notice.action)
        assertTrue(notice.message, notice.message.contains(LABEL))
    }

    /** **一時失敗には再試行を出す。** 次は状態を取れるかもしれない。 */
    @Test
    fun `取得失敗は再試行導線を持つ`() {
        val notice = requireNotNull(
            aiStatusNotice(AiAvailability.TemporarilyUnavailable(IllegalStateException("boom")), LABEL)
        )
        assertEquals(AiNoticeAction.Retry, notice.action)
        assertTrue(notice.message, notice.message.contains(LABEL))
    }

    /**
     * **SDKの例外文言を画面へ出さない。** 英語か null で、ユーザーの次の行動を助けない。
     * 原因は診断のために型が運ぶだけにする。
     */
    @Test
    fun `取得失敗の原因を文言へ混ぜない`() {
        val cause = IllegalStateException("AICore service not bound")
        val notice = requireNotNull(aiStatusNotice(AiAvailability.TemporarilyUnavailable(cause), LABEL))
        assertFalse(notice.message, notice.message.contains("AICore"))
        assertFalse(notice.message, notice.message.contains("not bound"))
    }

    /**
     * **非対応と取得失敗を同じ見せ方へ畳まない。** 畳んでいたことが、
     * 「再試行しても直らないものに再試行導線が付く」状態を生んでいた。
     */
    @Test
    fun `非対応と取得失敗は文言も導線も違う`() {
        val unsupported = requireNotNull(aiStatusNotice(AiAvailability.Unsupported, LABEL))
        val failed = requireNotNull(
            aiStatusNotice(AiAvailability.TemporarilyUnavailable(IllegalStateException()), LABEL)
        )
        assertNotEquals(unsupported.message, failed.message)
        assertNotEquals(unsupported.action, failed.action)
    }

    @Test
    fun `機能名は呼び出し側の指定がそのまま入る`() {
        val notice = requireNotNull(aiStatusNotice(AiAvailability.Unsupported, "蒸留"))
        assertEquals("この端末では蒸留を利用できません。", notice.message)
        assertNotNull(aiStatusNotice(AiAvailability.Unsupported, "Q&A"))
    }

    private companion object {
        const val LABEL = "テスト機能"
    }
}
