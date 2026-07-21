package com.example.newproject.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class DistillResponseParserTest {
    private val valid = (0 until 6).map(::distillCandidateId).toSet()

    @Test
    fun `preamble commas and lowercase ids are accepted`() {
        val response = "はい、重要なIDは: s001, S005 です。"
        assertEquals(listOf("S001", "S005"), parseDistillResponseIds(response, valid))
    }

    @Test
    fun `unknown duplicates and malformed boundaries are discarded`() {
        val response = "S001 S001 S999 S01 S0012 S002A XS003"
        assertEquals(listOf("S001"), parseDistillResponseIds(response, valid))
    }

    @Test
    fun `selection limit is enforced`() {
        assertEquals(
            listOf("S001", "S002"),
            parseDistillResponseIds("S001 S002 S003", valid, limit = 2)
        )
    }
}
