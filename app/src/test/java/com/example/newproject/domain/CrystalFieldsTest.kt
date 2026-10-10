package com.example.newproject.domain

import com.example.newproject.model.Crystal
import com.example.newproject.model.CrystalSource
import com.example.newproject.model.NoteField
import com.example.newproject.model.NoteField.Business
import com.example.newproject.model.NoteField.Living
import com.example.newproject.model.NoteField.Reflection
import com.example.newproject.model.NoteField.Technical
import org.junit.Assert.assertEquals
import org.junit.Test

// 結晶の宝石を塗る分野（→ features/reflect_crystal.md 判断11）。
class CrystalFieldsTest {

    @Test
    fun `根拠がすべて同じ分野なら、その1つだけ`() {
        assertEquals(listOf(Technical), fieldsOf(Technical, Technical))
    }

    /** 同数なら根拠の並びで先のほうを左に置く。先頭は作ったときに読んでいたノート。 */
    @Test
    fun `同数なら根拠の並びで先に出た分野が左に来る`() {
        assertEquals(listOf(Reflection, Technical), fieldsOf(Reflection, Technical))
        assertEquals(listOf(Technical, Reflection), fieldsOf(Technical, Reflection))
    }

    @Test
    fun `数の多い分野が、作ったときに読んでいたノートの分野より先に来る`() {
        assertEquals(listOf(Technical, Reflection), fieldsOf(Reflection, Technical, Technical))
    }

    /** 3分野にまたがっても2つまで。数の多い順、同数は根拠の並び。 */
    @Test
    fun `3つの分野にまたがっても2つまでにする`() {
        assertEquals(listOf(Reflection, Technical), fieldsOf(Reflection, Technical, Living))
        assertEquals(listOf(Living, Reflection), fieldsOf(Reflection, Technical, Living, Living))
    }

    /** 分野の分からない根拠は数えない。数えると、分かる分野を押しのけて無彩色が混ざる。 */
    @Test
    fun `分野の分からない根拠は数えない`() {
        assertEquals(listOf(Business), fieldsOf(null, Business))
        assertEquals(listOf(Business, Living), fieldsOf(null, null, Business, Living))
    }

    @Test
    fun `どの根拠の分野も分からなければ空`() {
        assertEquals(emptyList<NoteField>(), fieldsOf(null, null))
    }

    /** 根拠の i 番目を `n{i}.md` に置き、その分野を [fields] の i 番目にする。 */
    private fun fieldsOf(vararg fields: NoteField?): List<NoteField> {
        val crystal = Crystal(
            createdAt = 0L,
            sentence = "共通する筋。",
            sources = fields.indices.map { CrystalSource("n$it.md", "n$it", "断片") },
            fileName = "0.json"
        )
        val byPath = fields.withIndex().associate { (i, field) -> "n$i.md" to field }
        return crystalFields(crystal) { path -> byPath[path] }
    }
}
