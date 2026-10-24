package com.example.newproject.domain

import com.example.newproject.domain.markdown.buildNoteSectionModel
import com.example.newproject.model.MemoPassage
import com.example.newproject.model.QuoteContext
import com.example.newproject.model.ReadingTraceLimits
import com.example.newproject.model.SectionRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 選択メニューの「引用して書く」で受け取った文字から、付ける引用を作る（→ features/margin_quote.md §5.2・§11）。
 * 受け取る文字は Compose の選択のコピーと同じ形 — 部品の間にもブロックの間にも何も挟まない。
 */
class QuoteAttachmentTest {

    private fun indexOf(content: String): Pair<PassageIndex, HeadingIndex> {
        val model = buildNoteSectionModel(content)
        return model.passages to model.headingIndex
    }

    // ── ブロックの境目で切る ─────────────────────────────────────────────

    @Test
    fun `1つのブロックの中で選んだ文字はそのまま`() {
        val (index, _) = indexOf("意志の力に頼ると、\n三日で元に戻る。\n\n次の段落")

        assertEquals("頼ると、 三日で", splitAtBlocks("頼ると、 三日で", from = 0, index))
    }

    /** Compose はブロックの間に何も挟まないので、境目を改行にしないと最初の行が次の段落へはみ出す。 */
    @Test
    fun `ブロックをまたいで選んだ文字は境目で改行に切る`() {
        val (index, _) = indexOf("最初の段落の終わり。\n\n二つ目の段落の全部。\n\n三つ目の段落の始まりと続き")

        assertEquals(
            "段落の終わり。\n二つ目の段落の全部。\n三つ目の段落",
            splitAtBlocks("段落の終わり。二つ目の段落の全部。三つ目の段落", from = 0, index)
        )
    }

    @Test
    fun `画像と区切り線は文字を持たないので境目にならない`() {
        val (index, _) = indexOf("前の段落\n\n---\n\n![図](a.png)\n\n後の段落")

        assertEquals("前の段落\n後の段落", splitAtBlocks("前の段落後の段落", from = 0, index))
    }

    @Test
    fun `境目が合わなくなったところから先は1行のまま残す`() {
        val (index, _) = indexOf("前の段落\n\n次の段落")

        assertEquals("前の段落\n本文に無い文字", splitAtBlocks("前の段落本文に無い文字", from = 0, index))
    }

    @Test
    fun `始まりのブロックが分からなければ選んだ文字のまま`() {
        val (index, _) = indexOf("前の段落\n\n次の段落")

        assertEquals("前の段落次の段落", splitAtBlocks("前の段落次の段落", from = null, index))
        assertEquals("前の段落次の段落", splitAtBlocks("前の段落次の段落", from = 9, index))
    }

    /** 引用の上限より先は切られて入らないので、境目を探すのもそこで止める（本文が長くても仕事は上限まで）。上限を越えた後の残りは1行のまま。 */
    @Test
    fun `境目を探すのは引用の上限までで、その先は1行のまま`() {
        val long = "あ".repeat(ReadingTraceLimits.MAX_QUOTE_BYTES)
        val (index, _) = indexOf("前の段落\n\n$long\n\n三つ目\n\n四つ目")

        assertEquals("前の段落\n$long\n三つ目四つ目", splitAtBlocks("前の段落${long}三つ目四つ目", from = 0, index))
        assertEquals("前の段落\n三つ目\n四つ目", splitAtBlocks("前の段落三つ目四つ目", from = 0, indexOf("前の段落\n\n三つ目\n\n四つ目").first))
    }

    // ── 付ける引用 ───────────────────────────────────────────────────

    /** **同じ文が2度ある本文で後ろを選ぶと、引用元は後ろに決まる**（位置で決め、文字だけでは決めない → 判断11）。 */
    @Test
    fun `位置から決まれば同じ文が2度あってもそのブロックを引用元にする`() {
        val (index, headings) = indexOf("## 節A\n\n結論として、この方法は有効である。ただし条件Aでは使わない。\n\n## 節B\n\n結論として、この方法は有効である。ただし条件Bでは使わない。")
        val attachment = checkNotNull(quoteAttachmentFor("この方法は有効である。", sourceBlock = 3, hintBlock = 3, index))

        assertEquals(SectionRef("節B"), attachment.section)
        assertEquals(QuoteContext("結論として、", "ただし条件Bでは使わな"), attachment.quote.passage.context)
        assertFalse(attachment.quote.choosing)
        assertEquals(PassageLocation.Located(3, SectionRef("節B")), locatePassage(attachment.quote.passage, "節B", index, headings))
    }

    @Test
    fun `位置から決まらなくても本文に1か所だけなら引用元にする`() {
        val (index, _) = indexOf("## 節A\n\n一度だけ出る一節。\n\n## 節B\n\n別の段落")
        val attachment = checkNotNull(quoteAttachmentFor("一度だけ出る一節", sourceBlock = null, hintBlock = 3, index))

        assertEquals(SectionRef("節A"), attachment.section)
        assertEquals(QuoteContext("", "。"), attachment.quote.passage.context)
    }

    /** **見えている範囲で1つでも根拠にしない** — 2か所以上なら付ける場で選んでもらい、前後は控えない（→ 判断13）。 */
    @Test
    fun `位置から決まらず本文に2か所以上あれば付ける場で選んでもらう`() {
        val (index, _) = indexOf("## 節A\n\n同じ一節\n\n## 節B\n\n同じ一節")
        val attachment = checkNotNull(quoteAttachmentFor("同じ一節", sourceBlock = null, hintBlock = 3, index))

        assertTrue(attachment.quote.choosing)
        assertNull(attachment.quote.passage.context)
        assertNull(attachment.section)
    }

    @Test
    fun `本文に見つからなければ引用元を決めないまま付ける`() {
        val (index, _) = indexOf("本文")
        val attachment = checkNotNull(quoteAttachmentFor("どこにも無い", sourceBlock = 0, hintBlock = 0, index))

        assertFalse(attachment.quote.choosing)
        assertNull(attachment.quote.passage.context)
        assertNull(attachment.section)
    }

    @Test
    fun `空白だけの選択は引用にしない`() {
        val (index, _) = indexOf("本文")

        assertNull(quoteAttachmentFor(" \n\t ", sourceBlock = 0, hintBlock = 0, index))
    }

    /** ブロックをまたいで選んでも、引用元は最初のブロックで決まり、次に開いたときもそこへ戻る。 */
    @Test
    fun `ブロックをまたいで選んだ引用は最初のブロックに決まり照らし直しでも戻る`() {
        val (index, headings) = indexOf("## 節\n\n最初の段落の終わり。\n\n二つ目の段落")
        val attachment = checkNotNull(quoteAttachmentFor("段落の終わり。二つ目の", sourceBlock = 1, hintBlock = 1, index))

        assertEquals("段落の終わり。\n二つ目の", attachment.quote.passage.text)
        assertEquals(QuoteContext("最初の", ""), attachment.quote.passage.context)
        assertEquals(PassageLocation.Located(1, SectionRef("節")), locatePassage(attachment.quote.passage, "節", index, headings))
    }

    @Test
    fun `上限を超えた部分は切れる部分として持ち、途中までの引用にする`() {
        val long = "い".repeat(400)
        val (index, _) = indexOf(long)
        val attachment = checkNotNull(quoteAttachmentFor(long, sourceBlock = 0, hintBlock = 0, index))

        assertTrue(attachment.quote.passage.truncated)
        assertEquals("い".repeat(341), attachment.quote.passage.text)
        assertEquals("い".repeat(59), attachment.quote.cut)
        assertEquals(SectionRef(title = null), attachment.section)
    }

    // ── 候補 ───────────────────────────────────────────────────────

    @Test
    fun `候補は鍵を含むブロックを本文の順にすべて、前後の文字つきで並べる`() {
        val (index, _) = indexOf("## 節A\n\n前Aの同じ一節の後A\n\n## 節B\n\n別の段落\n\n## 節A\n\n同じ一節")

        assertEquals(
            listOf(
                QuoteCandidate(1, SectionRef("節A"), QuoteContext("前Aの", "の後A")),
                QuoteCandidate(5, SectionRef("節A", ordinal = 1), QuoteContext("", ""))
            ),
            quoteCandidates("同じ一節\n次の行は鍵に入らない", index)
        )
    }

    @Test
    fun `鍵が空なら候補は無い`() {
        val (index, _) = indexOf("本文")

        assertTrue(quoteCandidates(" ", index).isEmpty())
    }

    @Test
    fun `付ける場で選んだ候補で前後の文字が決まる`() {
        val (index, _) = indexOf("## 節A\n\n前の同じ一節\n\n## 節B\n\n同じ一節の後")
        val candidates = quoteCandidates("同じ一節", index)

        assertEquals(QuoteSource.Resolved(3, QuoteContext("", "の後")), resolveQuoteAt("同じ一節", candidates[1].block, index))
        assertEquals(MemoPassage.Quoted("同じ一節", null, false).text, "同じ一節")
    }
}
