package com.example.newproject.ui

import com.example.newproject.DISPLAY_TRUNCATED_NOTICE
import com.example.newproject.DISTILL_TOO_LARGE_REASON
import com.example.newproject.DISTILL_UNREADABLE_TEXT_REASON
import com.example.newproject.ui.screen.DISTILL_CONFIRM_BOLD_HEAVY
import com.example.newproject.ui.screen.DISTILL_CONFIRM_IRREVERSIBLE
import com.example.newproject.ui.screen.DISTILL_CONFIRM_WHAT_HAPPENS
import com.example.newproject.ui.screen.DISTILL_PANEL_LEAD
import com.example.newproject.ui.screen.distillBoldNotice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 蒸留の面の文（→ `docs/dev/system/ui_copy.md` §2・`docs/dev/features/reflect_distill.md`）。 */
class DistillCopyTest {

    private val texts = listOfNotNull(
        DISTILL_PANEL_LEAD, DISTILL_CONFIRM_BOLD_HEAVY, DISTILL_CONFIRM_WHAT_HAPPENS, DISTILL_CONFIRM_IRREVERSIBLE,
        DISTILL_TOO_LARGE_REASON, DISTILL_UNREADABLE_TEXT_REASON, DISPLAY_TRUNCATED_NOTICE,
        distillBoldNotice(isWithinBoldLimit = false, selectedCount = 0),
        distillBoldNotice(isWithinBoldLimit = false, selectedCount = 3)
    )

    @Test
    fun `内部の数字と文字コードの名前を出さない`() {
        texts.forEach { assertFalse(it, it.any { c -> c.isDigit() } || it.contains("%") || it.contains("UTF") || it.contains("KB") || it.contains("MB")) }
    }

    @Test
    fun `助言と指示の口調を使わない`() {
        texts.forEach { assertFalse(it, it.contains("ください") || it.contains("ますが")) }
    }

    @Test
    fun `取り消せないことは、起きることと別の文で言う`() {
        assertTrue(DISTILL_CONFIRM_IRREVERSIBLE.contains("取り消せません"))
        assertFalse(DISTILL_CONFIRM_WHAT_HAPPENS.contains("取り消"))
        assertTrue("文字が消えないことを言う", DISTILL_CONFIRM_WHAT_HAPPENS.contains("消えず"))
    }

    @Test
    fun `太字が目安を超えても保存できることを言い、超えなければ何も出さない`() {
        assertTrue(distillBoldNotice(isWithinBoldLimit = false, selectedCount = 3)!!.contains("保存できます"))
        assertTrue(distillBoldNotice(isWithinBoldLimit = true, selectedCount = 3) == null)
    }

    /** 赤は失敗にだけ使う（→ `docs/dev/system/ui_copy.md` §5）。保存できる注意・取り消せない旨・重なりの告知に失敗の色を使わない。 */
    @Test
    fun `蒸留の正常な確認と注意に失敗の色を使わない`() {
        val sheet = File("src/main/java/com/example/newproject/ui/screen/DistillRangeSheet.kt").readText()
        assertFalse("範囲のシートが失敗の色を使っている", sheet.contains("color = ErrorText") || sheet.contains("else ErrorText"))
        val tab = File("src/main/java/com/example/newproject/ui/screen/AiTab.kt").readLines()
        val calm = listOf("DISTILL_CONFIRM_IRREVERSIBLE", "この後は復元できません", "noteUnavailableReason, fontSize", "Text(notice,", "isWithinBoldLimit) OnSurface", "範囲が重なるため")
        tab.forEachIndexed { i, line ->
            if (calm.any { line.contains(it) }) {
                val around = tab.subList(i, minOf(i + 3, tab.size)).joinToString("\n")
                assertFalse("AiTab.kt:${i + 1} の注意が失敗の色を使っている", around.contains("ErrorText"))
            }
        }
    }
}
