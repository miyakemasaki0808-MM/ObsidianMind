package com.example.newproject

import com.example.newproject.domain.markdown.ListMarker
import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.domain.markdown.blocksToMarkdown
import com.example.newproject.domain.markdown.parseMarkdownBlocks
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

    // --- リストのマーカー保持 -------------------------------------------------

    private fun itemsOf(content: String) =
        parseMarkdownBlocks(content).filterIsInstance<MarkdownBlock.ListBlock>().single().items

    @Test
    fun `番号付きリストは区切り記号と先頭ゼロを原文のまま保持する`() {
        val items = itemsOf("1. いち\n2) に\n03. さん")
        assertEquals(ListMarker.Ordered("1", '.'), items[0].marker)
        assertEquals(ListMarker.Ordered("2", ')'), items[1].marker)
        assertEquals(ListMarker.Ordered("03", '.'), items[2].marker)
    }

    @Test
    fun `Int桁を超える番号でも落とさない`() {
        val huge = "9".repeat(30)
        assertEquals(ListMarker.Ordered(huge, '.'), itemsOf("$huge. 巨大").single().marker)
    }

    @Test
    fun `箇条書き記号の違いは保持せず正規化する`() {
        val items = itemsOf("- ハイフン\n* アスタリスク\n+ プラス")
        assertTrue(items.all { it.marker == ListMarker.Bullet })
    }

    @Test
    fun `同じ階層で箇条書きと番号付きが切り替わっても段数は変わらない`() {
        val items = itemsOf("- 箇条書き\n1. 番号\n- また箇条書き")
        assertEquals(listOf(0, 0, 0), items.map { it.depth })
        assertEquals(ListMarker.Ordered("1", '.'), items[1].marker)
    }

    // --- 段数の算出規則 -------------------------------------------------------

    @Test
    fun `同じ幅は同じ段数になり、深くなれば幅の差に関係なく1段だけ増える`() {
        val items = itemsOf("- 親\n  - 子\n  - 弟\n        - 孫")
        assertEquals(listOf(0, 1, 1, 2), items.map { it.depth })
    }

    @Test
    fun `既知の祖先幅へ戻ればその段数になる`() {
        val items = itemsOf("- 親\n  - 子\n    - 孫\n  - 子に戻る\n- 親に戻る")
        assertEquals(listOf(0, 1, 2, 1, 0), items.map { it.depth })
    }

    @Test
    fun `未知の浅い幅は直近の浅い祖先の1段下へ正規化する`() {
        // 4 は pop され、2 はどの祖先幅とも一致しない。親（幅0・段数0）の1段下に寄せる。
        val items = itemsOf("- 親\n    - 子\n  - 幅の異なる兄弟")
        assertEquals(listOf(0, 1, 1), items.map { it.depth })
    }

    @Test
    fun `枝を抜けたら幅を忘れ、別の枝で同じ幅が違う段数になり得る`() {
        val items = itemsOf("- A\n  - A1\n    - A2\n- B\n    - B1")
        // B1 の幅4は A2 と同じだが、A の枝は pop 済みなので段数2を再利用しない
        assertEquals(listOf(0, 1, 2, 0, 1), items.map { it.depth })
    }

    @Test
    fun `タブは4文字加算ではなく次の4列境界まで展開する`() {
        // "  \t" は 2 → 4 列。"    "（4スペース）と同じ段数に落ちる
        val withTab = itemsOf("- 親\n  \t- 子")
        val withSpaces = itemsOf("- 親\n    - 子")
        assertEquals(withSpaces.map { it.depth }, withTab.map { it.depth })
    }

    @Test
    fun `別ブロックのリストは段数の追跡を引き継がない`() {
        val blocks = parseMarkdownBlocks("  - 深く始まる\n\n段落\n\n- 浅く始まる")
        val lists = blocks.filterIsInstance<MarkdownBlock.ListBlock>()
        assertEquals(0, lists[0].items.single().depth)
        assertEquals(0, lists[1].items.single().depth)
    }

    // --- 往復 -----------------------------------------------------------------

    @Test
    fun `番号と階層はblocksToMarkdownを往復しても同じ型に戻る`() {
        val content = "1. 手順いち\n    - 補足\n2) 手順に\n- 箇条書き"
        val once = parseMarkdownBlocks(content)
        val twice = parseMarkdownBlocks(blocksToMarkdown(once))
        assertEquals(once, twice)
    }
}
