package com.example.newproject

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuizResponseParserTest {

    private fun fourChoiceBlock(n: Int, answer: String = "B") = """
        Q: 質問$n
        A: 選択肢A
        B: 選択肢B
        C: 選択肢C
        D: 選択肢D
        ANSWER: $answer
        EXPLANATION: 解説$n
    """.trimIndent()

    private fun threeChoiceBlock(n: Int, answer: String = "C") = """
        Q: 質問$n
        A: 選択肢A
        B: 選択肢B
        C: 選択肢C
        ANSWER: $answer
    """.trimIndent()

    @Test
    fun `標準形式の4択1問をパースできる`() {
        val cards = parseQuizResponse(fourChoiceBlock(1), QuizFormat.FourChoice)
        assertEquals(1, cards.size)
        assertEquals("質問1", cards[0].question)
        assertEquals(listOf("選択肢A", "選択肢B", "選択肢C", "選択肢D"), cards[0].choices)
        assertEquals(1, cards[0].correctIndex)
        assertEquals("解説1", cards[0].explanation)
        assertEquals(QuizFormat.FourChoice, cards[0].format)
    }

    @Test
    fun `3択は最大2問を空行なしでもパースできる`() {
        val raw = threeChoiceBlock(1) + "\n" + threeChoiceBlock(2) + "\n" + threeChoiceBlock(3)
        val cards = parseQuizResponse(raw, QuizFormat.ThreeChoice)
        assertEquals(2, cards.size)
        assertEquals("質問2", cards[1].question)
        assertTrue(cards.all { it.format == QuizFormat.ThreeChoice })
    }

    @Test
    fun `○×は選択肢をアプリ側で補う`() {
        val raw = """
            Q: この記述は正しい
            ANSWER: TRUE

            Q: この記述は誤りである
            ANSWER: FALSE
        """.trimIndent()
        val cards = parseQuizResponse(raw, QuizFormat.TrueFalse)
        assertEquals(2, cards.size)
        assertEquals(listOf("正しい", "誤り"), cards[0].choices)
        assertEquals(0, cards[0].correctIndex)
        assertEquals(1, cards[1].correctIndex)
        assertTrue(cards.all { it.format == QuizFormat.TrueFalse })
    }

    @Test
    fun `○×の日本語と記号の回答も許容する`() {
        val raw = """
            Q：記述1
            ANSWER：○
            Q：記述2
            ANSWER：誤り
        """.trimIndent()
        val cards = parseQuizResponse(raw, QuizFormat.TrueFalse)
        assertEquals(listOf(0, 1), cards.map { it.correctIndex })
    }

    @Test
    fun `番号付き太字ラベルと全角コロンを許容する`() {
        val raw = """
            1. **Q**： 質問
            **A**： 選択肢A
            **B**： 選択肢B
            **C**： 選択肢C
            **ANSWER**： B
        """.trimIndent()
        val cards = parseQuizResponse(raw, QuizFormat.ThreeChoice)
        assertEquals(1, cards.size)
        assertEquals(1, cards[0].correctIndex)
    }

    @Test
    fun `CRLF改行と行頭空白でもパースできる`() {
        val raw = fourChoiceBlock(1).lines().joinToString("\r\n") { "  $it" }
        assertEquals(1, parseQuizResponse(raw, QuizFormat.FourChoice).size)
    }

    @Test
    fun `最初のQより前の前置きテキストは無視される`() {
        val cards = parseQuizResponse("以下がクイズです。\n\n" + fourChoiceBlock(1))
        assertEquals(1, cards.size)
        assertEquals("質問1", cards[0].question)
    }

    @Test
    fun `選択肢や正解が不完全な問題はスキップされる`() {
        val missingChoice = threeChoiceBlock(1).lines()
            .filterNot { it.startsWith("B:") }
            .joinToString("\n")
        assertTrue(parseQuizResponse(missingChoice, QuizFormat.ThreeChoice).isEmpty())
        assertTrue(parseQuizResponse(threeChoiceBlock(1, answer = "D"), QuizFormat.ThreeChoice).isEmpty())
    }

    @Test
    fun `4択要求でも3択として完結した応答は救済する`() {
        val cards = parseQuizResponse(threeChoiceBlock(1), QuizFormat.FourChoice)
        assertEquals(1, cards.size)
        assertEquals(QuizFormat.ThreeChoice, cards[0].format)
    }

    @Test
    fun `EXPLANATIONは省略可能で空文字になる`() {
        val raw = fourChoiceBlock(1).lines()
            .filterNot { it.startsWith("EXPLANATION:") }
            .joinToString("\n")
        val cards = parseQuizResponse(raw)
        assertEquals("", cards.single().explanation)
    }

    @Test
    fun `空文字や無関係なテキストは空リストを返す`() {
        assertTrue(parseQuizResponse("").isEmpty())
        assertTrue(parseQuizResponse("クイズを生成できませんでした。").isEmpty())
    }

    @Test
    fun `多択の回答は末尾記号や括弧や余分な語があっても救済する`() {
        listOf("B.", "(B)", "B) 選択肢B", "The answer is B", " b ").forEach { answer ->
            val cards = parseQuizResponse(fourChoiceBlock(1, answer = answer), QuizFormat.FourChoice)
            assertEquals("回答 '$answer' が救済されること", 1, cards.size)
            assertEquals("回答 '$answer' の正解index", 1, cards[0].correctIndex)
        }
    }

    @Test
    fun `多択で単語内のA〜Dレターを誤検出しない`() {
        // "correct" の C や "choice" の C を含んでも、独立した B だけを正解として拾う。
        val cards = parseQuizResponse(
            fourChoiceBlock(1, answer = "The correct choice is B"),
            QuizFormat.FourChoice
        )
        assertEquals(1, cards.size)
        assertEquals(1, cards[0].correctIndex)
    }

    @Test
    fun `3択で範囲外のDは記号が付いてもスキップされる`() {
        assertTrue(parseQuizResponse(threeChoiceBlock(1, answer = "(D)"), QuizFormat.ThreeChoice).isEmpty())
    }
}
