package com.example.newproject.domain

import com.example.newproject.domain.markdown.InlineSpanKind
import com.example.newproject.domain.markdown.scanInlineSyntax
import com.example.newproject.model.DistillTextRange
import com.example.newproject.model.state.DistillRangeEdge
import com.example.newproject.model.state.DistillRangeEdgeMove
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自由範囲の端が**置ける位置にしか止まらない**ことと、**倒す向き**を固定する。
 *
 * 保護範囲が絡むものは `buildDistillSourceModel` を通してから引く（分割器が出さない範囲を
 * 手で作らないため）。書記素の判定だけは分割器を通さず、判定そのものを直接問う。
 */
class DistillRangeSnapTest {

    private fun wholeText(content: String) = DistillTextRange(0, content.length)

    private fun boundaries(content: String) =
        distillBoundaryOffsets(content, wholeText(content), emptyList())

    // ---- 書記素 ----

    @Test
    fun `an offset inside a surrogate pair is not a boundary`() {
        val content = "感想は😀でした"
        val emoji = content.indexOf("\uD83D")

        val offsets = boundaries(content)

        assertFalse(offsets.contains(emoji + 1))
        assertTrue(offsets.contains(emoji))
        assertTrue(offsets.contains(emoji + 2))
    }

    @Test
    fun `combining marks, variation selectors and skin tones hold their base character`() {
        // が＝か＋濁点、1️⃣＝1＋異体字セレクタ＋囲み記号、👍🏻＝親指＋肌色修飾子。
        val content = "がと1️⃣と👍🏻"

        val offsets = boundaries(content)

        assertFalse("濁点の前で割れました", offsets.contains(content.indexOf('゙')))
        assertFalse("異体字セレクタの前で割れました", offsets.contains(content.indexOf('️')))
        assertFalse("囲み記号の前で割れました", offsets.contains(content.indexOf('⃣')))
        assertFalse("肌色修飾子の前で割れました", offsets.contains(content.indexOf('\uD83C')))
    }

    @Test
    fun `a zero width joiner sequence is one grapheme`() {
        val content = "家族は👨‍👩‍👧です"
        val joiner = content.indexOf('‍')

        val offsets = boundaries(content)

        // ZWJ の前後どちらでも割れない。**片方だけ塞いでも連結は割れる。**
        assertFalse(offsets.contains(joiner))
        assertFalse(offsets.contains(joiner + 1))
    }

    @Test
    fun `flags break between pairs but not inside one`() {
        // 🇯🇵🇺🇸 — 地域指示子4つで2つの旗。奇数個目の前では割れない。
        val content = "🇯🇵🇺🇸"

        val offsets = boundaries(content)

        assertFalse("1つ目の旗を割りました", offsets.contains(2))
        assertTrue("旗と旗のあいだで割れません", offsets.contains(4))
        assertFalse("2つ目の旗を割りました", offsets.contains(6))
    }

    // ---- 保護範囲 ----

    private val italicContent = "この段落は*強調した語句*を含んでいて、端の扱いを確かめるのに十分な長さがあります。"

    private fun italicCase(): Triple<String, DistillTextRange, List<DistillTextRange>> {
        val model = buildDistillSourceModel(italicContent)
        val sentence = model.sentences.first { !it.isTerm }
        return Triple(
            model.content,
            sentence.contextRange,
            distillProtectedSpansWithin(model, sentence.contextRange)
        )
    }

    @Test
    fun `widening onto a decoration takes the whole pair`() {
        val (content, context, spans) = italicCase()
        val italic = spans.single()
        // 装飾より後ろから始まっている範囲を、装飾の内側めがけて左へ広げる。
        val current = DistillTextRange(italic.endExclusive, context.endExclusive)

        val snapped = snapDistillRangeEdge(
            content, context, current, DistillRangeEdge.Start,
            desiredOffset = italic.start + 3, fromOffset = current.start, protectedSpans = spans
        )

        assertEquals(italic.start, snapped?.start)
    }

    @Test
    fun `narrowing onto a decoration steps past the whole pair`() {
        val (content, context, spans) = italicCase()
        val italic = spans.single()
        val current = DistillTextRange(context.start, context.endExclusive)

        val snapped = snapDistillRangeEdge(
            content, context, current, DistillRangeEdge.Start,
            desiredOffset = italic.start + 3, fromOffset = current.start, protectedSpans = spans
        )

        assertEquals(italic.endExclusive, snapped?.start)
    }

    @Test
    fun `no reachable offset sits inside a decoration`() {
        val (content, context, spans) = italicCase()
        val italic = spans.single()

        val offsets = distillBoundaryOffsets(content, context, spans)

        assertTrue(offsets.none { italic.contains(it) })
        // 対の外側と内側の境ではなく、**対そのものの両端**は置いてよい。
        assertTrue(offsets.contains(italic.start))
        assertTrue(offsets.contains(italic.endExclusive))
    }

    // ---- 空白と外枠 ----

    @Test
    fun `an edge never leaves whitespace at the border`() {
        val content = "この段落は long word を挟んでいて、端の空白の扱いを確かめられる長さがあります。"
        val context = wholeText(content)
        val space = content.indexOf(' ')

        val start = snapDistillRangeEdge(
            content, context, context, DistillRangeEdge.Start,
            desiredOffset = space, fromOffset = context.start, protectedSpans = emptyList()
        )
        val end = snapDistillRangeEdge(
            content, context, context, DistillRangeEdge.End,
            desiredOffset = space + 1, fromOffset = context.endExclusive, protectedSpans = emptyList()
        )

        assertFalse(content[start!!.start].isWhitespace())
        assertFalse(content[end!!.endExclusive - 1].isWhitespace())
    }

    @Test
    fun `an edge cannot cross the opposite edge`() {
        val content = "この文はちょうどよい長さの本文です。"
        val context = wholeText(content)
        val current = DistillTextRange(4, 8)

        val start = snapDistillRangeEdge(
            content, context, current, DistillRangeEdge.Start,
            desiredOffset = context.endExclusive, fromOffset = current.start, protectedSpans = emptyList()
        )
        val end = snapDistillRangeEdge(
            content, context, current, DistillRangeEdge.End,
            desiredOffset = context.start, fromOffset = current.endExclusive, protectedSpans = emptyList()
        )

        assertTrue(start!!.length > 0)
        assertTrue(end!!.length > 0)
    }

    @Test
    fun `an edge stays inside the parent sentence`() {
        val content = "前の文です。この文が親になります。後の文です。"
        val context = DistillTextRange(content.indexOf("この文"), content.indexOf("後の文"))
        // 親文の内側から、前の文まで引き抜こうとする。
        val current = DistillTextRange(context.start + 3, context.endExclusive)

        val widened = snapDistillRangeEdge(
            content, context, current, DistillRangeEdge.Start,
            desiredOffset = 0, fromOffset = current.start, protectedSpans = emptyList()
        )

        assertEquals(context.start, widened?.start)
    }

    @Test
    fun `an edge that is already where the finger points does not move`() {
        val content = "この文はちょうどよい長さの本文です。"
        val context = wholeText(content)
        val current = DistillTextRange(4, 8)

        val unmoved = snapDistillRangeEdge(
            content, context, current, DistillRangeEdge.Start,
            desiredOffset = 4, fromOffset = 4, protectedSpans = emptyList()
        )

        // **動かさないことが往復を止める。** 指が文字の内側で動くだけでも同じoffsetが続けて届く。
        assertNull(unmoved)
    }

    // ---- 微調整 ----

    @Test
    fun `a nudge moves exactly one placeable offset`() {
        val content = "この文はちょうどよい長さの本文です。"
        val context = wholeText(content)
        val current = DistillTextRange(4, 8)

        val expanded = nudgeDistillRangeEdge(
            content, context, current, DistillRangeEdgeMove.ExpandStart, emptyList()
        )
        val shrunk = nudgeDistillRangeEdge(
            content, context, current, DistillRangeEdgeMove.ShrinkEnd, emptyList()
        )

        assertEquals(DistillTextRange(3, 8), expanded)
        assertEquals(DistillTextRange(4, 7), shrunk)
    }

    @Test
    fun `a nudge steps over a whole decoration rather than into it`() {
        val (content, context, spans) = italicCase()
        val italic = spans.single()
        val current = DistillTextRange(italic.endExclusive, context.endExclusive)

        val expanded = nudgeDistillRangeEdge(
            content, context, current, DistillRangeEdgeMove.ExpandStart, spans
        )

        assertEquals(italic.start, expanded?.start)
    }

    @Test
    fun `moves that would leave the parent or collapse the range are not offered`() {
        val content = "この文はちょうどよい長さの本文です。"
        val context = wholeText(content)

        val atFullWidth = availableDistillEdgeMoves(content, context, context, emptyList())
        assertEquals(
            setOf(DistillRangeEdgeMove.ShrinkStart, DistillRangeEdgeMove.ShrinkEnd),
            atFullWidth
        )

        val single = DistillTextRange(4, 5)
        val atMinimum = availableDistillEdgeMoves(content, context, single, emptyList())
        assertEquals(
            setOf(DistillRangeEdgeMove.ExpandStart, DistillRangeEdgeMove.ExpandEnd),
            atMinimum
        )
        assertNull(
            nudgeDistillRangeEdge(content, context, single, DistillRangeEdgeMove.ShrinkStart, emptyList())
        )
    }

    // ---- レビュー再現（未修正なら落ちる） ----

    @Test
    fun `an edge never lands right after a backslash`() {
        // `**` を挿した瞬間に `\*` がエスケープになり、太字が成立しなくなる。
        // 元の `\U` はエスケープ構文ではないので、既存の保護範囲では守れない。
        val content = "ファイルの場所は C:\\Users です。"
        val context = wholeText(content)
        val afterBackslash = content.indexOf("Users")

        val offsets = distillBoundaryOffsets(content, context, emptyList())

        assertFalse("バックスラッシュ直後に端を置けています", offsets.contains(afterBackslash))
    }

    @Test
    fun `the same requested offset does not flip the edge back and forth`() {
        val (content, context, spans) = italicCase()
        val italic = spans.single()
        var current = DistillTextRange(italic.endExclusive, context.endExclusive)
        val desired = italic.start + 3
        var from = current.start

        val seen = mutableListOf<Int>()
        repeat(4) {
            val next = snapDistillRangeEdge(
                content, context, current, DistillRangeEdge.Start,
                desiredOffset = desired, fromOffset = from, protectedSpans = spans
            )
            if (next != null) current = next
            from = desired
            seen += current.start
        }

        // 2回目以降は動かない。指が止まっているのに端が往復してはいけない。
        assertEquals(listOf(italic.start, italic.start, italic.start, italic.start), seen)
    }

    @Test
    fun `a run held in one direction never reverses`() {
        val (content, context, spans) = italicCase()
        val italic = spans.single()
        var current = DistillTextRange(italic.endExclusive, context.endExclusive)
        var from = current.start

        // 装飾の内側を左へ進み続ける列。
        val starts = mutableListOf<Int>()
        for (desired in (italic.endExclusive - 1) downTo italic.start) {
            snapDistillRangeEdge(
                content, context, current, DistillRangeEdge.Start,
                desiredOffset = desired, fromOffset = from, protectedSpans = spans
            )?.let { current = it }
            from = desired
            starts += current.start
        }

        assertTrue("端が右へ戻りました: $starts", starts.zipWithNext().all { (a, b) -> b <= a })
    }

    /**
     * 保存後を**再解析して**、狙った文字列が太字として読めることまで見る。
     *
     * **「追加した `**` を除けば原文一致」では足りない。** 記法が壊れてもその比較は通る
     * （P1-1 はまさにその形で、出力は原文一致のまま太字が消えていた）。
     */
    private fun assertBoldSurvives(content: String, range: DistillTextRange) {
        val output = applyDistillBold(content, listOf(range)).content
        assertEquals("原文が変わりました", content, output.replace("**", ""))

        val expected = content.substring(range.start, range.endExclusive)
        val bold = scanInlineSyntax(output).flatten()
            .filter { it.kind == InlineSpanKind.Bold }
            .map { output.substring(it.contentStart, it.contentEnd) }

        assertEquals("太字が成立していません（出力: $output）", listOf(expected), bold)
    }

    @Test
    fun `a windows path keeps its bold after a round trip`() {
        val content = "ファイルの場所は C:\\Users です。手元の控えもそこへ置いてあります。"
        val model = buildDistillSourceModel(content)
        val sentence = model.sentences.first { !it.isTerm }
        val context = sentence.contextRange
        val spans = distillProtectedSpansWithin(model, context)

        // バックスラッシュ直後（`U` の前）を狙う。置けないので手前か先へ倒れる。
        val target = content.indexOf("Users")
        val start = snapDistillRangeEdge(
            content, context, sentence.range, DistillRangeEdge.Start,
            desiredOffset = target, fromOffset = sentence.range.start, protectedSpans = spans
        )!!
        assertBoldSurvives(content, start)

        // 終点側も同じ位置を狙う。
        val end = snapDistillRangeEdge(
            content, context, sentence.range, DistillRangeEdge.End,
            desiredOffset = target, fromOffset = sentence.range.endExclusive, protectedSpans = spans
        )!!
        assertBoldSurvives(content, end)
    }

    @Test
    fun `escaped markers and doubled backslashes keep their bold`() {
        val content = "式は a \\* b で、退避先は D:\\\\share です。読み返すときの手がかりにします。"
        val model = buildDistillSourceModel(content)
        val sentence = model.sentences.first { !it.isTerm }
        val context = sentence.contextRange
        val spans = distillProtectedSpansWithin(model, context)

        // 置ける位置すべてを端にしても、太字が壊れないこと。
        val offsets = distillBoundaryOffsets(content, context, spans)
        offsets.filter { it < sentence.range.endExclusive }.forEach { start ->
            if (content[start].isWhitespace()) return@forEach
            assertBoldSurvives(content, DistillTextRange(start, sentence.range.endExclusive))
        }
        offsets.filter { it > sentence.range.start }.forEach { end ->
            if (content[end - 1].isWhitespace()) return@forEach
            assertBoldSurvives(content, DistillTextRange(sentence.range.start, end))
        }
    }

    @Test
    fun `nudging across a backslash keeps its bold`() {
        val content = "ファイルの場所は C:\\Users です。手元の控えもそこへ置いてあります。"
        val model = buildDistillSourceModel(content)
        val sentence = model.sentences.first { !it.isTerm }
        val context = sentence.contextRange
        val spans = distillProtectedSpansWithin(model, context)

        // バックスラッシュをまたぐまで内側へ詰め続ける。どの段階でも壊れない。
        var current = sentence.range
        repeat(content.indexOf("Users") - sentence.range.start + 2) {
            current = nudgeDistillRangeEdge(
                content, context, current, DistillRangeEdgeMove.ShrinkStart, spans
            ) ?: return@repeat
            assertBoldSurvives(content, current)
        }
        assertTrue("バックスラッシュを越えていません", current.start > content.indexOf('\\'))
    }
}

