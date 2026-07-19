package com.example.newproject

import com.example.newproject.ui.markdown.MarkdownBlock
import com.example.newproject.ui.markdown.parseMarkdownBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    @Test
    fun `frontmatterは描画ブロックから除外される`() {
        val content = """
            ---
            tags: [test]
            date: 2026-07-19
            ---
            # 見出し
            本文
        """.trimIndent()
        val blocks = parseMarkdownBlocks(content)
        assertEquals(MarkdownBlock.Heading(1, "見出し"), blocks[0])
        assertTrue(blocks.none { it is MarkdownBlock.Paragraph && "tags" in it.text })
    }

    @Test
    fun `閉じられていないfrontmatterは通常の本文として扱う`() {
        val content = """
            ---
            tags: [test]
            本文のつもり
        """.trimIndent()
        val blocks = parseMarkdownBlocks(content)
        // 先頭の --- は水平線として残る（frontmatter とはみなさない）
        assertTrue(blocks.isNotEmpty())
        assertTrue(blocks.any { it is MarkdownBlock.Paragraph })
    }

    @Test
    fun `テーブルの中間空セルは列位置を保って保持される`() {
        val content = """
            | 列1 | 列2 | 列3 |
            |---|---|---|
            | a |  | c |
        """.trimIndent()
        val table = parseMarkdownBlocks(content).filterIsInstance<MarkdownBlock.Table>().single()
        assertEquals(listOf("列1", "列2", "列3"), table.headers)
        assertEquals(listOf("a", "", "c"), table.rows[0])
    }

    @Test
    fun `見出しレベルが正しくパースされる`() {
        val blocks = parseMarkdownBlocks("## 第二レベル")
        assertEquals(MarkdownBlock.Heading(2, "第二レベル"), blocks[0])
    }

    @Test
    fun `コードブロックは中身をそのまま保持する`() {
        val content = "```\nval x = 1\nval y = 2\n```"
        val code = parseMarkdownBlocks(content).filterIsInstance<MarkdownBlock.CodeBlock>().single()
        assertEquals("val x = 1\nval y = 2", code.code)
    }

    @Test
    fun `CRLF改行のノートもパースできる`() {
        val blocks = parseMarkdownBlocks("# 見出し\r\n本文")
        assertEquals(MarkdownBlock.Heading(1, "見出し"), blocks[0])
        assertEquals(MarkdownBlock.Paragraph("本文"), blocks[1])
    }

    @Test
    fun `引用ブロックは連続行がまとめられる`() {
        val blocks = parseMarkdownBlocks("> 一行目\n> 二行目")
        val quote = blocks.filterIsInstance<MarkdownBlock.Blockquote>().single()
        assertEquals(listOf("一行目", "二行目"), quote.lines)
    }
}
