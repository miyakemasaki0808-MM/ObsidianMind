package com.example.newproject.domain

import com.example.newproject.model.MarginMemo
import com.example.newproject.model.ReadingTraceLimits
import com.example.newproject.model.SectionRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * メモを今の見出しと照合して面に並べる（→ features/margin_pane.md §5.6・§10）。
 *
 * **メモの件数が失われず、印と飛ぶ先が一致する**ことを、長い見出し・整形後に同じになる見出し・同名・
 * 改名と削除・見出し無し・冒頭で確かめる。
 */
class MarginMemoSectionsTest {

    private val headings = listOf("導入", "まとめ", "本論", "まとめ")
    private val index = HeadingIndex(headings)

    private fun matchMemoSection(sectionTitle: String?, headings: List<String>) = HeadingIndex(headings).match(sectionTitle)

    private fun memo(text: String, section: String?, at: Long = 1L) =
        MarginMemo(text, at, composeMemoSectionTitle(section))

    // ── 照合 ───────────────────────────────────────────────────────────────

    @Test
    fun `見出しを持たないメモは冒頭`() {
        assertEquals(MemoSectionMatch.Opening, matchMemoSection(null, headings))
    }

    @Test
    fun `一意に一致すればその節`() {
        assertEquals(MemoSectionMatch.Unique(SectionRef("本論")), matchMemoSection("本論", headings))
    }

    /** **同名を黙って先頭へ決めない。** 候補を全部返す。 */
    @Test
    fun `同名の見出しは候補を全部返す`() {
        assertEquals(
            MemoSectionMatch.Shared(listOf(SectionRef("まとめ", 0), SectionRef("まとめ", 1))),
            matchMemoSection("まとめ", headings)
        )
    }

    @Test
    fun `改名や削除で見つからなければ見つからない`() {
        assertEquals(MemoSectionMatch.Missing, matchMemoSection("消えた見出し", headings))
        assertEquals("見出しの無いノート", MemoSectionMatch.Missing, matchMemoSection("導入", emptyList()))
    }

    /** **保存時と同じ整形をかけてから比べる。** 生のまま比べると、長い見出しのメモが全部見つからない。 */
    @Test
    fun `長い見出しは保存時と同じく切ってから照合する`() {
        val long = "長".repeat(ReadingTraceLimits.MAX_SECTION_TITLE_BYTES)
        val saved = composeMemoSectionTitle(long)

        assertEquals(MemoSectionMatch.Unique(SectionRef(long)), matchMemoSection(saved, listOf(long)))
    }

    /** 制御文字や前後の空白だけが違う見出しは、整えると同じになる。どちらにも出す。 */
    @Test
    fun `整えると同じになる見出しは同名として扱う`() {
        val match = matchMemoSection("要点", listOf("要点", " 要点\u0007"))

        assertEquals(MemoSectionMatch.Shared(listOf(SectionRef("要点"), SectionRef(" 要点\u0007"))), match)
    }

    // ── 並べる ───────────────────────────────────────────────────────────────

    @Test
    fun `今の節のメモとほかの節のメモに分け、ほかは本文の順に並べる`() {
        val memos = listOf(
            memo("本論のメモ", "本論", at = 5L),
            memo("冒頭のメモ", null, at = 4L),
            memo("導入のメモ", "導入", at = 3L),
            memo("導入の古いメモ", "導入", at = 1L)
        )

        val arranged = arrangeMemos(memos, index, current = SectionRef("導入"))

        assertEquals(listOf("導入のメモ", "導入の古いメモ"), arranged.current.map { it.memo.text })
        assertEquals(listOf(SectionRef(null), SectionRef("本論")), arranged.others.map { it.section })
        assertEquals(2, arranged.otherCount)
    }

    /** **同名に共通のメモはどの候補にも出すが、畳んだ件数では1件と数える。** */
    @Test
    fun `同名に共通のメモはどの候補にも出し、件数は1件と数える`() {
        val arranged = arrangeMemos(listOf(memo("まとめのメモ", "まとめ")), index, current = SectionRef("導入"))

        assertEquals(listOf(SectionRef("まとめ", 0), SectionRef("まとめ", 1)), arranged.others.map { it.section })
        assertEquals(1, arranged.otherCount)
        assertEquals(1, arranged.countsBySection[SectionRef("まとめ", 0)])
        assertEquals(1, arranged.countsBySection[SectionRef("まとめ", 1)])
    }

    @Test
    fun `今の節が同名の候補ならこの節にも出し、ほかの候補にも出す`() {
        val arranged = arrangeMemos(listOf(memo("まとめのメモ", "まとめ")), index, current = SectionRef("まとめ", 1))

        assertEquals(listOf("まとめのメモ"), arranged.current.map { it.memo.text })
        assertEquals(listOf(SectionRef("まとめ", 0)), arranged.others.map { it.section })
        assertEquals("今の節にあるメモを、ほかの節の件数に数えた", 0, arranged.otherCount)
    }

    @Test
    fun `見出しが見つからないメモは最後の組にまとめ、印は付けない`() {
        val arranged = arrangeMemos(
            listOf(memo("消えた節のメモ", "消えた見出し"), memo("本論のメモ", "本論")),
            index,
            current = SectionRef("導入")
        )

        assertEquals(MemoGroup(null, listOf(PlacedMemo(memo("消えた節のメモ", "消えた見出し"), MemoSectionMatch.Missing))), arranged.others.last())
        assertEquals(mapOf(SectionRef("本論") to 1), arranged.countsBySection)
    }

    /** どう並べても**メモを1件も落とさない。** 今の節とほかの節の件数を足すと全件になる。 */
    @Test
    fun `どの節を開いていてもメモを落とさない`() {
        val memos = listOf(
            memo("冒頭", null, 1L), memo("導入", "導入", 2L), memo("まとめ", "まとめ", 3L),
            memo("本論", "本論", 4L), memo("消えた", "消えた見出し", 5L)
        )
        val sections = listOf(SectionRef(null), SectionRef("導入"), SectionRef("まとめ", 0), SectionRef("本論"), SectionRef("まとめ", 1))

        sections.forEach { current ->
            val arranged = arrangeMemos(memos, index, current)
            val shown = (arranged.current + arranged.others.flatMap { it.memos }).map { it.memo }.toSet()
            assertEquals("$current で落とした", memos.toSet(), shown)
            assertEquals("$current で件数が合わない", memos.size, arranged.current.size + arranged.otherCount)
        }
    }

    @Test
    fun `見出しの無いノートでは見出しを持たないメモが全体の節に出る`() {
        val arranged = arrangeMemos(listOf(memo("全体のメモ", null)), HeadingIndex(emptyList()), current = SectionRef(null))

        assertEquals(listOf("全体のメモ"), arranged.current.map { it.memo.text })
        assertTrue(arranged.others.isEmpty())
        assertTrue(arranged.countsBySection.isEmpty())
    }

    // ── 見出しの多いノート（→ lessons L13・L52）──────────────────────────────────

    /**
     * **見出しの数に比例する仕事は索引を作るときの1回だけ**で、並べ直すたびには見出しを読まない。
     * 照合のたびに見出しを数え直していたころは、見出しを倍にすると読む回数が4倍になり、Main が秒単位で止まった。
     */
    @Test
    fun `索引は見出しの数に比例して作り、並べるときは見出しを読まない`() {
        fun build(count: Int): Pair<CountingList, HeadingIndex> {
            val headings = CountingList((0 until count).map { if (it % 2 == 0) "まとめ" else "節$it" })
            return headings to HeadingIndex(headings)
        }
        val (small, _) = build(1_024)
        val (large, index) = build(2_048)
        assertTrue("見出しを倍にして読む回数が ${large.reads}/${small.reads} 倍になった", large.reads <= small.reads * 5 / 2)

        large.reads = 0
        val memos = (1..20).map { memo("メモ$it", if (it % 2 == 0) "まとめ" else "節${it * 2 - 1}", at = it.toLong()) }
        listOf(emptyList(), memos).forEach { input ->
            listOf(SectionRef(null), SectionRef("まとめ", 3), SectionRef("節7")).forEach { current ->
                arrangeMemos(input, index, current)
            }
        }
        assertEquals("並べるたびに見出しを読み直した", 0, large.reads)
    }

    /** 読んだ回数を数える見出しの並び。 */
    private class CountingList(private val inner: List<String>) : AbstractList<String>() {
        var reads = 0
        override val size: Int get() = inner.size
        override fun get(index: Int): String {
            reads++
            return inner[index]
        }
    }
}
