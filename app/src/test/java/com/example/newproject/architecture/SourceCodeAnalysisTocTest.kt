package com.example.newproject.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **解析書の目次が、見出しとずれていないことを固定する。**
 *
 * ## なぜ要るか
 *
 * 目次は見出しの**複製**なので、放っておけば必ず古くなる。
 * このリポジトリは同じ形で何度も踏んでいる — 現行スキーマ版が6ファイル9箇所に散った件、
 * 実機ケースの範囲を正本と一覧の両方に書いた件。
 * **どちらも「片方だけ直す」で壊れた**（→ [lessons L14](../../../../../../../../docs/dev/lessons.md)）。
 *
 * 解析書は1,300行あり、§16 が「章を部分的に直さず、通しで見直す」と定めている。
 * 目次はその通し見直しから最も漏れやすい場所なので、検査に載せる。
 *
 * ## 見ているもの
 *
 * `## ` / `### ` の見出しが、**同じ並びで**目次に載っていること。リンク先のアンカーが
 * 見出しから導かれる値と一致すること。
 *
 * ## 見ていないもの
 *
 * **本文の中身が見出しと合っているか。** それは読み手が判断する。
 */
class SourceCodeAnalysisTocTest {

    @Test
    fun `目次は見出しと同じ並びで、同じ項目を持つ`() {
        val text = document().readText()
        val headings = headings(text)
        val toc = tocEntries(text)

        assertTrue("目次が見つかりません（`## 目次` を置くこと）", toc.isNotEmpty())
        assertEquals(
            "解析書の目次が見出しとずれています。章を足したら目次も直すこと。",
            headings.map { it.title },
            toc.map { it.title }
        )
    }

    @Test
    fun `目次のリンク先は見出しから導かれるアンカーと一致する`() {
        val text = document().readText()
        val violations = tocEntries(text)
            .filter { it.anchor != anchorOf(it.title) }
            .map { "「${it.title}」→ #${it.anchor}（期待: #${anchorOf(it.title)}）" }

        assertTrue(
            "目次のリンクが見出しへ解決できません:\n" + violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    /** 見出しの入れ子（`##` は最上位、`###` は1段下げ）が目次側でも保たれていること。 */
    @Test
    fun `目次の入れ子は見出しの階層と一致する`() {
        val text = document().readText()
        val expected = headings(text).map { it.level }
        val actual = tocEntries(text).map { if (it.indent.isEmpty()) 2 else 3 }

        assertEquals("目次の字下げが見出しの階層と食い違っています。", expected, actual)
    }

    // --- 読み取り -------------------------------------------------------------

    private data class Heading(val level: Int, val title: String)
    private data class TocEntry(val indent: String, val title: String, val anchor: String)

    /** **目次自身の見出しは数えない**（自分を目次へ載せることになる）。 */
    private fun headings(text: String): List<Heading> =
        HEADING.findAll(text)
            .map { Heading(it.groupValues[1].length, it.groupValues[2].trim()) }
            .filterNot { it.title == TOC_HEADING }
            .toList()

    private fun tocEntries(text: String): List<TocEntry> {
        val start = text.indexOf("## $TOC_HEADING")
        if (start < 0) return emptyList()
        // 目次は最初の水平線までとする（そこから本文が始まる）。
        val end = text.indexOf("\n---\n", start)
        val block = if (end < 0) text.substring(start) else text.substring(start, end)
        return TOC_ENTRY.findAll(block)
            .map { TocEntry(it.groupValues[1], it.groupValues[2].trim(), it.groupValues[3]) }
            .toList()
    }

    /**
     * GitHub のアンカー生成に合わせる。**小文字化し、英数字と空白・ハイフン以外を落とし、
     * 空白をハイフンへ。** 日本語はそのまま残る。
     */
    private fun anchorOf(title: String): String =
        title.lowercase()
            .filter { it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_' }
            .trim()
            .replace(' ', '-')

    private fun document(): File {
        val workingDirectory = File(
            requireNotNull(System.getProperty("user.dir")) { "user.dir が設定されていません" }
        )
        return sequenceOf(workingDirectory, workingDirectory.parentFile)
            .filterNotNull()
            .map { it.resolve("docs/owner/source_code_analysis.md") }
            .firstOrNull { it.isFile }
            ?: error("解析書が見つかりません（作業ディレクトリ: $workingDirectory）")
    }

    private companion object {
        const val TOC_HEADING = "目次"
        val HEADING = Regex("""^(#{2,3}) (.+)$""", RegexOption.MULTILINE)
        val TOC_ENTRY = Regex("""^( *)- \[([^\]]+)]\(#([^)]+)\)$""", RegexOption.MULTILINE)
    }
}
