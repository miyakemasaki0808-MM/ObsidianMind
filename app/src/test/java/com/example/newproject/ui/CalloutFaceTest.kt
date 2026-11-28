package com.example.newproject.ui

import androidx.compose.ui.unit.Density
import com.example.newproject.domain.markdown.Callout
import com.example.newproject.domain.markdown.CalloutRole
import com.example.newproject.domain.markdown.parseMarkdownBlocks
import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.ui.markdown.CalloutFace
import com.example.newproject.ui.markdown.calloutFaceOf
import com.example.newproject.ui.markdown.calloutLabelOf
import com.example.newproject.ui.markdown.calloutLabelPlaceholders
import com.example.newproject.ui.markdown.calloutLabelText
import com.example.newproject.ui.markdown.quoteInnerWidthPx
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** コールアウトの面（→ `docs/dev/features/note_typesetting.md` §5.4）。 */
class CalloutFaceTest {

    private fun calloutOf(markdown: String): Callout =
        (parseMarkdownBlocks(markdown).single() as MarkdownBlock.Blockquote).content.callout!!

    @Test
    fun `役割ごとに面を引き、引用の役割とコールアウトでない引用は面を敷かない`() {
        assertEquals(CalloutFace.Summary, calloutFaceOf(calloutOf("> [!note] 題")))
        assertEquals(CalloutFace.Question, calloutFaceOf(calloutOf("> [!question] 題")))
        assertEquals(CalloutFace.Example, calloutFaceOf(calloutOf("> [!tip] 題")))
        assertEquals(CalloutFace.Caution, calloutFaceOf(calloutOf("> [!warning] 題")))
        assertEquals("知らない種類はまとめの面", CalloutFace.Summary, calloutFaceOf(calloutOf("> [!独自] 題")))
        assertNull(calloutFaceOf(calloutOf("> [!quote] 題")))
        assertNull(calloutFaceOf(null))
    }

    @Test
    fun `面を持つ種類は記号の位置に線画を置き、持たない種類は記号の文字のまま`() {
        val note = calloutOf("> [!note] 題")
        val label = calloutLabelOf(note)
        val text = calloutLabelText(label, CalloutFace.Summary)
        val placeholder = calloutLabelPlaceholders(label, CalloutFace.Summary).single()
        assertEquals(0, placeholder.start)
        assertEquals(label.mark.length, placeholder.end)
        assertTrue("言葉は記号の後ろに続く", text.text.endsWith(" ${label.text}"))
        val quote = calloutLabelOf(calloutOf("> [!quote] 題"))
        assertEquals("${quote.mark} ${quote.text}", calloutLabelText(quote, null).text)
        assertTrue(calloutLabelPlaceholders(quote, null).isEmpty())
    }

    @Test
    fun `面の内側の幅は左右の余白を片側ずつ丸めて引き、縦線の引用とは別に数える`() {
        // 14dp は密度 2.25 で片側 31.5px。片側ずつ丸めて 32px ずつ引く。
        assertEquals(1000 - 64, quoteInnerWidthPx(1000, Density(2.25f), CalloutFace.Summary))
        // 面を敷かない引用は字下げ（28dp）を丸める。
        assertEquals(1000 - 63, quoteInnerWidthPx(1000, Density(2.25f), null))
        assertEquals(CalloutRole.Summary, calloutOf("> [!note] 題").role)
    }
}
