package com.example.newproject.ui

import com.example.newproject.domain.MemoSectionMatch
import com.example.newproject.model.MarginMemo
import com.example.newproject.model.SectionRef
import com.example.newproject.ui.screen.memoCaption
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
}
