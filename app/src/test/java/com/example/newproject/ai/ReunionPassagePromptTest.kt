package com.example.newproject.ai

import com.example.newproject.model.ReunionPassage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// 再会カードの前後の要約のプロンプト（→ features/reunion_card.md 判断6）。
class ReunionPassagePromptTest {

    /**
     * 渡すのは前回**いちばん先まで**読んだところで、最後に見ていた場所ではない。
     * 「止まった」と言わせると、巻き戻して離れた回に事実と違う説明になる。
     */
    @Test
    fun `前後の要約のプロンプトは止まったと言わない`() {
        val prompt = PromptBuilder.buildReunionPassagePrompt("題名", ReunionPassage(before = "前の段落。", after = "後の段落。"))

        assertFalse(prompt.contains("stop", ignoreCase = true))
        assertFalse(prompt.contains("止ま"))
    }

    @Test
    fun `境目の印は前と後のあいだに1つだけ置く`() {
        val prompt = PromptBuilder.buildReunionPassagePrompt("題名", ReunionPassage(before = "前の段落。", after = "後の段落。"))
        val marker = PromptBuilder.REUNION_READ_MARKER

        val body = prompt.substringAfter("Note title:")
        assertTrue(body.indexOf("前の段落。") < body.indexOf(marker))
        assertTrue(body.indexOf(marker) < body.indexOf("後の段落。"))
        assertTrue("本文側に印が2つある", body.indexOf(marker) == body.lastIndexOf(marker))
    }

    @Test
    fun `後ろが空でも境目の印は残す`() {
        val prompt = PromptBuilder.buildReunionPassagePrompt("題名", ReunionPassage(before = "最後の段落。", after = ""))

        assertTrue(prompt.trimEnd().endsWith(PromptBuilder.REUNION_READ_MARKER))
    }
}
