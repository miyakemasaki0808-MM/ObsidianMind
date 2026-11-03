package com.example.newproject.domain.markdown

import com.example.newproject.model.SectionRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 節を見出し名と同名の中での順番で指す（→ features/margin_pane.md §5.3）。 */
class SectionRefTest {

    private val model = buildNoteSectionModel(
        "冒頭の段落\n\n# まとめ\n\n一つ目\n\n# 本論\n\n本論の段落\n\n# まとめ\n\n二つ目"
    )

    @Test
    fun `見出しより前は名前の無い節`() {
        assertEquals(SectionRef(title = null), model.sectionRefAt(0))
    }

    /** **同名の見出しを取り違えない。** 名前だけで指すと、後ろの「まとめ」が前の「まとめ」になる。 */
    @Test
    fun `同名の見出しは順番で区別する`() {
        val firstSummary = model.sectionRefAt(1)
        val secondSummary = model.sectionRefAt(model.blocks.lastIndex)

        assertEquals(SectionRef("まとめ", ordinal = 0), firstSummary)
        assertEquals(SectionRef("まとめ", ordinal = 1), secondSummary)
    }

    @Test
    fun `見出しの無いノートは全体が名前の無い節`() {
        val plain = buildNoteSectionModel("段落だけ\n\nもう一つ")

        assertEquals(SectionRef(title = null), plain.sectionRefAt(1))
    }

    /** 引き直しは同じ名前と順番で行う。**同名の2つ目を1つ目と取り違えない。** */
    @Test
    fun `節の始まりを名前と順番で引き直す`() {
        val second = model.sectionRefAt(model.blocks.lastIndex)

        assertEquals(0, model.startBlockOf(SectionRef(null)))
        assertEquals(second, model.sectionRefAt(model.startBlockOf(second)!!))
        assertEquals(SectionRef("まとめ", 0), model.sectionRefAt(model.startBlockOf(SectionRef("まとめ", 0))!!))
    }

    @Test
    fun `同じ名前と順番の見出しが無ければ引けない`() {
        assertNull(model.startBlockOf(SectionRef("まとめ", 2)))
        assertNull(model.startBlockOf(SectionRef("消えた見出し")))
    }

    // ── 部分要約に渡す本文（→ features/margin_pane.md §5.3）──────────────

    /** **見出しより前は見出しより前だけ。** 面に出している節の範囲と揃える。 */
    @Test
    fun `見出しより前の要約には、見出しより前だけを渡す`() {
        val source = model.summarySourceOf(SectionRef(title = null), noteTitle = "ノート")!!

        assertEquals("ノート", source.title)
        assertEquals("冒頭の段落", source.text)
    }

    @Test
    fun `見出しの無いノートの要約には、全体を渡す`() {
        val plain = buildNoteSectionModel("段落だけ\n\nもう一つ")

        assertEquals("段落だけ\n\nもう一つ", plain.summarySourceOf(SectionRef(title = null), "ノート")!!.text)
    }

    /** 同名の2つ目の要約に、1つ目の本文を渡さない。 */
    @Test
    fun `見出しのある節は、名前と順番で引いた節の本文を渡す`() {
        val second = model.summarySourceOf(SectionRef("まとめ", 1), "ノート")!!

        assertEquals("まとめ", second.title)
        assertTrue(second.text.contains("二つ目"))
        assertFalse(second.text.contains("一つ目"))
    }

    @Test
    fun `今の本文に無い節と、空の冒頭は要約に渡さない`() {
        assertNull(model.summarySourceOf(SectionRef("消えた見出し"), "ノート"))
        assertNull(buildNoteSectionModel("# 見出し\n\n本文").summarySourceOf(SectionRef(title = null), "ノート"))
    }

    /** **短さは固有の本文で測る。** 節の本文は配下を含むので、見出しだけの親は長く見えても作らない。 */
    @Test
    fun `全節の要約は固有の本文が短い節を作らず、見出しだけの親も作らない`() {
        val model = buildNoteSectionModel(
            "# 親\n\n## 子1\n\n$LONG\n\n## 子2\n\n短い\n\n## 子3\n\n$LONG"
        )

        assertEquals(listOf(SectionRef("子1"), SectionRef("子3")), model.summaryTargets)
    }

    /** **固有の本文を持つ親は作る。** 面に出ている節を要約するので、範囲は1節の要求と同じ配下込みのまま。 */
    @Test
    fun `固有の本文を持つ親は全節の要約の対象になる`() {
        val model = buildNoteSectionModel("# 親\n\n$LONG\n\n## 子\n\n短い")

        assertEquals(listOf(SectionRef("親")), model.summaryTargets)
        assertTrue(model.summarySourceOf(SectionRef("親"), "題")!!.text.contains("短い"))
    }

    /** 線の境目。空白は数えない。 */
    @Test
    fun `全節の要約の線は空白を除いた字数で測る`() {
        val exactly = "あ".repeat(SECTION_SUMMARY_MIN_OWN_CHARS)
        val below = "あ".repeat(SECTION_SUMMARY_MIN_OWN_CHARS - 1) + "    "

        assertEquals(listOf(SectionRef("節")), buildNoteSectionModel("# 節\n\n$exactly").summaryTargets)
        assertTrue(buildNoteSectionModel("# 節\n\n$below").summaryTargets.isEmpty())
    }

    @Test
    fun `見出しより前と見出しの無いノートも全節の要約の対象になる`() {
        assertEquals(
            listOf(SectionRef(title = null), SectionRef("節")),
            buildNoteSectionModel("$LONG\n\n# 節\n\n$LONG").summaryTargets
        )
        assertEquals(listOf(SectionRef(title = null)), buildNoteSectionModel(LONG).summaryTargets)
        assertTrue(buildNoteSectionModel("# 節\n\n短い").summaryTargets.isEmpty())
    }

    /** 今の節から末尾まで、続けて先頭から今の節の手前まで。 */
    @Test
    fun `全節の要約は今の節から順に回る`() {
        val model = buildNoteSectionModel("# A\n\n$LONG\n\n# B\n\n$LONG\n\n# C\n\n$LONG")
        val (a, b, c) = listOf(SectionRef("A"), SectionRef("B"), SectionRef("C"))

        assertEquals(listOf(a, b, c), model.summaryTargetsFrom(a))
        assertEquals(listOf(b, c, a), model.summaryTargetsFrom(b))
        assertEquals(listOf(c, a, b), model.summaryTargetsFrom(c))
    }

    /** 今の節が対象でなければ、その後ろの最初の対象から始める。**後ろに無ければ先頭から。** */
    @Test
    fun `今の節が対象でなければ後ろの最初の対象から始める`() {
        val model = buildNoteSectionModel("# A\n\n$LONG\n\n# 短\n\n短い\n\n# B\n\n$LONG\n\n# 末尾\n\n短い")
        val (a, b) = listOf(SectionRef("A"), SectionRef("B"))

        assertEquals(listOf(b, a), model.summaryTargetsFrom(SectionRef("短")))
        assertEquals(listOf(a, b), model.summaryTargetsFrom(SectionRef("末尾")))
    }

    /** 同名の見出しは順番で区別したまま並ぶ。 */
    @Test
    fun `同名の見出しも別の節として順に回る`() {
        val model = buildNoteSectionModel("# 同\n\n$LONG\n\n# 同\n\n$LONG\n\n# 同\n\n$LONG")
        val second = SectionRef("同", ordinal = 1)

        assertEquals(
            listOf(second, SectionRef("同", ordinal = 2), SectionRef("同", ordinal = 0)),
            model.summaryTargetsFrom(second)
        )
    }

    private companion object {
        val LONG = "本文".repeat(SECTION_SUMMARY_MIN_OWN_CHARS)
    }
}
