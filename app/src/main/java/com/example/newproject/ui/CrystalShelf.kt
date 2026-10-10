package com.example.newproject.ui

import com.example.newproject.model.Crystal
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

// ---------------------------------------------------------------------------
// 結晶の一覧の棚の並べ方（→ docs/dev/features/reflect_crystal.md 判断11）。
// 月ごとの棚に宝石を新しい順に並べ、開いた宝石の段の直下にだけ中身を置く。
// ---------------------------------------------------------------------------

/** 棚の1項目。一覧の1行になる。[key] は項目の鍵で、一覧の中で重ならない。 */
internal sealed interface CrystalShelfItem {
    val key: String

    /** 月の見出し。その月の棚の上端になる（見出しは棚の中に置く）。[count] はその月の結晶の数。 */
    data class Month(val month: YearMonth, val count: Int) : CrystalShelfItem {
        override val key: String get() = "month-$month"
    }

    /** 宝石の段。[bottom] は、その月の棚の下端にあるか（角を丸める側）。 */
    data class Row(val crystals: List<Crystal>, val bottom: Boolean) : CrystalShelfItem {
        override val key: String get() = "row-${crystals.first().fileName}"
    }

    /** 開いた結晶の中身。開いた宝石の段の直下に置く。[column] は宝石がその段の何番目か。 */
    data class Detail(val crystal: Crystal, val column: Int, val bottom: Boolean) : CrystalShelfItem {
        override val key: String get() = "detail-${crystal.fileName}"
    }
}

/**
 * 結晶を月ごとの棚に並べる。月も宝石も新しい順で、1段は [columns] 個。月は [zone] の暦で分ける。
 * [opened] の結晶（ファイル名）があれば、その宝石の段の直下に中身を置く。無ければ（別の Vault へ移った等）何も開かない。
 * **月をまたいで段を詰めない** — 棚の区切りが月なので、月の終わりの段は欠けたままにする。
 */
internal fun crystalShelf(crystals: List<Crystal>, columns: Int, opened: String?, zone: ZoneId): List<CrystalShelfItem> {
    require(columns >= 1) { "1段に宝石が1つも入らない: $columns" }
    val byMonth = crystals
        .sortedByDescending { it.createdAt }
        .groupBy { YearMonth.from(Instant.ofEpochMilli(it.createdAt).atZone(zone)) }
    return buildList {
        byMonth.forEach { (month, inMonth) ->
            add(CrystalShelfItem.Month(month, inMonth.size))
            val rows = inMonth.chunked(columns)
            rows.forEachIndexed { i, row ->
                val last = i == rows.lastIndex
                val openedColumn = row.indexOfFirst { it.fileName == opened }
                // 開いた段の下端は中身のほうなので、段は下端にならない。
                add(CrystalShelfItem.Row(row, bottom = last && openedColumn < 0))
                if (openedColumn >= 0) add(CrystalShelfItem.Detail(row[openedColumn], openedColumn, bottom = last))
            }
        }
    }
}

/** 宝石 [pressed]（ファイル名）を押した後に開いている結晶。開いていた宝石なら閉じ、別の宝石なら替える。 */
internal fun crystalShelfToggle(opened: String?, pressed: String): String? = if (opened == pressed) null else pressed

/** 宝石1つの枠の幅。宝石と日の文字に、押せる幅 48dp を含めて左右の余白を足した値。 */
internal const val CRYSTAL_GEM_CELL_DP = 76f

/** 棚の中の幅 [widthDp] に入る宝石の数。少なくとも1つ。 */
internal fun crystalShelfColumns(widthDp: Float): Int = maxOf(1, (widthDp / CRYSTAL_GEM_CELL_DP).toInt())
