package com.example.newproject.domain

import com.example.newproject.model.DistillConfirmedRange
import com.example.newproject.model.DistillLimits
import com.example.newproject.model.DistillTextRange
import com.example.newproject.model.state.DistillRangePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 太字範囲の3段と、調整で生まれる重なりの解消を固定する。
 *
 * **3段は新しい境界規則を作らない。** すべて `buildDistillSourceModel` の出力を引き直すだけなので、
 * 検査もモデルを実際に組んでから引く。範囲を手で作ると、分割器が出さない範囲まで通してしまう。
 */
class DistillRangeAdjustTest {

    private fun clauseOf(label: String, length: Int): String = label + "あ".repeat(length - label.length)

    @Test
    fun `unsplit sentences collapse the clause and sentence presets into one`() {
        val model = buildDistillSourceModel("短い文です。")
        val sentence = model.sentences.single()

        val options = presetRangesFor(model, sentence)

        // 割れなかった文では句と親文が同じ範囲になる。同じ結果になるボタンを2つ出さない。
        assertEquals(listOf(DistillRangePreset.Sentence), options.map { it.preset })
        assertEquals(sentence.contextRange, options.single().range.range)
    }

    @Test
    fun `a clause offers itself and its parent sentence`() {
        val content = "${clauseOf("前半", 30)}、${clauseOf("後半", 30)}。"
        val model = buildDistillSourceModel(content)
        val clause = model.sentences.first()

        val options = presetRangesFor(model, clause)

        assertTrue(content.length > DistillLimits.CLAUSE_SPLIT_THRESHOLD)
        assertEquals(listOf(DistillRangePreset.Clause, DistillRangePreset.Sentence), options.map { it.preset })
        assertEquals(clause.range, options.first().range.range)
        assertEquals(clause.contextRange, options.last().range.range)
    }

    @Test
    fun `a clause holding exactly one term offers all three presets`() {
        val content = "${clauseOf("前半", 30)}、「設計思想」${"い".repeat(24)}。"
        val model = buildDistillSourceModel(content)
        val term = model.sentences.single { it.isTerm }
        val clause = model.sentences.first { !it.isTerm && it.range.encloses(term.range) }

        val options = presetRangesFor(model, clause)

        assertEquals(
            listOf(DistillRangePreset.Term, DistillRangePreset.Clause, DistillRangePreset.Sentence),
            options.map { it.preset }
        )
        assertEquals("設計思想", content.substring(options.first().range.range.start, options.first().range.range.endExclusive))
    }

    @Test
    fun `a clause holding several terms does not offer the term preset`() {
        // どの語句を指すか決められないので段を出さない。語句は独立候補として一覧に並びうる。
        val content = "${clauseOf("前半", 30)}、「設計」と「検証」${"い".repeat(20)}。"
        val model = buildDistillSourceModel(content)
        val clause = model.sentences.first { !it.isTerm && model.sentences.count { term ->
            term.isTerm && it.range.encloses(term.range)
        } > 1 }

        val options = presetRangesFor(model, clause)

        assertTrue(model.sentences.count { it.isTerm } > 1)
        assertEquals(listOf(DistillRangePreset.Clause, DistillRangePreset.Sentence), options.map { it.preset })
    }

    @Test
    fun `a term resolves its parent clause by containment`() {
        // 語句の文脈範囲は句ではなく親文なので、親句はモデルのどこにも書かれていない。
        val content = "${clauseOf("前半", 30)}、「設計思想」${"い".repeat(24)}。"
        val model = buildDistillSourceModel(content)
        val term = model.sentences.single { it.isTerm }
        val parentClause = model.sentences.first { !it.isTerm && it.range.encloses(term.range) }

        val options = presetRangesFor(model, term)

        assertEquals(
            listOf(DistillRangePreset.Term, DistillRangePreset.Clause, DistillRangePreset.Sentence),
            options.map { it.preset }
        )
        assertEquals(term.range, options[0].range.range)
        assertEquals(parentClause.range, options[1].range.range)
        assertEquals(term.contextRange, options[2].range.range)
        assertTrue(parentClause.range != term.contextRange)
    }

    @Test
    fun `every preset stays inside the context range`() {
        val content = "${clauseOf("前半", 30)}、「設計思想」${"い".repeat(24)}。"
        val model = buildDistillSourceModel(content)

        model.sentences.forEach { sentence ->
            presetRangesFor(model, sentence).forEach { option ->
                assertTrue(
                    "${option.preset} が文脈範囲の外へ出ました",
                    sentence.contextRange.encloses(option.range.range)
                )
                assertEquals(sentence.contextRange, option.range.contextRange)
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a confirmed range outside its context cannot be built`() {
        DistillConfirmedRange(
            contextRange = DistillTextRange(10, 20),
            range = DistillTextRange(8, 12)
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an empty confirmed range cannot be built`() {
        DistillConfirmedRange(
            contextRange = DistillTextRange(10, 20),
            range = DistillTextRange(12, 12)
        )
    }

    @Test
    fun `widening both clauses of one sentence deselects the untouched candidate`() {
        val content = "${clauseOf("前半", 30)}、${clauseOf("後半", 30)}。"
        val model = buildDistillSourceModel(content)
        val first = model.sentences[0]
        val second = model.sentences[1]
        // 同じ親文の2つの句を、両方 `文全体` にした形。
        val ranges = mapOf("S001" to first.contextRange, "S002" to second.contextRange)

        val resolved = resolveOverlaps(listOf("S001", "S002"), ranges, priorityId = "S002")

        assertEquals(listOf("S002"), resolved.selectedIds)
        assertEquals(listOf("S001"), resolved.deselectedIds)
        assertFalse(hasOverlappingDistillRanges(resolved.selectedIds.map(ranges::getValue)))
    }

    @Test
    fun `re-checking a deselected candidate keeps the set free of overlaps`() {
        // 範囲変更だけに解消を置くと、外された候補をチェックし直すだけで重なりが戻る。
        val ranges = mapOf(
            "S001" to DistillTextRange(0, 20),
            "S002" to DistillTextRange(0, 20)
        )

        val resolved = resolveOverlaps(listOf("S002", "S001"), ranges, priorityId = "S001")

        assertEquals(listOf("S001"), resolved.selectedIds)
        assertEquals(listOf("S002"), resolved.deselectedIds)
        assertFalse(hasOverlappingDistillRanges(resolved.selectedIds.map(ranges::getValue)))
    }

    @Test
    fun `non overlapping selections are left untouched`() {
        val ranges = mapOf(
            "S001" to DistillTextRange(0, 10),
            "S002" to DistillTextRange(10, 20),
            "S003" to DistillTextRange(30, 40)
        )

        val resolved = resolveOverlaps(listOf("S001", "S002", "S003"), ranges, priorityId = "S002")

        assertEquals(listOf("S001", "S002", "S003"), resolved.selectedIds)
        assertTrue(resolved.deselectedIds.isEmpty())
    }

    @Test
    fun `an unselected priority candidate pushes nobody out`() {
        // 未選択の候補の範囲を変えても、選択集合は動かない。
        // 保存対象でないものの編集が取捨を動かしてはいけない。
        val ranges = mapOf(
            "S001" to DistillTextRange(0, 10),
            "S002" to DistillTextRange(0, 20)
        )

        val resolved = resolveOverlaps(listOf("S001"), ranges, priorityId = "S002")

        assertEquals(listOf("S001"), resolved.selectedIds)
        assertTrue(resolved.deselectedIds.isEmpty())
    }

    @Test
    fun `identical ranges count as an overlap`() {
        // `applyDistillBold` は同一範囲を distinct で畳んで落ちない代わりに、
        // 画面の選択件数と保存件数が食い違う。ガードは同一範囲も重なりとして数える。
        val same = DistillTextRange(4, 9)

        assertTrue(hasOverlappingDistillRanges(listOf(same, same)))
        assertTrue(hasOverlappingDistillRanges(listOf(DistillTextRange(0, 5), DistillTextRange(3, 8))))
        assertFalse(hasOverlappingDistillRanges(listOf(DistillTextRange(0, 3), DistillTextRange(3, 6))))
    }
}
