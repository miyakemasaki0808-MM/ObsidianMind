package com.example.newproject.ui

import com.example.newproject.model.Crystal
import com.example.newproject.model.CrystalSource
import com.example.newproject.ui.CrystalShelfItem.Detail
import com.example.newproject.ui.CrystalShelfItem.Month
import com.example.newproject.ui.CrystalShelfItem.Row
import java.time.OffsetDateTime
import java.time.YearMonth
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// 結晶の一覧の棚の並べ方（→ features/reflect_crystal.md 判断11）。
class CrystalShelfTest {

    private val tokyo = ZoneOffset.ofHours(9)

    /** 月も宝石も新しい順。月の見出しには、その月の数を添える。 */
    @Test
    fun `月ごとの棚に新しい順に並べる`() {
        val shelf = crystalShelf(listOf(c("9-29", "2026-09-29"), c("10-07", "2026-10-07"), c("10-01", "2026-10-01")), columns = 4, opened = null, zone = tokyo)

        assertEquals(
            listOf(
                Month(YearMonth.of(2026, 10), 2),
                Row(listOf(c("10-07", "2026-10-07"), c("10-01", "2026-10-01")), bottom = true),
                Month(YearMonth.of(2026, 9), 1),
                Row(listOf(c("9-29", "2026-09-29")), bottom = true)
            ),
            shelf
        )
    }

    /** 月は端末の暦で分ける。UTC の月末の夜は、日本では翌月の朝。 */
    @Test
    fun `月は端末の暦で分ける`() {
        val lateNightUtc = Crystal(OffsetDateTime.parse("2026-09-30T20:00:00Z").toInstant().toEpochMilli(), "筋。", SOURCES, "x.json")
        assertEquals(Month(YearMonth.of(2026, 10), 1), crystalShelf(listOf(lateNightUtc), 4, null, tokyo).first())
        assertEquals(Month(YearMonth.of(2026, 9), 1), crystalShelf(listOf(lateNightUtc), 4, null, ZoneOffset.UTC).first())
    }

    /** 1段は [columns] 個で、月をまたいで詰めない。月の最後の段だけが棚の下端。 */
    @Test
    fun `1段は決めた数で割り、月をまたいで詰めない`() {
        val october = (1..5).map { c("10-$it", "2026-10-%02d".format(10 - it)) }
        val september = listOf(c("9-1", "2026-09-20"))
        val rows = crystalShelf(october + september, columns = 2, opened = null, zone = tokyo).filterIsInstance<Row>()

        assertEquals(listOf(2, 2, 1, 1), rows.map { it.crystals.size })
        assertEquals(listOf(false, false, true, true), rows.map { it.bottom })
    }

    /** 開いた宝石の段の直下にだけ中身を置く。中身は段の下端を引き取る。 */
    @Test
    fun `開いた宝石の段の直下にだけ中身を置く`() {
        val october = (1..5).map { c("10-$it", "2026-10-%02d".format(10 - it)) }
        val middle = crystalShelf(october, columns = 2, opened = "10-4.json", zone = tokyo)
        val detailAt = middle.indexOfFirst { it is Detail }
        assertEquals(Detail(october[3], column = 1, bottom = false), middle[detailAt])
        assertEquals(Row(october.subList(2, 4), bottom = false), middle[detailAt - 1])
        assertEquals(1, middle.count { it is Detail })

        val last = crystalShelf(october, columns = 2, opened = "10-5.json", zone = tokyo)
        assertEquals(Row(october.subList(4, 5), bottom = false), last[last.lastIndex - 1])
        assertEquals(Detail(october[4], column = 0, bottom = true), last.last())
    }

    @Test
    fun `開いた結晶が一覧に無ければ何も開かない`() {
        val shelf = crystalShelf(listOf(c("10-1", "2026-10-01")), 4, opened = "別のVault.json", zone = tokyo)
        assertTrue(shelf.none { it is Detail })
        assertEquals(Row(listOf(c("10-1", "2026-10-01")), bottom = true), shelf.last())
    }

    /** 鍵は一覧の中で重ならない（一覧の項目の鍵に使う）。 */
    @Test
    fun `項目の鍵は重ならない`() {
        val crystals = (1..9).map { c("k$it", "2026-%02d-01".format(it)) }
        val shelf = crystalShelf(crystals, columns = 1, opened = "k3.json", zone = tokyo)
        assertEquals(shelf.size, shelf.map { it.key }.toSet().size)
    }

    /** スマホの棚に4つ、開いた Fold の棚に9つ。狭くても1つは入れる。 */
    @Test
    fun `1段の数は棚の幅で決め、少なくとも1つ`() {
        assertEquals(4, crystalShelfColumns(356f))
        assertEquals(9, crystalShelfColumns(724f))
        assertEquals(1, crystalShelfColumns(40f))
    }

    /** 開いていた宝石を押すと閉じ、別の宝石を押すと替わる。開くのは1つだけ。 */
    @Test
    fun `同じ宝石を押すと閉じ、別の宝石を押すと替わる`() {
        assertEquals("a.json", crystalShelfToggle(null, "a.json"))
        assertEquals(null, crystalShelfToggle("a.json", "a.json"))
        assertEquals("b.json", crystalShelfToggle("a.json", "b.json"))
    }

    private fun c(name: String, date: String): Crystal = Crystal(
        createdAt = OffsetDateTime.parse("${date}T12:00:00+09:00").toInstant().toEpochMilli(),
        sentence = "共通する筋。",
        sources = SOURCES,
        fileName = "$name.json"
    )

    private companion object {
        val SOURCES = listOf(CrystalSource("a.md", "a", "断片"), CrystalSource("b.md", "b", "断片"))
    }
}
