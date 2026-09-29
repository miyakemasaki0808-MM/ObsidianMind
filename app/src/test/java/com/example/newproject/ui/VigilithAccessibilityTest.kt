package com.example.newproject.ui

import com.example.newproject.domain.SectionSummaryStatus
import com.example.newproject.ui.vigilith.VigilithNoteAction
import com.example.newproject.ui.vigilith.vigilithActionDescription
import com.example.newproject.domain.markdown.NoteSection
import org.junit.Assert.assertEquals
import org.junit.Test

class VigilithAccessibilityTest {

    @Test
    fun `TalkBack説明は状態と対象セクションを一度で伝える`() {
        assertEquals(
            "Vigilith。AIメニューを開く。対象は設計",
            description(SectionSummaryStatus.Idle)
        )
        assertEquals(
            "Vigilith。AI要約を生成中。タップで開く。対象は設計",
            description(SectionSummaryStatus.Working)
        )
        assertEquals(
            "Vigilith。AI結果を生成済み。タップで開く。対象は設計",
            description(SectionSummaryStatus.Ready)
        )
        assertEquals(
            "Vigilith。AI処理でエラー。タップで確認。対象は設計",
            description(SectionSummaryStatus.Error)
        )
    }

    private fun description(status: SectionSummaryStatus) = vigilithActionDescription(
        VigilithNoteAction(
            section = NoteSection("設計", 0, "本文"),
            sectionLabel = "設計",
            status = status
        )
    )
}
