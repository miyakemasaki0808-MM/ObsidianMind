package com.example.newproject

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.example.newproject.ui.markdown.inlineMarkdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InlineMarkdownTest {

    private fun plainText(input: String) = inlineMarkdown(input).text

    private fun hasItalic(input: String) =
        inlineMarkdown(input).spanStyles.any { it.item.fontStyle == FontStyle.Italic }

    private fun hasBold(input: String) =
        inlineMarkdown(input).spanStyles.any { it.item.fontWeight == FontWeight.Bold }

    // ── 正常系 ───────────────────────────────────────────────────────────

    @Test
    fun `イタリック・太字・太字イタリックが装飾される`() {
        assertEquals("強調", plainText("*強調*"))
        assertTrue(hasItalic("*強調*"))
        assertEquals("太字", plainText("**太字**"))
        assertTrue(hasBold("**太字**"))
        assertEquals("両方", plainText("***両方***"))
        assertTrue(hasBold("***両方***") && hasItalic("***両方***"))
    }

    @Test
    fun `単語内の強調は有効`() {
        assertEquals("abc", plainText("a*b*c"))
        assertTrue(hasItalic("a*b*c"))
    }

    @Test
    fun `Obsidianリンクは表示名だけ残る`() {
        assertEquals("表示名", plainText("[[ノート名|表示名]]"))
        assertEquals("ノート名", plainText("[[ノート名]]"))
    }

    @Test
    fun `通常リンクはラベルだけ残る`() {
        assertEquals("参考資料 を見る", plainText("[参考資料](https://example.com) を見る"))
    }

    // ── M7回帰: 単独 * の誤ペアリング ────────────────────────────────────

    @Test
    fun `スペース区切りの単独アスタリスクは強調にならない`() {
        val input = "3 * 4 = 12 と 5 * 6 = 30"
        assertEquals(input, plainText(input))
        assertTrue(!hasItalic(input))
    }

    @Test
    fun `閉じの無いアスタリスクはそのまま残る`() {
        assertEquals("*未完", plainText("*未完"))
        assertTrue(!hasItalic("*未完"))
    }

    @Test
    fun `空の強調記号は装飾されない`() {
        // ** は「* を2つ」と解釈されても中身が空なので文字として残る
        assertEquals("**", plainText("**"))
    }

    // ── M7回帰: 角括弧の誤検知 ───────────────────────────────────────────

    @Test
    fun `配列表記の角括弧はリンクにならない`() {
        val input = "配列 arr[0] を参照する"
        assertEquals(input, plainText(input))
    }

    @Test
    fun `配列表記と本物のリンクが混在しても本文を巻き込まない`() {
        // 以前は arr[0] の [ が後方の ]( とペアリングし、間の本文が消えていた
        val input = "arr[0] と [リンク](https://example.com) を併記"
        assertEquals("arr[0] と リンク を併記", plainText(input))
    }

    @Test
    fun `閉じ括弧の無い角括弧はそのまま残る`() {
        assertEquals("[未完", plainText("[未完"))
        assertEquals("[ラベル](URLなし", plainText("[ラベル](URLなし"))
    }

    // ── その他の装飾 ─────────────────────────────────────────────────────

    @Test
    fun `インラインコードと打ち消し線が処理される`() {
        assertEquals("code", plainText("`code`"))
        assertEquals("済み", plainText("~~済み~~"))
    }
}
