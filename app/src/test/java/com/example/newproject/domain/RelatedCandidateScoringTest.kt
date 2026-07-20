package com.example.newproject.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Phase 3a: 全Vaultのタイトルを話題類似で順位付けする純ロジックの回帰テスト。
class RelatedCandidateScoringTest {

    @Test
    fun `bigramは採番と区切りを除き大小文字と拡張子を正規化する`() {
        assertEquals(setOf("ai", "i設", "設計"), titleBigrams("0F01_AI設計.MD"))
        assertEquals(titleBigrams("0F01 AI設計"), titleBigrams("0F01-AI設計.md"))
    }

    @Test
    fun `1文字以下の話題タイトルはbigramを持たない`() {
        assertTrue(titleBigrams("0F01 A").isEmpty())
        assertTrue(titleBigrams("0F01").isEmpty())
    }

    @Test
    fun `Dice係数は完全一致と無関係と部分一致を表せる`() {
        val base = setOf("ab", "bc")
        assertEquals(1.0, diceCoefficient(base, base), DELTA)
        assertEquals(0.0, diceCoefficient(base, setOf("xy")), DELTA)

        val partial = diceCoefficient(base, setOf("bc", "cd"))
        assertTrue(partial > 0.0)
        assertTrue(partial < 1.0)
    }

    @Test
    fun `両方のbigramが空ならDice係数は0`() {
        assertEquals(0.0, diceCoefficient(emptySet(), emptySet()), DELTA)
    }

    @Test
    fun `合成スコアは話題一致に採番tierを段階的に加点する`() {
        val current = "0F01 AI設計"
        assertEquals(1.30, relatedCandidateScore(current, "0F99 AI設計", "0F01"), DELTA)
        assertEquals(1.15, relatedCandidateScore(current, "0A99 AI設計", "0F01"), DELTA)
        assertEquals(1.00, relatedCandidateScore(current, "3A99 AI設計", "0F01"), DELTA)
        assertEquals(1.00, relatedCandidateScore(current, "0F99 AI設計", null), DELTA)
    }

    @Test
    fun `話題が近ければ採番の離れた候補も無関係な兄弟より上位になる`() {
        val ranked = rankTitles(
            currentTitle = "0F01 AIエージェント設計",
            candidates = listOf(
                "0F99 料理レシピ",
                "3A10 AIエージェント協調設計",
                "0A20 読書記録"
            )
        )

        assertEquals("3A10 AIエージェント協調設計", ranked.first())
        assertTrue("異なる採番カテゴリも候補に残る", "3A10 AIエージェント協調設計" in ranked)
    }

    @Test
    fun `現ノートに採番が無くても話題類似で順位付けする`() {
        val ranked = rankTitles(
            currentTitle = "AIエージェント設計",
            candidates = listOf("料理レシピ", "AIエージェント協調設計")
        )
        assertEquals("AIエージェント協調設計", ranked.first())
    }

    @Test
    fun `除外は上限適用前に行い空いた枠を次候補で満たす`() {
        val excluded = "0F01 AI設計.md"
        val ranked = rankRelatedCandidates(
            currentTitle = "0F00 AI設計",
            candidates = listOf(excluded, "3A10 AI設計", "0F99 料理"),
            titleOf = { it },
            excludedTitles = setOf("path/to/0f01 ai設計"),
            limit = 2
        )

        assertEquals(2, ranked.size)
        assertFalse(excluded in ranked)
        assertTrue("3A10 AI設計" in ranked)
        assertTrue("0F99 料理" in ranked)
    }

    @Test
    fun `同点はprefixTierを優先し最後に入力順で決める`() {
        val current = "0F00 abcdefghijklmnopqrstu"
        val sameCategoryWithSimilarity = "0A99 abcd0123456789!@$%&*?"
        val sameGroupWithoutSimilarity = "0F99 0123456789!@$%&*?XYZ"
        assertEquals(
            relatedCandidateScore(current, sameCategoryWithSimilarity, "0F00"),
            relatedCandidateScore(current, sameGroupWithoutSimilarity, "0F00"),
            DELTA
        )

        val rankedByTier = rankTitles(
            currentTitle = current,
            candidates = listOf(sameCategoryWithSimilarity, sameGroupWithoutSimilarity)
        )
        assertEquals(sameGroupWithoutSimilarity, rankedByTier.first())

        val rankedByInput = rankTitles(
            currentTitle = "0F00 abc",
            candidates = listOf("3A10 xyz", "4B10 xyz")
        )
        assertEquals(listOf("3A10 xyz", "4B10 xyz"), rankedByInput)
    }

    @Test
    fun `上限で件数を切り0以下なら空にする`() {
        val candidates = listOf("AI設計", "AI運用", "料理")
        assertEquals(2, rankTitles("AI", candidates, limit = 2).size)
        assertTrue(rankTitles("AI", candidates, limit = 0).isEmpty())
        assertTrue(rankTitles("AI", candidates, limit = -1).isEmpty())
    }

    private fun rankTitles(
        currentTitle: String,
        candidates: List<String>,
        limit: Int = 40
    ): List<String> = rankRelatedCandidates(
        currentTitle = currentTitle,
        candidates = candidates,
        titleOf = { it },
        excludedTitles = emptySet(),
        limit = limit
    )

    companion object {
        private const val DELTA = 0.000001
    }
}
