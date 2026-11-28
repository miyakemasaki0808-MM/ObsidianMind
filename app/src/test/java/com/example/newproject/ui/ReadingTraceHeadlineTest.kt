package com.example.newproject.ui

import com.example.newproject.ui.component.elapsedLabel
import com.example.newproject.ui.component.readingTraceHeadline
import com.example.newproject.model.state.ReadingTraceCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * カード本文の組み立ては純関数に切り出してあるので、文面をJVMテストで押さえられる。
 * AI要約が無くてもこの1文だけで「前回の自分」が伝わることが要件。
 */
class ReadingTraceHeadlineTest {

    @Test
    fun `回数と割合を出さず、いつとどの節までを言う`() {
        listOf(1, 3).forEach { visits ->
            val text = readingTraceHeadline(card(visitCount = visits, section = "導入", progress = 40), NOW)
            assertEquals("今日、「導入」の節まで読みました。", text)
            assertTrue("回数が出ている: $text", !text.contains("回"))
            assertTrue("割合が出ている: $text", !text.contains("%"))
        }
    }

    // 「今日、」に助詞は付けず、「5日前に、」には付ける。
    @Test
    fun `particle is added only to relative labels`() {
        val today = readingTraceHeadline(card(visitCount = 1, section = null, progress = 30), NOW)
        val past = readingTraceHeadline(
            card(visitCount = 1, section = null, progress = 30).copy(lastVisitAtMillis = NOW - days(5)),
            NOW
        )

        assertTrue(today.startsWith("今日、"))
        assertTrue(past.startsWith("5日前に、"))
    }

    @Test
    fun `finished reading is stated plainly instead of a percentage`() {
        val text = readingTraceHeadline(card(visitCount = 1, section = "まとめ", progress = 100), NOW)

        assertTrue(text.contains("最後まで読みました"))
        assertTrue("到達率が残っている: $text", !text.contains("100%"))
    }

    // 見出しの無いノートは節の名前が付かないので、途中までと言う。
    @Test
    fun `note without headings says it was read partway`() {
        val text = readingTraceHeadline(card(visitCount = 1, section = null, progress = 30), NOW)

        assertEquals("今日、途中まで読みました。", text)
    }

    /**
     * **「止まった」と言わない。** 記録は前回いちばん先まで読んだところで、巻き戻して離れても下がらない。
     * 最後に見ていた場所だと読める文言は、事実と違う案内になる（→ features/reunion_card.md 判断6）。
     */
    @Test
    fun `見出しの1文は止まったと言わない`() {
        listOf(
            card(visitCount = 2, section = "導入", progress = 40),
            card(visitCount = 2, section = null, progress = 40),
            card(visitCount = 2, section = "まとめ", progress = 100)
        ).forEach { card ->
            val text = readingTraceHeadline(card, NOW)
            assertTrue("止まったと案内している: $text", !text.contains("止ま"))
        }
    }

    @Test
    fun `blank section title falls back to partway`() {
        val text = readingTraceHeadline(card(visitCount = 1, section = "  ", progress = 30), NOW)

        assertTrue(text.contains("途中まで読みました"))
    }

    @Test
    fun `elapsed label buckets by distance`() {
        assertEquals("今日", elapsedLabel(NOW, NOW))
        assertEquals("昨日", elapsedLabel(NOW - days(1), NOW))
        assertEquals("5日前", elapsedLabel(NOW - days(5), NOW))
        assertEquals("29日前", elapsedLabel(NOW - days(29), NOW))
        assertEquals("1ヶ月前", elapsedLabel(NOW - days(30), NOW))
        assertEquals("6ヶ月前", elapsedLabel(NOW - days(200), NOW))
        // 1ヶ月=30日で割るため、360〜364日は「12ヶ月前」になる（365日から「1年前」）。
        // 粒度を粗くする前提なのでこの5日間のズレは許容する。
        assertEquals("12ヶ月前", elapsedLabel(NOW - days(360), NOW))
        assertEquals("1年前", elapsedLabel(NOW - days(365), NOW))
        assertEquals("2年前", elapsedLabel(NOW - days(800), NOW))
    }

    // 端末の時計が巻き戻っても「未来に読んだ」等の破綻した表示にならないこと。
    @Test
    fun `future timestamp degrades to today`() {
        assertEquals("今日", elapsedLabel(NOW + days(3), NOW))
    }

    private fun card(visitCount: Int, section: String?, progress: Int) = ReadingTraceCard(
        visitCount = visitCount,
        lastVisitAtMillis = NOW,
        lastSectionTitle = section,
        lastProgressPercent = progress
    )

    private companion object {
        const val NOW = 1_800_000_000_000L
        fun days(count: Long): Long = count * 24L * 60L * 60L * 1000L
    }
}
