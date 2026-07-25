package com.example.newproject.ui

import com.example.newproject.DistillCandidateItem
import com.example.newproject.DistillState
import com.example.newproject.ReadingTraceCard
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

        assertEquals(
            VigilithMode.Distilling,
            resolveVigilithPresentation("ai", candidates, null).mode
        )
        assertEquals(
            VigilithMode.Idle,
            resolveVigilithPresentation("search", candidates, null).mode
        )
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
                DistillState.NeedsDownload("ノート"),
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
