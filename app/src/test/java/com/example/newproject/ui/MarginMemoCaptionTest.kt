package com.example.newproject.ui

import com.example.newproject.domain.MemoPlacement
import com.example.newproject.domain.MemoSectionMatch
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.example.newproject.ui.screen.memoFieldEdit
import com.example.newproject.ui.screen.memoFieldValueFor
import com.example.newproject.model.MemoPassage
import com.example.newproject.ui.screen.quoteChoiceQuestion
import com.example.newproject.ui.screen.memoInputPlaceholder
import com.example.newproject.ui.screen.candidateExcerpt
import com.example.newproject.model.state.MarginMemoDraft
import com.example.newproject.model.state.DraftQuote
import com.example.newproject.model.QuoteContext
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

    // ── 引用の欄（→ features/margin_quote.md §5.2・§5.3）────────────────────────

    @Test
    fun `引用があって言葉が空なら添えることを促す`() {
        val quote = DraftQuote(MemoPassage.Quoted("一節", context = null, truncated = false))

        assertEquals("引用にひとこと添えて置く", memoInputPlaceholder(MarginMemoDraft(quote = quote)))
        assertEquals("いま思ったこと", memoInputPlaceholder(MarginMemoDraft()))
        assertEquals("いま思ったこと", memoInputPlaceholder(MarginMemoDraft(text = "書", quote = quote)))
    }

    /** 候補はブロックの途中なら「…」で切れ目を示す。ブロックの端なら付けない。 */
    @Test
    fun `候補の抜き出しは前後の文字を添えてブロックの途中なら切れ目を示す`() {
        assertEquals("…前の同じ一節の後…", candidateExcerpt("同じ一節", QuoteContext("前の", "の後")))
        assertEquals("同じ一節", candidateExcerpt("同じ一節", QuoteContext("", "")))
        assertEquals("同じ一節の後…", candidateExcerpt("同じ一節", QuoteContext("", "の後")))
    }

    @Test
    fun `付ける場の問いは候補の数を言う`() {
        assertEquals("この一節は本文に 3 か所あります。どこから引用しましたか", quoteChoiceQuestion(3))
    }

    // ── 入力欄の編集の状態（→ features/margin_quote.md §5.6）──────────────────────

    /** 書きかけの文字と同じなら、持っているカーソルと選択をそのまま使う（面が外れて組み直されても保つ）。 */
    @Test
    fun `書きかけの文字と同じならカーソルと選択をそのまま使う`() {
        val held = TextFieldValue("いま思ったこと", TextRange(3, 5))

        assertEquals(held, memoFieldValueFor(held, "いま思ったこと"))
    }

    /** 文字が違えば（受け取れて空になった・ノートを替えた）末尾にカーソルを置き直し、前の選択を混ぜない。 */
    @Test
    fun `書きかけの文字が違えば末尾にカーソルを置き直す`() {
        val held = TextFieldValue("前の書きかけ", TextRange(1, 3))

        assertEquals(TextFieldValue("別の書きかけです", TextRange(8)), memoFieldValueFor(held, "別の書きかけです"))
        assertEquals(TextFieldValue("", TextRange(0)), memoFieldValueFor(held, ""))
    }

    /**
     * **描き直しの前に続けて届く編集を落とさない。** 変換中の「abc」を「abcd」で確定して1文字消すと、戻した「abc」は描いた時点の文字と同じだが、
     * 直前に反映した「abcd」とは違うので書きかけへ渡す。比べる相手は直前に反映した値。
     */
    @Test
    fun `描き直しの前に足して戻しても最後の編集を書きかけへ渡す`() {
        val drawn = TextFieldValue("abc", TextRange(3), composition = TextRange(0, 3))
        val added = TextFieldValue("abcd", TextRange(4))
        val removed = TextFieldValue("abc", TextRange(3))

        assertEquals("abcd", memoFieldEdit(drawn, added))
        assertEquals("abc", memoFieldEdit(added, removed))
    }

    @Test
    fun `カーソルと選択だけの変更は書きかけへ渡さない`() {
        assertEquals(null, memoFieldEdit(TextFieldValue("abc", TextRange(3)), TextFieldValue("abc", TextRange(1, 2))))
    }

    /** 受け取れて空になった後に、前と同じ文字を新しく入れても渡す（持っている値は描くときに空へ合わせてある）。 */
    @Test
    fun `空へ合わせた後に前と同じ文字を入れても渡す`() {
        val synced = memoFieldValueFor(TextFieldValue("abc", TextRange(3)), "")

        assertEquals("abc", memoFieldEdit(synced, TextFieldValue("abc", TextRange(3))))
    }
}
