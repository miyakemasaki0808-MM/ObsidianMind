package com.example.newproject

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.example.newproject.domain.markdown.InlineSpanKind
import com.example.newproject.domain.markdown.scanInlineSyntax
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

    /** 描かれたSpanStyleから記法の種別を読み取る。表示側が何を装飾したかを外から数えるため。 */
    private fun renderedKinds(input: String): List<String> =
        inlineMarkdown(input).spanStyles.map { style ->
            val item = style.item
            when {
                item.fontWeight == FontWeight.Bold && item.fontStyle == FontStyle.Italic -> "BoldItalic"
                item.fontWeight == FontWeight.Bold -> "Bold"
                item.fontStyle == FontStyle.Italic -> "Italic"
                item.textDecoration == TextDecoration.LineThrough -> "Strikethrough"
                item.fontFamily == FontFamily.Monospace -> "Code"
                item.textDecoration == TextDecoration.Underline -> "Link"
                else -> "?"
            }
        }

    /**
     * **表示と蒸留が同じ解釈器を使っていることを、入力表で確かめる。**
     *
     * 記法の一覧を揃えるだけでは足りない。バッククォートの数え方とエスケープの扱いが
     * 食い違っていたために、表示上の装飾の内側へ蒸留が候補境界を置けた（→ lessons L51）。
     * ここが落ちるときは、表示側が [scanInlineSyntax] の答えを使わなくなっている。
     */
    @Test
    fun `装飾する範囲は共有トークナイザーの答えと一致する`() {
        listOf(
            "これは*斜体*と**太字**と***両方***です",
            "これは~~取消~~と`コード`です",
            "記法は ``a*。B``。後に*文字*。",
            "記号は \\*A。B\\* と書く。",
            "参照 [a*b](url) と [[note|表示名]] と [[a*b]]。",
            "計算は 2 * 3 * 4 である",
            "これは**未閉じの強調。次の文には*斜体*がある。",
            "*強調 [a*b](url) 続き*",
            "**A *B* `c` [d](u) E**",
            "***斜体*。**"
        ).forEach { input ->
            // wikilink と通常リンクは表示属性が同じなので、種別としては同一に畳む。
            val expected = scanInlineSyntax(input).flatten().map {
                if (it.kind == InlineSpanKind.WikiLink) "Link" else it.kind.name
            }
            assertEquals(input, expected, renderedKinds(input))
        }
    }

    @Test
    fun `入れ子の装飾は外側を足しても残る`() {
        // **蒸留が文を太字にしても、ユーザーが書いた斜体・コード・リンクは表示から消えない。**
        assertEquals(listOf("Bold", "Italic", "Code", "Link"), renderedKinds("**A *B* `c` [d](u) E**"))
        assertEquals("A B c d E", plainText("**A *B* `c` [d](u) E**"))
    }

    @Test
    fun `長い記号で閉じられないときは短い記号で開き直す`() {
        // `*斜体*。` を保存した `***斜体*。**` に、余分な `*` が本文として出ないこと。
        assertEquals("斜体。", plainText("***斜体*。**"))
        assertEquals(listOf("Bold", "Italic"), renderedKinds("***斜体*。**"))
    }

    @Test
    fun `エスケープした記号は装飾せず、記号だけを出す`() {
        // Obsidian と同じ見え方にする。以前は `\*` を斜体の開始として読み、
        // バックスラッシュもそのまま描いていた。
        assertEquals("*強調*ではない", plainText("\\*強調\\*ではない"))
        assertTrue(!hasItalic("\\*強調\\*ではない"))
    }

    @Test
    fun `コードは開いた数と同じバッククォートで閉じる`() {
        assertEquals("a*。B", plainText("``a*。B``"))
    }

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
