package com.example.newproject.ui

import androidx.compose.ui.text.AnnotatedString
import com.example.newproject.domain.markdown.buildNoteSectionModel
import com.example.newproject.ui.markdown.SpreadBlockRender
import com.example.newproject.ui.markdown.SpreadTableCell
import com.example.newproject.ui.markdown.spreadTableLines
import com.example.newproject.ui.screen.BodyPositionRequest
import com.example.newproject.ui.screen.BodyPositionSource
import com.example.newproject.ui.screen.SPREAD_MAX_BLOCK_CHARS
import com.example.newproject.ui.screen.SpreadAnchor
import com.example.newproject.ui.screen.SpreadBlock
import com.example.newproject.ui.screen.SpreadLine
import com.example.newproject.ui.screen.SpreadPage
import com.example.newproject.ui.screen.SpreadProgress
import com.example.newproject.ui.screen.SpreadTableRow
import com.example.newproject.ui.screen.anchorAfterReparse
import com.example.newproject.ui.screen.anchorAfterRequest
import com.example.newproject.ui.screen.anchorAfterTurn
import com.example.newproject.ui.screen.maxBlockChars
import com.example.newproject.ui.screen.paginateSpread
import com.example.newproject.ui.screen.splitTableRow
import com.example.newproject.ui.screen.spreadAllowed
import com.example.newproject.ui.screen.spreadCount
import com.example.newproject.ui.screen.spreadIndexOf
import com.example.newproject.ui.screen.spreadProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// 本文の見開きのページの割り方と、読む位置の規則（→ features/note_spread.md §5・§8 判断11）。
class SpreadPaginationTest {

    // ── ページの割り方 ─────────────────────────────────────────────────

    @Test
    fun `段落は行の境目で割り、残りを次のページの頭から置く`() {
        val pages = paginateSpread(listOf(lines(25)), leftPageHeight = 100f, blockSpacing = 0f)

        assertEquals(listOf(0 until 10, 10 until 20, 20 until 25), pages.map { it.slices.single().run { unitStart until unitEnd } })
        assertEquals(listOf(0 to 200, 200 to 400, 400 to 500), pages.map { it.slices.single().run { charStart to charEnd } })
    }

    /** ブロックの間の余白はページの頭には付けない。付けると、どのページの頭にも空きができる。 */
    @Test
    fun `ブロックの間の余白はページの頭に付けない`() {
        val pages = paginateSpread(List(6) { lines(1, lineHeight = 30f) }, leftPageHeight = 100f, blockSpacing = 5f)

        // 30 + 5 + 30 + 5 + 30 = 100 で3つ収まり、次のページは余白なしで始まる。
        assertEquals(listOf(listOf(0, 1, 2), listOf(3, 4, 5)), pages.map { page -> page.slices.map { it.blockIndex } })
    }

    @Test
    fun `どの文字も欠けず重ならずに1回だけ出て、組版はかならず終わる`() {
        val blocks = mixedBlocks()
        val pages = paginateSpread(blocks, leftPageHeight = 100f, rightPageHeight = 80f, blockSpacing = 4f)

        assertCovered(blocks, pages)
        assertTrue(pages.all { it.slices.isNotEmpty() })
    }

    @Test
    fun `見出しはページの末尾に残さず、次のブロックの最初の行と一緒に送る`() {
        val pages = paginateSpread(
            listOf(lines(9), heading(), lines(5)),
            leftPageHeight = 100f,
            blockSpacing = 0f
        )

        // 見出しまでは90+10で1ページ目に収まるが、次の段落の最初の行が収まらないので、見出しごと次のページへ送る。
        assertEquals(listOf(listOf(0), listOf(1, 2)), pages.map { page -> page.slices.map { it.blockIndex } })
    }

    @Test
    fun `空のページに次の行と一緒に収まらない見出しは、見出しだけで置く`() {
        val pages = paginateSpread(
            listOf(lines(3), heading(lineHeight = 95f), lines(5)),
            leftPageHeight = 100f,
            blockSpacing = 0f
        )

        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), pages.map { page -> page.slices.map { it.blockIndex } })
    }

    /** 最初のページはノートの題名の分だけ短い。2枚目からは左右の高さに戻る。 */
    @Test
    fun `最初のページだけ題名の分を短くして詰める`() {
        val pages = paginateSpread(listOf(lines(30)), leftPageHeight = 100f, firstPageHeight = 60f, blockSpacing = 0f)

        assertEquals(listOf(6, 10, 10, 4), pages.map { it.slices.single().run { unitEnd - unitStart } })
    }

    @Test
    fun `ブロックの文字数の最大は、表・リスト・引用をまとめて1ブロックとして数える`() {
        val model = buildNoteSectionModel("# 見出し\n\n短い段落。\n\n- 一つ目\n- 二つ目\n\n| 列A | 列B |\n|---|---|\n| あいう | えお |")
        val longest = maxBlockChars(model.blocks)

        assertTrue("表のセルを合わせた長さが最大になる: $longest", longest >= "列A列Bあいうえお".length)
        assertEquals(0, maxBlockChars(emptyList()))
    }

    @Test
    fun `画像は割らず、低いほうのページの高さまで縮める`() {
        val pages = paginateSpread(listOf(SpreadBlock.Image(300f)), leftPageHeight = 100f, rightPageHeight = 90f, blockSpacing = 0f)

        assertEquals(90f, pages.single().slices.single().height)
    }

    /** 割った段落の切れ端ごとに、コードの面の内側の余白のような上下の余白が付く。 */
    @Test
    fun `切れ端ごとに上下の余白を足してページに収める`() {
        val code = SpreadBlock.Lines(List(20) { SpreadLine(10f, it * 5) }, textLength = 100, fragmentPadding = 24f)
        val pages = paginateSpread(listOf(code), leftPageHeight = 100f, blockSpacing = 0f)

        assertTrue(pages.all { page -> page.slices.sumOf { it.height.toDouble() } <= 100.0 })
        assertEquals(listOf(7, 7, 6), pages.map { it.slices.single().run { unitEnd - unitStart } })
    }

    @Test
    fun `表は行の境目で割り、ページより高い行はセルの中の行で割る`() {
        val tall = tableRow(listOf(30, 12), lineHeight = 10f, padding = 6f, firstChar = 0)
        val chunks = splitTableRow(tall, maxHeight = 100f)

        assertTrue(chunks.all { it.height <= 100f })
        // どのセルの行も、欠けず重ならずに1回だけ出る。
        assertEquals(listOf(0 until 30, 0 until 12), (0..1).map { cell -> chunks.first().cellLines[cell].first until chunks.last().cellLines[cell].last + 1 })
        tall.cells.indices.forEach { cell ->
            assertEquals(tall.cells[cell].size, chunks.sumOf { it.cellLines[cell].count() })
        }
        // 切れ端ごとに始まりの位置が異なる（読む位置がどの切れ端かを見分けられる）。
        assertEquals(chunks.size, chunks.map { it.charStart }.distinct().size)
    }

    /**
     * 最初のページが題名の分だけ短いと、見出しの行の後ろの切れ端が2つ右ページに入る。
     * 描くときに見出しの切れ端を1つに絞ると、残りの文字がどのページにも出ない。
     */
    @Test
    fun `割れた見出しの行は同じページに入った切れ端を全部描く`() {
        val lineCount = 33
        val text = AnnotatedString((0 until lineCount).joinToString("") { "line%02d".format(it) })
        val starts = List(lineCount) { it * 6 }
        val table = SpreadBlock.Table(
            listOf(SpreadTableRow(listOf(starts.map { SpreadLine(24f, it) }), padding = 13f)),
            textLength = text.length + 1
        )
        val render = SpreadBlockRender.Table(listOf(listOf(SpreadTableCell(text, starts))))
        val pages = paginateSpread(listOf(table), leftPageHeight = 450f, firstPageHeight = 410f, blockSpacing = 0f)
        val fit = 410f

        assertEquals(listOf(0 until 1, 1 until 3), pages.map { page -> page.slices.single().run { unitStart until unitEnd } })
        val drawn = pages.flatMap { page -> spreadTableLines(table, render, page.slices.single(), fit) }
        assertTrue(drawn.all { it.header })
        assertEquals(text.text, drawn.joinToString("") { it.cells.single().text })
    }

    /** レビューの受理条件: ページより高い1行の表でも終わり、全セルの全文へ届き、空のページを作らない。 */
    @Test
    fun `ページより高い1行の表でも組版は終わり、全文に届く`() {
        val table = SpreadBlock.Table(listOf(tableRow(listOf(300), lineHeight = 10f, padding = 6f, firstChar = 0)), textLength = 3_000)
        val pages = paginateSpread(listOf(lines(2), table, lines(2)), leftPageHeight = 100f, blockSpacing = 0f)

        assertCovered(listOf(lines(2), table, lines(2)), pages)
        assertTrue(pages.all { it.slices.isNotEmpty() })
    }

    // ── 読む位置 ──────────────────────────────────────────────────────

    /**
     * 判断11の例。同じ高さのブロック0〜11を、1ページ2つから3つへ組み直し、2つへ戻す。
     * 読む位置を左ページの先頭とすると、組み直すたびに手前へずれて `[4,5]` へ戻らない。
     */
    @Test
    fun `組み直しても読む位置は動かず、読む位置を含む見開きを出す`() {
        val blocks = List(12) { lines(1, lineHeight = 50f) }
        val two = paginateSpread(blocks, leftPageHeight = 100f, blockSpacing = 0f)
        val anchor = anchorAfterTurn(two, spread = 1)!!
        assertEquals(SpreadAnchor(4), anchor)
        assertEquals(listOf(listOf(4, 5), listOf(6, 7)), pagesOf(two, spreadIndexOf(two, anchor)))

        val three = paginateSpread(blocks, leftPageHeight = 150f, blockSpacing = 0f)
        assertEquals(listOf(listOf(0, 1, 2), listOf(3, 4, 5)), pagesOf(three, spreadIndexOf(three, anchor)))

        val back = paginateSpread(blocks, leftPageHeight = 100f, blockSpacing = 0f)
        assertEquals(listOf(listOf(4, 5), listOf(6, 7)), pagesOf(back, spreadIndexOf(back, anchor)))
    }

    @Test
    fun `画像が縮んでも、再会カードを畳んでも、読む位置のブロックを含む見開きを出す`() {
        val anchor = SpreadAnchor(5)
        fun blocks(imageHeight: Float) = listOf(SpreadBlock.Image(imageHeight)) + List(11) { lines(1, lineHeight = 30f) }
        listOf(
            paginateSpread(blocks(100f), leftPageHeight = 100f, blockSpacing = 0f),
            paginateSpread(blocks(20f), leftPageHeight = 100f, blockSpacing = 0f),
            // カードが出ている間は左ページが短い。
            paginateSpread(blocks(20f), leftPageHeight = 40f, rightPageHeight = 100f, blockSpacing = 0f)
        ).forEach { pages ->
            assertTrue(pagesOf(pages, spreadIndexOf(pages, anchor)).flatten().contains(5))
        }
    }

    /** めくりで決まった読む位置は、その見開きへ戻る。戻らないと、めくった直後に前の見開きへ跳ねる。 */
    @Test
    fun `どの見開きでも、めくった後の読む位置はその見開きを指す`() {
        val blocks = mixedBlocks()
        val pages = paginateSpread(blocks, leftPageHeight = 100f, rightPageHeight = 80f, blockSpacing = 4f)

        (0 until spreadCount(pages)).forEach { spread ->
            assertEquals("見開き$spread", spread, spreadIndexOf(pages, anchorAfterTurn(pages, spread)!!))
        }
    }

    @Test
    fun `まだ組まれていない読む位置は最後の見開き、ページが無ければ0`() {
        val pages = paginateSpread(List(4) { lines(1, lineHeight = 50f) }, leftPageHeight = 100f, blockSpacing = 0f)

        assertEquals(0, spreadIndexOf(pages, SpreadAnchor(40)))
        assertEquals(0, spreadIndexOf(emptyList(), SpreadAnchor(3)))
    }

    // ── 読書の記録 ────────────────────────────────────────────────────

    @Test
    fun `読書の記録は出ている最後の切れ端を送り、右ページが空なら左ページの最後を送る`() {
        val blocks = listOf(lines(25))
        val pages = paginateSpread(blocks, leftPageHeight = 100f, blockSpacing = 0f)

        assertEquals(SpreadProgress(0, 0.8f), spreadProgress(pages, spread = 0, blocks = blocks))
        // 2つ目の見開きは左ページだけ。
        assertEquals(SpreadProgress(0, 1f), spreadProgress(pages, spread = 1, blocks = blocks))
    }

    @Test
    fun `文字を持たないブロックは出せば読み切ったと数える`() {
        val blocks = listOf(lines(1), SpreadBlock.Image(50f))
        val pages = paginateSpread(blocks, leftPageHeight = 100f, blockSpacing = 0f)

        assertEquals(SpreadProgress(1, 1f), spreadProgress(pages, spread = 0, blocks = blocks))
    }

    // ── 位置の書き込み ──────────────────────────────────────────────────

    /** レビューの受理条件: 長いブロックの途中までめくっても自分の書き戻しでは保ち、同じ値の外からの送りでは毎回替える。 */
    @Test
    fun `外からの送りは同じ値でも読む位置を替え、自分の書き戻しと見た番号では替えない`() {
        val inside = SpreadAnchor(0, charOffset = 1_000)

        assertEquals(inside, anchorAfterRequest(inside, seenRequest = 3, BodyPositionRequest(4, 0, BodyPositionSource.SpreadTurn)))
        assertEquals(SpreadAnchor(0), anchorAfterRequest(inside, seenRequest = 3, BodyPositionRequest(4, 0, BodyPositionSource.External)))
        assertEquals(inside, anchorAfterRequest(inside, seenRequest = 4, BodyPositionRequest(4, 0, BodyPositionSource.External)))
    }

    @Test
    fun `本文の解析が替わったら、ブロックを保って文字の位置を0に戻す`() {
        assertEquals(SpreadAnchor(3), anchorAfterReparse(SpreadAnchor(3, charOffset = 120), blockCount = 10))
        assertEquals(SpreadAnchor(1), anchorAfterReparse(SpreadAnchor(3, charOffset = 120), blockCount = 2))
        assertEquals(SpreadAnchor(0), anchorAfterReparse(SpreadAnchor(3), blockCount = 0))
    }

    /**
     * 続きから読むの送り先（`resumeBlockFor`）をそのまま読む位置にし、見出しへ変換しない（→ features/note_spread.md §5）。
     * 値は `ReunionPassageTest` と同じ。見出しへ変換すると、長い節で数画面前まで巻き戻る。
     */
    @Test
    fun `続きから読むの送り先をそのまま読む位置にし、見出しへ変換しない`() {
        val headed = buildNoteSectionModel("# 導入\n導入1\n\n# 本論\n" + (1..9).joinToString("\n\n") { "本論$it" } + "\n\n# まとめ\nまとめ1")
        val plain = buildNoteSectionModel((0 until 10).joinToString("\n\n") { "段落${it}の本文。" })

        listOf(headed to 11 to 10, headed to 4 to 2, plain to 7 to 6, plain to 0 to 0).forEach { (input, expected) ->
            val (model, frontier) = input
            val target = model.resumeBlockFor(frontier)
            val anchor = anchorAfterRequest(SpreadAnchor(0, 40), seenRequest = 0, BodyPositionRequest(1, target, BodyPositionSource.External))
            assertEquals("到達$frontier", SpreadAnchor(expected), anchor)
        }
        // 送り先が右ページにあっても、その見開きを出す。カードを畳んで組み直しても読む位置は動かない。
        val blocks = List(headed.blocks.size) { lines(1, lineHeight = 30f) }
        listOf(
            paginateSpread(blocks, leftPageHeight = 40f, rightPageHeight = 100f, blockSpacing = 0f),
            paginateSpread(blocks, leftPageHeight = 100f, blockSpacing = 0f)
        ).forEach { pages ->
            assertTrue(pagesOf(pages, spreadIndexOf(pages, SpreadAnchor(10))).flatten().contains(10))
        }
    }

    // ── 上限の入力 ────────────────────────────────────────────────────

    /** 要素数が最大の入力（→ lessons L52）。短いブロックが2万あっても、欠けず重ならずに終わる。 */
    @Test
    fun `要素数が最大でも、欠けず重ならずに組み終える`() {
        val blocks = List(20_000) { lines(1, lineHeight = 24f, charsPerLine = 50) }
        val pages = paginateSpread(blocks, leftPageHeight = 780f, blockSpacing = 8f)

        assertCovered(blocks, pages)
    }

    /** 上限ちょうどの1ブロック。1つのブロックが何十ページにまたがっても、欠けず重ならずに終わる。 */
    @Test
    fun `上限ちょうどの1ブロックでも、欠けず重ならずに組み終える`() {
        val charsPerLine = 22
        val block = lines(SPREAD_MAX_BLOCK_CHARS / charsPerLine + 1, lineHeight = 24f, charsPerLine = charsPerLine)
        val pages = paginateSpread(listOf(block), leftPageHeight = 780f, blockSpacing = 8f)

        assertCovered(listOf(block), pages)
        assertTrue(pages.size > 10)
    }

    // ── 入る条件 ──────────────────────────────────────────────────────

    /** 同じ窓・同じ密度で文字の倍率だけを変える（→ features/note_spread.md §8 判断9）。幅だけで決めると倍率で切り替わらない。 */
    @Test
    fun `同じページで文字の倍率だけを上げると、1行の字数が下限を割って見開きに入らない`() {
        assertTrue(spreadAllowed(pageTextWidthDp = 360f, pageTextHeightDp = 780f, fontScale = 1.0f, maxBlockChars = 500))
        assertTrue(spreadAllowed(pageTextWidthDp = 360f, pageTextHeightDp = 780f, fontScale = 1.3f, maxBlockChars = 500))
        assertFalse(spreadAllowed(pageTextWidthDp = 360f, pageTextHeightDp = 780f, fontScale = 1.5f, maxBlockChars = 500))
    }

    @Test
    fun `ページの行数が下限を割るか、巨大なブロックがあれば見開きに入らない`() {
        assertFalse(spreadAllowed(pageTextWidthDp = 360f, pageTextHeightDp = 250f, fontScale = 1.0f, maxBlockChars = 500))
        assertTrue(spreadAllowed(pageTextWidthDp = 360f, pageTextHeightDp = 780f, fontScale = 1.0f, maxBlockChars = SPREAD_MAX_BLOCK_CHARS))
        assertFalse(spreadAllowed(pageTextWidthDp = 360f, pageTextHeightDp = 780f, fontScale = 1.0f, maxBlockChars = SPREAD_MAX_BLOCK_CHARS + 1))
    }

    // ── 補助 ──────────────────────────────────────────────────────────

    private fun lines(count: Int, lineHeight: Float = 10f, charsPerLine: Int = 20) =
        SpreadBlock.Lines(List(count) { SpreadLine(lineHeight, it * charsPerLine) }, textLength = count * charsPerLine)

    private fun heading(lineHeight: Float = 10f) = SpreadBlock.Lines(listOf(SpreadLine(lineHeight, 0)), textLength = 4, heading = true)

    /** セルごとの行数から表の1行を作る。行の位置はセルを左から順につないで数える。 */
    private fun tableRow(lineCounts: List<Int>, lineHeight: Float, padding: Float, firstChar: Int): SpreadTableRow {
        var next = firstChar
        val cells = lineCounts.map { count -> List(count) { SpreadLine(lineHeight, next).also { next += 10 } } }
        return SpreadTableRow(cells, padding)
    }

    private fun mixedBlocks(): List<SpreadBlock> = listOf(
        lines(12),
        heading(),
        lines(3),
        SpreadBlock.Image(140f),
        SpreadBlock.Rule(17f),
        SpreadBlock.Lines(List(15) { SpreadLine(10f, it * 5) }, textLength = 75, fragmentPadding = 24f),
        SpreadBlock.Table(listOf(tableRow(listOf(2, 3), 10f, 6f, 0), tableRow(listOf(20, 4), 10f, 6f, 50)), textLength = 290),
        heading(),
        lines(30)
    )

    /** 見開き [spread] の左右のページに出るブロック番号。 */
    private fun pagesOf(pages: List<SpreadPage>, spread: Int): List<List<Int>> =
        listOfNotNull(pages.getOrNull(spread * 2), pages.getOrNull(spread * 2 + 1)).map { page -> page.slices.map { it.blockIndex } }

    /** 文字を持つブロックは、切れ端が0から表示文字列の長さまで途切れず重ならずに並ぶ。文字を持たないブロックは1回だけ出る。 */
    private fun assertCovered(blocks: List<SpreadBlock>, pages: List<SpreadPage>) {
        val slices = pages.flatMap { it.slices }
        assertEquals(slices.map { it.blockIndex }.sorted(), slices.map { it.blockIndex })
        blocks.forEachIndexed { index, block ->
            val own = slices.filter { it.blockIndex == index }
            assertTrue("ブロック$index が出ていない", own.isNotEmpty())
            if (block.textLength > 0) {
                assertEquals("ブロック$index の始まり", 0, own.first().charStart)
                own.zipWithNext().forEach { (a, b) -> assertEquals("ブロック$index の継ぎ目", a.charEnd, b.charStart) }
                assertEquals("ブロック$index の終わり", block.textLength, own.last().charEnd)
            } else {
                assertEquals(1, own.size)
            }
        }
    }
}
