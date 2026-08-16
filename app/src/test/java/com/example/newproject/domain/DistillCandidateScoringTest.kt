package com.example.newproject.domain

import com.example.newproject.model.DistillLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DistillCandidateScoringTest {

    @Test
    fun `candidate ids are fixed three digit values`() {
        assertEquals("S001", distillCandidateId(0))
        assertEquals("S024", distillCandidateId(23))
    }

    @Test
    fun `each chunk contributes a candidate while slots remain`() {
        val content = """
            # Alpha
            Alpha topic first sentence. Ordinary detail follows.
            # Beta
            Beta topic first sentence. Another detail follows.
            # Gamma
            Gamma topic first sentence. Final conclusion follows.
        """.trimIndent()
        val model = buildDistillSourceModel(content)

        val selected = selectDistillCandidates(model, "Alpha Beta Gamma", limit = 3)

        assertEquals(3, selected.size)
        assertEquals(3, selected.map { it.sentence.chunkIndex }.distinct().size)
    }

    @Test
    fun `unique final conclusion survives first stage`() {
        val repeated = (1..20).joinToString(" ") { "General explanation repeats." }
        val content = "$repeated Unique final conclusion."
        val model = buildDistillSourceModel(content)

        val selected = selectDistillCandidates(model, "Unrelated", limit = 3)

        assertTrue(selected.any { it.sentence.text == "Unique final conclusion." })
    }

    @Test
    fun `sentences longer than input contract are excluded`() {
        val longSentence = "x".repeat(DistillLimits.MAX_SENTENCE_CHARACTERS + 1) + "。"
        val model = buildDistillSourceModel("短い文です。$longSentence")

        val selected = selectDistillCandidates(model, "短い")

        assertTrue(selected.none { it.sentence.text == longSentence })
    }

    @Test
    fun `sentences made only of links are excluded`() {
        val content = """
            # 本文
            この節の結論をここに書いています。
            # リンク
            - [[リンク情報]]
            - [[A]] と [[B]]
        """.trimIndent()
        val model = buildDistillSourceModel(content)

        val selected = selectDistillCandidates(model, "本文")

        assertTrue(selected.none { it.sentence.text.contains("リンク情報") })
        assertTrue(selected.none { it.sentence.text.contains("[[A]]") })
    }

    @Test
    fun `link runs are excluded while short claims survive`() {
        // 実機レビュー 2026-08-16 P2-1。区切り記号の数で判定が反転していた組み合わせ。
        val content = """
            # 本文
            [[A]]、[[B]]、[[C]]、[[D]]。
            [[A]]は核。
        """.trimIndent()
        val model = buildDistillSourceModel(content)

        val selected = selectDistillCandidates(model, "本文")

        assertEquals(listOf("[[A]]は核。"), selected.map { it.sentence.text })
    }

    @Test
    fun `words containing connective characters survive next to a link`() {
        // 修正確認レビュー 2026-08-16 P2-1。接続語をリンク間へ限定しないと `のもの` が空になる。
        val content = """
            # 本文
            [[A]]、[[B]]。
            [[A]]のもの。
        """.trimIndent()
        val model = buildDistillSourceModel(content)

        val selected = selectDistillCandidates(model, "本文")

        assertEquals(listOf("[[A]]のもの。"), selected.map { it.sentence.text })
    }

    @Test
    fun `chunk with only links contributes no representative`() {
        val content = """
            # 本文
            この節の結論をここに書いています。
            # リンク
            - [[リンク情報]]
        """.trimIndent()
        val model = buildDistillSourceModel(content)
        val linkChunk = model.sentences.first { it.text.contains("リンク情報") }.chunkIndex

        val selected = selectDistillCandidates(model, "本文")

        assertTrue(selected.isNotEmpty())
        assertTrue(selected.none { it.sentence.chunkIndex == linkChunk })
    }

    @Test
    fun `links inside an ordinary sentence stay eligible`() {
        val content = """
            詳細は [[リンク情報]] を参照してください。
            [[設計思想]]は重要。
            [[Vigilith]]を採用する。
        """.trimIndent()
        val model = buildDistillSourceModel(content)

        val selected = selectDistillCandidates(model, "設計")

        assertEquals(3, selected.size)
    }

    /** 読点を1つ含み、全体で [total] 字ちょうどの文を作る。 */
    private fun sentenceWithComma(total: Int): String {
        val head = (total - 2) / 2
        val tail = total - 2 - head
        return "あ".repeat(head) + "、" + "い".repeat(tail) + "。"
    }

    @Test
    fun `oversized parent sentences stay excluded even when they split`() {
        // 実機レビュー 2026-08-16 P2-1。上限を句長で見ると、読点のある超過文だけが分割後に復活する。
        val content = sentenceWithComma(DistillLimits.MAX_SENTENCE_CHARACTERS + 1)
        val model = buildDistillSourceModel(content)

        val selected = selectDistillCandidates(model, "あ")

        assertTrue(model.sentences.size > 1)
        assertTrue(model.sentences.all { it.text.length <= DistillLimits.MAX_SENTENCE_CHARACTERS })
        assertTrue(selected.isEmpty())
    }

    @Test
    fun `parent sentences at the length limit still yield clauses`() {
        val content = sentenceWithComma(DistillLimits.MAX_SENTENCE_CHARACTERS)
        val model = buildDistillSourceModel(content)

        val selected = selectDistillCandidates(model, "あ")

        assertEquals(DistillLimits.MAX_SENTENCE_CHARACTERS, content.length)
        assertTrue(selected.isNotEmpty())
    }

    @Test
    fun `candidates never overlap each other`() {
        // 書き戻しは重なりを require で拒む。重なる候補を同時に選べる状態をUIへ出してはいけない。
        val content = """
            # 本文
            「オンデバイスAI」は端末内で動く仕組みです。
            結論として「プログレッシブ要約」を採用します。
        """.trimIndent()
        val model = buildDistillSourceModel(content)

        val selected = selectDistillCandidates(model, "本文")

        selected.forEachIndexed { i, left ->
            selected.drop(i + 1).forEach { right ->
                assertTrue(
                    "候補が重なっている: ${left.sentence.text} / ${right.sentence.text}",
                    !left.sentence.range.overlaps(right.sentence.range)
                )
            }
        }
    }

    @Test
    fun `bracketed terms become candidates instead of their container`() {
        val content = "「オンデバイスAI」は端末内で動く仕組みです。"
        val model = buildDistillSourceModel(content)

        val selected = selectDistillCandidates(model, "AI")

        // 重なったら細かいほうを残す。粒度を細かくするのが語句候補の目的。
        assertEquals(listOf("オンデバイスAI"), selected.map { it.sentence.text })
    }

    @Test
    fun `repeated terms are collapsed to one candidate`() {
        val content = (1..5).joinToString("\n") { "「共通語」について述べた${it}番目の本文です。" }
        val model = buildDistillSourceModel(content)

        val selected = selectDistillCandidates(model, "共通語")

        assertEquals(1, selected.count { it.sentence.text == "共通語" })
    }

    @Test
    fun `terms cannot fill every candidate slot`() {
        val content = (1..6).joinToString("\n") { "「語句$it」を含む${it}番目の十分な長さの本文です。" }
        val model = buildDistillSourceModel(content)

        val selected = selectDistillCandidates(model, "語句")

        assertTrue(selected.count { it.sentence.isTerm } <= DistillLimits.MAX_TERM_CANDIDATES)
        assertTrue(selected.any { !it.sentence.isTerm })
    }

    @Test
    fun `one sentence contributes at most the clause limit`() {
        // 1文が多数の句に割れても、候補枠を1文で埋め尽くさない。
        val content = (1..24).joinToString("、") { "節$it" } + "。"
        val model = buildDistillSourceModel(content)

        val selected = selectDistillCandidates(model, "節")

        assertTrue(model.sentences.size > DistillLimits.MAX_CLAUSES_PER_SENTENCE)
        assertEquals(DistillLimits.MAX_CLAUSES_PER_SENTENCE, selected.size)
    }

    @Test
    fun `split sentences offer clauses instead of the whole sentence`() {
        val first = "前半" + "あ".repeat(28)
        val second = "後半" + "い".repeat(28)
        val model = buildDistillSourceModel("$first、$second。")

        val selected = selectDistillCandidates(model, "前半")

        // 親の全文候補は入れない。全文と句が枠を二重に食うのを避ける。
        assertTrue(selected.none { it.sentence.text.contains("、") })
        assertTrue(selected.all { it.sentence.contextRange != it.sentence.range })
    }

    @Test
    fun `singleton chunk survives maximum sentence prefilter`() {
        val before = (1..250).joinToString("\n") { "前半の説明文${it}です。" }
        val after = (1..250).joinToString("\n") { "後半の説明文${it}です。" }
        val content = "$before\n# 重要\n孤立した重要な結論です。\n# 続き\n$after"
        val model = buildDistillSourceModel(content)

        val selected = selectDistillCandidates(model, "無関係", limit = DistillLimits.MAX_AI_CANDIDATES)

        assertTrue(model.sentences.size > DistillLimits.MAX_SENTENCES_FOR_SCORING)
        assertTrue(selected.any { it.sentence.text == "孤立した重要な結論です。" })
    }
}
