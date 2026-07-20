package com.example.newproject.domain

import org.junit.Assert.assertEquals
import org.junit.Test

// Phase 1b: 候補ID採番と応答パースの回帰テスト（Uri非依存）。
// 「モデルが本当にIDだけ返すか」の遵守は実機（Pixel）検証で担保する。
class RelatedCandidateIdTest {

    private val valid = (0 until 5).map { relatedCandidateId(it) }.toSet() // C01..C05

    @Test
    fun `IDは1始まりの2桁ゼロ埋め`() {
        assertEquals("C01", relatedCandidateId(0))
        assertEquals("C10", relatedCandidateId(9))
        assertEquals("C40", relatedCandidateId(39))
    }

    @Test
    fun `行頭のIDを出現順で抽出する`() {
        assertEquals(listOf("C02", "C04", "C01"), parseCandidateIds("C02\nC04\nC01", valid, 5))
    }

    @Test
    fun `箇条書き・連番・コードフェンス・引用符を許容する`() {
        val response = """
            ```
            - C01
            2) C03
            "C05"
            ```
        """.trimIndent()
        assertEquals(listOf("C01", "C03", "C05"), parseCandidateIds(response, valid, 5))
    }

    @Test
    fun `未知ID・候補外IDは破棄する`() {
        assertEquals(listOf("C01"), parseCandidateIds("C99\nC01\nC40", valid, 5))
    }

    @Test
    fun `重複IDは1件に畳む`() {
        assertEquals(listOf("C01", "C02"), parseCandidateIds("C01\nC01\nC02\nC02", valid, 5))
    }

    @Test
    fun `説明文中に紛れたIDは拾わない`() {
        assertEquals(emptyList<String>(), parseCandidateIds("The note C03 seems related.", valid, 5))
    }

    @Test
    fun `大文字小文字と桁落ちを吸収する`() {
        assertEquals(listOf("C05", "C01"), parseCandidateIds("c05\nC1", valid, 5))
    }

    @Test
    fun `上限を超えて返さない`() {
        assertEquals(listOf("C01", "C02", "C03"), parseCandidateIds("C01\nC02\nC03\nC04\nC05", valid, 3))
    }

    @Test
    fun `該当なしは空リスト`() {
        assertEquals(emptyList<String>(), parseCandidateIds("no ids here", valid, 5))
    }
}
