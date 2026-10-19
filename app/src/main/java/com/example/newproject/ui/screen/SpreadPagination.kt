package com.example.newproject.ui.screen

import com.example.newproject.domain.markdown.MarkdownBlock
import kotlin.math.max
import kotlin.math.min

// 本文の見開き — 計測済みの寸法をページへ割り、読む位置と見開きを対応づける純関数（→ features/note_spread.md）。
// 高さの単位は呼ぶ側にそろえる（ページの高さと同じ単位なら dp でも px でもよい）。

/**
 * 見開きで組む本文の1ブロック。計測した結果を、割れる単位（行・表の行）の高さで持つ。
 * [textLength] は表示文字列（記法を消し、リンクを表示名へ替えた文字列）の長さで、読書の記録の割合の分母になる。
 */
internal sealed interface SpreadBlock {
    val textLength: Int

    /**
     * 行の境目で割れるブロック（段落・見出し・リストの項目・引用の行・コード）。
     * [fragmentPadding] は切れ端ごとに上下へ付く余白の合計（コードの面の内側の余白など）で、ページをまたいで割ると切れ端の数だけ付く。
     * [heading] なら、ページの末尾に見出しだけを残さない。
     */
    data class Lines(
        val lines: List<SpreadLine>,
        override val textLength: Int,
        val fragmentPadding: Float = 0f,
        val heading: Boolean = false
    ) : SpreadBlock

    /** 割らない画像。ページの高さに収まらなければ、ページの高さまで縮める。 */
    data class Image(val height: Float) : SpreadBlock {
        override val textLength: Int get() = 0
    }

    /** 割らない水平線。 */
    data class Rule(val height: Float) : SpreadBlock {
        override val textLength: Int get() = 0
    }

    /** 表。行の境目で割り、ページより高い行はセルの中の行の境目で割る（→ [splitTableRow]）。 */
    data class Table(val rows: List<SpreadTableRow>, override val textLength: Int) : SpreadBlock
}

/** 1行。[charStart] はブロックの表示文字列の中で、この行が始まる位置（UTF-16）。 */
internal data class SpreadLine(val height: Float, val charStart: Int)

/**
 * 表の1行。[cells] はセルごとの行で、行の位置は表の表示文字列（セルを左から順につないだもの）の中で数える。
 * [padding] はセルの上下の余白の合計。行の高さは、最も高いセルに余白を足したもの。
 */
internal data class SpreadTableRow(val cells: List<List<SpreadLine>>, val padding: Float) {
    val height: Float get() = (cells.maxOfOrNull { cell -> cell.sumOf { it.height.toDouble() }.toFloat() } ?: 0f) + padding
}

/**
 * ページより高い表の行を割った1つ。[cellLines] はセルごとに出す行の範囲。
 * [charStart] はこの切れ端が出す最初の文字の位置で、**切れ端ごとに異なる** — 読む位置がどの切れ端かを見分けられるように。
 */
internal data class SpreadTableChunk(val cellLines: List<IntRange>, val height: Float, val charStart: Int)

/**
 * ページの中の、1つのブロックの切れ端。[unitStart] until [unitEnd] はブロックの中の単位（行・表の行の切れ端）の範囲で、描画が使う。
 * [charStart] until [charEnd] はこの切れ端が出す表示文字列の範囲。[height] は余白と縮めた画像を含む、この切れ端の高さ。
 */
internal data class SpreadSlice(
    val blockIndex: Int,
    val unitStart: Int,
    val unitEnd: Int,
    val charStart: Int,
    val charEnd: Int,
    val height: Float
)

internal data class SpreadPage(val slices: List<SpreadSlice>)

/** 読む位置。ブロック番号と、そのブロックの表示文字列の位置（→ features/note_spread.md §5「読む位置」）。 */
internal data class SpreadAnchor(val blockIndex: Int, val charOffset: Int = 0)

/** 読書の記録へ送る位置。そのブロックの表示文字列をどこまで出したかの割合。 */
internal data class SpreadProgress(val blockIndex: Int, val fraction: Float)

/**
 * ページより高い表の行を、セルの中の行の境目で割る。どの切れ端もセルの上下の余白を持ち、[maxHeight] を超えない。
 * **どの切れ端も少なくとも1行を出す** — 1行が [maxHeight] より高くても1行ずつ進めるので、かならず終わる
 * （入る条件で、本文の1行はページに収まる → [spreadAllowed]）。
 */
internal fun splitTableRow(row: SpreadTableRow, maxHeight: Float): List<SpreadTableChunk> {
    val firstChar = row.cells.firstNotNullOfOrNull { it.firstOrNull()?.charStart } ?: 0
    if (row.height <= maxHeight) return listOf(SpreadTableChunk(row.cells.map { it.indices }, row.height, firstChar))
    val room = maxHeight - row.padding
    val next = IntArray(row.cells.size)
    val chunks = mutableListOf<SpreadTableChunk>()
    while (row.cells.indices.any { next[it] < row.cells[it].size }) {
        var tallest = 0f
        var start: Int? = null
        val ranges = row.cells.mapIndexed { cell, lines ->
            val from = next[cell]
            var end = from
            var used = 0f
            while (end < lines.size && (end == from || used + lines[end].height <= room)) {
                used += lines[end].height
                end++
            }
            if (start == null && end > from) start = lines[from].charStart
            next[cell] = end
            tallest = max(tallest, used)
            from until end
        }
        chunks += SpreadTableChunk(ranges, tallest + row.padding, start ?: firstChar)
    }
    return chunks
}

/** ページへ詰める、割れない単位。[charStart] はブロックの表示文字列の中の始まり。 */
private data class SpreadUnit(val height: Float, val charStart: Int)

private fun SpreadBlock.units(pageHeight: Float): List<SpreadUnit> = when (this) {
    is SpreadBlock.Lines -> lines.map { SpreadUnit(it.height, it.charStart) }
    is SpreadBlock.Image -> listOf(SpreadUnit(min(height, pageHeight), 0))
    is SpreadBlock.Rule -> listOf(SpreadUnit(height, 0))
    is SpreadBlock.Table -> rows.flatMap { row -> splitTableRow(row, pageHeight).map { SpreadUnit(it.height, it.charStart) } }
}

private fun SpreadBlock.fragmentPadding(): Float = (this as? SpreadBlock.Lines)?.fragmentPadding ?: 0f

/**
 * ブロックを本文の順に、ページへ詰める（→ features/note_spread.md §5「ページの割り方」）。
 * [leftPageHeight]・[rightPageHeight] は左右のページの本文の高さ（再会カードが出ている間は左が短い）、
 * [firstPageHeight] は最初のページの本文の高さ（ノートの題名の分だけ短い。null なら左と同じ）、
 * [blockSpacing] はブロックの間の余白（ページの頭には付けない）。
 *
 * - **どのページにも少なくとも1つの単位を置く。** 空のページに収まらない単位でも置いて進めるので、かならず終わる
 * - 見出しは、次のブロックの最初の単位と一緒に収まらなければ次のページへ送る。**空のページでも一緒に収まらなければ、見出しだけで置く**
 * - 画像は低いほうのページの高さまで縮め、表のページより高い行はセルの行で割る（どちらのページに落ちても収まるように）
 */
internal fun paginateSpread(
    blocks: List<SpreadBlock>,
    leftPageHeight: Float,
    rightPageHeight: Float = leftPageHeight,
    firstPageHeight: Float? = null,
    blockSpacing: Float
): List<SpreadPage> {
    val pages = mutableListOf<SpreadPage>()
    val current = mutableListOf<SpreadSlice>()
    var used = 0f
    fun pageHeight(): Float = when {
        pages.isEmpty() && firstPageHeight != null -> firstPageHeight
        pages.size % 2 == 0 -> leftPageHeight
        else -> rightPageHeight
    }

    fun closePage() {
        if (current.isNotEmpty()) pages += SpreadPage(current.toList())
        current.clear()
        used = 0f
    }

    val fit = minOf(leftPageHeight, rightPageHeight, firstPageHeight ?: leftPageHeight)
    val unitsOf = blocks.map { it.units(fit) }
    blocks.forEachIndexed { index, block ->
        val units = unitsOf[index]
        if (units.isEmpty()) return@forEachIndexed
        val padding = block.fragmentPadding()
        if (block is SpreadBlock.Lines && block.heading && current.isNotEmpty()) {
            val next = blocks.getOrNull(index + 1)
            val nextFirst = unitsOf.getOrNull(index + 1)?.firstOrNull()
            val headingHeight = blockSpacing + units.sumOf { it.height.toDouble() }.toFloat() + padding
            val withNext = headingHeight + (nextFirst?.let { blockSpacing + it.height + (next?.fragmentPadding() ?: 0f) } ?: 0f)
            if (used + withNext > pageHeight()) closePage()
        }
        var unit = 0
        while (unit < units.size) {
            val spacing = if (current.isEmpty()) 0f else blockSpacing
            var end = unit
            var height = padding
            while (end < units.size && (end == unit && current.isEmpty() || used + spacing + height + units[end].height <= pageHeight())) {
                height += units[end].height
                end++
            }
            if (end == unit) {
                closePage()
                continue
            }
            current += SpreadSlice(
                blockIndex = index,
                unitStart = unit,
                unitEnd = end,
                charStart = units[unit].charStart,
                charEnd = if (end < units.size) units[end].charStart else block.textLength,
                height = height
            )
            used += spacing + height
            unit = end
            if (unit < units.size) closePage()
        }
    }
    closePage()
    return pages
}

/** 見開きの数。2ページで1つ、最後の右ページは空でもよい。 */
internal fun spreadCount(pages: List<SpreadPage>): Int = (pages.size + 1) / 2

/**
 * 読む位置を含む見開きの番号（→ features/note_spread.md §8 判断11）。**組み直しても読む位置は変えず、それを含む見開きを出す。**
 * 読む位置がどのページにも無ければ（本文より先・まだ組まれていない）、最後の見開き。ページが無ければ0。
 */
internal fun spreadIndexOf(pages: List<SpreadPage>, anchor: SpreadAnchor): Int {
    val page = pages.indexOfFirst { page ->
        page.slices.any { it.blockIndex == anchor.blockIndex && anchor.charOffset in it.charStart until max(it.charEnd, it.charStart + 1) }
    }.takeIf { it >= 0 } ?: pages.indexOfFirst { page -> page.slices.any { it.blockIndex > anchor.blockIndex } }
        .takeIf { it >= 0 }?.let { max(it - 1, 0) }
        ?: (pages.size - 1)
    return max(page, 0) / 2
}

/** めくりが決まったときの読む位置。その見開きの左ページの先頭。 */
internal fun anchorAfterTurn(pages: List<SpreadPage>, spread: Int): SpreadAnchor? =
    pages.getOrNull(spread * 2)?.slices?.firstOrNull()?.let { SpreadAnchor(it.blockIndex, it.charStart) }

/**
 * 見開きを出したときに読書の記録へ送る位置。出ている最後の切れ端（右ページが空なら左ページの最後）のブロックと、
 * そのブロックの表示文字列をどこまで出したかの割合。文字を持たないブロックは出せば1。
 */
internal fun spreadProgress(pages: List<SpreadPage>, spread: Int, blocks: List<SpreadBlock>): SpreadProgress? {
    val last = (pages.getOrNull(spread * 2 + 1) ?: pages.getOrNull(spread * 2))?.slices?.lastOrNull() ?: return null
    val length = blocks.getOrNull(last.blockIndex)?.textLength ?: 0
    val fraction = if (length <= 0) 1f else (last.charEnd.toFloat() / length).coerceIn(0f, 1f)
    return SpreadProgress(last.blockIndex, fraction)
}

/**
 * 書き込みを受けたあとの読む位置。**番号が進んだ外からの送りなら、値が同じでも送り先のブロックの先頭へ替える。**
 * 自分の書き戻しと、見たことのある番号では替えない。
 */
internal fun anchorAfterRequest(current: SpreadAnchor, seenRequest: Long, request: BodyPositionRequest): SpreadAnchor =
    if (request.request != seenRequest && request.source == BodyPositionSource.External) SpreadAnchor(request.blockIndex) else current

/** 同じノートで本文の解析結果が替わった。読む位置のブロックは保ち、文字の位置は古い表示文字列のものなので0に戻す。 */
internal fun anchorAfterReparse(current: SpreadAnchor, blockCount: Int): SpreadAnchor =
    SpreadAnchor(current.blockIndex.coerceIn(0, max(blockCount - 1, 0)))

/**
 * 見開きに入れるか（→ features/note_spread.md §8 判断9・判断10）。並べ方の条件（折り目・ペイン）とは別に、
 * **1行に入る字数**と**ページに入る行数**を文字の倍率込みで数え、巨大なブロックの無いノートだけを入れる。
 * [pageTextWidthDp]・[pageTextHeightDp] はページの本文の幅と高さ、[maxBlockChars] は本文のブロックの表示文字数の最大。
 */
internal fun spreadAllowed(pageTextWidthDp: Float, pageTextHeightDp: Float, fontScale: Float, maxBlockChars: Int): Boolean {
    val charsPerLine = pageTextWidthDp / (SPREAD_BODY_FONT_SP * fontScale)
    val lines = pageTextHeightDp / (SPREAD_BODY_LINE_SP * fontScale)
    return charsPerLine >= SPREAD_MIN_CHARS_PER_LINE && lines >= SPREAD_MIN_LINES && maxBlockChars <= SPREAD_MAX_BLOCK_CHARS
}

/** 本文の段落の文字の大きさと行の高さ（`MarkdownParagraph` と同じ）。 */
internal const val SPREAD_BODY_FONT_SP = 16f
internal const val SPREAD_BODY_LINE_SP = 24f

/** 見開きに入る、1行の字数の下限。倍率1.0で約22字、1.3で約17字、1.5で約15字になる幅を想定する。 */
internal const val SPREAD_MIN_CHARS_PER_LINE = 16f

/** 見開きに入る、ページの行数の下限。 */
internal const val SPREAD_MIN_LINES = 12f

/**
 * 見開きに入る、ブロックの表示文字数の上限。1回の計測は途中で止められず、字数にほぼ比例して Main を占める。
 * この字数で約2ms（→ features/note_spread.md §8 判断10 の実測）で、実際の本文で数倍かかっても 120Hz の1フレームに収まる。
 */
internal const val SPREAD_MAX_BLOCK_CHARS = 8_000

/**
 * 本文のブロックの文字数の最大。見開きに入るかの判定（[spreadAllowed]）に使う。
 * **原文の文字数で数える** — 表示文字列（記法を消したもの）より長いか同じなので、上限の判定は厳しい側に倒れる。
 * 組み始める前に解析結果だけから決まるので、読んでいる途中で並べ方が替わらない。
 */
internal fun maxBlockChars(blocks: List<MarkdownBlock>): Int = blocks.maxOfOrNull { block ->
    when (block) {
        is MarkdownBlock.Heading -> block.text.length
        is MarkdownBlock.Paragraph -> block.text.length
        is MarkdownBlock.ListBlock -> block.items.sumOf { it.text.length + 1 }
        is MarkdownBlock.CodeBlock -> block.code.length
        is MarkdownBlock.Blockquote -> block.lines.sumOf { it.length + 1 }
        is MarkdownBlock.Table -> (block.headers + block.rows.flatten()).sumOf { it.length + 1 }
        else -> 0
    }
} ?: 0
