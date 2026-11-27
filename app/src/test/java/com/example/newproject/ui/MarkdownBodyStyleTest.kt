package com.example.newproject.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
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
        "Title" to MarkdownBodyStyle.Title,
        "Paragraph" to MarkdownBodyStyle.Paragraph,
        "Lead" to MarkdownBodyStyle.Lead,
        "Quote" to MarkdownBodyStyle.Quote,
        "CalloutLabelText" to MarkdownBodyStyle.CalloutLabelText,
        "CalloutTitleText" to MarkdownBodyStyle.CalloutTitleText,
        "TableCell" to MarkdownBodyStyle.TableCell,
        "TableHeader" to MarkdownBodyStyle.TableHeader,
        "Code" to MarkdownBodyStyle.Code
    ) + headings.mapIndexed { i, style -> "heading(${i + 1})" to style }

    @Test
    fun `題は見出しより大きく、見出しは段が深いほど大きくならず、本文より小さくならない`() {
        val sizes = listOf(MarkdownBodyStyle.Title.fontSize.value) + headings.map { it.fontSize.value }
        sizes.zipWithNext().forEach { (upper, lower) -> assertTrue("$upper の次が $lower", upper >= lower) }
        assertTrue("題は H1 より大きい", MarkdownBodyStyle.Title.fontSize.value > headings.first().fontSize.value)
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
    fun `約物の詰めは題と見出しだけに掛ける`() {
        assertEquals("palt", MarkdownBodyStyle.Title.fontFeatureSettings)
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
        (1..6).forEach { level ->
            val extra = if (MarkdownBodyStyle.hasHeadingMark(level)) {
                MarkdownBodyStyle.HeadingMarkHeight + MarkdownBodyStyle.HeadingMarkGap
            } else {
                0.dp
            }
            assertEquals(MarkdownBodyStyle.headingTopPadding(level) + extra, MarkdownBodyStyle.headingSpaceAbove(level))
        }
        assertTrue(MarkdownBodyStyle.hasHeadingMark(1))
        assertTrue(MarkdownBodyStyle.hasHeadingMark(2))
        assertFalse(MarkdownBodyStyle.hasHeadingMark(3))
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
