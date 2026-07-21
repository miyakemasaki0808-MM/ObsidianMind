package com.example.newproject.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase 3b準備: 採点戦略に依存しない共有ランキング基盤の契約を固定する。
// ここが安定していれば、別シグナル（本文・タグ等）の scoreOf を差し替えても
// 除外・並び順・上限の挙動は変わらない。
class RelatedCandidateRankingTest {

    // 採点を明示的に与え、パイプラインの並べ替え規約だけを検証する。
    private fun rank(
        items: List<String>,
        limit: Int = 10,
        isExcluded: (String) -> Boolean = { false },
        scoreOf: (String) -> CandidateScore
    ): List<String> = rankByScore(items, isExcluded, limit, scoreOf)

    @Test
    fun `score降順で並ぶ`() {
        val ranked = rank(listOf("a", "b", "c")) {
            CandidateScore(score = it.first().code.toDouble(), tieBreak = 0.0)
        }
        assertEquals(listOf("c", "b", "a"), ranked)
    }

    @Test
    fun `同点はtieBreak降順で解決する`() {
        val ranked = rank(listOf("low", "high")) {
            val tie = if (it == "high") 1.0 else 0.0
            CandidateScore(score = 1.0, tieBreak = tie)
        }
        assertEquals(listOf("high", "low"), ranked)
    }

    @Test
    fun `scoreもtieBreakも同点なら入力順を保つ`() {
        val input = listOf("x", "y", "z")
        val ranked = rank(input) { CandidateScore(score = 1.0, tieBreak = 1.0) }
        assertEquals(input, ranked)
    }

    @Test
    fun `除外は上限適用の前に行う`() {
        val ranked = rank(
            items = listOf("keep1", "drop", "keep2"),
            limit = 2,
            isExcluded = { it == "drop" }
        ) { CandidateScore(score = 0.0, tieBreak = 0.0) }

        assertEquals(2, ranked.size)
        assertTrue("drop" !in ranked)
        assertEquals(listOf("keep1", "keep2"), ranked)
    }

    @Test
    fun `上限で件数を切り0以下なら空にする`() {
        val items = listOf("a", "b", "c")
        val scoreOf: (String) -> CandidateScore = { CandidateScore(0.0, 0.0) }
        assertEquals(2, rank(items, limit = 2, scoreOf = scoreOf).size)
        assertTrue(rank(items, limit = 0, scoreOf = scoreOf).isEmpty())
        assertTrue(rank(items, limit = -1, scoreOf = scoreOf).isEmpty())
    }
}
