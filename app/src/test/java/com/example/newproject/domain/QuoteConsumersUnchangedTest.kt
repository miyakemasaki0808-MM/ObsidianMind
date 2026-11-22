package com.example.newproject.domain

import com.example.newproject.domain.markdown.blocksToMarkdown
import com.example.newproject.domain.markdown.parseMarkdownBlocks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引用とコールアウトの中を描くための解析は、**描く側だけに効かせる**（→ docs/dev/system/markdown_rendering.md §8）。
 * AI へ渡す文・知識索引が数えるリンク・反証の一文の候補・他人の言葉の判定は、引用の原文の行から作り、中の解析を見ない。
 * ここは、そうした読み手の出力を固定した入力で固定する。値は中を解析する前の出力そのもので、変わったら描く側の変更が漏れている。
 */
class QuoteConsumersUnchangedTest {

    private val note = """
        # 題

        > [!NOTE] **まとめ** の題 [[リンク先A]]
        > - 項目1 [[リンク先B]]
        > - 項目2 はここに書いた主張である。
        >
        > | 列1 | 列2 |
        > |:---|---:|
        > | [[リンク先C\|表示]] | 値 |

        > 引用の地の文です。これは引用した主張である。
        > > 入れ子の引用で書いた主張である。
        > ---
        > ---

        >[!tips] 演習問題
        >	 P.17, 18 を解いた結果の主張である。

        本文の主張の一文である。[[リンク先D]]
    """.trimIndent()

    private val quotesAsMarkdown = listOf(
        "> [!NOTE] **まとめ** の題 [[リンク先A]]",
        "> - 項目1 [[リンク先B]]",
        "> - 項目2 はここに書いた主張である。",
        "> ",
        "> | 列1 | 列2 |",
        "> |:---|---:|",
        "> | [[リンク先C\\|表示]] | 値 |",
        "",
        "> 引用の地の文です。これは引用した主張である。",
        "> > 入れ子の引用で書いた主張である。",
        "> ---",
        "> ---",
        "",
        "> [!tips] 演習問題",
        ">  P.17, 18 を解いた結果の主張である。"
    ).joinToString("\n")

    @Test
    fun `書き戻した原文は、引用の行をそのまま並べる`() {
        assertEquals(
            "# 題\n\n$quotesAsMarkdown\n\n本文の主張の一文である。[[リンク先D]]",
            blocksToMarkdown(parseMarkdownBlocks(note))
        )
    }

    @Test
    fun `AI へ渡す抜粋は、引用の行をそのまま並べる`() {
        val excerpt = buildNoteExcerpt(note + "\n\n" + "埋め草の段落。".repeat(150), 700).text

        assertTrue(excerpt, "## Beginning excerpt\n# 題\n\n$quotesAsMarkdown\n\n本文の主張の…" in excerpt)
    }

    @Test
    fun `知識索引は、引用の行から今と同じリンクと文を取る`() {
        assertEquals(
            listOf(
                Triple("リンク先A", "[!NOTE] まとめ の題 リンク先A", "題"),
                Triple("リンク先B", "- 項目1 リンク先B", "題"),
                Triple("リンク先C", "| 表示 | 値 |", "題"),
                Triple("リンク先D", "リンク先D", "題")
            ),
            extractIndexedLinks(note).map { Triple(it.target, it.sentence, it.sectionHeading) }
        )
    }

    @Test
    fun `反証の一文の候補は、引用の行から今と同じ文を取る`() {
        assertEquals(
            listOf(
                "[!NOTE] まとめ の題 リンク先A - 項目1 リンク先B - 項目2 はここに書いた主張である。",
                "これは引用した主張である。",
                "> 入れ子の引用で書いた主張である。",
                "[!tips] 演習問題P.17, 18 を解いた結果の主張である。",
                "本文の主張の一文である。"
            ),
            scanRebuttalClaims(note)
        )
    }

    @Test
    fun `他人の言葉の判定は、引用とコールアウトの種類で今と同じに分ける`() {
        val quoted = (10..13).toSet()

        assertEquals(
            note.lines().indices.map { it in quoted },
            quotationLines(note.lines()).toList()
        )
    }
}
