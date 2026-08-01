package com.example.newproject.ui

import com.example.newproject.model.OrphanBlockReason
import com.example.newproject.model.OrphanWithholdReason
import com.example.newproject.model.WithheldOrphans
import com.example.newproject.model.state.ReadingTraceCleanupState
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 整理画面の文面。**内部語を出さないこと**と、
 * **「候補ゼロ」と「判定できなかった」が別の言葉になること**を固定する。
 */
class ReadingTraceCleanupTextTest {

    @Test
    fun `blocked explanations never claim that nothing was found`() {
        val texts = listOf(
            blockedExplanation(
                ReadingTraceCleanupState.Blocked(OrphanBlockReason.VAULT_ROOT_UNREADABLE, 0)
            ),
            blockedExplanation(
                ReadingTraceCleanupState.Blocked(OrphanBlockReason.TOO_MANY_CANDIDATES, 120)
            )
        )

        texts.forEach { text ->
            // 「ありませんでした」は候補ゼロ側の文面。見送りと混ぜない。
            assertTrue(text, !text.contains("ありませんでした"))
            assertTrue(text, text.isNotBlank())
        }
    }

    @Test
    fun `the too many case tells the user how many were seen`() {
        val text = blockedExplanation(
            ReadingTraceCleanupState.Blocked(OrphanBlockReason.TOO_MANY_CANDIDATES, 120)
        )

        assertTrue(text, text.contains("120"))
    }

    @Test
    fun `root level withholding is not shown as an empty folder name`() {
        val text = withheldLocation(
            WithheldOrphans("", 2, OrphanWithholdReason.FOLDER_WIDE_ABSENCE)
        )

        assertTrue(text, text.contains("Vault 直下"))
        assertTrue(text, text.contains("2"))
    }

    @Test
    fun `unresolvable withholding does not pretend to know a folder`() {
        val text = withheldLocation(
            WithheldOrphans("ideas", 3, OrphanWithholdReason.UNRESOLVABLE)
        )

        // 読めなかった痕跡は、そもそもどのフォルダのものか確定していない。
        assertTrue(text, !text.contains("ideas"))
    }

    @Test
    fun `named folders are shown with their path`() {
        val text = withheldLocation(
            WithheldOrphans("ideas/2026", 1, OrphanWithholdReason.FOLDER_WIDE_ABSENCE)
        )

        assertTrue(text, text.contains("ideas/2026"))
    }

    @Test
    fun `withhold reasons avoid internal vocabulary`() {
        val internalWords = listOf("孤児", "遮断器", "列挙", "SAF", "null")

        OrphanWithholdReason.entries.forEach { reason ->
            val text = withheldReasonText(WithheldOrphans("ideas", 1, reason))
            internalWords.forEach { word ->
                assertTrue("$reason: $text", !text.contains(word))
            }
            assertTrue(text, text.isNotBlank())
        }
    }
}
