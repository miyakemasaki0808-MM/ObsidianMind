package com.example.newproject.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteExcerptBuilderTest {

    @Test
    fun `予算内は無加工で返す`() {
        val content = "# 見出し\n\n本文"

        val excerpt = buildNoteExcerpt(content, content.length)

        assertEquals(content, excerpt.text)
        assertFalse(excerpt.isAbridged)
    }

    @Test
    fun `予算超過は現行どおり先頭から切る`() {
        val excerpt = buildNoteExcerpt("abcdefghij", 6)

        assertEquals("abcdef", excerpt.text)
        assertTrue(excerpt.isAbridged)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `負の予算は拒否する`() {
        buildNoteExcerpt("本文", -1)
    }
}
