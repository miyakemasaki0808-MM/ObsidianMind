package com.example.newproject.architecture

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * コメントの形の検査が、**実際のコメントだけ**を数えることを入力の差し替えで固定する。
 *
 * 違反の入力だけでは足りない。同じ記号を文字列の中に持つ正しい入力を対照に置かないと、
 * 「何でも落とす」検査が通ってしまう。
 */
class KotlinCommentScannerTest {

    @Test
    fun `文字列の中の行コメント記号と日付は数えない`() {
        assertNoFindings("val text = \"// 2026-09-14\"")
    }

    @Test
    fun `文字列の中のブロックコメント記号と日付は数えない`() {
        assertNoFindings("val text = \"/* 2026-09-14 */\"")
    }

    @Test
    fun `raw string の中の行コメント記号と日付は数えない`() {
        assertNoFindings("val text = \"\"\"// 2026-09-14\"\"\"")
    }

    @Test
    fun `文字列の中に並んだKDoc記号は連続KDocにしない`() {
        assertNoFindings("val text = \"/** first */ /** second */\"")
    }

    @Test
    fun `日付だけの文字列は数えない`() {
        assertNoFindings("val date = \"2026-09-14\"")
    }

    @Test
    fun `コメントの中で引用した書式の例は数えない`() {
        assertNoFindings("// 「最終検証: 2026-08-12」の行を読む\nval x = 1")
    }

    @Test
    fun `日付の入った実コメントは行番号つきで数える`() {
        val source = "val x = 1\n// 2026-09-14 に直した\nval y = 2"

        assertEquals(listOf(2 to "// 2026-09-14 に直した"), KotlinCommentScanner.historyMarkLines(source))
    }

    @Test
    fun `レビューの文脈と並んだ枝番の無い指摘番号は数える`() {
        listOf(
            "// （前回P1と同じ壊れ方）",
            "// 今回のP3で見つかった",
            "// レビューのP2で直した",
            "/** 指摘 P0 への対応 */",
            "// P1指摘の対応",
            "// P2 の指摘を受けた",
            "// 前回P1-1と同じ"
        ).forEach { comment ->
            assertEquals(comment, 1, KotlinCommentScanner.historyMarkLines("$comment\nval x = 1").size)
        }
    }

    @Test
    fun `文脈の無い枝番の無い番号は数えない`() {
        listOf(
            "// 始点P1と終点P2を結ぶ",
            "// P1 は左上の角",
            "// 前回の位置 P1 を覚えておく",
            "// AP1 と P10 は別の名前",
            "// 指摘の要点をP4へ移す",
            "// 「前回P1」の行を読む"
        ).forEach { comment ->
            assertEquals(comment, emptyList<Pair<Int, String>>(), KotlinCommentScanner.historyMarkLines("$comment\nval x = 1"))
        }
        assertNoFindings("val text = \"前回P1と同じ\"")
    }

    @Test
    fun `実際に続けて置いたKDocは前の1つを数える`() {
        val source = "/** first */\n/** second */\nval x = 1"

        assertEquals(listOf(0), KotlinCommentScanner.detachedKDocOffsets(source))
    }

    @Test
    fun `宣言を挟んだKDocは連続にしない`() {
        assertNoFindings("/** first */\nval x = 1\n/** second */\nval y = 2")
    }

    @Test
    fun `空のブロックコメントはKDocにしない`() {
        assertNoFindings("/**/\n/** second */\nval x = 1")
    }

    @Test
    fun `テンプレートの中の文字列を抜けた後のコメントは数える`() {
        val source = "val t = \"\${\"/*\"} x\" // P2-1"

        assertEquals(listOf("// P2-1"), KotlinCommentScanner.comments(source).map { it.text })
    }

    @Test
    fun `文字リテラルの引用符で文字列を開かない`() {
        val source = "val c = '\"' // 2026-09-14"

        assertEquals(1, KotlinCommentScanner.historyMarkLines(source).size)
    }

    @Test
    fun `エスケープした引用符で文字列を閉じない`() {
        assertNoFindings("val s = \"\\\" // 2026-09-14\"")
    }

    @Test
    fun `raw string の閉じに続く余分な引用符は中身として読む`() {
        val source = "val r = \"\"\"a\"\"\"\" // P1-2"

        assertEquals(listOf("// P1-2"), KotlinCommentScanner.comments(source).map { it.text })
    }

    @Test
    fun `入れ子のブロックコメントは外側の閉じまでを1つにする`() {
        val source = "/* a /* b */ 2026-09-14 */ val x = 1"

        assertEquals(listOf("/* a /* b */ 2026-09-14 */"), KotlinCommentScanner.comments(source).map { it.text })
    }

    private fun assertNoFindings(source: String) {
        assertEquals("経緯の目印", emptyList<Pair<Int, String>>(), KotlinCommentScanner.historyMarkLines(source))
        assertEquals("連続KDoc", emptyList<Int>(), KotlinCommentScanner.detachedKDocOffsets(source))
    }
}
