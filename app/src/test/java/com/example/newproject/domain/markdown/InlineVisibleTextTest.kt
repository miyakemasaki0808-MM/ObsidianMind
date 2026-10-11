package com.example.newproject.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/** 本文に見える文字（→ [inlineVisibleText]）。描画と一致することは `InlineMarkdownTest` が見る。 */
class InlineVisibleTextTest {

    @Test
    fun `コードは中身をそのまま残す`() {
        assertEquals("left || right と a * b", inlineVisibleText("`left || right` と `a * b`"))
    }

    @Test
    fun `リンクは見えるラベルを残す`() {
        assertEquals("表示名とノート名とラベル", inlineVisibleText("[[note|表示名]]と[[ノート名]]と[ラベル](https://example.com)"))
    }

    @Test
    fun `強調は記号を落として入れ子の中身も残す`() {
        assertEquals("太字の中の斜体の後", inlineVisibleText("**太字の中の*斜体*の後**"))
    }

    @Test
    fun `エスケープは記号だけを残す`() {
        assertEquals("*星* と [括弧]", inlineVisibleText("\\*星\\* と \\[括弧\\]"))
    }

    @Test
    fun `記法でない記号はそのまま残す`() {
        assertEquals("2 * 3 * 4 と a | b", inlineVisibleText("2 * 3 * 4 と a | b"))
    }
}
