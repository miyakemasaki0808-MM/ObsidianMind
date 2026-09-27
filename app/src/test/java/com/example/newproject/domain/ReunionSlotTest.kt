package com.example.newproject.domain

import com.example.newproject.model.ReunionKind
import com.example.newproject.model.state.ReadingTraceCard
import com.example.newproject.model.state.SummaryState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// 再会カードの枠の中身（→ features/reunion_card.md 判断6「枠の中身は1つの純関数が決める」の表）。
class ReunionSlotTest {

    private val summary = SummaryState.Success("Kotlin の Flow の使い方をまとめたノート。後半は例外の扱い。")

    // ── 途中まで ────────────────────────────────────────────────────────

    @Test
    fun `途中までで前後の要約があればそれを出す`() {
        val slot = reunionSlot(midway(item = "直前は導入。この先は例。", kind = ReunionKind.Passage), summary)
        assertEquals(ReunionSlot.Shown("直前は導入。この先は例。", ReunionKind.Passage), slot)
    }

    @Test
    fun `途中までで前後の要約を作っている間は待ちにする`() {
        assertEquals(ReunionSlot.Waiting, reunionSlot(midway(loading = true), summary))
    }

    /** 知りたいのは読み進めたところの前後。出せなければ要約で埋めず、枠ごと出さない。 */
    @Test
    fun `途中までで前後の要約が無ければ、ノートの要約へ倒さない`() {
        assertEquals(ReunionSlot.Hidden, reunionSlot(midway(), summary))
    }

    // ── 最後まで ────────────────────────────────────────────────────────

    @Test
    fun `読了で問いが決まっていればその原文を出す`() {
        val slot = reunionSlot(finished(item = "これでいいのか。", kind = ReunionKind.Question), summary)
        assertEquals(ReunionSlot.Shown("これでいいのか。", ReunionKind.Question), slot)
    }

    @Test
    fun `読了で選別が終わっていなければ、要約を先に出さない`() {
        assertEquals(ReunionSlot.Waiting, reunionSlot(finished(loading = true), summary))
    }

    @Test
    fun `読了で1件が無ければ、ノートの要約の先頭1文を出す`() {
        val slot = reunionSlot(finished(), summary)
        assertEquals(ReunionSlot.Shown("Kotlin の Flow の使い方をまとめたノート。", ReunionKind.Overview), slot)
    }

    @Test
    fun `読了で要約がまだなら待ちにする`() {
        assertEquals(ReunionSlot.Waiting, reunionSlot(finished(), SummaryState.Idle))
        assertEquals(ReunionSlot.Waiting, reunionSlot(finished(), SummaryState.Loading))
        assertEquals(ReunionSlot.Waiting, reunionSlot(finished(), SummaryState.Downloading(1L, 10L)))
    }

    @Test
    fun `読了で要約が出ないなら枠を出さない`() {
        assertEquals(ReunionSlot.Hidden, reunionSlot(finished(), SummaryState.AiUnavailable))
        assertEquals(ReunionSlot.Hidden, reunionSlot(finished(), SummaryState.Error("失敗")))
    }

    // ── 先頭1文 ─────────────────────────────────────────────────────────

    @Test
    fun `先頭1文は括弧の内側で切らない`() {
        assertEquals("「これでいいのか？」と書いたノート。", firstSentenceOf("「これでいいのか？」と書いたノート。次の文。"))
    }

    @Test
    fun `終止符が無ければ全体を1文とみなし、空なら無い`() {
        assertEquals("終止符の無い要約", firstSentenceOf("  終止符の無い要約  "))
        assertNull(firstSentenceOf("   "))
    }

    private fun midway(
        item: String? = null,
        kind: ReunionKind? = null,
        loading: Boolean = false
    ) = card(progress = 40, item = item, kind = kind, loading = loading)

    private fun finished(
        item: String? = null,
        kind: ReunionKind? = null,
        loading: Boolean = false
    ) = card(progress = 100, item = item, kind = kind, loading = loading)

    private fun card(progress: Int, item: String?, kind: ReunionKind?, loading: Boolean) =
        ReadingTraceCard(
            visitCount = 2,
            lastVisitAtMillis = 0L,
            lastSectionTitle = "導入",
            lastProgressPercent = progress,
            aiSummary = item,
            aiSummaryKind = kind,
            isSummaryLoading = loading
        )
}
