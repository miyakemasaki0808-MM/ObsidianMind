package com.example.newproject.domain

import com.example.newproject.domain.markdown.buildNoteSectionModel
import com.example.newproject.model.MarginMemo
import com.example.newproject.model.state.MarginMemoDraft
import com.example.newproject.model.state.DraftQuote
import com.example.newproject.model.ReadingVisit
import com.example.newproject.model.ReadingTrace
import com.example.newproject.data.ReadingTraceReadResult
import com.example.newproject.data.ReadingTraceJson
import com.example.newproject.model.MemoPassage
import com.example.newproject.model.QuoteContext
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

    /**
     * **保存値に整形をかけ直さない。** 切り口の直前が空白だと保存値の末尾に空白が残り、かけ直すと消える。
     * 切り口と同じ短い見出しを併設しても、メモは元の長い見出しの節に出る。
     */
    @Test
    fun `切り口の直前が空白の長い見出しのメモは、切り口と同じ短い見出しへ取り違えない`() {
        val prefix = "あ".repeat(ReadingTraceLimits.MAX_SECTION_TITLE_BYTES / 3)
        val long = "$prefix 尾"
        val saved = requireNotNull(composeMemoSectionTitle(long))
        assertTrue(saved.endsWith(" "))

        assertEquals(MemoSectionMatch.Unique(SectionRef(long)), matchMemoSection(saved, listOf(long, prefix)))
    }

    /** 整える前に生のまま記録した見出し名（制御文字を含む）だけは、整えてから照合する。 */
    @Test
    fun `制御文字を含む生の見出し名は整えてから照合する`() {
        assertEquals(MemoSectionMatch.Unique(SectionRef("本\u0007論")), matchMemoSection("本\u0007論", listOf("本\u0007論")))
        assertEquals("制御文字だけの見出し名", MemoSectionMatch.Missing, matchMemoSection("\u0007", listOf("本論")))
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

    /**
     * **今の節と同名のほかの候補に共通のメモだけでも、ほかの候補を選べる。** どちらの節にいても他方の組が出て、件数は1件。
     * 「今の節に無いメモ」で数えていたころは0件になり、候補の組があるのに開く口が消えた。
     */
    @Test
    fun `今の節が同名の候補なら、この節にも出し、他方の候補も組として出して1件と数える`() {
        listOf(SectionRef("まとめ", 0) to SectionRef("まとめ", 1), SectionRef("まとめ", 1) to SectionRef("まとめ", 0))
            .forEach { (current, other) ->
                val arranged = arrangeMemos(listOf(memo("まとめのメモ", "まとめ")), index, current)

                assertEquals(listOf("まとめのメモ"), arranged.current.map { it.memo.text })
                assertEquals("$current から他方の候補が出ない", listOf(other), arranged.others.map { it.section })
                assertEquals(1, arranged.otherCount)
            }
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
            assertEquals(
                "$current でほかの節の件数が、並ぶメモの数と合わない",
                arranged.others.flatMap { group -> group.memos.map { it.memo } }.toSet().size,
                arranged.otherCount
            )
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

    // ── 引用と控えた位置で節を決める（→ features/margin_quote.md §5.5）──────────────

    private val sameNameBody = "## まとめ\n\n結論として、この方法は有効である。ただし条件Aでは使わない。\n\n" +
        "## まとめ\n\n結論として、この方法は有効である。ただし条件Bでは使わない。"

    private fun arrangeWithPassages(body: String, memos: List<MarginMemo>, current: SectionRef): ArrangedMemos {
        val model = buildNoteSectionModel(body)
        val passages = buildPassageIndex(model)
        return arrangeMemos(memos, model.headingIndex, current, locateMemos(memos, passages, model.headingIndex))
    }

    /** **同名の見出しでも、前後とも合う引用は1つの節に決まる。** 見出しの照合だけなら両方の候補に出る。 */
    @Test
    fun `確定した引用は同名の見出しでも1つの節に並ぶ`() {
        val quoted = MarginMemo(
            "条件Bについて", 1L, "まとめ",
            MemoPassage.Quoted("この方法は有効である。", QuoteContext("結論として、", "ただし条件Bでは使わな"), truncated = false)
        )

        val arranged = arrangeWithPassages(sameNameBody, listOf(quoted), current = SectionRef("まとめ", ordinal = 1))

        assertEquals(listOf(quoted), arranged.current.map { it.memo })
        assertTrue("先の同名の節にも出ている", arranged.others.isEmpty())
        assertEquals(mapOf(SectionRef("まとめ", ordinal = 1) to 1), arranged.countsBySection)
    }

    /** **特定できない引用は候補の節のどれにも出し、一意の節として数えない。** */
    @Test
    fun `特定できない引用は候補の節のどれにも並ぶ`() {
        // 控えた見出しは本文に無い。見出しの照合だけなら「見出しが見つからないメモ」になる。
        val unresolved = MarginMemo("どちらか", 1L, "消えた見出し", MemoPassage.Quoted("この方法は有効である。", context = null, truncated = false))

        val arranged = arrangeWithPassages(sameNameBody, listOf(unresolved), current = SectionRef("まとめ"))

        assertEquals(listOf(unresolved), arranged.current.map { it.memo })
        assertEquals(listOf(SectionRef("まとめ", ordinal = 1)), arranged.others.map { it.section })
        assertEquals(mapOf(SectionRef("まとめ") to 1, SectionRef("まとめ", ordinal = 1) to 1), arranged.countsBySection)
    }

    /** 見出しを改名しても、引用が前後とも合えばその段落の節に並び、「見出しが見つからないメモ」にならない。 */
    @Test
    fun `見出しを改名しても確定した引用は段落の節に並ぶ`() {
        val quoted = MarginMemo(
            "改名の前に書いた", 1L, "元の見出し",
            MemoPassage.Quoted("この方法は有効である。", QuoteContext("結論として、", "ただし条件Aでは使わな"), truncated = false)
        )
        val body = "## 新しい見出し\n\n結論として、この方法は有効である。ただし条件Aでは使わない。"

        val arranged = arrangeWithPassages(body, listOf(quoted), current = SectionRef(title = null))

        assertEquals(listOf(SectionRef("新しい見出し")), arranged.others.map { it.section })
    }

    /** 控えた位置が特定できなければ、今どおり見出しの照合で並べる。 */
    @Test
    fun `特定できない控えた位置は見出しの照合で並ぶ`() {
        val body = "## 導入\n\n同じ書き出し\n\n## 本論\n\n同じ書き出し"
        val seen = MarginMemo("どこかで書いた", 1L, "導入", MemoPassage.Seen("同じ書き出し"))
        val missingHeading = MarginMemo("見出しが消えた", 2L, "消えた見出し", MemoPassage.Seen("同じ書き出し"))

        val arranged = arrangeWithPassages(body, listOf(seen, missingHeading), current = SectionRef("導入"))

        assertEquals(listOf(seen), arranged.current.map { it.memo })
        assertEquals(listOf<SectionRef?>(null), arranged.others.map { it.section })
    }

    /** 照らし直しの結果を渡さなければ、v7 までと同じ並べ方になる。 */
    @Test
    fun `照らし直しが無ければ見出しの照合だけで並ぶ`() {
        val quoted = MarginMemo("条件B", 1L, "まとめ", MemoPassage.Quoted("この方法は有効である。", QuoteContext("結論として、", "ただし条件Bでは使わな"), truncated = false))
        val model = buildNoteSectionModel(sameNameBody)

        val arranged = arrangeMemos(listOf(quoted), model.headingIndex, SectionRef("まとめ"))

        assertEquals(listOf(SectionRef("まとめ", ordinal = 1)), arranged.others.map { it.section })
        assertEquals(listOf(quoted), arranged.current.map { it.memo })
    }

    // ── 並べる処理の仕事はメモの数だけ（→ features/margin_quote.md §5.5）─────────────

    /** 読んだ回数を数える列（見出しの [CountingList] と同じ形）。`hashCode` と `equals` も要素を読むので、隠れた走査もここで数える。 */
    private class CountingReads<T>(private val inner: List<T>) : AbstractList<T>() {
        var reads = 0
        override val size: Int get() = inner.size
        override fun get(index: Int): T {
            reads++
            return inner[index]
        }
    }

    /**
     * **事前計算の後の並べる処理は、候補の列とブロックごとの節の列を読まない。** 候補を1,000から10,000へ増やしても0回のまま。
     * 時間ではなく読み取りの回数で見る — 走査は明示のループだけでなく、地図の鍵のハッシュや等価比較にも隠れる。
     */
    @Test
    fun `事前計算の後の並べる処理は候補の数に比例する読み取りをしない`() {
        listOf(1_000, 10_000).forEach { blocks ->
            val section = SectionRef("まとめ")
            val sections = CountingReads(List(blocks) { section })
            val passages = PassageIndex(texts = List(blocks) { "Q" }, sections = sections, readable = List(blocks) { true })
            val candidateLists = List(20) { CountingReads(List(blocks) { it }) }
            val memos = List(20) { k ->
                MarginMemo("メモ$k", k.toLong(), "まとめ", MemoPassage.Quoted("Q", context = null, truncated = false))
            }
            val locations = MemoLocations(
                byMemo = memos.zip(candidateLists).associate { (memo, candidates) -> memo to PassageLocation.Uncertain(candidates) },
                passages = passages
            )
            sections.reads = 0
            candidateLists.forEach { it.reads = 0 }

            val arranged = arrangeMemos(memos, HeadingIndex(listOf("まとめ")), current = section, locations = locations)

            assertEquals("候補 $blocks 件でブロックごとの節の列を読んだ", 0, sections.reads)
            assertEquals("候補 $blocks 件で候補の列を読んだ", 0, candidateLists.sumOf { it.reads })
            assertEquals(20, arranged.current.size)
            assertEquals(mapOf(section to 20), arranged.countsBySection)
        }
    }

    /** **同じ一節を引いたメモが多くても、本文を鍵で探すのは鍵につき1度だけ**（候補の列もメモの間で共有する）。 */
    @Test
    fun `同じ鍵のメモをまとめて照らしても本文を探すのは1度だけ`() {
        val blocks = 1_000
        val texts = CountingReads(List(blocks) { "Q" })
        val passages = PassageIndex(texts = texts, sections = List(blocks) { SectionRef(title = null) }, readable = List(blocks) { true })
        val memos = List(20) { k -> MarginMemo("メモ$k", k.toLong(), null, MemoPassage.Quoted("Q", context = null, truncated = false)) }
        texts.reads = 0

        locateMemos(memos, passages, HeadingIndex(emptyList()))

        assertEquals(blocks, texts.reads)
    }

    /** 候補の節は、事前計算のときに同じ節を1つにまとめてある。 */
    @Test
    fun `特定できない引用の候補が同じ節に多くても1つの節に1度だけ並ぶ`() {
        val body = (1..50).joinToString("\n\n") { "同じ一節" }
        val memo = MarginMemo("どれか", 1L, null, MemoPassage.Quoted("同じ一節", context = null, truncated = false))

        val arranged = arrangeWithPassages(body, listOf(memo), current = SectionRef(title = null))

        assertEquals(listOf(memo), arranged.current.map { it.memo })
        assertTrue(arranged.others.isEmpty())
    }

    // ── 書き込み先と所属する節が食い違わない（→ features/margin_quote.md §5.3）──────────

    private val twoSections = "## A\n\nAで読んでいた段落\n\n## B\n\nBから引く一節"

    /** 置いたメモを、痕跡の JSON に通して読み戻す（保存して開き直したのと同じ値にする）。 */
    private fun throughJson(memo: MarginMemo): MarginMemo {
        val trace = ReadingTrace(
            vaultRelativePath = "note.md", noteTitle = "ノート", documentId = null,
            visits = listOf(ReadingVisit(1L, null, 10)), memos = listOf(memo)
        )
        val decoded = ReadingTraceJson.decode(ReadingTraceJson.encode(trace)) as ReadingTraceReadResult.Valid
        return decoded.trace.memos.single()
    }

    private fun placeAndArrange(draft: MarginMemoDraft, current: SectionRef): Pair<MarginMemo, ArrangedMemos> {
        val model = buildNoteSectionModel(twoSections)
        val passages = buildPassageIndex(model)
        val submission = submissionOf(draft.text, draft.target?.title, 1_000L, draft.passage, target = draft.target)!!
        val memo = throughJson(submission.memo)
        return memo to arrangeMemos(listOf(memo), model.headingIndex, current, locateMemos(listOf(memo), passages, model.headingIndex))
    }

    /**
     * **Aで書き始めて、Bから引用し、引用を外して置いたメモは、Bの組に残る。** 古い控え（Aの段落）が書き込み先を取り消さない。
     * 保存・読み直し・並べ直しまでつないで確かめる（操作の直後だけでは通ってしまう）。
     */
    @Test
    fun `Aで書き始めBから引用して外したメモは保存して並べ直してもBに残る`() {
        val passages = buildPassageIndex(buildNoteSectionModel(twoSections))
        val source = quoteSourceFor("Bから引く一節", selectedBlock = 3, index = passages) as QuoteSource.Resolved
        val draft = MarginMemoDraft()
            .edited("書いていた", passages.sections[1], seenPassageAt(0, passages))
            .quoted(DraftQuote(MemoPassage.Quoted("Bから引く一節", source.context, truncated = false)), passages.sections[source.block])
            .unquoted()

        val (memo, arranged) = placeAndArrange(draft, current = SectionRef("B"))

        assertEquals("B", memo.sectionTitle)
        assertEquals(listOf(memo), arranged.current.map { it.memo })
        assertEquals(mapOf(SectionRef("B") to 1), arranged.countsBySection)
    }

    @Test
    fun `引用を付けずに置いたメモは元の控えのまま元の段落の節に並ぶ`() {
        val passages = buildPassageIndex(buildNoteSectionModel(twoSections))
        val draft = MarginMemoDraft().edited("Aについて", passages.sections[1], seenPassageAt(0, passages))

        val (memo, arranged) = placeAndArrange(draft, current = SectionRef("A"))

        assertEquals(MemoPassage.Seen("Aで読んでいた段落"), memo.passage)
        assertEquals(listOf(memo), arranged.current.map { it.memo })
    }
}
