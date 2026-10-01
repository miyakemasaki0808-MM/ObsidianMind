package com.example.newproject.domain.markdown

import com.example.newproject.model.SectionRef
import org.junit.Assert.assertEquals
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
}
