package com.example.newproject.ui

import com.example.newproject.domain.assessReadingTraceOrphans
import com.example.newproject.model.OrphanAssessment
import com.example.newproject.model.OrphanBlockReason
import com.example.newproject.model.OrphanTraceInfo
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
    fun `同じ場所の複数の欠けを、フォルダ全体の不在として言い切らない`() {
        // ideas の a・b が欠けて c は残る入力も、Vault 直下の2件の欠けも、判定は同じ理由になる。
        listOf(WithheldOrphans("ideas", 2, OrphanWithholdReason.FOLDER_WIDE_ABSENCE), WithheldOrphans("", 2, OrphanWithholdReason.FOLDER_WIDE_ABSENCE)).forEach { group ->
            val text = withheldReasonText(group)
            assertFalse(text, text.contains("フォルダごと") || text.contains("フォルダが"))
            assertTrue("複数の欠けと保留を言う: $text", text.contains("いくつも") && text.contains("残します"))
            assertTrue("読み取れなかった可能性を言う: $text", text.contains("読み取れなかった"))
        }
    }

    /** 判定器から文までを通す。ideas の a・b を消して c は残り、Vault 直下でも2件が欠ける。 */
    @Test
    fun `判定から文まで、2件の欠けをフォルダの不在と言わない`() {
        val traces = mapOf(
            "k-a" to OrphanTraceInfo("ideas/a.md", "a", 1, null),
            "k-b" to OrphanTraceInfo("ideas/b.md", "b", 1, null),
            "k-c" to OrphanTraceInfo("ideas/c.md", "c", 1, null),
            "k-x" to OrphanTraceInfo("x.md", "x", 1, null),
            "k-y" to OrphanTraceInfo("y.md", "y", 1, null),
            "k-z" to OrphanTraceInfo("z.md", "z", 1, null)
        )
        val assessment = assessReadingTraceOrphans(
            traceKeys = traces.keys,
            noteKeys = setOf("k-c", "k-z"),
            unreadableFolderPaths = emptySet()
        ) { traces[it] }
        val texts = (assessment as OrphanAssessment.Assessed).withheld.map { withheldReasonText(it) }
        assertTrue("保留が無い: $assessment", texts.isNotEmpty())
        texts.forEach { assertFalse(it, it.contains("フォルダごと")) }
    }

    @Test
    fun `保留した群があるとき、候補0件の文で見つからない痕跡は無いと言わない`() {
        val withHeld = noCandidateText(withheldGroups = 2)
        assertFalse(withHeld, withHeld.contains("見つからない") || withHeld.contains("ありませんでした"))
        assertTrue(withHeld, withHeld.contains("候補"))
        // 候補も保留も無いときは、見つからない痕跡が無いことを言う。
        assertTrue(noCandidateText(withheldGroups = 0).contains("見つからない痕跡はありませんでした"))
    }

    @Test
    fun `整理の文は判定できなかったことを読み取れないとして言う`() {
        val vaultRoot = blockedExplanation(ReadingTraceCleanupState.Blocked(OrphanBlockReason.VAULT_ROOT_UNREADABLE, 0))
        assertTrue(vaultRoot, vaultRoot.contains("読み取れ") && vaultRoot.contains("判定していません"))
    }
}
