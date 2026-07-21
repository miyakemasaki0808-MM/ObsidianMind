package com.example.newproject

import com.example.newproject.ai.PromptBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuizPromptBuilderTest {

    @Test
    fun `○×は選択肢と解説を要求しない`() {
        val prompt = PromptBuilder.buildQuizPrompt("メモ", "本文", QuizFormat.TrueFalse)
        assertTrue(prompt.contains("exactly 2 true-or-false"))
        assertTrue(prompt.contains("ANSWER: <TRUE or FALSE>"))
        assertFalse(prompt.contains("A: <choice>"))
        assertFalse(prompt.contains("EXPLANATION:"))
    }

    @Test
    fun `3択は2問でDと解説を要求しない`() {
        val prompt = PromptBuilder.buildQuizPrompt("メモ", "本文", QuizFormat.ThreeChoice)
        assertTrue(prompt.contains("exactly 2 three-choice"))
        assertTrue(prompt.contains("C: <choice>"))
        assertFalse(prompt.contains("D: <choice>"))
        assertFalse(prompt.contains("EXPLANATION:"))
    }

    @Test
    fun `4択は1問と短い解説を要求する`() {
        val prompt = PromptBuilder.buildQuizPrompt("メモ", "本文", QuizFormat.FourChoice)
        assertTrue(prompt.contains("exactly 1 four-choice"))
        assertTrue(prompt.contains("D: <choice>"))
        assertTrue(prompt.contains("EXPLANATION: <one short sentence>"))
    }
}
