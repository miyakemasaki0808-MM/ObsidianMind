package com.example.newproject.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.ui.markdown.MarkdownBodyStyle
import com.example.newproject.ui.markdown.isLeadParagraph
import com.example.newproject.ui.screen.SPREAD_BODY_FONT_SP
import com.example.newproject.ui.screen.SPREAD_BODY_LINE_SP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 本文の書式の**関係**（→ `docs/dev/features/note_typesetting.md` §5.2・判断5・判断7・判断8）。
 * 値そのものは実機で読み比べて動かすので固定しない。動かしても崩してはいけない順序と約束だけを見る。
 */
class MarkdownBodyStyleTest {

    private val headings = (1..6).map { MarkdownBodyStyle.heading(it) }

    /** アプリが書く本文の書式すべて。ここに無い書式を足したら、ここへも足す。 */
    private val bodyStyles: List<Pair<String, TextStyle>> = listOf(
        "Tag" to MarkdownBodyStyle.Tag,
        "Paragraph" to MarkdownBodyStyle.Paragraph,
        "Lead" to MarkdownBodyStyle.Lead,
        "Quote" to MarkdownBodyStyle.Quote,
        "PullQuote" to MarkdownBodyStyle.PullQuote,
        "QuoteMarkText" to MarkdownBodyStyle.QuoteMarkText,
        "CalloutLabelText" to MarkdownBodyStyle.CalloutLabelText,
        "CalloutTitleText" to MarkdownBodyStyle.CalloutTitleText,
        "TableCell" to MarkdownBodyStyle.TableCell,
        "TableHeader" to MarkdownBodyStyle.TableHeader,
        "Code" to MarkdownBodyStyle.Code
    ) + headings.mapIndexed { i, style -> "heading(${i + 1})" to style }

    @Test
    fun `見出しは H1 が最大で、段が深いほど大きくならず、本文より小さくならない`() {
        val sizes = headings.map { it.fontSize.value }
        sizes.zipWithNext().forEach { (upper, lower) -> assertTrue("$upper の次が $lower", upper >= lower) }
        assertTrue("H1 は本文より大きい", headings.first().fontSize.value > MarkdownBodyStyle.Paragraph.fontSize.value)
        headings.forEachIndexed { i, style ->
            assertTrue("H${i + 1} は本文より小さくならない", style.fontSize.value >= MarkdownBodyStyle.Paragraph.fontSize.value)
        }
    }

    @Test
    fun `アプリの書式は斜体を使わない`() {
        bodyStyles.forEach { (name, style) -> assertNotEquals("$name が斜体", FontStyle.Italic, style.fontStyle) }
    }

    @Test
    fun `字間はどの書式も0を明示する`() {
        // 明示しないと MaterialTheme の bodyLarge の字間（0.5sp）が重ねた書式に残る。
        bodyStyles.forEach { (name, style) -> assertEquals("$name の字間", 0.sp, style.letterSpacing) }
    }

    @Test
    fun `約物の詰めは見出しだけに掛ける`() {
        headings.forEach { assertEquals("palt", it.fontFeatureSettings) }
        listOf(MarkdownBodyStyle.Paragraph, MarkdownBodyStyle.Lead, MarkdownBodyStyle.Quote).forEach {
            assertEquals(null, it.fontFeatureSettings)
        }
    }

    @Test
    fun `見開きの計測の定数は本文の段落の書式と一致する`() {
        assertEquals(MarkdownBodyStyle.Paragraph.fontSize.value, SPREAD_BODY_FONT_SP)
        assertEquals(MarkdownBodyStyle.Paragraph.lineHeight.value, SPREAD_BODY_LINE_SP)
    }

    @Test
    fun `リード文は本文と同じ行の高さで、少し大きい`() {
        assertEquals(MarkdownBodyStyle.Paragraph.lineHeight, MarkdownBodyStyle.Lead.lineHeight)
        assertTrue(MarkdownBodyStyle.Lead.fontSize.value > MarkdownBodyStyle.Paragraph.fontSize.value)
    }

    @Test
    fun `見出しの上の高さは、線を持つ段だけ線と線の下の間を足す`() {
        val unit = Density(1f)
        (1..6).forEach { level ->
            val extra = if (MarkdownBodyStyle.hasHeadingMark(level)) {
                MarkdownBodyStyle.HeadingMarkHeight + MarkdownBodyStyle.HeadingMarkGap
            } else {
                0.dp
            }
            assertEquals((MarkdownBodyStyle.headingTopPadding(level) + extra).value.toInt(), MarkdownBodyStyle.headingSpaceAbovePx(level, unit))
        }
        assertTrue(MarkdownBodyStyle.hasHeadingMark(1))
        assertTrue(MarkdownBodyStyle.hasHeadingMark(2))
        assertFalse(MarkdownBodyStyle.hasHeadingMark(3))
    }

    /** 片側の余白が端数の画素になる密度。描画は余白を片側ずつ丸めるので、足してから丸めると1画素ずれる。 */
    private val oddDensity = Density(2.75f)

    @Test
    fun `見開きの計測の寸法は、描画と同じく部品ごとに画素へ丸めてから足す`() {
        // 9dp は 24.75px → 描画は片側 25px。足してから丸めると 49.5px で、枠の幅が描画より細くなる。
        assertEquals(64, MarkdownBodyStyle.tagChipWidthPx(14, oddDensity))
        // 密度 2.625 では 23.625px → 片側 24px。両側を足してから丸めると 47px になる。
        assertEquals(62, MarkdownBodyStyle.tagChipWidthPx(14, Density(2.625f)))
        // 14dp は 38.5px → 描画は片側 39px。
        assertEquals(1000 - 78, MarkdownBodyStyle.codeInnerWidthPx(1000, oddDensity))
        // 上下の余白 12dp（33px）×2 と、札 40px・間 10dp（28px）×2・罫 1dp（3px）。
        assertEquals(66, MarkdownBodyStyle.codeFragmentPaddingPx(null, oddDensity))
        assertEquals(66 + 40 + 56 + 3, MarkdownBodyStyle.codeFragmentPaddingPx(40, oddDensity))
        // H2 の上の余白 18dp（50px）＋線 2dp（6px）＋線の下の間 10dp（28px）。
        assertEquals(84, MarkdownBodyStyle.headingSpaceAbovePx(2, oddDensity))
        assertEquals(28, MarkdownBodyStyle.headingSpaceAbovePx(3, oddDensity))
    }

    @Test
    fun `本文の列は最大幅を超える分だけ左右に余白を取り、狭い窓では取らない`() {
        val max = MarkdownBodyStyle.ColumnMaxWidth
        assertEquals(0.dp, MarkdownBodyStyle.columnInset(360.dp))
        assertEquals(0.dp, MarkdownBodyStyle.columnInset(max))
        assertEquals(82.dp, MarkdownBodyStyle.columnInset(max + 164.dp))
        // 余白を引いた列の幅は最大幅になる。
        val wide = 900.dp
        assertEquals(max, wide - MarkdownBodyStyle.columnInset(wide) * 2)
    }

    @Test
    fun `リード文は本文の最初のブロックが段落のときだけ`() {
        val paragraph = MarkdownBlock.Paragraph("起きてから家を出るまで")
        assertTrue(isLeadParagraph(0, paragraph))
        assertFalse("2つ目の段落はリード文にしない", isLeadParagraph(1, paragraph))
        assertFalse("最初が見出しならリード文は無い", isLeadParagraph(0, MarkdownBlock.Heading(2, "うまくいった週")))
        assertFalse(isLeadParagraph(0, MarkdownBlock.CodeBlock("print(1)")))
    }
}
