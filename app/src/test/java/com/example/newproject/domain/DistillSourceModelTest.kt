package com.example.newproject.domain

import com.example.newproject.model.DistillLimits
import com.example.newproject.model.DistillSourceModel
import com.example.newproject.model.DistillTextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

class DistillSourceModelTest {

    /** `[[...]]` の範囲を素直に拾う。純関数の境界を、パーサを通さずに直接固定するため。 */
    private fun wikilinkSpans(content: String): List<DistillTextRange> {
        val result = mutableListOf<DistillTextRange>()
        var index = content.indexOf("[[")
        while (index >= 0) {
            val close = content.indexOf("]]", index + 2)
            if (close < 0) break
            result += DistillTextRange(index, close + 2)
            index = content.indexOf("[[", close + 2)
        }
        return result
    }

    private fun isLinkOnly(content: String): Boolean =
        isLinkOnlyRange(content, DistillTextRange(0, content.length), wikilinkSpans(content))

    @Test
    fun `link only judgement does not depend on separator count`() {
        // 区切り記号を実質文字として数えていたため、リンクを増やすほど残りやすくなっていた。
        assertTrue(isLinkOnly("[[A]]"))
        assertTrue(isLinkOnly("[[A]] と [[B]]"))
        assertTrue(isLinkOnly("[[A]]、[[B]]。"))
        assertTrue(isLinkOnly("[[A]]、[[B]]、[[C]]、[[D]]。"))
        assertTrue(isLinkOnly("[[A]] and [[B]]"))
    }

    @Test
    fun `link only judgement keeps short but substantive claims`() {
        // 文字数で切ると落ちていた短い主張。記号と接続語を除いても文字が残る。
        assertFalse(isLinkOnly("[[A]]は核。"))
        assertFalse(isLinkOnly("[[設計思想]]は重要。"))
        assertFalse(isLinkOnly("[[Vigilith]]を採用する。"))
        assertFalse(isLinkOnly("詳細は [[リンク情報]] を参照してください。"))
    }

    @Test
    fun `connective removal applies only between links`() {
        // 一覧の文字を含む語は、リンクに挟まれていなければ剥がさない。
        // 日本語は助詞と普通名詞の語境界が空白で分かれないため、位置を捨てると語中まで落ちる。
        assertFalse(isLinkOnly("[[A]]のもの。"))
        assertFalse(isLinkOnly("ものの[[A]]。"))
        // 同じ `の`・`も` でも、両側がリンクなら接続語として剥がす。
        assertTrue(isLinkOnly("[[A]]の[[B]]"))
        assertTrue(isLinkOnly("[[A]]も[[B]]"))
    }

    @Test
    fun `link free ranges are never link only`() {
        // リンクを含まない短文の扱いは従来どおり。短さは候補選定側の減点が引き受ける。
        assertFalse(isLinkOnly("短い。"))
        assertFalse(isLinkOnly("。"))
    }

    /** 閾値を確実に超える長さの節を作る。 */
    private fun clauseOf(label: String, length: Int): String = label + "あ".repeat(length - label.length)

    @Test
    fun `short sentences are not split into clauses`() {
        val content = "短い文です。次の文です。"
        val model = buildDistillSourceModel(content)

        assertEquals(listOf("短い文です。", "次の文です。"), model.sentences.map { it.text })
        // 割っていない文の親文は自分自身。
        assertTrue(model.sentences.all { it.contextRange == it.range })
    }

    @Test
    fun `long sentences split at commas and keep the parent range`() {
        val first = clauseOf("前半", 30)
        val second = clauseOf("後半", 30)
        val content = "$first、$second。"
        val model = buildDistillSourceModel(content)

        assertEquals(listOf(first, "$second。"), model.sentences.map { it.text })
        // 読点は句に含めない。含めると区切り記号まで太字になる。
        assertTrue(model.sentences.none { it.text.startsWith("、") || it.text.endsWith("、") })
        // 句はどちらも同じ親文を指す。
        assertEquals(1, model.sentences.map { it.contextRange }.distinct().size)
        assertEquals(content.trimEnd(), model.sentences.first().let {
            content.substring(it.contextRange.start, it.contextRange.endExclusive)
        })
    }

    @Test
    fun `fragments below the clause minimum accumulate until they reach it`() {
        // 読点が細かい長文。断片を1つずつ句にすると単語まみれになるので、下限へ届くまで積む。
        val content = (1..24).joinToString("、") { "節$it" } + "。"
        val model = buildDistillSourceModel(content)

        assertTrue(content.length > DistillLimits.CLAUSE_SPLIT_THRESHOLD)
        assertTrue(model.sentences.size > 1)
        assertTrue(model.sentences.all { it.text.length >= DistillLimits.MIN_CLAUSE_CHARACTERS })
    }

    @Test
    fun `long sentences without commas stay whole`() {
        // 読点が無ければ割れない。無理に割ると意味が壊れるので、割れないことを受け入れる。
        val content = "あ".repeat(100) + "。"
        val model = buildDistillSourceModel(content)

        assertTrue(content.length > DistillLimits.CLAUSE_SPLIT_THRESHOLD)
        assertEquals(listOf(content), model.sentences.map { it.text })
    }

    @Test
    fun `trailing remainder below the minimum is absorbed by the previous clause`() {
        val head = clauseOf("先頭", 40)
        val content = "$head、余り。"
        val model = buildDistillSourceModel(content)

        // 末尾の「余り。」は下限未満なので新しい句にせず、直前の句を末尾まで伸ばす。
        assertEquals(listOf(content), model.sentences.map { it.text })
    }

    @Test
    fun `positional bonuses land on the first and last clause only`() {
        val first = clauseOf("前半", 30)
        val second = clauseOf("後半", 30)
        val model = buildDistillSourceModel("$first、$second。")

        // 位置のフラグは、その位置を実際に占める句だけへ付く。全句が継承すると構造点が多重取りになる。
        assertTrue(model.sentences.first().isParagraphFirst)
        assertFalse(model.sentences.last().isParagraphFirst)
        assertTrue(model.sentences.last().isNoteLast)
        assertFalse(model.sentences.first().isNoteLast)
    }

    @Test
    fun `bracketed words become term candidates without taking positional bonuses`() {
        val content = "「オンデバイスAI」は端末内で動く仕組みです。"
        val model = buildDistillSourceModel(content)

        val term = model.sentences.single { it.isTerm }
        assertEquals("オンデバイスAI", term.text)
        // 語句は本文の線形構造ではないので、段落先頭・ノート末尾を横取りしない。
        assertFalse(term.isParagraphFirst)
        assertFalse(term.isNoteLast)
        assertTrue(model.sentences.single { !it.isTerm }.isNoteLast)
    }

    @Test
    fun `brackets outside the length range are not terms`() {
        // 1字は語句として短すぎ、上限超えは語句ではなく文の一部。
        val model = buildDistillSourceModel("「あ」と「" + "長".repeat(30) + "」を含む本文です。")

        assertTrue(model.sentences.none { it.isTerm })
    }

    @Test
    fun `frontmatter heading code fence and table are excluded`() {
        val content = """
            ---
            title: 秘密
            ---
            # 見出し
            本文です。次の文です。
            ```kotlin
            println("候補にしない。")
            ```
            | a | b |
            |---|---|
            | 候補外。 | x |
            最後です。
        """.trimIndent()

        val model = buildDistillSourceModel(content)

        assertEquals(listOf("本文です。", "次の文です。", "最後です。"), model.sentences.map { it.text })
        assertTrue(model.sentences.all { it.heading == "見出し" })
    }

    @Test
    fun `table without outer pipes and longer fence stay excluded`() {
        val content = """
            A | B
            --- | :---:
            候補外。 | value
            ````kotlin
            コード内。
            ```
            まだコード内。
            ````
            本文です。
        """.trimIndent()

        val model = buildDistillSourceModel(content)

        assertEquals(listOf("本文です。"), model.sentences.map { it.text })
    }

    @Test
    fun `setext heading and empty cell table are excluded`() {
        val content = """
            Setext見出し
            =====
            | | 内容 |
            | --- | --- |
            | | 候補外。 |
            本文です。
        """.trimIndent()

        val model = buildDistillSourceModel(content)

        assertEquals(listOf("本文です。"), model.sentences.map { it.text })
        assertEquals("Setext見出し", model.sentences.single().heading)
    }

    @Test
    fun `BOM CRLF and emoji keep exact UTF16 offsets`() {
        val content = "\uFEFF# H\r\n😀の文です。\r\n次です。\r\n"
        val model = buildDistillSourceModel(content)

        assertEquals(listOf("😀の文です。", "次です。"), model.sentences.map { it.text })
        model.sentences.forEach { sentence ->
            assertEquals(sentence.text, content.substring(sentence.range.start, sentence.range.endExclusive))
        }
    }

    @Test
    fun `list and quote markers stay outside candidate ranges`() {
        val content = "- リストです。\n2. 二番目です。\n> 引用です。"
        val model = buildDistillSourceModel(content)

        assertEquals(listOf("リストです。", "二番目です。", "引用です。"), model.sentences.map { it.text })
        assertTrue(model.sentences.all { content.substring(it.range.start, it.range.endExclusive) == it.text })
    }

    @Test
    fun `inline code and links stay inside sentence and punctuation inside them does not split`() {
        val content = "Use `a.b` now. See [site](https://example.com/a.b). [[Note.Name]]を読む。"
        val model = buildDistillSourceModel(content)

        assertEquals(
            listOf("Use `a.b` now.", "See [site](https://example.com/a.b).", "[[Note.Name]]を読む。"),
            model.sentences.map { it.text }
        )
    }

    @Test
    fun `existing strong sentence is excluded and counted`() {
        val content = "通常文です。**既に太字です。** 次です。"
        val model = buildDistillSourceModel(content)

        assertEquals(listOf("通常文です。", "次です。"), model.sentences.map { it.text })
        assertTrue(model.existingBoldCharacterCount > 0)
    }

    @Test
    fun `existing strong spanning soft line break is excluded and counted once`() {
        val content = "通常文です。**既に太字の前半\n太字の後半です。** 次です。"
        val model = buildDistillSourceModel(content)

        assertEquals(listOf("通常文です。", "次です。"), model.sentences.map { it.text })
        assertEquals("既に太字の前半太字の後半です。".length, model.existingBoldCharacterCount)
    }

    @Test
    fun `strong text in ATX heading does not consume body bold budget`() {
        val content = "# **重要な見出し**\n本文です。次の文です。"

        val model = buildDistillSourceModel(content)

        assertEquals(0, model.existingBoldCharacterCount)
        assertEquals(listOf("本文です。", "次の文です。"), model.sentences.map { it.text })
    }

    @Test
    fun `headingless content still creates chunks and long regions split`() {
        val content = "一つ目です。二つ目です。三つ目です。"
        val model = buildDistillSourceModel(content, chunkCharacterLimit = 8)

        assertEquals(3, model.sentences.size)
        assertTrue(model.chunks.size >= 2)
        assertTrue(model.sentences.last().isNoteLast)
    }

    @Test
    fun `decimal and abbreviation do not create false sentence boundaries`() {
        val content = "Version 1.5 is used. e.g. this one. Done."
        val model = buildDistillSourceModel(content)

        assertEquals(listOf("Version 1.5 is used.", "e.g. this one.", "Done."), model.sentences.map { it.text })
    }

    @Test
    fun `unclosed frontmatter is conservatively excluded`() {
        val content = "---\ntags: x\n本文です。"
        val model = buildDistillSourceModel(content)

        assertTrue(model.sentences.isEmpty())
    }

    @Test
    fun `maximum sized source completes bounded first stage without copying root content`() {
        val sentence = "x".repeat(148) + ".\n"
        val content = sentence.repeat(DistillLimits.MAX_FILE_BYTES / sentence.length)
            .padEnd(DistillLimits.MAX_FILE_BYTES, 'x')
        lateinit var model: DistillSourceModel
        val heapBefore = usedHeapBytes()

        val elapsedMillis = measureTimeMillis {
            model = buildDistillSourceModel(content)
            assertTrue(selectDistillCandidates(model, "title").size <= DistillLimits.MAX_AI_CANDIDATES)
        }
        val heapGrowth = (usedHeapBytes() - heapBefore).coerceAtLeast(0L)

        assertEquals(DistillLimits.MAX_FILE_BYTES, content.toByteArray(Charsets.UTF_8).size)
        assertSame(content, model.content)
        assertTrue("processing took ${elapsedMillis}ms", elapsedMillis < 10_000)
        assertTrue("heap grew by $heapGrowth bytes", heapGrowth < 64L * 1024L * 1024L)
    }

    private fun usedHeapBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }
}
