package com.example.newproject.domain

import com.example.newproject.model.ReadingTraceLimits
import com.example.newproject.model.ReadingVisit
import com.example.newproject.model.SectionRef
import com.example.newproject.model.state.PreviousVisit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 前回の読書の跡（→ features/margin_pane.md §5.7・§10）。
 *
 * 見る訪問は今の読書より前の最新の1件だけで、遡らない。出すのは見出しが一意に一致した節だけ。
 */
class PreviousReadingTest {

    private val index = HeadingIndex(listOf("導入", "まとめ", "本論", "まとめ"))

    private fun visit(at: Long, section: String?, percent: Int) = ReadingVisit(at, section, percent)

    private fun midway(section: String?) = PreviousVisit(section, atEpochMillis = 1_000L, readToEnd = false)

    // ── 選び方 ─────────────────────────────────────────────────────────────

    @Test
    fun `訪問が無ければ前回は無い`() {
        assertNull(previousVisitOf(emptyList(), readingStartedAtMillis = 10_000L))
    }

    @Test
    fun `今の読書より前の最新の1件を選ぶ`() {
        val previous = previousVisitOf(
            listOf(visit(1_000L, "導入", 20), visit(3_000L, "本論", 60), visit(2_000L, "まとめ", 40)),
            readingStartedAtMillis = 10_000L
        )

        assertEquals(PreviousVisit("本論", 3_000L, readToEnd = false), previous)
    }

    /** 背面へ回すたびに今の読書の訪問が書かれる。**それを前回に数えると、自分の今の位置を「前回」と出す。** */
    @Test
    fun `今の読書の訪問は数えない`() {
        val previous = previousVisitOf(
            listOf(visit(1_000L, "導入", 20), visit(10_000L, "本論", 60), visit(12_000L, "まとめ", 90)),
            readingStartedAtMillis = 10_000L
        )

        assertEquals(PreviousVisit("導入", 1_000L, readToEnd = false), previous)
    }

    /** **遡らない。** 最新が最後まで読んだ回なら、その前の読みかけを持ち出さない。 */
    @Test
    fun `最新が最後まで読んだ回なら、その前の読みかけは選ばない`() {
        val previous = previousVisitOf(
            listOf(visit(1_000L, "導入", 40), visit(2_000L, "本論", 100)),
            readingStartedAtMillis = 10_000L
        )

        assertEquals(PreviousVisit("本論", 2_000L, readToEnd = true), previous)
        assertFalse("最後まで読んだ回を出した", showsPreviousReading(previous, index, SectionRef("本論")))
        assertFalse("その前の読みかけまで遡った", showsPreviousReading(previous, index, SectionRef("導入")))
    }

    // ── 出す節 ─────────────────────────────────────────────────────────────

    @Test
    fun `見出しが一意に一致した節にだけ出す`() {
        assertTrue(showsPreviousReading(midway("本論"), index, SectionRef("本論")))
        assertFalse("ほかの節", showsPreviousReading(midway("本論"), index, SectionRef("導入")))
    }

    /** どの節か言い切れないときに出すと、読んでいない節を指しうる。 */
    @Test
    fun `同名の見出しが複数あればどの候補にも出さない`() {
        assertFalse(showsPreviousReading(midway("まとめ"), index, SectionRef("まとめ", 0)))
        assertFalse(showsPreviousReading(midway("まとめ"), index, SectionRef("まとめ", 1)))
    }

    @Test
    fun `見出しが見つからない・見出しより前・見出しの無いノートでは出さない`() {
        assertFalse("改名・削除", showsPreviousReading(midway("消えた見出し"), index, SectionRef("本論")))
        assertFalse("見出しより前", showsPreviousReading(midway(null), index, SectionRef(title = null)))
        assertFalse(
            "見出しの無いノート",
            showsPreviousReading(midway(null), HeadingIndex(emptyList()), SectionRef(title = null))
        )
    }

    @Test
    fun `前回が無いか本文の節がまだ決まっていなければ出さない`() {
        assertFalse(showsPreviousReading(null, index, SectionRef("本論")))
        assertFalse(showsPreviousReading(midway("本論"), index, current = null))
    }

    /** 訪問の見出し名は保存の前に上限で切ってある。**索引のキーも同じ整形で作るので、保存値のまま引ける。** */
    @Test
    fun `長い見出しの節にも出す`() {
        val long = "長".repeat(ReadingTraceLimits.MAX_SECTION_TITLE_BYTES)
        val recorded = composeMemoSectionTitle(long)

        assertTrue(showsPreviousReading(midway(recorded), HeadingIndex(listOf("導入", long)), SectionRef(long)))
    }

    /**
     * **保存値に整形をかけ直さない。** 切り口の直前が空白だと保存値の末尾に空白が残り、かけ直すと消えて索引とずれる。
     * 切り口と同じ短い見出しを併設し、そちらへ当たらないことも見る。
     */
    @Test
    fun `切り口の直前が空白の長い見出しでも、元の節にだけ出す`() {
        val prefix = "あ".repeat(ReadingTraceLimits.MAX_SECTION_TITLE_BYTES / 3)
        val long = "$prefix 尾"
        val recorded = requireNotNull(composeMemoSectionTitle(long))
        assertTrue("保存値の末尾に空白が残る前提が崩れた", recorded.endsWith(" "))
        val index = HeadingIndex(listOf("導入", long, prefix))

        assertTrue("元の節に出ない", showsPreviousReading(midway(recorded), index, SectionRef(long)))
        assertFalse("切り口と同じ短い見出しへ出た", showsPreviousReading(midway(recorded), index, SectionRef(prefix)))
    }

    /** 整形の前に記録した訪問は、生の見出し名を持っている。 */
    @Test
    fun `生のまま記録した見出し名でも同じ節に出す`() {
        val raw = "本\u0007論"

        assertTrue(showsPreviousReading(midway(raw), HeadingIndex(listOf("導入", raw)), SectionRef(raw)))
    }
}
