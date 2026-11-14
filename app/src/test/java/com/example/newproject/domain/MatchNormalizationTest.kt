package com.example.newproject.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 照らし直しの整え方（[normalizeForMatch]）。控える引用の前後の文字と、書き始めた頃の位置の手掛かりはこれを通る。
 *
 * **整えた文字は制御文字を含まない。** 含むと JSON で1文字6バイトへ膨らみ、痕跡の容量の見積もり（その欄は2倍）の外に出る
 * （→ ReadingTraceLimitsTest・features/margin_quote.md）。
 * U+001C〜U+001F は Java では空白だが、正規表現の `\s` には入らないので、畳まれずに文字の中へ残っていた。
 */
class MatchNormalizationTest {

    @Test
    fun `整えた文字には、どの制御文字も残らない`() {
        val controls = ((0x00..0x1F) + (0x7F..0x9F)).map { it.toChar() }.joinToString("")

        val normalized = normalizeForMatch("前$controls" + "後")

        assertTrue("制御文字が残った: ${normalized.map { it.code.toString(16) }}", normalized.none(Char::isISOControl))
        assertEquals("前 後", normalized)
    }

    @Test
    fun `空白として残っていた4つの制御文字は落とし、前後の語はそのまま続ける`() {
        assertEquals("ab", normalizeForMatch("a\u001Cb"))
        assertEquals("a b", normalizeForMatch("a \u001F b"))
    }

    /**
     * **既存の控えとの照合を変えない。** 4つの制御文字を含まない文字は、直す前と同じに整える。
     * 全角空白・ノーブレークスペースは今までどおり畳まずに残す（畳むと、控えた前後の文字が見つからなくなる）。
     */
    @Test
    fun `4つの制御文字を含まない文字は、直す前と同じに整える`() {
        listOf(
            "日本語の　全角空白と ノーブレーク",
            "改行\nとタブ\tと復帰\rと\u000Bと\u000C",
            "  前後の空白  と   連続した空白  ",
            "制御\u0001文字\u007Fと\u0085と\u009F",
            "em space と ideographic　space"
        ).forEach { text ->
            assertEquals(text, previousNormalizeForMatch(text), normalizeForMatch(text))
        }
    }

    /** 直す前の整え方。照合が変わらないことの基準として、そのまま写す。 */
    private fun previousNormalizeForMatch(text: String): String =
        text.filter { it.isWhitespace() || !it.isISOControl() }
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .joinToString(" ")
}
