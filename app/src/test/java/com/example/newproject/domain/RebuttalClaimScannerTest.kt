package com.example.newproject.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 反証の一文の候補列挙（→ features/rebuttal_sentence.md §5）。
 *
 * **形だけを見る段である。** 反証かどうかはAIが選ぶので、ここでは問い・ラベル・見出し・コードを
 * 落とせているかと、原文をそのまま返しているかだけを固定する。
 */
class RebuttalClaimScannerTest {

    @Test
    fun `frontmatter は候補にしない`() {
        val content = """
            ---
            summary: 前置きの欄に書いた主張めいた一文である。
            ---
            本文に書いた主張の一文がここにある。
        """.trimIndent()

        assertEquals(listOf("本文に書いた主張の一文がここにある。"), scanRebuttalClaims(content))
    }

    @Test
    fun `コードフェンスの中は候補にしない`() {
        val content = """
            ```kotlin
            // コードの中に書いた説明文の一文である。
            ```
            フェンスの外に書いた本文の一文である。
        """.trimIndent()

        assertEquals(listOf("フェンスの外に書いた本文の一文である。"), scanRebuttalClaims(content))
    }

    /** 見出しは「何について書いたか」であって主張ではない。句点で終わる見出しも落とす。 */
    @Test
    fun `見出しは候補にしない`() {
        val content = """
            # 見出しに書いてしまった主張の一文である。
            見出しの下に書いた本文の一文である。
        """.trimIndent()

        assertEquals(listOf("見出しの下に書いた本文の一文である。"), scanRebuttalClaims(content))
    }

    @Test
    fun `表の区切りとリンクだけの行は候補にしない`() {
        val content = """
            |---|:--|
            [[別のノートの長いタイトルの名前である。]]
            [リンクのラベルに書いた一文である。](https://example.com)
            リンクの無い行に書いた本文の一文である。
        """.trimIndent()

        assertEquals(listOf("リンクの無い行に書いた本文の一文である。"), scanRebuttalClaims(content))
    }

    /** 枠には原文をそのまま出すので、中身ごと落とすと主語が消えた文になる。 */
    @Test
    fun `インラインコードは記法だけ落として中身を残す`() {
        assertEquals(
            listOf("NoteViewModel は分割しないほうがよい。"),
            scanRebuttalClaims("`NoteViewModel` は分割しないほうがよい。")
        )
    }

    @Test
    fun `括弧の内側の終止符では切らない`() {
        assertEquals(
            listOf("彼は「それは違う。」と言って席を立った。"),
            scanRebuttalClaims("彼は「それは違う。」と言って席を立った。")
        )
    }

    @Test
    fun `長さの上下限を外れた文は候補にしない`() {
        val tooShort = "短い文である。"
        val atMin = "a".repeat(REBUTTAL_CLAIM_MIN_CHARS - 1) + "。"
        val atMax = "b".repeat(REBUTTAL_CLAIM_MAX_CHARS - 1) + "。"
        val tooLong = "c".repeat(REBUTTAL_CLAIM_MAX_CHARS) + "。"

        assertEquals(
            listOf(atMin, atMax),
            scanRebuttalClaims(listOf(tooShort, atMin, atMax, tooLong).joinToString("\n"))
        )
    }

    /** ラベルの行（「最終検証: 2026-08-12」）は文の形をしていない。 */
    @Test
    fun `終止符で終わらない行は候補にしない`() {
        val content = """
            最終検証: 2026-08-12 と関連のテスト

            This sentence is an English claim.
        """.trimIndent()

        assertEquals(listOf("This sentence is an English claim."), scanRebuttalClaims(content))
    }

    /** 問いは主張ではない。再会カードが問いとして拾う形を、ここでは全部捨てる。 */
    @Test
    fun `疑問文は候補にしない`() {
        listOf(
            "この設計で本当に必要なものを満たせるだろうか。",
            "この方式は本当に速いのだろうか？",
            "先に測ってから決めるほうがよいかな。"
        ).forEach { line ->
            assertTrue(line, scanRebuttalClaims(line).isEmpty())
        }
    }

    /** 「か」で終わらない断定は残る。過去形の「〜かった。」が最も紛らわしい。 */
    @Test
    fun `かで終わらない断定文は候補に残す`() {
        assertEquals(
            listOf("この方式のほうが明らかに速かった。"),
            scanRebuttalClaims("この方式のほうが明らかに速かった。")
        )
    }

    @Test
    fun `同じ文は1つに畳み原文順を保つ`() {
        val content = """
            最初に書いた主張の一文である。
            二番目に書いた主張の一文である。
            最初に書いた主張の一文である。
        """.trimIndent()

        assertEquals(
            listOf("最初に書いた主張の一文である。", "二番目に書いた主張の一文である。"),
            scanRebuttalClaims(content)
        )
    }

    @Test
    fun `1行に複数の文があれば文ごとに割る`() {
        assertEquals(
            listOf("一つめの主張をここに書いた。", "二つめの主張もここに書いた。"),
            scanRebuttalClaims("- 一つめの主張をここに書いた。二つめの主張もここに書いた。")
        )
    }

    // --- 文の単位（本文の画面と同じ段落） ------------------------------------

    /**
     * 段落の中で折り返した1文を、行ごとに割らない。割ると条件節を落とした後半だけが「本文にある一文」として出る。
     * つなぎ方は本文の画面と同じ（行を空白でつなぐ）。
     */
    @Test
    fun `段落の中で折り返した1文は条件節ごと1つの候補にする`() {
        val content = """
            十分な睡眠を取れていない場合に限り、
            朝の光だけでは生活リズムを整えられない。
        """.trimIndent()

        assertEquals(
            listOf("十分な睡眠を取れていない場合に限り、 朝の光だけでは生活リズムを整えられない。"),
            scanRebuttalClaims(content)
        )
    }

    @Test
    fun `別の段落・見出し・リスト項目はつながない`() {
        val content = """
            句点で終わらない段落の書きかけ

            次の段落の主張はここで終わる。
            ## 見出しの行
            - 箇条書きの項目は独立した主張である。
            続けて書いた段落の文もここで終わる。
        """.trimIndent()

        assertEquals(
            listOf("次の段落の主張はここで終わる。", "箇条書きの項目は独立した主張である。", "続けて書いた段落の文もここで終わる。"),
            scanRebuttalClaims(content)
        )
    }

    /** フェンスを詰めて落とすと、前後の段落が1つにつながる。 */
    @Test
    fun `コードフェンスを挟んだ前後の段落はつながない`() {
        val content = """
            前の段落の書きかけで終わる
            ```
            val x = 1
            ```
            後ろの段落の主張はここで終わる。
        """.trimIndent()

        assertEquals(listOf("後ろの段落の主張はここで終わる。"), scanRebuttalClaims(content))
    }

    @Test
    fun `引用は空行の区切りまでを1つの段落としてつなぐ`() {
        val content = """
            > 引用の中で折り返した
            > 一文はここで終わる。
            >
            > 次の引用の段落もここで終わる。
        """.trimIndent()

        assertEquals(
            listOf("引用の中で折り返した 一文はここで終わる。", "次の引用の段落もここで終わる。"),
            scanRebuttalClaims(content)
        )
    }

    /** 表は本文の画面でもセルごとに描く。列の名前は主張ではない。 */
    @Test
    fun `表は本文の行のセルを1つずつ候補にし、列の名前は候補にしない`() {
        val content = """
            | 列の名前を書いた見出しである。 | 理由 |
            |---|---|
            | 睡眠 | 朝の光で生活リズムが整うと書いた。 |
        """.trimIndent()

        assertEquals(listOf("朝の光で生活リズムが整うと書いた。"), scanRebuttalClaims(content))
    }

    // --- 本文に見える文字 ---------------------------------------------------

    /** コードの中の `|` や `*` は記法ではない。消すと演算子が欠けた別の主張になる。 */
    @Test
    fun `インラインコードの中の記号を残す`() {
        assertEquals(
            listOf("left || right はどちらかの条件が真なら成立する。"),
            scanRebuttalClaims("`left || right` はどちらかの条件が真なら成立する。")
        )
        assertEquals(
            listOf("a * b * c は掛ける順を入れ替えても同じになる。"),
            scanRebuttalClaims("`a * b * c` は掛ける順を入れ替えても同じになる。")
        )
    }

    /** 本文の画面は `[[note|表示名]]` の表示名を描く。候補だけリンク先の名前にすると主語が変わる。 */
    @Test
    fun `Wikiリンクは本文と同じく表示名を残す`() {
        assertEquals(
            listOf("対面勤務は常に生産性を高める。"),
            scanRebuttalClaims("[[リモートワーク|対面勤務]]は常に生産性を高める。")
        )
        assertEquals(
            listOf("リモートワークは常に生産性を高める。"),
            scanRebuttalClaims("[[リモートワーク]]は常に生産性を高める。")
        )
        assertEquals(
            listOf("光療法は睡眠の乱れには効かない。"),
            scanRebuttalClaims("[光療法](https://example.com)は睡眠の乱れには効かない。")
        )
    }

    /** 強調の記号は落とし、強調でない `*` は本文と同じく残す。 */
    @Test
    fun `強調の記号は落とし強調でない記号は残す`() {
        assertEquals(
            listOf("睡眠は朝の光で整うと考えている。", "2 * 3 * 4 を先に計算すると24になる。"),
            scanRebuttalClaims("**睡眠**は朝の光で*整う*と考えている。\n\n2 * 3 * 4 を先に計算すると24になる。")
        )
    }

    // --- 英文の文境界 ---------------------------------------------------------

    @Test
    fun `英文はピリオドで文ごとに割る`() {
        assertEquals(
            listOf("Remote work always improves productivity.", "Office work always improves productivity."),
            scanRebuttalClaims("Remote work always improves productivity. Office work always improves productivity.")
        )
    }

    /** 割らないと、各文が上限内でも合計が上限を超えた段落は丸ごと消える。 */
    @Test
    fun `段落全体が上限を超えても上限内の文は残す`() {
        val first = "Remote work always improves the productivity of every team that tries it for a month."
        val second = "Office work always improves the productivity of every team that returns to it for good."
        assertTrue((first.length + 1 + second.length) > REBUTTAL_CLAIM_MAX_CHARS)

        assertEquals(listOf(first, second), scanRebuttalClaims("$first $second"))
    }

    /** 小数・版番号・略語・頭文字は文の終わりではない。 */
    @Test
    fun `小数や略語のピリオドでは割らない`() {
        listOf(
            "The rate rose to 3.5 percent after the change in v1.2 of the app.",
            "Dr. Smith said that sleep matters more than light, e.g. in winter.",
            "The approx. figure came from J. K. Rowling and her team et al. in print."
        ).forEach { line ->
            assertEquals(line, listOf(line), scanRebuttalClaims(line))
        }
    }

    @Test
    fun `英文の後の日本語の文も割る`() {
        assertEquals(
            listOf("This is an English claim about sleep.", "次の文は日本語で書いた主張である。"),
            scanRebuttalClaims("This is an English claim about sleep. 次の文は日本語で書いた主張である。")
        )
    }
}
