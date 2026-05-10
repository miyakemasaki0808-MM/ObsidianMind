package com.example.newproject

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteRepositoryTest {

    @Test
    fun `markdown files are recognized`() {
        assertTrue(isMarkdownFile("my-note.md"))
        assertTrue(isMarkdownFile("Note With Spaces.md"))
        assertTrue(isMarkdownFile("UPPERCASE.MD"))
        assertTrue(isMarkdownFile("Mixed.Md"))
    }

    @Test
    fun `non-markdown files are rejected`() {
        assertFalse(isMarkdownFile("image.png"))
        assertFalse(isMarkdownFile("document.txt"))
        assertFalse(isMarkdownFile("archive.md.zip"))
        assertFalse(isMarkdownFile(null))
        assertFalse(isMarkdownFile(""))
        assertFalse(isMarkdownFile(".md.bak"))
    }
}
