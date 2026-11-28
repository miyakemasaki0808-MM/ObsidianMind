package com.example.newproject.ui

import com.example.newproject.model.OrphanBlockReason
import com.example.newproject.model.OrphanWithholdReason
import com.example.newproject.model.WithheldOrphans
import com.example.newproject.model.state.ReadingTraceCleanupState
import com.example.newproject.ui.screen.BACKUP_PLAINTEXT_NOTICE
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 退避と整理の文（→ `docs/dev/system/ui_copy.md` §2 の4・5・7）。言い方は短くし、指示の口調にしないが、
 * 平文がクラウドへ渡ること・「読み取れない」こと・まだ書き戻していないことの意味は残す。
 */
class BackupCleanupCopyTest {

    private val cleanupTexts: List<String> =
        OrphanBlockReason.entries.map { blockedExplanation(ReadingTraceCleanupState.Blocked(it, candidateCount = 120)) } +
            OrphanWithholdReason.entries.map { withheldReasonText(WithheldOrphans("ideas", 1, it)) }

    @Test
    fun `平文の注意は1行で、Vault の中に置くと同期でクラウドへ渡ることを言う`() {
        assertTrue(BACKUP_PLAINTEXT_NOTICE.contains("そのまま読める"))
        assertTrue(BACKUP_PLAINTEXT_NOTICE.contains("Vault の中"))
        assertTrue(BACKUP_PLAINTEXT_NOTICE.contains("クラウド"))
        assertFalse(BACKUP_PLAINTEXT_NOTICE.contains("ください"))
    }

    @Test
    fun `退避と整理の文は指示の口調にしない`() {
        (cleanupTexts + backupProtectedDataText() + revisedPlanNotice()).forEach {
            assertFalse(it, it.contains("ください") || it.contains("お試し"))
        }
    }

    @Test
    fun `整理の文は判定できなかったことを読み取れないとして言う`() {
        val vaultRoot = blockedExplanation(ReadingTraceCleanupState.Blocked(OrphanBlockReason.VAULT_ROOT_UNREADABLE, 0))
        assertTrue(vaultRoot, vaultRoot.contains("読み取れ") && vaultRoot.contains("判定していません"))
    }
}
