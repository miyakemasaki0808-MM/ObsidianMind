package com.example.newproject.domain

import com.example.newproject.model.DocumentRef
import com.example.newproject.model.RelatedNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 反証の一文の候補の順位付け（→ features/rebuttal_sentence.md §5）。
 *
 * 見ているのは**上限と同点の順**だけ。採点の良し悪しは実データで当て直す初期値なので固定しない。
 */
class RebuttalCandidateRankingTest {

    private val noteA = RelatedNote(title = "A", ref = DocumentRef("content://vault/a.md"), isWikilinked = false)
    private val noteB = RelatedNote(title = "B", ref = DocumentRef("content://vault/b.md"), isWikilinked = false)

    @Test
    fun `ノートごとに上位3件まで残す`() {
        val claims = (1..5).map { "無関係の文その$it。" }

        val ranked = rankRebuttalCandidates(excerptText = "", claimsBySource = listOf(noteA to claims))

        assertEquals(claims.take(REBUTTAL_CLAIMS_PER_NOTE), ranked.map { it.text })
    }

    @Test
    fun `全体で12件まで残す`() {
        val sources = (1..6).map { index ->
            RelatedNote(title = "N$index", ref = DocumentRef("content://vault/$index.md"), isWikilinked = false) to
                (1..3).map { "ノート${index}の文その$it。" }
        }

        val ranked = rankRebuttalCandidates(excerptText = "", claimsBySource = sources)

        assertEquals(REBUTTAL_CANDIDATE_LIMIT, ranked.size)
    }

    /** 話題の近い文を先頭へ寄せる。Nano は先頭の候補を選びやすい。 */
    @Test
    fun `今のノートと語が重なる文を先頭へ寄せる`() {
        val ranked = rankRebuttalCandidates(
            excerptText = "睡眠は朝の光で整う。",
            claimsBySource = listOf(
                noteA to listOf("料理は火加減がすべてである。"),
                noteB to listOf("睡眠は朝の光では整わない。")
            )
        )

        assertEquals(listOf("睡眠は朝の光では整わない。", "料理は火加減がすべてである。"), ranked.map { it.text })
    }

    @Test
    fun `同点は関連ノートの並び、次に原文順`() {
        val ranked = rankRebuttalCandidates(
            excerptText = "",
            claimsBySource = listOf(
                noteA to listOf("A の一つめ。", "A の二つめ。"),
                noteB to listOf("B の一つめ。")
            )
        )

        assertEquals(listOf("A の一つめ。", "A の二つめ。", "B の一つめ。"), ranked.map { it.text })
        assertEquals(listOf(noteA, noteA, noteB), ranked.map { it.source })
    }

    /** 日本語の短文は bigram が重ならないことが普通にある。0 で捨てると候補がほぼ残らない。 */
    @Test
    fun `語が1つも重ならない文も捨てない`() {
        val ranked = rankRebuttalCandidates(
            excerptText = "まったく別の話題。",
            claimsBySource = listOf(noteA to listOf("xyz の主張である。"))
        )

        assertEquals(listOf("xyz の主張である。"), ranked.map { it.text })
    }

    @Test
    fun `入力が空なら空を返す`() {
        assertTrue(rankRebuttalCandidates(excerptText = "本文", claimsBySource = emptyList()).isEmpty())
        assertTrue(rankRebuttalCandidates(excerptText = "本文", claimsBySource = listOf(noteA to emptyList())).isEmpty())
    }
}
