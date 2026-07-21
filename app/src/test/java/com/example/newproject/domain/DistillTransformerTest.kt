package com.example.newproject.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DistillTransformerTest {

    @Test
    fun `default cumulative bold limit is thirty percent`() {
        assertEquals(0.30, DistillLimits.MAX_BOLD_RATIO, 0.0)
    }

    @Test
    fun `multiple ranges are wrapped without shifting original locations`() {
        val content = "一文目。二文目。三文目。"
        val model = buildDistillSourceModel(content)
        val selected = listOf(model.sentences[0].range, model.sentences[2].range)

        val result = applyDistillBold(content, selected)

        assertEquals("**一文目。**二文目。**三文目。**", result.content)
        assertEquals(content, result.content.replace("**", ""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `overlapping ranges are rejected`() {
        applyDistillBold("abcdef", listOf(DistillTextRange(0, 3), DistillTextRange(2, 5)))
    }

    @Test
    fun `bold ratio includes existing bold text`() {
        val content = "**既存です。**追加です。残りです。"
        val model = buildDistillSourceModel(content)
        val added = model.sentences.first { it.text == "追加です。" }.range

        assertTrue(projectedBoldRatio(model, listOf(added)) > 0.0)
        assertFalse(isWithinDistillBoldLimit(model, listOf(added), maxRatio = 0.01))
    }

    @Test
    fun `identical sentences are transformed only at selected source position`() {
        val content = "同じ文です。間の文です。同じ文です。"
        val model = buildDistillSourceModel(content)
        val identical = model.sentences.filter { it.text == "同じ文です。" }

        val result = applyDistillBold(content, listOf(identical.last().range))

        assertEquals("同じ文です。間の文です。**同じ文です。**", result.content)
    }
}
