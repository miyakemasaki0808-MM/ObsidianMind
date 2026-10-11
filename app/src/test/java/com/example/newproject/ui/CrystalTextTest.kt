package com.example.newproject.ui

import com.example.newproject.model.Crystal
import com.example.newproject.model.CrystalSource
import com.example.newproject.model.NoteField
import java.time.YearMonth
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class CrystalTextTest {

    @Test
    fun `根拠の行はノート名から拡張子を落として並べる`() {
        val crystal = Crystal(
            createdAt = 0,
            sentence = "共通する筋の一文。",
            sources = listOf(CrystalSource("a/習慣.md", "習慣.md", "断片"), CrystalSource("b.md", "読書メモ", "断片")),
            fileName = "0.json"
        )
        assertEquals("習慣・読書メモ から", crystalSourcesLine(crystal))
    }

    @Test
    fun `一覧への入口は件数を添える`() {
        assertEquals("結晶の一覧（12件）", crystalListEntryLabel(12))
    }

    @Test
    fun `作った日は端末の暦日で出す`() {
        val lateNightUtc = java.time.OffsetDateTime.parse("2026-09-27T20:00:00Z").toInstant().toEpochMilli()
        assertEquals("2026/9/28", crystalDateLabel(lateNightUtc, ZoneOffset.ofHours(9)))
        assertEquals("2026/9/27", crystalDateLabel(lateNightUtc, ZoneOffset.UTC))
    }
    @Test
    fun `棚の見出しは年と月に数を添え、宝石の下は日だけ`() {
        assertEquals("2026年10月 · 7", crystalMonthLabel(YearMonth.of(2026, 10), 7))
        val lateNightUtc = java.time.OffsetDateTime.parse("2026-09-30T20:00:00Z").toInstant().toEpochMilli()
        assertEquals("1日", crystalDayLabel(lateNightUtc, ZoneOffset.ofHours(9)))
        assertEquals("30日", crystalDayLabel(lateNightUtc, ZoneOffset.UTC))
    }

    /** 分野は色以外の手がかりとして文字でも出す（1.4.1）。分からなければ同じ呼び名を使う。 */
    @Test
    fun `開いた中身と読み上げに分野を出し、分からなければ分野なしと言う`() {
        val at = java.time.OffsetDateTime.parse("2026-10-07T12:00:00+09:00").toInstant().toEpochMilli()
        val zone = ZoneOffset.ofHours(9)
        val two = listOf(NoteField.Technical, NoteField.Reflection)
        assertEquals("技術・開発 ＋ 思考・内省 · 2026/10/7", crystalDetailCaption(at, two, zone))
        assertEquals("分野なし・未判定 · 2026/10/7", crystalDetailCaption(at, emptyList(), zone))
        assertEquals("2026年10月7日の結晶、技術・開発と思考・内省", crystalGemDescription(at, two, zone))
        assertEquals("2026年10月7日の結晶、分野なし・未判定", crystalGemDescription(at, emptyList(), zone))
    }

    @Test
    fun `根拠のノート名には、分かれば分野を添える`() {
        assertEquals("設計メモ（技術・開発）", crystalSourceLabel("設計メモ.md", NoteField.Technical))
        assertEquals("設計メモ", crystalSourceLabel("設計メモ.md", null))
    }
}
