package com.example.newproject.domain

import com.example.newproject.domain.markdown.buildNoteSectionModel
import com.example.newproject.model.MemoPassage
import com.example.newproject.model.QuoteContext
import com.example.newproject.model.ReadingTraceLimits
import com.example.newproject.model.SectionRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 引用と控えた位置の整え方・引用元の決め方・照らし直し（→ features/margin_quote.md §5.2・§5.4・§5.5・§10）。
 *
 * **不確かなものを確定として扱わない**ことを、同じ文が2度ある本文・元の段落を消した本文・前後を控えていない引用で確かめる。
 */
class MemoPassagesTest {

    private val conditionA = "結論として、この方法は有効である。ただし条件Aでは使わない。"
    private val conditionB = "結論として、この方法は有効である。ただし条件Bでは使わない。"
    private val quote = "この方法は有効である。"

    private fun indexOf(content: String): Pair<PassageIndex, HeadingIndex> {
        val model = buildNoteSectionModel(content)
        return buildPassageIndex(model) to model.headingIndex
    }

    private fun blockOf(index: PassageIndex, text: String): Int = index.texts.indexOfFirst { it == text }

    /** 引用を付けたときと同じ取り方で、[block] から控える。 */
    private fun quotedFrom(index: PassageIndex, block: Int, text: String = quote): MemoPassage.Quoted {
        val source = resolveQuoteAt(text, block, index) as QuoteSource.Resolved
        return MemoPassage.Quoted(text, source.context, truncated = false)
    }

    // ── 引用を整える ───────────────────────────────────────────────────────

    @Test
    fun `引用は制御文字を落として前後の空白を削る`() {
        assertEquals(ComposedQuote("一節\n次の行", cut = ""), composeQuote("  一節\u0001\n次の行\u0007  "))
        assertNull(composeQuote(" \n\t "))
    }

    @Test
    fun `上限を超えた引用は切れる部分を返し、文字の途中では切らない`() {
        val limit = ReadingTraceLimits.MAX_QUOTE_BYTES
        val composed = composeQuote("あ".repeat(limit / 3) + "😀" + "続き")!!

        assertTrue("上限を超えた", composed.text.toByteArray(Charsets.UTF_8).size <= limit)
        assertEquals("あ".repeat(limit / 3), composed.text)
        assertEquals("😀続き", composed.cut)
        assertTrue(composed.truncated)
        assertEquals(false, composeQuote("短い一節")!!.truncated)
    }

    @Test
    fun `末尾からの切り方も絵文字を割らない`() {
        assertEquals("😀", takeLastUtf8Bytes("あ😀", 5))
        assertEquals("", takeLastUtf8Bytes("😀", 3))
        assertEquals("あ", takeFirstUtf8Bytes("あ😀", 5))
    }

    @Test
    fun `照らし直しの鍵は最初の行を整えたもの`() {
        assertEquals("最初の 行", quoteKey("\n  最初の\t 行\n二つ目"))
        assertEquals("", quoteKey(" \n "))
    }

    // ── 引用元を決める（付けるとき）──────────────────────────────────────

    @Test
    fun `選んだ位置があればそのブロックの前後を控える`() {
        val (index, _) = indexOf("$conditionA\n\n$conditionB")
        val b = blockOf(index, conditionB)

        val source = quoteSourceFor(quote, selectedBlock = b, index = index)

        // 後ろの文字は32バイト（日本語で10字）まで。
        assertEquals(QuoteSource.Resolved(b, QuoteContext("結論として、", "ただし条件Bでは使わな")), source)
    }

    @Test
    fun `前後の文字は各32バイトまでで、ブロックの端では空`() {
        val long = "あ".repeat(20) + quote + "い".repeat(20)
        val (index, _) = indexOf("$long\n\n$quote")

        val inner = (resolveQuoteAt(quote, blockOf(index, long), index) as QuoteSource.Resolved).context
        val whole = (resolveQuoteAt(quote, blockOf(index, quote), index) as QuoteSource.Resolved).context

        assertEquals(QuoteContext("あ".repeat(10), "い".repeat(10)), inner)
        assertEquals(QuoteContext("", ""), whole)
    }

    /** **見えている範囲の一意を根拠にしない。** 位置が取れなければ本文の全体で数える（→ 判断11）。 */
    @Test
    fun `位置が取れず本文に同じ一節が2か所あれば付ける場で選んでもらう`() {
        val (index, _) = indexOf("$conditionA\n\n$conditionB")

        val source = quoteSourceFor(quote, selectedBlock = null, index = index)

        assertEquals(QuoteSource.Ambiguous(listOf(blockOf(index, conditionA), blockOf(index, conditionB))), source)
    }

    @Test
    fun `位置が取れなくても本文の全体で1か所なら補う`() {
        val (index, _) = indexOf("$conditionA\n\nほかの段落")

        assertEquals(
            QuoteSource.Resolved(blockOf(index, conditionA), QuoteContext("結論として、", "ただし条件Aでは使わな")),
            quoteSourceFor(quote, selectedBlock = null, index = index)
        )
    }

    @Test
    fun `本文に無い引用は引用元を決めない`() {
        val (index, _) = indexOf("ほかの段落")

        assertEquals(QuoteSource.Unresolved, quoteSourceFor(quote, selectedBlock = null, index = index))
    }

    /** 選択の文字の形が本文と合わず、選んだブロックに鍵が無いときは、本文の全体で数え直す。 */
    @Test
    fun `選んだブロックに鍵が無ければ本文の全体で数え直す`() {
        val (index, _) = indexOf("$conditionA\n\nほかの段落")

        val source = quoteSourceFor(quote, selectedBlock = blockOf(index, "ほかの段落"), index = index)

        assertEquals(blockOf(index, conditionA), (source as QuoteSource.Resolved).block)
    }

    // ── 照らし直す ─────────────────────────────────────────────────────────

    @Test
    fun `後ろの段落から引いた引用は後ろへ戻る`() {
        val (index, headings) = indexOf("## 節\n\n$conditionA\n\n$conditionB")
        val b = blockOf(index, conditionB)

        val location = locatePassage(quotedFrom(index, b), "節", index, headings)

        assertEquals(PassageLocation.Located(b, SectionRef("節")), location)
    }

    /** **前の文字だけが合う候補を、元の場所と確定しない**（元の段落が消えたのか書き換わったのかは区別できない）。 */
    @Test
    fun `元の段落を消すと前の文字だけが合う段落を確定しない`() {
        val (before, _) = indexOf("## 節\n\n$conditionA\n\n$conditionB")
        val passage = quotedFrom(before, blockOf(before, conditionB))
        val (after, headings) = indexOf("## 節\n\n$conditionA")

        val location = locatePassage(passage, "節", after, headings)

        assertEquals(PassageLocation.Uncertain(listOf(blockOf(after, conditionA))), location)
    }

    @Test
    fun `後ろの文字だけが共通の場合も確定しない`() {
        val a = "前置きA。この方法は有効である。ただし例外がある。"
        val b = "前置きB。この方法は有効である。ただし例外がある。"
        val (before, _) = indexOf("$a\n\n$b")
        val passage = quotedFrom(before, blockOf(before, b))
        val (after, headings) = indexOf(a)

        assertEquals(PassageLocation.Uncertain(listOf(0)), locatePassage(passage, null, after, headings))
    }

    @Test
    fun `前後とも合う候補が2つあれば特定できない`() {
        val (index, headings) = indexOf("## 節\n\n$conditionA\n\n## 節\n\n$conditionA")
        val first = index.texts.indexOf(conditionA)
        val second = index.texts.lastIndexOf(conditionA)

        val location = locatePassage(quotedFrom(index, first), "節", index, headings)

        assertEquals(PassageLocation.Uncertain(listOf(first, second)), location)
    }

    /** 同名の見出しの中でも、前後とも合う1か所なら確定する。 */
    @Test
    fun `同名の見出しの中でも前後とも合う1か所なら確定する`() {
        val (index, headings) = indexOf("## 節\n\n$conditionA\n\n## 節\n\n$conditionB")
        val b = blockOf(index, conditionB)

        assertEquals(PassageLocation.Located(b, SectionRef("節", ordinal = 1)), locatePassage(quotedFrom(index, b), "節", index, headings))
    }

    /** **段落を丸ごと引いた引用（前後が空）と、前後を控えていない引用は、候補が1件でも結果が違う。** */
    @Test
    fun `前後を控えていない引用は候補が1件でも確定しない`() {
        val (index, headings) = indexOf("Q\n\nほかの段落")
        val wholeBlock = MemoPassage.Quoted("Q", QuoteContext("", ""), truncated = false)
        val unresolved = MemoPassage.Quoted("Q", context = null, truncated = false)

        assertEquals(PassageLocation.Located(0, SectionRef(title = null)), locatePassage(wholeBlock, null, index, headings))
        assertEquals(PassageLocation.Uncertain(listOf(0)), locatePassage(unresolved, null, index, headings))
    }

    @Test
    fun `前後を控えていない引用は候補が減っても確定せず、無くなれば見つからない`() {
        val unresolved = MemoPassage.Quoted(quote, context = null, truncated = false)
        val (two, h2) = indexOf("$conditionA\n\n$conditionB")
        val (one, h1) = indexOf(conditionA)
        val (none, h0) = indexOf("ほかの段落")

        assertEquals(PassageLocation.Uncertain(listOf(0, 1)), locatePassage(unresolved, null, two, h2))
        assertEquals(PassageLocation.Uncertain(listOf(0)), locatePassage(unresolved, null, one, h1))
        assertEquals(PassageLocation.NotFound, locatePassage(unresolved, null, none, h0))
    }

    /** 前後が空の引用は、候補の側も空（ブロックの端）のときだけ合う。 */
    @Test
    fun `前後が空の引用は段落の中ほどにある同じ文には合わない`() {
        val (index, headings) = indexOf("前置き。Q。続き。")
        val wholeBlock = MemoPassage.Quoted("Q。", QuoteContext("", ""), truncated = false)

        assertEquals(PassageLocation.Uncertain(listOf(0)), locatePassage(wholeBlock, null, index, headings))
    }

    /**
     * 段落の中の改行は、本文の画面で空白1つにつないで描かれる（`joinParagraphLines` の既定）。選んで写した文字にも空白が入る。
     * 照らし直しは太字の記号を落とした見える文字で比べ、空白と改行の並びの違いでは外れない。
     */
    @Test
    fun `段落の中の改行と太字の記号では外れない`() {
        val (index, headings) = indexOf("結論として、この**方法**は\n有効である。ただし条件Bでは使わない。")
        val passage = MemoPassage.Quoted("この方法は  有効である。", QuoteContext("結論として、", "ただし条件Bでは使わな"), truncated = false)

        assertEquals(PassageLocation.Located(0, SectionRef(title = null)), locatePassage(passage, null, index, headings))
    }

    // ── 書き始めた頃の位置 ─────────────────────────────────────────────────

    @Test
    fun `最上端が見出しなら同じ節の次の本文のブロックを控える`() {
        val (index, _) = indexOf("## 習慣\n\n意志の力に頼ると、三日で元に戻る。\n\n## 次")

        assertEquals(MemoPassage.Seen("意志の力に頼ると、三日で元に戻る。"), seenPassageAt(0, index))
    }

    @Test
    fun `同じ節に本文のブロックが無ければ控えない`() {
        val (index, _) = indexOf("## 空の節\n\n---\n\n## 次\n\n本文")

        assertNull(seenPassageAt(0, index))
    }

    @Test
    fun `控える位置はブロックの頭の64バイトまで`() {
        val (index, _) = indexOf("あ".repeat(40))

        assertEquals(MemoPassage.Seen("あ".repeat(21)), seenPassageAt(0, index))
    }

    @Test
    fun `控えた位置は控えた見出しの節の中で1か所なら使う`() {
        val (index, headings) = indexOf("## 習慣\n\n同じ書き出し\n\n## 別\n\n同じ書き出し")
        val seen = MemoPassage.Seen("同じ書き出し")

        assertEquals(PassageLocation.Located(1, SectionRef("習慣")), locatePassage(seen, "習慣", index, headings))
        assertEquals(PassageLocation.Uncertain(listOf(1, 3)), locatePassage(seen, null, index, headings))
    }

    @Test
    fun `控えた見出しが無くなっても本文の全体で1か所なら使う`() {
        val (index, headings) = indexOf("## 改名した見出し\n\n意志の力に頼ると")

        assertEquals(
            PassageLocation.Located(1, SectionRef("改名した見出し")),
            locatePassage(MemoPassage.Seen("意志の力に頼ると"), "元の見出し", index, headings)
        )
    }
}
