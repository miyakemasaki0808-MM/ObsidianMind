package com.example.newproject.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase 3b: 本文シグナル（tags/snippet/title）による再ランク採点の純ロジック検証。
// 実際のプロンプト順・Nanoの選択はPixel実機で担保する。
class RelatedContextScoringTest {

    @Test
    fun `textBigramsは小文字化と空白畳みを行い1文字以下は空`() {
        assertEquals(textBigrams("A B"), textBigrams("a   b"))
        assertEquals(setOf("本文", "文テ", "テス", "スト"), textBigrams("本文テスト"))
        assertTrue(textBigrams("x").isEmpty())
        assertTrue(textBigrams("").isEmpty())
    }

    @Test
    fun `buildCurrentNoteSignalsは採番除去タイトルとfrontmatter除去本文とタグ正規化を組む`() {
        val signals = buildCurrentNoteSignals(
            currentTitle = "0F01 AI設計",
            currentContent = "---\ntags: x\n---\n本文テスト",
            currentTags = listOf("#Foo", "bar"),
            snippetLen = 150
        )
        assertEquals(setOf("ai", "i設", "設計"), signals.titleBigrams)
        assertEquals(setOf("本文", "文テ", "テス", "スト"), signals.snippetBigrams)
        assertEquals(setOf("foo", "bar"), signals.tags)
    }

    @Test
    fun `全シグナル一致で重みの総和になりtieBreakはタイトル類似`() {
        val current = CurrentNoteSignals(
            titleBigrams = setOf("ab", "bc"),
            snippetBigrams = setOf("xy", "yz"),
            tags = setOf("ai")
        )
        // 候補: title "abc"→{ab,bc}, snippet "xyz"→{xy,yz}, tag "#AI"→{ai} で全一致。
        val score = relatedContextScore(current, "abc", "xyz", listOf("#AI"))
        assertEquals(1.0 * 1.0 + 0.5 * 1.0 + 0.5 * 1.0, score.score, DELTA) // = 2.0
        assertEquals(1.0, score.tieBreak, DELTA)
    }

    @Test
    fun `タグだけ一致でも主シグナルとして加点される`() {
        val current = CurrentNoteSignals(setOf("ab", "bc"), setOf("xy", "yz"), setOf("ai"))
        val score = relatedContextScore(current, "zzz", "qqq", listOf("ai"))
        assertEquals(1.0, score.score, DELTA) // W_TAG*1 のみ
        assertEquals(0.0, score.tieBreak, DELTA)
    }

    @Test
    fun `タグが無い候補は本文とタイトルへフォールバックする`() {
        val current = CurrentNoteSignals(setOf("ab", "bc"), setOf("xy", "yz"), setOf("ai"))
        val score = relatedContextScore(current, "abc", "qqq", emptyList())
        assertEquals(0.5, score.score, DELTA) // W_TITLE*1 のみ（tag=0, body=0）
        assertEquals(1.0, score.tieBreak, DELTA)
    }

    @Test
    fun `共有タグを持つ候補はタイトルだけ近い候補より上位になる`() {
        val current = CurrentNoteSignals(
            titleBigrams = titleBigrams("abc"),
            snippetBigrams = emptySet(),
            tags = setOf("ai")
        )
        val tagShared = Triple("zzz", "", listOf("ai"))        // タグ一致・タイトル遠 → 1.0
        val titleOnly = Triple("abc", "", emptyList<String>()) // タイトル一致のみ → 0.5

        val ranked = rankByScore(
            candidates = listOf(titleOnly, tagShared),     // あえて逆順で投入
            isExcluded = { false },
            limit = 2
        ) { relatedContextScore(current, it.first, it.second, it.third) }

        assertEquals("zzz", ranked.first().first)
    }

    companion object {
        private const val DELTA = 0.000001
    }
}
