package com.example.newproject

import org.junit.Assert.assertEquals
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

    @Test
    fun `obsidian wikilinks are normalized to note titles`() {
        val content = """
            See [[Folder/Sub Note.md|alias]], [[Target#Heading]], [[Block Note^abc123]], and [[Plain Note]].
        """.trimIndent()

        val meta = NoteRepository().parseMeta(content)

        assertEquals(
            setOf("Sub Note", "Target", "Block Note", "Plain Note"),
            meta.wikilinkTitles
        )
    }

    @Test
    fun `note title normalization handles paths anchors aliases and md extension`() {
        assertEquals("sub note", "Folder/Sub Note.md|alias".toNormalizedObsidianTitle())
        assertEquals("target", "Target#Heading".toNormalizedObsidianTitle())
        assertEquals("block note", "Block Note^abc123".toNormalizedObsidianTitle())
        assertEquals("plain note", "Plain Note.md".toNormalizedObsidianTitle())
    }

    @Test
    fun `annotation file titles are sanitized for saf file creation`() {
        assertEquals(
            "a_b_c_d_e_f_g_h_i",
            sanitizeAnnotationFileTitle("a/b\\c:d*e?f\"g<h>i")
        )
        assertEquals("multi_line_title", sanitizeAnnotationFileTitle("multi\nline\ttitle"))
        assertEquals("untitled", sanitizeAnnotationFileTitle("////"))
    }
}
