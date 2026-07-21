package com.example.newproject

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventKeyTest {

    @Test
    fun `Idleはキーを持たない`() {
        assertNull(QuizState.Idle.toEventKey())
        assertNull(AnnotationState.Idle.toEventKey())
    }

    @Test
    fun `確認済みにするとキーが変わりSnackbarが再発火できる`() {
        val unviewed = QuizState.Error("生成エラー", "対象ノート")
        val viewed = unviewed.copy(isViewed = true)

        assertNotEquals(unviewed.toEventKey(), viewed.toEventKey())
    }

    @Test
    fun `同じ状態なら同じキーになる`() {
        val cards = listOf(QuizCard("問題", listOf("A", "B", "C", "D"), 0))
        val first = QuizState.Success("対象ノート", cards)
        val second = QuizState.Success("対象ノート", cards)

        assertEquals(first.toEventKey(), second.toEventKey())
    }

    @Test
    fun `別ノートの生成中は別キーになる`() {
        assertNotEquals(
            AnnotationState.Loading("ノートA").toEventKey(),
            AnnotationState.Loading("ノートB").toEventKey()
        )
    }

    @Test
    fun `同じノートでも出題形式が変われば生成中キーが変わる`() {
        assertNotEquals(
            QuizState.Loading("ノート", QuizFormat.TrueFalse).toEventKey(),
            QuizState.Loading("ノート", QuizFormat.ThreeChoice).toEventKey()
        )
    }
}
