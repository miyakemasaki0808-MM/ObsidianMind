package com.example.newproject

import com.example.newproject.data.NoteRepository
import com.example.newproject.data.decodeNoteText
import com.example.newproject.data.decodeNoteTextStrict
import com.example.newproject.domain.buildNoteExcerpt
import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.domain.markdown.buildNoteSectionModel
import com.example.newproject.domain.markdown.parseMarkdownBlocks
import com.example.newproject.domain.scanReunionCandidates
import com.example.newproject.domain.selectCoverLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BOM で始まるファイルを、読み込みの復号から各機能の解析まで通す。
 *
 * **本文から BOM を外すのは読み込みの復号である。** ここでは解析器へ文字列を直接渡さず、
 * 必ずファイルのバイト列を復号してから渡す（復号を飛ばすと、外していない本文を試すことになる）。
 */
class NoteByteOrderMarkTest {

    private fun bomFile(text: String): ByteArray =
        byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8)

    /** 蒸留・本文表示の経路と、表示の代替・スニペットの経路。どちらも同じ本文になる。 */
    private fun bothDecodings(bytes: ByteArray): List<String> =
        listOf(decodeNoteTextStrict(bytes), decodeNoteText(bytes))

    @Test
    fun `the first heading is a heading in the reader and in the section list`() {
        val bytes = bomFile("# @Composable と基本部品\n\n最初の段落です。\n\n## 次の節\n\n続きです。\n")

        bothDecodings(bytes).forEach { content ->
            assertEquals(MarkdownBlock.Heading(1, "@Composable と基本部品"), parseMarkdownBlocks(content).first())
            assertEquals(
                listOf("@Composable と基本部品", "次の節"),
                buildNoteSectionModel(content).sections.map { it.title }
            )
        }
    }

    @Test
    fun `front matter behind a BOM is neither shown nor lost`() {
        val bytes = bomFile("---\ntags: [compose]\n---\n# 見出し\n\n本文の段落です。\n")

        bothDecodings(bytes).forEach { content ->
            assertEquals(MarkdownBlock.Heading(1, "見出し"), parseMarkdownBlocks(content).first())
            assertEquals(listOf("compose"), NoteRepository().parseMeta(content).tags)
            assertEquals("本文の段落です。", selectCoverLine(content, "題"))
        }
    }

    @Test
    fun `the booklet cover skips the first heading`() {
        val bytes = bomFile("# 0500_0015_B000 - @Composable と基本部品\n\n関数に注釈を付けると部品になる。\n")

        bothDecodings(bytes).forEach { content ->
            assertEquals("関数に注釈を付けると部品になる。", selectCoverLine(content, "題"))
        }
    }

    @Test
    fun `the abridged excerpt keeps the first heading in its outline`() {
        val body = "長い段落です。".repeat(200)
        val bytes = bomFile("# 最初の見出し\n\n$body\n\n## 二つ目\n\n$body\n")

        bothDecodings(bytes).forEach { content ->
            val excerpt = buildNoteExcerpt(content, budget = 600)
            assertTrue(excerpt.isAbridged)
            assertTrue(excerpt.text, excerpt.text.lines().contains("# 最初の見出し"))
        }
    }

    @Test
    fun `a reunion question on the first line loses its heading marker`() {
        val bytes = bomFile("# これは何のためのノートなのか？\n\n本文です。\n")

        bothDecodings(bytes).forEach { content ->
            assertEquals(listOf("これは何のためのノートなのか？"), scanReunionCandidates(content).questions)
        }
    }
}
