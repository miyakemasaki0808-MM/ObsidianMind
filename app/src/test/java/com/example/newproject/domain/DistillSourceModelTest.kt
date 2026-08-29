package com.example.newproject.domain

import com.example.newproject.domain.markdown.InlineSpanKind
import com.example.newproject.domain.markdown.scanInlineSyntax
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
    fun `clause split does not cut inside brackets`() {
        // 机上レビュー 2026-08-17 P2-1。括弧の中の読点で割ると、括弧が2句へまたがり語句が消える。
        val content = "あ".repeat(30) + "「設計、検証」" + "い".repeat(30) + "。"
        val model = buildDistillSourceModel(content)

        assertTrue(content.length > DistillLimits.CLAUSE_SPLIT_THRESHOLD)
        assertEquals("設計、検証", model.sentences.single { it.isTerm }.text)
        // 句は括弧をまたがない。
        assertTrue(model.sentences.none { !it.isTerm && it.text.endsWith("「設計") })
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

    /** 候補の単位（文・句）だけを取り出す。語句は親の内側に重なるので、境界の検査では数えない。 */
    private fun linearTexts(model: DistillSourceModel): List<String> =
        model.sentences.filterNot { it.isTerm }.map { it.text }

    @Test
    fun `italic pairs are not split at commas inside them`() {
        // 保護していなかった頃は、先頭句だけが開始 `*` を含み終了 `*` を含まなかった。
        // その句へ `**` を挿すと、文字を1つも消さないまま装飾の対応が変わる。
        val content = "*" + clauseOf("前半", 30) + "、" + clauseOf("後半", 30) + "*。"
        val model = buildDistillSourceModel(content)

        assertEquals(listOf(content), linearTexts(model))
    }

    @Test
    fun `strikethrough pairs are not split at commas inside them`() {
        val content = "~~" + clauseOf("前半", 30) + "、" + clauseOf("後半", 30) + "~~。"
        val model = buildDistillSourceModel(content)

        assertEquals(listOf(content), linearTexts(model))
    }

    @Test
    fun `sentence boundaries inside italic do not split`() {
        // **句分割を使わない文単位の候補でも起きる。** 長文だけの問題ではない。
        val content = "*斜体の中に句点がある。この二文目も同じ斜体の内側にあって閉じ記号は末尾にしかない*。"
        val model = buildDistillSourceModel(content)

        assertEquals(listOf(content), linearTexts(model))
    }

    @Test
    fun `candidates whose edge falls inside an emphasis pair are dropped`() {
        // ソフト改行をまたぐ対。候補は行をまたがないので、どちらの行も片側しか含めない。
        val content = "*行をまたぐ斜体がここから始まり\nそして次の行で閉じる*ため、候補にしない。"
        val model = buildDistillSourceModel(content)

        assertTrue(linearTexts(model).toString(), model.sentences.isEmpty())
    }

    @Test
    fun `emphasis fully inside a candidate keeps the candidate`() {
        // 対を割らない限り候補にしてよい。`**` で囲んでも対はそのまま内側に残る。
        val content = "これは*強調*を含む文である。"
        val model = buildDistillSourceModel(content)

        assertEquals(listOf(content), linearTexts(model))
    }

    // **過剰保護は「偽の対が文境界を飲み込む」形でしか観測できない。**
    // 装飾が文の内側で閉じるだけのデータでは端が内側に入らず、候補が変わらないので検査が素通りする。

    @Test
    fun `asterisks surrounded by spaces are not emphasis`() {
        // 表示側と同じ空白規則。これが無いと掛け算の `*` どうしが対になり、間の句点まで保護する。
        val content = "計算は 2 * 3。次は 4 * 5 である。"
        val model = buildDistillSourceModel(content)

        assertEquals(listOf("計算は 2 * 3。", "次は 4 * 5 である。"), linearTexts(model))
    }

    @Test
    fun `unclosed strong markers are read as italic, like the renderer does`() {
        // **表示側は閉じていない `**` の2つ目を斜体の開始として描く。** 保護側だけが2文字読み飛ばすと、
        // 表示上は1つの斜体の内側に、蒸留だけが候補境界を置ける状態になる。
        val content = "これは**未閉じの強調。次の文には*斜体*がある。"
        val model = buildDistillSourceModel(content)

        assertEquals(listOf(content), linearTexts(model))
    }

    @Test
    fun `code spans close with the same number of backticks`() {
        // 保護側だけが run を数えていた頃は、コード内の `*` が外の `*` と対になり、
        // 表示上の斜体の内側へ候補境界が残った。
        val content = "記法は ``a*。B``。後に*文字*。"
        val model = buildDistillSourceModel(content)

        assertEquals(listOf("記法は ``a*。B``。", "後に*文字*。"), linearTexts(model))
    }

    @Test
    fun `separately rendered list items do not pair across lines`() {
        // 表示側はリスト項目を1つずつ描くので、行をまたぐ未閉じ記号は対にならない。
        // 連結して走査していた頃は偽の対ができ、候補が全部消えた。
        val content = "- 項目に*未閉じ。\n- 次項目に*未閉じ。"
        val model = buildDistillSourceModel(content)

        assertEquals(listOf("項目に*未閉じ。", "次項目に*未閉じ。"), linearTexts(model))
    }

    @Test
    fun `separately rendered quote lines do not pair across lines`() {
        val content = "> 引用に*未閉じ。\n> 次の引用に*未閉じ。"
        val model = buildDistillSourceModel(content)

        assertEquals(listOf("引用に*未閉じ。", "次の引用に*未閉じ。"), linearTexts(model))
    }

    @Test
    fun `symbols inside links do not pair with outside emphasis`() {
        // 表示側はリンク構文全体を消費し、内側の記号を装飾に使わない。
        val link = buildDistillSourceModel("参照 [a*b](url)。\n次の行は*強調*。")
        val wikilink = buildDistillSourceModel("参照 [[a*b]]。\n次の行は*強調*。")

        assertEquals(listOf("参照 [a*b](url)。", "次の行は*強調*。"), linearTexts(link))
        assertEquals(listOf("参照 [[a*b]]。", "次の行は*強調*。"), linearTexts(wikilink))
    }

    /** 装飾の**種別と対象文字列**の組。保存の前後で変わらないことが受理条件。 */
    private fun decorations(text: String): List<Pair<InlineSpanKind, String>> =
        scanInlineSyntax(text).flatten()
            .map { it.kind to text.substring(it.contentStart, it.contentEnd) }

    @Test
    fun `bolding any candidate keeps every existing decoration with the same kind`() {
        // **これが保存側の受理条件。** 文字列の部分一致では、`Italic:斜体` が `Bold:*斜体*。` へ
        // 変わっても通ってしまう（実際に一度それで見逃した）。**種別ごと突き合わせる。**
        listOf(
            "*斜体*。",
            "~~取消~~。",
            "*この段落はとても長い斜体になっていて、読点をまたいで後半まで続く強調である*。",
            "*斜体の中に句点がある。この二文目も同じ斜体の内側にあって閉じ記号は末尾にしかない*。",
            "これは*強調*を含む文です。次の文もあります。",
            "記法は ``a*。B``。後に*文字*。",
            "参照 [a*b](url)。次の行は*強調*。",
            "参照 [[note|表示名]]。次の文もある。",
            "書式は `code` を使う。次の文もある。"
        ).forEach { content ->
            val model = buildDistillSourceModel(content)
            val before = decorations(content)
            model.sentences.forEach { sentence ->
                val after = decorations(applyDistillBold(content, listOf(sentence.range)).content)
                assertTrue(
                    "$content / ${sentence.text}: $before -> $after",
                    after.containsAll(before)
                )
            }
        }
    }

    @Test
    fun `maximum sized source dense with links completes bounded first stage`() {
        // **上限テストは長さだけでなく、処理対象の要素数も最大化する。**
        // 文字だけの反復では記法spanが1つも無く、記法数×文字数の経路を通らない。
        val unit = "[a](u) x "
        val content = unit.repeat(DistillLimits.MAX_FILE_BYTES / unit.length)
        lateinit var model: DistillSourceModel

        val elapsedMillis = measureTimeMillis {
            model = buildDistillSourceModel(content)
            assertTrue(selectDistillCandidates(model, "title").size <= DistillLimits.MAX_AI_CANDIDATES)
        }

        assertTrue("content is ${content.length} chars", content.length > 250_000)
        assertTrue("processing took ${elapsedMillis}ms", elapsedMillis < 10_000)
    }

    @Test
    fun `maximum sized source dense with existing bold stays bounded per line`() {
        // **行数×記法数の経路は、記法の種類ごとに別々に開く。** リンク密の入力では通らない
        // 太字の集計（行ごとに全太字を舐める形）がここで効く。
        // 実測 503ms。行ごとの振り分けをやめる変異では 4,475ms まで落ちるので、上限は3秒に置く。
        val unit = "**a** x\n"
        val content = unit.repeat(DistillLimits.MAX_FILE_BYTES / unit.length)
        lateinit var model: DistillSourceModel

        val elapsedMillis = measureTimeMillis { model = buildDistillSourceModel(content) }

        assertTrue("content is ${content.length} chars", content.length > 250_000)
        assertTrue("existing bold is counted", model.existingBoldCharacterCount > 0)
        assertTrue("processing took ${elapsedMillis}ms", elapsedMillis < 3_000)
    }

    @Test
    fun `maximum sized source of unclosed markers stays bounded`() {
        // **閉じ記号が無い開始記号は、見つからないことを覚えないと毎回末尾まで探し直す。**
        // 実測 11ms／3ms／28ms。前方探索を戻す変異では `[` だけで 8,424ms まで落ちる。
        // 表示側（`inlineMarkdown`）も同じ解釈器を通るので、これはMainを止める時間でもある。
        listOf(
            "[".repeat(250_000),
            "[[".repeat(125_000),
            "`x".repeat(125_000)
        ).forEach { content ->
            val elapsedMillis = measureTimeMillis { buildDistillSourceModel(content) }
            assertTrue("${content.take(2)} took ${elapsedMillis}ms", elapsedMillis < 3_000)
        }
    }

    @Test
    fun `maximum sized source dense with decorated sentences stays bounded`() {
        // **保護範囲と候補を同時に最大化する。** 既存の上限テストはどちらか片方しか最大にしておらず、
        // リンク密は句点が無いので候補1件、太字密は保護範囲を1つも増やさない。
        // この2形だけが「候補数×保護範囲数」を通る。上限は実測から決めてある。
        //   短い装飾文: 実測 43ms。端点照合を毎回先頭から走らせる変異で 1,079ms
        //   括弧＋装飾: 実測 61ms。語句除外を毎回先頭から走らせる変異で 483ms
        listOf(
            "*abc*。".repeat(32_000) to 500L,
            "「a」*b*。".repeat(32_000) to 300L
        ).forEach { (content, limitMillis) ->
            lateinit var model: DistillSourceModel
            val elapsedMillis = measureTimeMillis { model = buildDistillSourceModel(content) }

            assertTrue("content is ${content.length} chars", content.length > 190_000)
            assertTrue("candidates are ${model.sentences.size}", model.sentences.size > 10_000)
            assertTrue("processing took ${elapsedMillis}ms (limit ${limitMillis}ms)", elapsedMillis < limitMillis)
        }
    }

    private fun usedHeapBytes(): Long {
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }
}
