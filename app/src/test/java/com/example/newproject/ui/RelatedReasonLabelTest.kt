package com.example.newproject.ui

import com.example.newproject.model.RelatedReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** 関連ノートの根拠の言い方。**数字を出さない**（→ features/related_notes_ai.md §5）。 */
class RelatedReasonLabelTest {

    @Test
    fun `根拠ごとの言い方`() {
        assertEquals("タグ #読書 が共通", relatedReasonLabel(RelatedReason.SharedTag("読書")))
        assertEquals("書き出しが近い", relatedReasonLabel(RelatedReason.SimilarOpening))
        assertEquals("題名が近い", relatedReasonLabel(RelatedReason.SimilarTitle))
    }

    @Test
    fun `数字を含まない`() {
        listOf(RelatedReason.SharedTag("読書"), RelatedReason.SimilarOpening, RelatedReason.SimilarTitle).forEach {
            assertFalse(relatedReasonLabel(it), relatedReasonLabel(it).any { c -> c.isDigit() })
        }
    }
}
