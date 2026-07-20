package com.example.newproject.domain

import com.example.newproject.ai.PromptBuilder
import com.example.newproject.ai.RelatedCandidateLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 関連ノートで共用する採番抽出と、ID方式プロンプトの回帰テスト。
class RelatedCandidateOrderingTest {

    // --- extractHexPrefix ---

    @Test
    fun `先頭4桁の16進のみを大文字で抽出する`() {
        assertEquals("0F01", extractHexPrefix("0f01 ノート"))
        assertEquals("3ABC", extractHexPrefix("3abcdef 続き")) // 先頭4桁のみ
    }

    @Test
    fun `採番が無い・4桁未満・非16進は null`() {
        assertNull(extractHexPrefix("採番なしノート"))
        assertNull(extractHexPrefix("0F1 三桁で空白"))
        assertNull(extractHexPrefix("GHIJ 非16進"))
    }

    // --- buildRelatedNotesPrompt（ID提示・ID応答／[linked]撤去） ---

    @Test
    fun `プロンプトはID付き候補を提示しIDだけ返させる（linked撤去）`() {
        val prompt = PromptBuilder.buildRelatedNotesPrompt(
            currentTitle = "現在ノート",
            currentContent = "本文スニペット",
            candidates = listOf(
                RelatedCandidateLine("C01", "候補A"),
                RelatedCandidateLine("C02", "候補B")
            )
        )
        assertFalse(prompt.contains("[linked]"))
        assertFalse(prompt.contains("Prefer"))
        assertTrue(prompt.contains("C01 | 候補A"))
        assertTrue(prompt.contains("C02 | 候補B"))
        assertTrue(prompt.contains("Return only the IDs"))
    }
}
