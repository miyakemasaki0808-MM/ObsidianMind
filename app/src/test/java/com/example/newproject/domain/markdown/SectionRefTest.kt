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
}
