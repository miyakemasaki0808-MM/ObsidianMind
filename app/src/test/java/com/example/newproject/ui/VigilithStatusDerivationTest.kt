package com.example.newproject.ui

import com.example.newproject.ui.screen.NoteReaderTab
import com.example.newproject.ui.vigilith.VigilithActionStatus
import com.example.newproject.ui.vigilith.fullscreenAiStatus
import com.example.newproject.ui.vigilith.sectionChatStatus
import com.example.newproject.model.state.QuizCard
import com.example.newproject.model.state.QuizState
import com.example.newproject.model.state.SectionChatState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AI操作4状態の導出（[sectionChatStatus] / [fullscreenAiStatus]）を固定する。
 *
 * 統合前は `NoteReaderTab.kt` の2箇所に同じ `when` が逐語コピーされており、
 * 特に全画面側の「要約×クイズ」合成にはテストが1件も無かった。
 */
class VigilithStatusDerivationTest {

    private fun chat(
        summary: String? = null,
        isSummaryLoading: Boolean = false,
        isGenerating: Boolean = false,
        error: String? = null
    ) = SectionChatState(
        sectionTitle = "設計",
        sectionContext = "本文",
        summary = summary,
        isSummaryLoading = isSummaryLoading,
        isGenerating = isGenerating,
        error = error
    )

    private fun quizSuccess(isViewed: Boolean) = QuizState.Success(
        sourceTitle = "設計",
        cards = listOf(QuizCard("問い", listOf("A", "B", "C", "D"), 0, "解説")),
        isViewed = isViewed
    )

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
    fun `要約生成中と回答生成中はどちらもWorking`() {
        assertEquals(VigilithActionStatus.Working, sectionChatStatus(chat(isSummaryLoading = true)))
        assertEquals(
            VigilithActionStatus.Working,
            sectionChatStatus(chat(summary = "要約", isGenerating = true))
        )
    }

    @Test
    fun `要約済みで停止していればReady`() {
        assertEquals(VigilithActionStatus.Ready, sectionChatStatus(chat(summary = "要約")))
    }

    @Test
    fun `チャットはあるが要約もエラーも無い状態はこれから始まるWorking`() {
        assertEquals(VigilithActionStatus.Working, sectionChatStatus(chat()))
    }

    @Test
    fun `全画面ではクイズ生成中もWorkingになる`() {
        assertEquals(
            VigilithActionStatus.Working,
            fullscreenAiStatus(chat(summary = "要約"), QuizState.Loading("設計"))
        )
    }

    @Test
    fun `全画面では生成中がエラーより優先される`() {
        assertEquals(
            VigilithActionStatus.Working,
            fullscreenAiStatus(chat(isSummaryLoading = true), QuizState.Error("失敗", "設計"))
        )
    }

    @Test
    fun `未閲覧のクイズエラーはErrorとして残る`() {
        assertEquals(
            VigilithActionStatus.Error,
            fullscreenAiStatus(null, QuizState.Error("失敗", "設計", isViewed = false))
        )
    }

    @Test
    fun `閲覧済みのクイズ結果はインジケータを光らせない`() {
        assertEquals(
            VigilithActionStatus.Idle,
            fullscreenAiStatus(null, quizSuccess(isViewed = true))
        )
        assertEquals(
            VigilithActionStatus.Idle,
            fullscreenAiStatus(null, QuizState.Error("失敗", "設計", isViewed = true))
        )
    }

    @Test
    fun `未閲覧のクイズ完了はチャットが無くてもReady`() {
        assertEquals(
            VigilithActionStatus.Ready,
            fullscreenAiStatus(null, quizSuccess(isViewed = false))
        )
    }

    @Test
    fun `どちらも動いていなければIdle`() {
        assertEquals(VigilithActionStatus.Idle, fullscreenAiStatus(null, QuizState.Idle))
    }
}
