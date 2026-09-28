package com.example.newproject.ui

import com.example.newproject.model.Crystal
import com.example.newproject.model.CrystalSource
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
}
