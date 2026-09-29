package com.example.newproject.ui

import com.example.newproject.ui.screen.NoteReaderTab
import com.example.newproject.ui.vigilith.VigilithActionStatus
import com.example.newproject.ui.vigilith.sectionChatStatus
import com.example.newproject.model.state.AiNoticeAction
import com.example.newproject.model.state.AiStatusNotice
import com.example.newproject.model.state.SectionChatProblem
import com.example.newproject.model.state.SectionChatState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * AI操作4状態の導出（[sectionChatStatus]）を固定する。通常画面と全画面が同じ導出を使う。
 */
class VigilithStatusDerivationTest {

    private fun chat(
        summary: String? = null,
        isSummaryLoading: Boolean = false,
        error: String? = null,
        summaryProblem: SectionChatProblem? = error?.let(SectionChatProblem::GenerationFailed)
    ) = SectionChatState(
        sectionTitle = "設計",
        sectionContext = "本文",
        summary = summary,
        isSummaryLoading = isSummaryLoading,
        summaryProblem = summaryProblem
    )

    private fun aiStatus(action: AiNoticeAction) = SectionChatProblem.AiStatus(
        AiStatusNotice("いま使えません。", action, canTryAgainLater = action != AiNoticeAction.None)
    )

    /**
     * **走っていないのに Working にしない。**
     *
     * 端末AIが使えず要約も無い状態で最後の `else` に落ち、
     * 生成していないのに Vigilith と全画面FABが「AI生成中」を出し続けていた。
     */
    @Test
    fun `端末AIが使えず要約も無いならIdle`() {
        assertEquals(
            VigilithActionStatus.Idle,
            sectionChatStatus(chat(summaryProblem = aiStatus(AiNoticeAction.None)))
        )
        assertEquals(
            VigilithActionStatus.Idle,
            sectionChatStatus(chat(summaryProblem = aiStatus(AiNoticeAction.Retry)))
        )
    }

    /** 状態の説明は失敗ではないので、Error にもしない。 */
    @Test
    fun `端末AIが使えないことをエラーとして扱わない`() {
        assertNotEquals(
            VigilithActionStatus.Error,
            sectionChatStatus(chat(summaryProblem = aiStatus(AiNoticeAction.None)))
        )
    }

    /** 説明が出ていても、生成が走っているならそちらが優先される。 */
    @Test
    fun `説明が出ていても生成中ならWorking`() {
        assertEquals(
            VigilithActionStatus.Working,
            sectionChatStatus(
                chat(isSummaryLoading = true, summaryProblem = aiStatus(AiNoticeAction.Retry))
            )
        )
    }

    @Test
    fun `チャットが無ければIdle`() {
        assertEquals(VigilithActionStatus.Idle, sectionChatStatus(null))
    }

    @Test
    fun `エラーは生成中や要約済みより優先される`() {
        assertEquals(
            VigilithActionStatus.Error,
            sectionChatStatus(chat(summary = "要約", isSummaryLoading = true, error = "失敗"))
        )
    }

    @Test
    fun `要約生成中はWorking`() {
        assertEquals(VigilithActionStatus.Working, sectionChatStatus(chat(isSummaryLoading = true)))
    }

    @Test
    fun `要約済みで停止していればReady`() {
        assertEquals(VigilithActionStatus.Ready, sectionChatStatus(chat(summary = "要約")))
    }

    @Test
    fun `チャットはあるが要約もエラーも無い状態はこれから始まるWorking`() {
        assertEquals(VigilithActionStatus.Working, sectionChatStatus(chat()))
    }
}
