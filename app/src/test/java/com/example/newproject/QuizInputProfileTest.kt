package com.example.newproject

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuizInputProfileTest {

    @Test
    fun `短い断片は○×になる`() {
        val profile = profileQuizInput("# メモ\n結論だけを書いた短いメモ。")
        assertEquals(QuizFormat.TrueFalse, profile.format)
    }

    @Test
    fun `コード主体なら短くても3択になる`() {
        val content = """
            # Kotlin
            ```kotlin
            fun main() {
                println("hello")
                println("world")
            }
            ```
        """.trimIndent()
        val profile = profileQuizInput(content)
        assertEquals(QuizFormat.ThreeChoice, profile.format)
        assertTrue(profile.codeRatio >= 0.45)
    }

    @Test
    fun `通常の中程度の本文は3択になる`() {
        val content = (1..6).joinToString("\n") { index ->
            "論点$index は具体例と背景を用いて説明されており、読者が内容を比較して理解できる文章です。"
        }
        assertEquals(QuizFormat.ThreeChoice, profileQuizInput(content).format)
    }

    @Test
    fun `情報量と文数が十分なら4択になる`() {
        val content = (1..18).joinToString("\n") { index ->
            "論点$index について、背景、具体例、判断理由、結果を詳しく説明し、他の論点との関係も整理しています。"
        }
        val profile = profileQuizInput(content)
        assertTrue(profile.meaningfulCharacters >= 700)
        assertTrue(profile.sentenceSignals >= 6)
        assertEquals(QuizFormat.FourChoice, profile.format)
    }
}
