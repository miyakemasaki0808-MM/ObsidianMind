package com.example.newproject.ui

import com.example.newproject.domain.MemoPlacement
import com.example.newproject.domain.MemoSectionMatch
import com.example.newproject.model.MemoPassage
import com.example.newproject.model.MarginMemo
import com.example.newproject.model.SectionRef
import com.example.newproject.ui.screen.memoCaption
import com.example.newproject.ui.screen.quoteDisplayText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** メモの添え書き（→ features/margin_pane.md §5.6）。日時の書式は端末の地域で変わるので、後ろだけを見る。 */
class MarginMemoCaptionTest {

    private val memo = MarginMemo("本文", 0L, sectionTitle = "まとめ")

    @Test
    fun `節ごとに並べたときは節の名前を繰り返さない`() {
        assertFalse(memoCaption(memo, MemoSectionMatch.Unique(SectionRef("まとめ"))).contains("まとめ"))
    }

    @Test
    fun `同名の見出しに共通なら、そう添える`() {
        val caption = memoCaption(memo, MemoSectionMatch.Shared(listOf(SectionRef("まとめ", 0), SectionRef("まとめ", 1))))

        assertTrue(caption.endsWith("同名の見出しに共通"))
    }

    /** **見つからないメモは、控えた見出し名を当時の記録として添える。** 消すと、どこで書いたかが分からなくなる。 */
    @Test
    fun `見つからないメモと照合の前は、控えた見出し名を添える`() {
        assertTrue(memoCaption(memo, MemoSectionMatch.Missing).endsWith("まとめ のあたり"))
        assertTrue(memoCaption(memo, null).endsWith("まとめ のあたり"))
    }

    // ── 引用と書いた頃の位置（→ features/margin_quote.md §5.5）─────────────────────

    /** 段落が確定すれば節も1つに決まるので、同名の見出しに共通とは言わない。 */
    @Test
    fun `確定した引用と位置は日時だけ`() {
        val caption = memoCaption(memo, MemoSectionMatch.Shared(listOf(SectionRef("まとめ", 0), SectionRef("まとめ", 1))), MemoPlacement.Located(SectionRef("まとめ", 1), block = 4))

        assertFalse(caption.contains("同名の見出しに共通"))
        assertFalse(caption.contains("・"))
    }

    /** **黙って先の方を引用元と言わない。** 特定できなければ、似た一節の数を添える。 */
    @Test
    fun `特定できない引用は似た一節の数を添える`() {
        val caption = memoCaption(memo, MemoSectionMatch.Unique(SectionRef("まとめ")), MemoPlacement.Uncertain(listOf(SectionRef("まとめ")), candidateCount = 2))

        assertTrue(caption.endsWith("引用した場所を特定できません · 似た一節 2 か所"))
    }

    @Test
    fun `見つからない引用はそのことを添え、控えた見出し名も残す`() {
        val caption = memoCaption(memo, MemoSectionMatch.Missing, MemoPlacement.Missing)

        assertTrue(caption.contains("まとめ のあたり"))
        assertTrue(caption.endsWith("引用した一節は本文に見つかりません"))
    }

    @Test
    fun `途中までの引用は末尾に三点を付ける`() {
        assertEquals("長い一節…", quoteDisplayText(MemoPassage.Quoted("長い一節", context = null, truncated = true)))
        assertEquals("短い一節", quoteDisplayText(MemoPassage.Quoted("短い一節", context = null, truncated = false)))
    }
}
