package com.example.newproject.ui

import com.example.newproject.AnnotationState
import com.example.newproject.NoteUiState
import com.example.newproject.QuizCard
import com.example.newproject.QuizState
import org.junit.Assert.assertEquals
import org.junit.Test

class AiTabBadgeStateTest {

    @Test
    fun `未確認エラーを最優先で表示する`() {
        val badge = resolveAiTabBadgeState(
            quizState = QuizState.Error("生成エラー", "対象ノート"),
            annotationState = AnnotationState.Loading("対象ノート")
        )

        assertEquals(AiTabBadgeState.Error, badge)
    }

    @Test
    fun `未確認完了は生成中より優先して表示する`() {
        val badge = resolveAiTabBadgeState(
            quizState = QuizState.Success(
                sourceTitle = "対象ノート",
                cards = listOf(QuizCard("問題", listOf("A", "B", "C", "D"), 0))
            ),
            annotationState = AnnotationState.Loading("対象ノート")
        )

        assertEquals(AiTabBadgeState.Success, badge)
    }

    @Test
    fun `未確認結果がなければ生成中を表示する`() {
        val badge = resolveAiTabBadgeState(
            quizState = QuizState.Idle,
            annotationState = AnnotationState.Loading("対象ノート")
        )

        assertEquals(AiTabBadgeState.Loading, badge)
    }

    @Test
    fun `両機能が確認済みなら通常表示へ戻る`() {
        val state = NoteUiState(
            quizState = QuizState.Error("Q&Aエラー", "対象ノート", isViewed = true),
            annotationState = AnnotationState.Error(
                message = "補記エラー",
                sourceTitle = "対象ノート",
                isViewed = true
            )
        )

        assertEquals(
            AiTabBadgeState.None,
            resolveAiTabBadgeState(state.quizState, state.annotationState)
        )
    }
}
