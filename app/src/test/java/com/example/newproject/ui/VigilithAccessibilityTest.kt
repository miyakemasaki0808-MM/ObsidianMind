package com.example.newproject.ui

import com.example.newproject.ui.vigilith.VigilithActionStatus
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
            description(VigilithActionStatus.Idle)
        )
        assertEquals(
            "Vigilith。AI要約を生成中。タップで開く。対象は設計",
            description(VigilithActionStatus.Working)
        )
        assertEquals(
            "Vigilith。AI結果を生成済み。タップで開く。対象は設計",
            description(VigilithActionStatus.Ready)
        )
        assertEquals(
            "Vigilith。AI処理でエラー。タップで確認。対象は設計",
            description(VigilithActionStatus.Error)
        )
    }

    @Test
    fun `回答生成中は要約と区別して読み上げる`() {
        assertEquals(
            "Vigilith。AI回答を生成中。タップで開く。対象は設計",
            description(VigilithActionStatus.Working, isAnswerGenerating = true)
        )
    }

    private fun description(
        status: VigilithActionStatus,
        isAnswerGenerating: Boolean = false
    ) = vigilithActionDescription(
        VigilithNoteAction(
            section = NoteSection("設計", 0, "本文"),
            sectionLabel = "設計",
            status = status,
            isAnswerGenerating = isAnswerGenerating
        )
    )
}
