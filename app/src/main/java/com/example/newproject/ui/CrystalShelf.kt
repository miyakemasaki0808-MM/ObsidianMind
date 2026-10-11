package com.example.newproject.ui

import com.example.newproject.model.Crystal
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

// ---------------------------------------------------------------------------
// 結晶の一覧の棚の並べ方（→ docs/dev/features/reflect_crystal.md 判断11）。
// 月ごとの棚に宝石を新しい順に並べ、開いた宝石の段の直下にだけ中身を置く。
// **一覧に出す項目はすべてここが並べる**（凡例を含む）。位置で一覧を送るので、画面が項目を足すとずれる。
// ---------------------------------------------------------------------------

/** 棚の1項目。一覧の1行になる。[key] は項目の鍵で、一覧の中で重ならない。 */
internal sealed interface CrystalShelfItem {
    val key: String

    /** 分野の凡例。一覧の先頭。 */
    data object Legend : CrystalShelfItem {
        override val key: String get() = "legend"
    }

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
        override val key: String get() = crystalShelfDetailKey(crystal.fileName)
    }
}

/**
 * 結晶を月ごとの棚に並べる。先頭は凡例、月も宝石も新しい順で、1段は [columns] 個。月は [zone] の暦で分ける。
 * [opened] の結晶（ファイル名）があれば、その宝石の段の直下に中身を置く。無ければ（別の Vault へ移った等）何も開かない。
 * **月をまたいで段を詰めない** — 棚の区切りが月なので、月の終わりの段は欠けたままにする。
 */
internal fun crystalShelf(crystals: List<Crystal>, columns: Int, opened: String?, zone: ZoneId): List<CrystalShelfItem> {
    require(columns >= 1) { "1段に宝石が1つも入らない: $columns" }
    val byMonth = crystals
        .sortedByDescending { it.createdAt }
        .groupBy { YearMonth.from(Instant.ofEpochMilli(it.createdAt).atZone(zone)) }
    if (crystals.isEmpty()) return emptyList()
    return buildList {
        add(CrystalShelfItem.Legend)
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

/** 結晶 [fileName] の中身の項目の鍵。開いた中身を一覧の中で探すときにも使う。 */
internal fun crystalShelfDetailKey(fileName: String): String = "detail-$fileName"

/** 結晶 [fileName] の宝石がある段の、[items] の中の位置。無ければ null。 */
internal fun crystalShelfRowIndex(items: List<CrystalShelfItem>, fileName: String): Int? =
    items.indexOfFirst { item -> item is CrystalShelfItem.Row && item.crystals.any { it.fileName == fileName } }.takeIf { it >= 0 }

/** 宝石を押して開いた中身を、画面の中へ送る量。 */
internal sealed interface CrystalShelfReveal {
    /** 中身は画面に収まっている。送らない。 */
    data object None : CrystalShelfReveal

    /** 中身は一覧に組まれているが、下が画面からはみ出す。[pixels] だけ送る。 */
    data class By(val pixels: Int) : CrystalShelfReveal

    /** 中身は画面の外にあり、一覧に組まれていない。押した宝石の段を画面の上端へ送り、その直下に中身を出す。 */
    data object ToRow : CrystalShelfReveal
}

/**
 * 開いた中身の上端と下端（[detailTop]・[detailBottom]、一覧に組まれていなければ null）から、送る量を決める（→ reflect_crystal.md 判断11）。
 * **中身の上端は画面の上端より上へ出さない** — 画面より長い中身は、下端ではなく1文のある上端を見せる。
 * 一覧は画面を満たした先の項目を組まないので、下端の宝石を押すと中身は組まれず、位置が分からない（null）。
 */
internal fun crystalShelfRevealFor(detailTop: Int?, detailBottom: Int?, viewportTop: Int, viewportBottom: Int): CrystalShelfReveal {
    if (detailTop == null || detailBottom == null) return CrystalShelfReveal.ToRow
    val pixels = minOf(detailBottom - viewportBottom, detailTop - viewportTop)
    return if (pixels > 0) CrystalShelfReveal.By(pixels) else CrystalShelfReveal.None
}

/** 宝石 [pressed]（ファイル名）を押した後に開いている結晶。開いていた宝石なら閉じ、別の宝石なら替える。 */
internal fun crystalShelfToggle(opened: String?, pressed: String): String? = if (opened == pressed) null else pressed

/** 宝石1つの枠の幅。宝石と日の文字に、押せる幅 48dp を含めて左右の余白を足した値。 */
internal const val CRYSTAL_GEM_CELL_DP = 76f

/** 棚の中の幅 [widthDp] に入る宝石の数。少なくとも1つ。 */
internal fun crystalShelfColumns(widthDp: Float): Int = maxOf(1, (widthDp / CRYSTAL_GEM_CELL_DP).toInt())
