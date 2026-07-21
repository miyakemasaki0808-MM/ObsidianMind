package com.example.newproject.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DistillCandidateScoringTest {

    @Test
    fun `candidate ids are fixed three digit values`() {
        assertEquals("S001", distillCandidateId(0))
        assertEquals("S024", distillCandidateId(23))
    }

    @Test
    fun `each chunk contributes a candidate while slots remain`() {
        val content = """
            # Alpha
            Alpha topic first sentence. Ordinary detail follows.
            # Beta
            Beta topic first sentence. Another detail follows.
            # Gamma
            Gamma topic first sentence. Final conclusion follows.
        """.trimIndent()
        val model = buildDistillSourceModel(content)

        val selected = selectDistillCandidates(model, "Alpha Beta Gamma", limit = 3)

        assertEquals(3, selected.size)
        assertEquals(3, selected.map { it.sentence.chunkIndex }.distinct().size)
    }

    @Test
    fun `unique final conclusion survives first stage`() {
        val repeated = (1..20).joinToString(" ") { "General explanation repeats." }
        val content = "$repeated Unique final conclusion."
        val model = buildDistillSourceModel(content)

        val selected = selectDistillCandidates(model, "Unrelated", limit = 3)

        assertTrue(selected.any { it.sentence.text == "Unique final conclusion." })
    }

    @Test
    fun `sentences longer than input contract are excluded`() {
        val longSentence = "x".repeat(DistillLimits.MAX_SENTENCE_CHARACTERS + 1) + "。"
        val model = buildDistillSourceModel("短い文です。$longSentence")

        val selected = selectDistillCandidates(model, "短い")

        assertTrue(selected.none { it.sentence.text == longSentence })
    }

    @Test
    fun `singleton chunk survives maximum sentence prefilter`() {
        val before = (1..250).joinToString("\n") { "前半の説明文${it}です。" }
        val after = (1..250).joinToString("\n") { "後半の説明文${it}です。" }
        val content = "$before\n# 重要\n孤立した重要な結論です。\n# 続き\n$after"
        val model = buildDistillSourceModel(content)

        val selected = selectDistillCandidates(model, "無関係", limit = DistillLimits.MAX_AI_CANDIDATES)

        assertTrue(model.sentences.size > DistillLimits.MAX_SENTENCES_FOR_SCORING)
        assertTrue(selected.any { it.sentence.text == "孤立した重要な結論です。" })
    }
}
