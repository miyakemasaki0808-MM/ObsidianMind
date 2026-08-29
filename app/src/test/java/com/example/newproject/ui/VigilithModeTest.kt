package com.example.newproject.ui

import com.example.newproject.domain.projectedBoldRatio
import com.example.newproject.ui.vigilith.VigilithDistillPhase
import com.example.newproject.ui.vigilith.VigilithMode
import com.example.newproject.ui.vigilith.resolveVigilithPresentation
import com.example.newproject.model.state.AiNoticeAction
import com.example.newproject.model.state.AiStatusNotice
import com.example.newproject.model.state.DistillCandidateItem
import com.example.newproject.model.state.DistillState
import com.example.newproject.model.state.ReadingTraceCard
import com.example.newproject.model.state.SummaryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VigilithModeTest {

    @Test
    fun `通常のタブルートではIdleで表示する`() {
        val result = resolveVigilithPresentation(
            currentRoute = "search",
            distillState = DistillState.Idle,
            readingTraceCard = null
        )

        assertTrue(result.isVisible)
        assertEquals(VigilithMode.Idle, result.mode)
    }

    @Test
    fun `分析中は画面を離れてもDistillingを優先する`() {
        val result = resolveVigilithPresentation(
            currentRoute = "note",
            distillState = DistillState.Analyzing("ノート"),
            readingTraceCard = traceCard()
        )

        assertEquals(VigilithMode.Distilling, result.mode)
        assertEquals(VigilithDistillPhase.FindingCandidates, result.distillPhase)
    }

    @Test
    fun `候補を指す姿勢はAIタブ上だけで使う`() {
        val candidates = DistillState.Candidates(
            sourceTitle = "ノート",
            items = listOf(
                DistillCandidateItem("1", "候補", null, "1", null)
            ),
            projectedBoldRatio = 0.1,
            isWithinBoldLimit = true
        )

        val aiResult = resolveVigilithPresentation("ai", candidates, null)
        assertEquals(VigilithMode.Distilling, aiResult.mode)
        assertEquals(VigilithDistillPhase.HoldingCandidate, aiResult.distillPhase)
        assertEquals(
            VigilithMode.Idle,
            resolveVigilithPresentation("search", candidates, null).mode
        )
    }

    @Test
    fun `範囲調整シート表示中は常駐Vigilithを出さない`() {
        // 放置すると HoldingCandidate の姿勢のままモーダルシートと重なる。
        val candidates = DistillState.Candidates(
            sourceTitle = "ノート",
            items = listOf(DistillCandidateItem("1", "候補", null, "1", null)),
            projectedBoldRatio = 0.1,
            isWithinBoldLimit = true,
            rangeSheetCandidateId = "1"
        )

        val result = resolveVigilithPresentation("ai", candidates, null)

        assertFalse(result.isVisible)
        assertEquals(VigilithMode.Idle, result.mode)
    }

    @Test
    fun `再会カード表示中のノート画面ではMessengerになる`() {
        val result = resolveVigilithPresentation(
            currentRoute = "note",
            distillState = DistillState.Idle,
            readingTraceCard = traceCard()
        )

        assertEquals(VigilithMode.Messenger, result.mode)
    }

    @Test
    fun `畳んだ再会カードではIdleへ戻る`() {
        val result = resolveVigilithPresentation(
            currentRoute = "note",
            distillState = DistillState.Idle,
            readingTraceCard = traceCard().copy(isDismissed = true)
        )

        assertEquals(VigilithMode.Idle, result.mode)
    }

    @Test
    fun `全画面ルートとブロッキングオーバーレイでは表示しない`() {
        val fullscreen = resolveVigilithPresentation(
            currentRoute = "note_fullscreen",
            distillState = DistillState.Idle,
            readingTraceCard = null
        )
        val sheet = resolveVigilithPresentation(
            currentRoute = "note",
            distillState = DistillState.Idle,
            readingTraceCard = null,
            isBlockingOverlayVisible = true
        )

        assertFalse(fullscreen.isVisible)
        assertFalse(sheet.isVisible)
    }

    @Test
    fun `要約中は横向き案内のSummarizingになり蒸留姿勢を使わない`() {
        val background = resolveVigilithPresentation(
            currentRoute = "search",
            distillState = DistillState.Idle,
            readingTraceCard = null,
            summaryState = SummaryState.Loading
        )
        val section = resolveVigilithPresentation(
            currentRoute = "note",
            distillState = DistillState.Idle,
            readingTraceCard = null,
            isSectionSummaryLoading = true
        )

        assertEquals(VigilithMode.Summarizing, background.mode)
        assertEquals(VigilithMode.Summarizing, section.mode)
        assertEquals(null, background.distillPhase)
    }

    @Test
    fun `再会カードはバックグラウンド要約より優先するが明示要約には譲る`() {
        val background = resolveVigilithPresentation(
            currentRoute = "note",
            distillState = DistillState.Idle,
            readingTraceCard = traceCard(),
            summaryState = SummaryState.Loading
        )
        val interactive = resolveVigilithPresentation(
            currentRoute = "note",
            distillState = DistillState.Idle,
            readingTraceCard = traceCard(),
            summaryState = SummaryState.Loading,
            isSectionSummaryLoading = true
        )

        assertEquals(VigilithMode.Messenger, background.mode)
        assertEquals(VigilithMode.Summarizing, interactive.mode)
    }

    @Test
    fun `保存中だけ下線工程になりモデル取得中は蒸留を演じない`() {
        val saving = resolveVigilithPresentation(
            currentRoute = "note",
            distillState = DistillState.Saving("ノート"),
            readingTraceCard = null
        )
        val downloading = resolveVigilithPresentation(
            currentRoute = "ai",
            distillState = DistillState.Downloading("ノート", 1L, 10L),
            readingTraceCard = null
        )

        assertEquals(VigilithMode.Distilling, saving.mode)
        assertEquals(VigilithDistillPhase.Underlining, saving.distillPhase)
        assertEquals(VigilithMode.Idle, downloading.mode)
    }

    @Test
    fun `エラーやダウンロード待ちは動作中として演じない`() {
        assertEquals(
            VigilithMode.Idle,
            resolveVigilithPresentation(
                "ai",
                DistillState.Error("失敗"),
                null
            ).mode
        )
        assertEquals(
            VigilithMode.Idle,
            resolveVigilithPresentation(
                "ai",
                DistillState.AiNotice(
                    AiStatusNotice("ダウンロードが必要です。", AiNoticeAction.Download, canTryAgainLater = true)
                ),
                null
            ).mode
        )
    }

    private fun traceCard() = ReadingTraceCard(
        visitCount = 2,
        lastVisitAtMillis = 1L,
        lastSectionTitle = "設計",
        lastProgressPercent = 60
    )
}
