package com.example.newproject

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuizResponseParserTest {

    private fun block(n: Int, answer: String = "B") = """
        Q: 質問$n
        A: 選択肢A
        B: 選択肢B
        C: 選択肢C
        D: 選択肢D
        ANSWER: $answer
        EXPLANATION: 解説$n
    """.trimIndent()

    @Test
    fun `標準形式の1問をパースできる`() {
        val cards = parseQuizResponse(block(1))
        assertEquals(1, cards.size)
        assertEquals("質問1", cards[0].question)
        assertEquals(listOf("選択肢A", "選択肢B", "選択肢C", "選択肢D"), cards[0].choices)
        assertEquals(1, cards[0].correctIndex)
        assertEquals("解説1", cards[0].explanation)
    }

    @Test
    fun `空行区切りの複数問をパースできる`() {
        val cards = parseQuizResponse(block(1) + "\n\n" + block(2) + "\n\n" + block(3))
        assertEquals(3, cards.size)
        assertEquals("質問3", cards[2].question)
    }

    @Test
    fun `空行なしで連続する複数問もパースできる`() {
        // モデルが空行を挟まないケース（M6 で対応した揺れ）
        val cards = parseQuizResponse(block(1) + "\n" + block(2))
        assertEquals(2, cards.size)
        assertEquals("質問2", cards[1].question)
    }

    @Test
    fun `CRLF改行でもパースできる`() {
        val cards = parseQuizResponse(block(1).replace("\n", "\r\n"))
        assertEquals(1, cards.size)
    }

    @Test
    fun `行頭に空白があってもパースできる`() {
        val indented = block(1).lines().joinToString("\n") { "  $it" }
        val cards = parseQuizResponse(indented)
        assertEquals(1, cards.size)
    }

    @Test
    fun `最初のQより前の前置きテキストは無視される`() {
        val cards = parseQuizResponse("以下がクイズです。\n\n" + block(1))
        assertEquals(1, cards.size)
        assertEquals("質問1", cards[0].question)
    }

    @Test
    fun `必須フィールドが欠けた問題はスキップされる`() {
        val broken = block(1).lines().filterNot { it.startsWith("C:") }.joinToString("\n")
        val cards = parseQuizResponse(broken + "\n" + block(2))
        assertEquals(1, cards.size)
        assertEquals("質問2", cards[0].question)
    }

    @Test
    fun `ANSWERがA-D以外の問題はスキップされる`() {
        val cards = parseQuizResponse(block(1, answer = "E"))
        assertTrue(cards.isEmpty())
    }

    @Test
    fun `EXPLANATIONは省略可能で空文字になる`() {
        val noExplanation = block(1).lines().filterNot { it.startsWith("EXPLANATION:") }.joinToString("\n")
        val cards = parseQuizResponse(noExplanation)
        assertEquals(1, cards.size)
        assertEquals("", cards[0].explanation)
    }

    @Test
    fun `空文字や無関係なテキストは空リストを返す`() {
        assertTrue(parseQuizResponse("").isEmpty())
        assertTrue(parseQuizResponse("クイズを生成できませんでした。").isEmpty())
    }
}
