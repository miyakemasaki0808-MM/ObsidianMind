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

    // --- タスクとの混在 -------------------------------------------------------

    @Test
    fun `タスクと箇条書きの混在は1ブロックにまとまり、入れ子段数が保たれる`() {
        val items = itemsOf("- 親\n  - [ ] 未完\n  - [x] 完了\n  - ただの子\n- 親に戻る")
        assertEquals(listOf(0, 1, 1, 1, 0), items.map { it.depth })
        assertEquals(listOf(null, false, true, null, null), items.map { it.checked })
    }

    @Test
    fun `同じ階層で箇条書きと番号付きとタスクが切り替わってもまとまる`() {
        val items = itemsOf("- 箇条書き\n1. 番号\n- [x] タスク")
        assertEquals(listOf(0, 0, 0), items.map { it.depth })
        assertEquals(listOf(null, null, true), items.map { it.checked })
    }

    @Test
    fun `番号付きのチェックボックス表記はタスクとして扱わない`() {
        // 型としては Ordered + checked を表現できるが、仕様は広げない
        val item = itemsOf("1. [ ] やること").single()
        assertEquals(null, item.checked)
        assertEquals(ListMarker.Ordered("1", '.'), item.marker)
        assertEquals("[ ] やること", item.text)
    }

    // --- 画像 -----------------------------------------------------------------

    @Test
    fun `単独行のリンク記法は画像ブロックになる`() {
        val blocks = parseMarkdownBlocks("![図の説明](attachments/zu.png)")
        assertEquals(
            MarkdownBlock.Image("図の説明", "attachments/zu.png", isEmbed = false),
            blocks.single()
        )
    }

    @Test
    fun `単独行のwiki埋め込みは画像ブロックになる`() {
        val blocks = parseMarkdownBlocks("![[Pasted image 20260802.png]]")
        assertEquals(
            MarkdownBlock.Image("", "Pasted image 20260802.png", isEmbed = true),
            blocks.single()
        )
    }

    @Test
    fun `拡張子を持たないwiki埋め込みは画像として扱わない`() {
        // ![[note]] は他ノートの埋め込みという別機能。画像として解決しにいってはいけない。
        val blocks = parseMarkdownBlocks("![[別のノート]]")
        assertTrue(blocks.single() is MarkdownBlock.Paragraph)
    }

    @Test
    fun `md拡張子のwiki埋め込みも画像として扱わない`() {
        val blocks = parseMarkdownBlocks("![[別のノート.md]]")
        assertTrue(blocks.single() is MarkdownBlock.Paragraph)
    }

    @Test
    fun `サイズヒントは判定から外すが原文のまま保持する`() {
        // 埋め込みは `|` の前がファイル名（リンクの別名とは前後が逆）。
        val blocks = parseMarkdownBlocks("![[zu.png|400]]")
        assertEquals(
            MarkdownBlock.Image("", "zu.png|400", isEmbed = true),
            blocks.single()
        )
    }

    @Test
    fun `行内に他の文字があれば画像ブロックにしない`() {
        val blocks = parseMarkdownBlocks("説明 ![図](zu.png) のように")
        assertTrue(blocks.single() is MarkdownBlock.Paragraph)
    }

    @Test
    fun `画像の後ろに括弧つきの文が続く行は画像ブロックにしない`() {
        // `.*` の最長一致だと対象が "zu.png) と説明(補足" になる。迷ったら段落のままに倒す。
        val blocks = parseMarkdownBlocks("![図](zu.png) と説明(補足)")
        assertTrue(blocks.single() is MarkdownBlock.Paragraph)
    }

    @Test
    fun `1行に画像が2つある場合は画像ブロックにしない`() {
        val embeds = parseMarkdownBlocks("![[a.png]] ![[b.png]]")
        assertTrue(embeds.single() is MarkdownBlock.Paragraph)
        val links = parseMarkdownBlocks("![a](a.png) ![b](b.png)")
        assertTrue(links.single() is MarkdownBlock.Paragraph)
    }

    @Test
    fun `対象に閉じ括弧を含む行は画像ブロックにしない`() {
        val blocks = parseMarkdownBlocks("![a](b)(c)")
        assertTrue(blocks.single() is MarkdownBlock.Paragraph)
    }

    @Test
    fun `リスト項目の画像は画像ブロックにしない`() {
        val blocks = parseMarkdownBlocks("- ![図](zu.png)")
        assertTrue(blocks.single() is MarkdownBlock.ListBlock)
    }

    @Test
    fun `空行を挟まなくても画像行は段落を切る`() {
        val blocks = parseMarkdownBlocks("説明の本文\n![[zu.png]]\n続きの本文")
        assertEquals(3, blocks.size)
        assertEquals(MarkdownBlock.Paragraph("説明の本文"), blocks[0])
        assertEquals(MarkdownBlock.Image("", "zu.png", isEmbed = true), blocks[1])
        assertEquals(MarkdownBlock.Paragraph("続きの本文"), blocks[2])
    }

    @Test
    fun `altが空でも画像ブロックになる`() {
        val blocks = parseMarkdownBlocks("![](zu.png)")
        assertEquals(MarkdownBlock.Image("", "zu.png", isEmbed = false), blocks.single())
    }

    @Test
    fun `拡張子を持たないリンク記法も画像として扱う`() {
        // 外部URLは「ネットワーク権限が無いので出せない」という理由を出す必要があり、
        // そのためには画像として認識されていなければならない。
        val blocks = parseMarkdownBlocks("![外部](https://example.com/a)")
        assertEquals(
            MarkdownBlock.Image("外部", "https://example.com/a", isEmbed = false),
            blocks.single()
        )
    }

    // --- 往復 -----------------------------------------------------------------

    @Test
    fun `画像は往復しても同じ型に戻る`() {
        val content = "![図の説明](attachments/a%20b.png)\n\n![[zu.png|400]]\n\n![](x.png)"
        val once = parseMarkdownBlocks(content)
        val twice = parseMarkdownBlocks(blocksToMarkdown(once))
        assertEquals(once, twice)
    }

    @Test
    fun `画像として認識しなかった行も往復して同じ型に戻る`() {
        // 段落へ倒した側も原文のまま往復する（倒したこと自体で情報が落ちない）。
        val content = "![a](b)(c)\n\n![[a.png]] ![[b.png]]\n\n![図](zu.png) と説明(補足)"
        val once = parseMarkdownBlocks(content)
        val twice = parseMarkdownBlocks(blocksToMarkdown(once))
        assertEquals(once, twice)
        assertTrue(once.all { it is MarkdownBlock.Paragraph })
    }

    @Test
    fun `画像のパスはAI入力から落ちない`() {
        // レンダラを分離せず忠実復元を選んだので、AI入力は現状（原文どおり）を維持する。
        val once = parseMarkdownBlocks("![図](attachments/zu.png)")
        assertEquals("![図](attachments/zu.png)", blocksToMarkdown(once))
    }

    @Test
    fun `番号と階層はblocksToMarkdownを往復しても同じ型に戻る`() {
        val content = "1. 手順いち\n    - 補足\n2) 手順に\n- 箇条書き"
        val once = parseMarkdownBlocks(content)
        val twice = parseMarkdownBlocks(blocksToMarkdown(once))
        assertEquals(once, twice)
    }

    @Test
    fun `タスクの混在も往復して同じ型に戻る`() {
        val content = "- 親\n  - [ ] 未完\n  - [x] 完了\n- 親に戻る"
        val once = parseMarkdownBlocks(content)
        val twice = parseMarkdownBlocks(blocksToMarkdown(once))
        assertEquals(once, twice)
    }
}
