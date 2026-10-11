package com.example.newproject.ai

import com.example.newproject.model.CrystalMaterial
import com.example.newproject.model.NoteExcerpt
import com.example.newproject.model.ReunionPassage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **文章を書かせるプロンプトは、出力を日本語と指定する**（→ features/note_summary.md 判断10）。
 *
 * 「本文と同じ言語で」と任せると、英語の注意書き・コード・ファイル名が混じる抜粋で、日本語のノートでも英語が返る。
 * IDだけを返させるプロンプト（関連ノート・蒸留・ピッカー・再会カードの選別・分野・反証の一文）は対象外。
 */
class PromptOutputLanguageTest {

    private val excerpt = NoteExcerpt("本文", isAbridged = true)

    private val prosePrompts = mapOf(
        "buildSummarizePrompt" to PromptBuilder.buildSummarizePrompt("background_ai_ux", excerpt),
        "buildSectionSummaryPrompt" to PromptBuilder.buildSectionSummaryPrompt("Overview", excerpt),
        "buildReunionPassagePrompt" to
            PromptBuilder.buildReunionPassagePrompt("題名", ReunionPassage(before = "前", after = "後")),
        "buildCrystalPrompt" to PromptBuilder.buildCrystalPrompt(
            listOf(CrystalMaterial("a.md", "A", "断片", lastSeenAt = 0), CrystalMaterial("b.md", "B", "断片", lastSeenAt = 0))
        ).text
    )

    @Test
    fun `文章を書かせるプロンプトは日本語で書くよう指定する`() {
        prosePrompts.forEach { (name, prompt) ->
            assertTrue("$name が出力の言語を日本語と指定していない", prompt.contains("Japanese"))
        }
    }

    @Test
    fun `文章を書かせるプロンプトは出力の言語を本文に任せない`() {
        prosePrompts.forEach { (name, prompt) ->
            assertFalse("$name が出力の言語を本文に任せている", prompt.contains("same language"))
        }
    }
}
