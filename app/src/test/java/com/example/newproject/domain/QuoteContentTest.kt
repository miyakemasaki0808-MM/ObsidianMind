package com.example.newproject.domain

import com.example.newproject.domain.markdown.Callout
import com.example.newproject.domain.markdown.CalloutRole
import com.example.newproject.domain.markdown.ListItem
import com.example.newproject.domain.markdown.ListMarker
import com.example.newproject.domain.markdown.MAX_QUOTE_DEPTH
import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.domain.markdown.QuoteContent
import com.example.newproject.domain.markdown.calloutRoleOf
import com.example.newproject.domain.markdown.calloutTypeOf
import com.example.newproject.domain.markdown.parseMarkdownBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 引用とコールアウトの中身の解析（→ docs/dev/system/markdown_rendering.md §8）。
 * 描く側だけが読む形なので、AI への書き戻しなどが変わらないことは [QuoteConsumersUnchangedTest] が固定する。
 */
class QuoteContentTest {

    private fun quoteOf(content: String): MarkdownBlock.Blockquote =
        parseMarkdownBlocks(content).filterIsInstance<MarkdownBlock.Blockquote>().single()

    @Test
    fun `コールアウトの頭から書かれたままの種類と役割と題を読み、頭の行は中に入れない`() {
        val quote = quoteOf("> [!NOTE] **まとめ** の題\n> 中の段落")

        assertEquals(Callout("NOTE", CalloutRole.Summary, "**まとめ** の題"), quote.content.callout)
        assertEquals(listOf(MarkdownBlock.Paragraph("中の段落")), quote.content.blocks)
    }

    @Test
    fun `空白を挟まない頭と畳みの印も読み、畳みは開いたまま中を読む`() {
        assertEquals(Callout("tips", CalloutRole.Example, "演習問題"), quoteOf(">[!tips] 演習問題").content.callout)
        assertEquals(Callout("info", CalloutRole.Summary, "題"), quoteOf("> [!info]+ 題").content.callout)

        val folded = quoteOf("> [!warning]- 閉じた題\n> 中身")
        assertEquals(Callout("warning", CalloutRole.Caution, "閉じた題"), folded.content.callout)
        assertEquals(listOf(MarkdownBlock.Paragraph("中身")), folded.content.blocks)
    }

    @Test
    fun `題の無い頭は題を空にする`() {
        assertEquals(Callout("question", CalloutRole.Question, ""), quoteOf("> [!question]\n> 問いの本文").content.callout)
    }

    @Test
    fun `素の引用はコールアウトを持たず、2行目の印は頭にしない`() {
        val quote = quoteOf("> 地の文\n> [!note] 2行目")

        assertNull(quote.content.callout)
        assertEquals(listOf(MarkdownBlock.Paragraph("地の文\n[!note] 2行目")), quote.content.blocks)
    }

    @Test
    fun `役割は Vault の使い方に合わせ、important はまとめ、tips は例・ヒント、知らない種類は持たない`() {
        assertEquals(CalloutRole.Quote, calloutRoleOf("cite"))
        assertEquals(CalloutRole.Summary, calloutRoleOf("IMPORTANT"))
        assertEquals(CalloutRole.Summary, calloutRoleOf("abstract"))
        assertEquals(CalloutRole.Question, calloutRoleOf("faq"))
        assertEquals(CalloutRole.Caution, calloutRoleOf("danger"))
        assertEquals(CalloutRole.Example, calloutRoleOf("tips"))
        assertNull(calloutRoleOf("custom"))
        assertEquals(Callout("Custom", null, "独自"), quoteOf("> [!Custom] 独自").content.callout)
    }

    @Test
    fun `種類は頭の行の中身でだけ読む`() {
        assertEquals("note", calloutTypeOf("[!NOTE]- 題"))
        assertEquals("quote", calloutTypeOf("   [!quote]"))
        assertNull(calloutTypeOf("    [!note] 字下げが4つ"))
        assertNull(calloutTypeOf("本文 [!note]"))
    }

    @Test
    fun `中の表は表として、箇条は箇条として読む`() {
        val quote = quoteOf(
            """
            > [!summary] 表の入ったまとめ
            > - 項目1
            >   - 子
            > 1. 手順
            >
            > | 列1 | 列2 |
            > |:---|---:|
            > | a | b |
            """.trimIndent()
        )

        assertEquals(
            listOf(
                MarkdownBlock.ListBlock(
                    listOf(
                        ListItem(0, ListMarker.Bullet, "項目1"),
                        ListItem(1, ListMarker.Bullet, "子"),
                        ListItem(0, ListMarker.Ordered("1", '.'), "手順")
                    )
                ),
                MarkdownBlock.Table(listOf("列1", "列2"), listOf(listOf("a", "b")))
            ),
            quote.content.blocks
        )
    }

    @Test
    fun `入れ子の引用は中の引用として読み、その中も読む`() {
        val quote = quoteOf("> 外の文\n> > [!quote] 内の題\n> > | a | b |\n> > |---|---|\n> > | 1 | 2 |")
        val inner = quote.content.blocks[1] as MarkdownBlock.Blockquote

        assertEquals(MarkdownBlock.Paragraph("外の文"), quote.content.blocks[0])
        assertEquals(Callout("quote", CalloutRole.Quote, "内の題"), inner.content.callout)
        assertEquals(listOf(MarkdownBlock.Table(listOf("a", "b"), listOf(listOf("1", "2")))), inner.content.blocks)
    }

    /** 段落の行は1行ずつ描くので、つながずに改行で持つ。中の段落の切れ目は本文と同じ規則。 */
    @Test
    fun `中の段落は行を改行でつなぎ、空行で次の段落に分ける`() {
        val quote = quoteOf("> 一行目\n> 二行目\n>\n> 三行目")

        assertEquals(listOf(MarkdownBlock.Paragraph("一行目\n二行目"), MarkdownBlock.Paragraph("三行目")), quote.content.blocks)
    }

    @Test
    fun `引用の頭の区切り線は前付けとして外さない`() {
        val quote = quoteOf("> ---\n> 間の文\n> ---")

        assertEquals(
            listOf(MarkdownBlock.HorizontalRule, MarkdownBlock.Paragraph("間の文"), MarkdownBlock.HorizontalRule),
            quote.content.blocks
        )
    }

    @Test
    fun `中の画像は原文の文字のまま段落にする`() {
        val quote = quoteOf("> ![[図.png]]\n> ![説明](a.png)")

        assertEquals(listOf(MarkdownBlock.Paragraph("![[図.png]]"), MarkdownBlock.Paragraph("![説明](a.png)")), quote.content.blocks)
    }

    @Test
    fun `入れ子の段数の上限からは中を読まず、行のまま段落にする`() {
        val deep = ">".repeat(5_000) + " 深い文"
        var quote = parseMarkdownBlocks(deep).single() as MarkdownBlock.Blockquote
        repeat(MAX_QUOTE_DEPTH) { quote = quote.content.blocks.single() as MarkdownBlock.Blockquote }

        // 引用は1段ごとに `>` を1つ外す。本文の段で1つ、中を読んだ段ごとに1つ。
        assertEquals(QuoteContent(null, listOf(MarkdownBlock.Paragraph(">".repeat(5_000 - MAX_QUOTE_DEPTH - 1) + " 深い文"))), quote.content)
    }

    /** 中身は行から決まる。行だけを渡して作った引用は、本文から読んだ引用と等しい。 */
    @Test
    fun `行だけから作った引用は本文から読んだ引用と等しい`() {
        assertEquals(MarkdownBlock.Blockquote(listOf("[!tip] 題", "- 項目")), quoteOf("> [!tip] 題\n> - 項目"))
    }
}
