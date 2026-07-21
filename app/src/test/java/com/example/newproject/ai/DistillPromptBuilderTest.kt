package com.example.newproject.ai

import com.example.newproject.domain.buildDistillSourceModel
import com.example.newproject.domain.selectDistillCandidates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DistillPromptBuilderTest {

    @Test
    fun `prompt keeps complete sentences inside both budgets`() {
        val content = (1..30).joinToString("\n") { index ->
            "これは候補文${index}で、途中で切断されてはいけません。"
        }
        val candidates = selectDistillCandidates(buildDistillSourceModel(content), "候補")

        val result = PromptBuilder.buildDistillPrompt(
            title = "候補",
            candidates = candidates,
            candidateLimit = 8,
            candidateCharacterBudget = 150
        )

        assertTrue(result.candidates.size <= 8)
        assertTrue(result.candidateBlock.length <= 150)
        result.candidates.forEach { candidate ->
            assertTrue(result.candidateBlock.contains(candidate.sentence.text))
        }
    }

    @Test
    fun `valid IDs contain only candidates actually sent to AI`() {
        val content = (1..10).joinToString("\n") { "十分な長さを持つ重要な候補文${it}です。" }
        val candidates = selectDistillCandidates(buildDistillSourceModel(content), "重要")

        val result = PromptBuilder.buildDistillPrompt(
            title = "重要",
            candidates = candidates,
            candidateCharacterBudget = 70
        )

        assertEquals(result.candidates.map { it.id }.toSet(), result.validIds)
        assertTrue(result.validIds.size < candidates.size)
        candidates.filterNot { it.id in result.validIds }.forEach {
            assertFalse(result.candidateBlock.contains("${it.id} |"))
        }
    }

    @Test
    fun `prompt requires IDs only and final selection limit`() {
        val model = buildDistillSourceModel("重要な結論をここに記します。")
        val result = PromptBuilder.buildDistillPrompt("結論", selectDistillCandidates(model, "結論"))

        assertTrue(result.text.contains("up to 6 candidates"))
        assertTrue(result.text.contains("Return only candidate IDs"))
        assertTrue(result.text.contains("descending order of importance"))
        assertTrue(result.text.contains("S001 |"))
    }

    @Test
    fun `zero budget sends no candidates`() {
        val model = buildDistillSourceModel("重要な結論をここに記します。")
        val result = PromptBuilder.buildDistillPrompt(
            "結論",
            selectDistillCandidates(model, "結論"),
            candidateCharacterBudget = 0
        )

        assertTrue(result.candidates.isEmpty())
        assertTrue(result.validIds.isEmpty())
        assertEquals("", result.candidateBlock)
    }
}
