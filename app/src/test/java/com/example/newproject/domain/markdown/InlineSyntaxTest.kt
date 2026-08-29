package com.example.newproject.domain.markdown

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * インライン記法の唯一の解釈器を固定する。
 *
 * **ここが表示と蒸留の両方の答えになる。** 片方だけの都合で規則を変えると、
 * 表示上の装飾の内側へ蒸留が候補境界を置ける状態が戻る（→ lessons L51）。
 */
class InlineSyntaxTest {

    /** 入れ子を含めた全範囲。`spans` は最上位だけなので、判定には [InlineSyntaxScan.flatten] を使う。 */
    private fun spans(text: String) =
        scanInlineSyntax(text).flatten().map { "${it.kind}:${text.substring(it.start, it.endExclusive)}" }

    @Test
    fun `種別ごとに対を取る`() {
        assertEquals(listOf("Italic:*斜体*"), spans("これは*斜体*です"))
        assertEquals(listOf("Bold:**太字**"), spans("これは**太字**です"))
        assertEquals(listOf("BoldItalic:***両方***"), spans("これは***両方***です"))
        assertEquals(listOf("Strikethrough:~~取消~~"), spans("これは~~取消~~です"))
    }

    @Test
    fun `エスケープした記号は対を開かない`() {
        // 表示側もここを見る。片方だけがエスケープを解釈すると、
        // 表示上は1つの斜体なのに蒸留だけが2文に割る状態になる。
        assertEquals(emptyList<String>(), spans("記号は \\*A。B\\* と書く。"))
        assertEquals(listOf("Italic:*本物*"), spans("\\*偽物\\* と *本物*"))
    }

    @Test
    fun `コードは開いた数と同じバッククォートで閉じる`() {
        assertEquals(listOf("Code:``a*。B``"), spans("記法は ``a*。B``。"))
        assertEquals(listOf("Code:`a*b`"), spans("記法は `a*b` です"))
    }

    @Test
    fun `長さの違う連なりは閉じにしない`() {
        // **開いた連なりより長い連なりの一部を閉じに使わない**（CommonMark と同じ）。
        // 部分一致を許すと、閉じていない連なりが「閉じている」ことになって範囲が変わる。
        assertEquals(emptyList<String>(), spans("記法は ``a```b です"))
        // 内側に短い連なりがあっても、同じ長さの連なりで閉じる。
        assertEquals(listOf("Code:`a``b`"), spans("記法は `a``b` です"))
    }

    @Test
    fun `リンクは構文全体を消費し、内側の記号を装飾に使わない`() {
        assertEquals(listOf("Link:[a*b](url)"), spans("参照 [a*b](url)。"))
        assertEquals(listOf("Link:[label](a*b)"), spans("参照 [label](a*b)。"))
        assertEquals(listOf("WikiLink:[[a*b]]"), spans("参照 [[a*b]]。"))
        assertEquals(listOf("WikiLink:[[note|a*b]]"), spans("参照 [[note|a*b]]。"))
    }

    @Test
    fun `対の探索はリンクとコードを飛ばす`() {
        // 飛ばさないと、リンク内の `*` が閉じに使われて範囲が途中で切れる。
        assertEquals(
            listOf("Italic:*強調 [a*b](url) 続き*", "Link:[a*b](url)"),
            spans("*強調 [a*b](url) 続き*")
        )
    }

    @Test
    fun `空白で挟まれた記号は対にしない`() {
        assertEquals(emptyList<String>(), spans("計算は 2 * 3 * 4 である"))
    }

    @Test
    fun `閉じていない太字の後ろの記号は斜体として読む`() {
        // **表示側がそう描くから、保護側もそう読む。** 以前は保護側だけが `**` を2文字
        // 読み飛ばしており、表示上の斜体の内側へ候補境界を置けた。
        assertEquals(listOf("Italic:*未閉じの強調。次の文には*"), spans("これは**未閉じの強調。次の文には*斜体*がある。"))
    }

    @Test
    fun `閉じない記号は対にしない`() {
        assertEquals(emptyList<String>(), spans("未閉じの*記号だけ"))
        assertEquals(emptyList<String>(), spans("配列は arr[0] を使う"))
    }
}

