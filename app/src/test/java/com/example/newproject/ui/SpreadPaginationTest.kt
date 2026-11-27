package com.example.newproject.ui

import androidx.compose.ui.text.AnnotatedString
import com.example.newproject.domain.markdown.buildNoteSectionModel
import com.example.newproject.ui.markdown.SpreadBlockRender
import com.example.newproject.ui.markdown.SpreadTableCell
import com.example.newproject.ui.markdown.spreadTableLines
import com.example.newproject.ui.screen.BodyPositionRequest
import com.example.newproject.ui.screen.FIRST_BODY_REQUEST
import com.example.newproject.ui.screen.BodyPositionSource
import com.example.newproject.ui.screen.SPREAD_MAX_BLOCK_CHARS
import com.example.newproject.ui.screen.SpreadAnchor
import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.ui.screen.SpreadBlock
import com.example.newproject.ui.screen.SpreadKeep
import com.example.newproject.ui.screen.SpreadLine
import com.example.newproject.ui.screen.SpreadPage
import com.example.newproject.ui.screen.SpreadProgress
import com.example.newproject.ui.screen.SpreadReading
import com.example.newproject.ui.screen.SpreadTableRow
import com.example.newproject.ui.screen.quoteSpreadBlock
import com.example.newproject.ui.screen.listSpreadBlock
import com.example.newproject.ui.screen.quoteChildSlice
import com.example.newproject.ui.screen.SpreadSlice
import com.example.newproject.ui.screen.QuoteChildLayout
import com.example.newproject.ui.screen.anchorAfterReparse
import com.example.newproject.ui.screen.anchorAfterRequest
import com.example.newproject.ui.screen.anchorAfterTurn
import com.example.newproject.ui.screen.maxBlockChars
import com.example.newproject.ui.screen.paginateSpread
import com.example.newproject.ui.screen.reconcileSpreadReading
import com.example.newproject.ui.screen.restoreSpreadReading
import com.example.newproject.ui.screen.splitTableRow
import com.example.newproject.ui.screen.spreadAllowed
import com.example.newproject.ui.screen.spreadCount
import com.example.newproject.ui.screen.spreadIndexOf
import com.example.newproject.ui.screen.spreadProgress
import com.example.newproject.ui.screen.readySpreadCount
import com.example.newproject.ui.screen.spreadKeepOf
import com.example.newproject.ui.screen.withKeep
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
        // 右ページは、節を次の見開きへ送ったときだけ空になる（判断15）。左ページはいつも切れ端を持つ。
        assertTrue(pages.filterIndexed { page, _ -> page % 2 == 0 }.all { it.slices.isNotEmpty() })
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

    /** 見出しと次の行は同じページに入らない。見出しの節は空の見開きに入るので、次の見開きの左ページの頭から見出しだけで置く（判断15）。 */
    @Test
    fun `空のページに次の行と一緒に収まらない見出しは、見出しだけで置く`() {
        val pages = paginateSpread(
            listOf(lines(3), heading(lineHeight = 95f), lines(5)),
            leftPageHeight = 100f,
            blockSpacing = 0f
        )

        assertEquals(listOf(listOf(0), emptyList(), listOf(1), listOf(2)), pages.map { page -> page.slices.map { it.blockIndex } })
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

    /** 見出しの脇の印の枠は、文字より高ければその高さを取る。文字の高さで詰めると、描いたページの下が切れる。 */
    @Test
    fun `印の枠のある見出しは枠の高さで詰める`() {
        val marked = SpreadBlock.Lines(listOf(SpreadLine(10f, 0)), textLength = 4, fragmentPadding = 2f, keep = SpreadKeep.Heading(1), minHeight = 30f)
        val blocks = List(6) { listOf(marked, lines(1)) }.flatten()
        val pages = paginateSpread(blocks, leftPageHeight = 100f, blockSpacing = 0f)

        assertEquals(listOf(4, 4, 4), pages.map { it.slices.size })
        assertTrue(pages.all { page -> page.slices.sumOf { it.height.toDouble() } <= 100.0 })
        assertTrue(pages.flatMap { it.slices }.filter { it.blockIndex % 2 == 0 }.all { it.height == 30f })
        assertCovered(blocks, pages)
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

    // ── 見開きの境目（判断15）──────────────────────────────────────────

    @Test
    fun `箇条・表・引用・コードは本文に直に置けばまとまりで、段落・画像・水平線はまとまりにしない`() {
        val model = buildNoteSectionModel(
            "# 見出し\n\n段落。\n\n- 一つ目\n- 二つ目\n\n| 列A | 列B |\n|---|---|\n| あ | い |\n\n> 引用の行\n\n" +
                "```\ncode\n```\n\n![[a.png]]\n\n---\n\n### 小見出し"
        )

        assertEquals(
            listOf(
                "Heading" to SpreadKeep.Heading(1),
                "Paragraph" to SpreadKeep.None,
                "ListBlock" to SpreadKeep.Whole,
                "Table" to SpreadKeep.Whole,
                "Blockquote" to SpreadKeep.Whole,
                "CodeBlock" to SpreadKeep.Whole,
                "Image" to SpreadKeep.None,
                "HorizontalRule" to SpreadKeep.None,
                "Heading" to SpreadKeep.Heading(3)
            ),
            model.blocks.map { it::class.simpleName to spreadKeepOf(it) }
        )
        // 測ったブロックへ持たせる。画像と水平線はいつもまとまりにしない。
        assertEquals(SpreadKeep.Whole, lines(2).withKeep(SpreadKeep.Whole).keep)
        assertEquals(SpreadKeep.Whole, SpreadBlock.Table(emptyList(), 0).withKeep(SpreadKeep.Whole).keep)
        assertEquals(SpreadKeep.None, SpreadBlock.Image(10f).withKeep(spreadKeepOf(MarkdownBlock.Image("", "a.png", isEmbed = true))).keep)
    }

    @Test
    fun `今の見開きに入らず空の見開きに入るまとまりは、次の見開きの左ページの頭から置く`() {
        val pages = paginateSpread(listOf(lines(15), whole(8)), leftPageHeight = 100f, blockSpacing = 0f)

        assertEquals(listOf(listOf(0), listOf(0), listOf(1)), blocksByPage(pages))
        assertEquals(0 until 8, pages[2].slices.single().run { unitStart until unitEnd })
        // 対照: 段落は見開きの境目で割る。
        val plain = paginateSpread(listOf(lines(15), lines(8)), leftPageHeight = 100f, blockSpacing = 0f)
        assertEquals(listOf(listOf(0), listOf(0, 1), listOf(1)), blocksByPage(plain))
    }

    /** 左ページから送ると右ページは空になる。読書の記録は左ページの最後を送り、読む位置の規則は今のまま。 */
    @Test
    fun `左ページから送れば右ページは空になり、記録と読む位置は今の規則どおり`() {
        val blocks = listOf(lines(5), whole(18))
        val pages = paginateSpread(blocks, leftPageHeight = 100f, blockSpacing = 0f)

        assertEquals(listOf(listOf(0), emptyList(), listOf(1), listOf(1)), blocksByPage(pages))
        assertEquals(SpreadProgress(0, 1f), spreadProgress(pages, spread = 0, blocks = blocks))
        assertEquals(SpreadAnchor(1, 0), anchorAfterTurn(pages, spread = 1))
        assertEquals(1, spreadIndexOf(pages, SpreadAnchor(1)))
        (0 until spreadCount(pages)).forEach { spread ->
            assertEquals("見開き$spread", spread, spreadIndexOf(pages, anchorAfterTurn(pages, spread)!!))
        }
    }

    @Test
    fun `空の見開きにも入らないまとまりは送らず、今と同じく割る`() {
        val kept = paginateSpread(listOf(lines(15), whole(25)), leftPageHeight = 100f, blockSpacing = 0f)
        val plain = paginateSpread(listOf(lines(15), lines(25)), leftPageHeight = 100f, blockSpacing = 0f)

        assertEquals(plain, kept)
    }

    /** 節は下の階層の見出し（3）を含み、同じ階層の次の見出し（5）の手前で終わる。 */
    @Test
    fun `見出しの節が今の見開きの残りに入らなければ、節ごと次の見開きへ送る`() {
        // 同じ階層の次の見出しの手前で閉じるので、節（1〜4）は今の見開きの残りにちょうど入る。次の節（5・6）は入らず送る。
        val closed = paginateSpread(
            listOf(lines(15), heading(level = 2), lines(2), heading(level = 3), lines(1), heading(level = 2), lines(5)),
            leftPageHeight = 100f,
            blockSpacing = 0f
        )
        assertEquals(listOf(listOf(0), listOf(0, 1, 2, 3, 4), listOf(5, 6)), blocksByPage(closed))

        // 下の階層の見出しは節の中にある。節（1〜4）ごと送り、下の階層の見出し（3）だけを送らない。
        val nested = paginateSpread(
            listOf(lines(15), heading(level = 2), lines(2), heading(level = 3), lines(3), heading(level = 2), lines(5)),
            leftPageHeight = 100f,
            blockSpacing = 0f
        )
        assertEquals(listOf(listOf(0), listOf(0), listOf(1, 2, 3, 4, 5, 6), listOf(6)), blocksByPage(nested))
    }

    /**
     * 節の末尾の本文の無い見出し（3）は、続く見出し（4）と連なりなので、節に数えず次のまとまりと一緒に扱う。
     * 数えると、見出し3と次の行が今の見開きに入らないせいで、入る節（1・2）まで送って今の見開きに空きを作る。
     */
    @Test
    fun `節の末尾の本文の無い見出しは節に数えず、続くまとまりと一緒に扱う`() {
        val pages = paginateSpread(
            listOf(lines(15), heading(level = 1), lines(3), heading(level = 2), heading(level = 1), lines(12)),
            leftPageHeight = 100f,
            blockSpacing = 0f
        )

        assertEquals(listOf(listOf(0), listOf(0, 1, 2), listOf(3, 4, 5), listOf(5)), blocksByPage(pages))

        // 本文の終わりで閉じる節も同じ。末尾の見出し（3）だけを次の見開きへ送り、入る節（1・2）は残す。
        val atEnd = paginateSpread(listOf(lines(15), heading(level = 1), lines(4), heading(level = 2)), leftPageHeight = 100f, blockSpacing = 0f)
        assertEquals(listOf(listOf(0), listOf(0, 1, 2), listOf(3)), blocksByPage(atEnd))
    }

    /**
     * 1見開きに入らない長い節が続く（実機の「まとまり3」と「まとまり4」の形）。2つ目の節は今の見開きの残りに入らないので、
     * 送っても割れるが、節の頭を次の見開きの頭から始める。節は、見開きの境目をまたぐなら見開きの頭から始まる。
     */
    @Test
    fun `1見開きに入らない長い節も、今の見開きの残りに入らなければ次の見開きの頭から始める`() {
        val pages = paginateSpread(
            listOf(heading(level = 2), lines(25), heading(level = 2), lines(25)),
            leftPageHeight = 100f,
            blockSpacing = 0f
        )

        assertEquals(listOf(listOf(0, 1), listOf(1), listOf(1), emptyList(), listOf(2, 3), listOf(3), listOf(3)), blocksByPage(pages))
    }

    /** 見出しの節が今の見開きの残りに入らないので、見出しと箇条を一緒に送る。箇条だけを判定して送ると、見出しが見開きの頭に1つだけ残る。 */
    @Test
    fun `見出しのすぐ後のまとまりは見出しと一緒に送り、まとまりだけを送らない`() {
        val pages = paginateSpread(listOf(lines(15), heading(level = 2), whole(20)), leftPageHeight = 100f, blockSpacing = 0f)

        assertEquals(listOf(0), blocksByPage(pages)[1])
        assertEquals(listOf(1, 2), blocksByPage(pages)[2])
    }

    @Test
    fun `続けて並ぶ見出しは連なりの頭でまとめて判定する`() {
        // 連なりの頭（1）の節を送る。途中の見出し（2）でも判定すると、その節も次の見開きの残りに入らないので、見出し1が見開きの頭に1つだけ残る。
        val nested = paginateSpread(listOf(lines(15), heading(level = 1), heading(level = 2), lines(30)), leftPageHeight = 100f, blockSpacing = 0f)
        assertEquals(listOf(0), blocksByPage(nested)[1])
        assertEquals(listOf(1, 2, 3), blocksByPage(nested)[2])

        // 連なりで最も浅い見出し（階層1）の節で判定する。後ろの階層2の見出し（4）では閉じず、長い段落まで含めて入らないので送る。
        // 連なりの先頭や末尾の見出しの階層（2）で判定すると、4で閉じて送らない。
        listOf(listOf(1, 2), listOf(2, 1)).forEach { levels ->
            val run = paginateSpread(
                listOf(lines(15)) + levels.map { heading(level = it) } + listOf(lines(2), heading(level = 2), lines(20)),
                leftPageHeight = 100f,
                blockSpacing = 0f
            )
            assertEquals("連なり$levels", listOf(0), blocksByPage(run)[1])
        }

        // 深い見出しのすぐ後に浅い見出し。深い見出しの節は本文が無いので、浅い見出しの節と合わせて送る。
        val shallower = paginateSpread(listOf(lines(15), heading(level = 3), heading(level = 2), lines(4)), leftPageHeight = 100f, blockSpacing = 0f)
        assertEquals(listOf(listOf(0), listOf(0), listOf(1, 2, 3)), blocksByPage(shallower))
    }

    /** 今の見開きの残りは題名の分だけ短い最初のページで数え、送り先の空の見開きは2つ目以降の高さで数える。 */
    @Test
    fun `最初の見開きは題名の分だけ短い高さで判定する`() {
        val blocks = listOf(lines(3), whole(17))

        val titled = paginateSpread(blocks, leftPageHeight = 100f, firstPageHeight = 60f, blockSpacing = 0f)
        assertEquals(listOf(listOf(0), emptyList(), listOf(1), listOf(1)), blocksByPage(titled))

        val untitled = paginateSpread(blocks, leftPageHeight = 100f, blockSpacing = 0f)
        assertEquals(listOf(listOf(0, 1), listOf(1)), blocksByPage(untitled))
    }

    @Test
    fun `測り終える前は、判定に要るブロックが測れていないまとまりの頭で止まり、止まった見開きを出さない`() {
        // 節の終わりがまだ測れていない。見出しの前で止め、止めたページを含む見開きは出さない。
        val stopped = paginateSpread(listOf(lines(15), heading(level = 2), lines(3)), leftPageHeight = 100f, blockSpacing = 0f, complete = false)
        assertEquals(listOf(listOf(0), listOf(0)), blocksByPage(stopped))
        assertEquals(0, readySpreadCount(stopped, complete = false))

        // 次の見出しまで測れば節の終わりが決まる。
        val decided = paginateSpread(
            listOf(lines(15), heading(level = 2), lines(3), heading(level = 2)),
            leftPageHeight = 100f,
            blockSpacing = 0f,
            complete = false
        )
        assertEquals(listOf(listOf(0), listOf(0, 1, 2)), blocksByPage(decided))

        // 測れた分だけで空の見開きに入らなければ、終わりまで測らずに決まる。
        val early = paginateSpread(listOf(lines(15), heading(level = 2), lines(30)), leftPageHeight = 100f, blockSpacing = 0f, complete = false)
        assertTrue(early.flatMap { it.slices }.any { it.blockIndex == 2 })
    }

    /** 測っている途中に出した見開きの中身が、測り終えた後で替わらない。送り直すと、読んでいる見開きの中身が後から替わる。 */
    @Test
    fun `測り終える前に出した見開きは、測り終えた後も中身が替わらない`() {
        val random = Random(26)
        var shownEarly = 0
        repeat(150) { round ->
            val blocks = randomKeepBlocks(random)
            val full = paginateSpread(blocks, leftPageHeight = 100f, firstPageHeight = 60f, blockSpacing = 4f)
            (0 until blocks.size).forEach { measured ->
                val partial = paginateSpread(blocks.take(measured), leftPageHeight = 100f, firstPageHeight = 60f, blockSpacing = 4f, complete = false)
                val ready = readySpreadCount(partial, complete = false)
                if (ready > 0) shownEarly++
                (0 until ready * 2).forEach { page ->
                    assertEquals("回$round 測った数$measured ページ$page", full.getOrNull(page), partial.getOrNull(page))
                }
            }
        }
        assertTrue("途中で出した見開きがある: $shownEarly", shownEarly > 100)
    }

    @Test
    fun `まとまりを送っても欠けず重ならずに組み終え、空の見開きに入るまとまりは1つの見開きに収まる`() {
        val random = Random(15)
        repeat(300) { round ->
            val blocks = randomKeepBlocks(random)
            val pages = paginateSpread(blocks, leftPageHeight = 100f, firstPageHeight = 60f, blockSpacing = 0f)

            assertCovered(blocks, pages)
            assertTrue("回$round 左ページが空", pages.filterIndexed { page, _ -> page % 2 == 0 }.all { it.slices.isNotEmpty() })
            // 20行（空の見開き）以下のまとまりは割れない。見出しのすぐ後のまとまりは見出しと一緒に判定するので除く。
            blocks.forEachIndexed { index, block ->
                if (block.keep != SpreadKeep.Whole || (block as SpreadBlock.Lines).lines.size > 20 || blocks[index - 1].keep is SpreadKeep.Heading) return@forEachIndexed
                val spreads = pages.indices.filter { page -> pages[page].slices.any { it.blockIndex == index } }.map { it / 2 }.distinct()
                assertEquals("回$round ブロック$index", 1, spreads.size)
            }
        }
    }

    /**
     * 本文を挟まない見出しが長く続いても、節が入るかの判定は連なりの頭から詰め直さない。手間はブロックの参照の数で固定する
     * （連なりの頭から詰め直すと、2万で約2億回になる）。
     */
    @Test(timeout = 20_000)
    fun `本文を挟まない見出しが長く続いても、組版の手間はブロックの数に比例する`() {
        listOf(8_000, 16_000, 20_000).forEach { count ->
            listOf(true, false).forEach { body ->
                val blocks = listOf(lines(15)) + List(count) { heading(lineHeight = 32f, level = 2) } + if (body) listOf(lines(3)) else emptyList()
                listOf(true, false).forEach { complete ->
                    val counted = CountingBlocks(blocks)
                    val pages = paginateSpread(counted, leftPageHeight = 600f, firstPageHeight = 540f, blockSpacing = 8f, complete = complete)

                    val label = "見出し$count 本文$body 測り終え$complete"
                    assertTrue("$label: 参照 ${counted.reads}", counted.reads <= READS_PER_BLOCK * blocks.size)
                    // 連なりだけで今の見開きの残りを越えるので、続きを測らなくても送ると決まる（止めない）。
                    assertEquals(label, listOf(listOf(0), emptyList(), listOf(1)), blocksByPage(pages).take(3).map { it.take(1) })
                    if (complete) assertEquals(label, blocks.indices.toList(), pages.flatMap { it.slices }.map { it.blockIndex }.distinct())
                }
            }
        }
    }

    /** 箇条や表が続いても、まとまりの頭ではそのブロックだけを空の見開きへ詰めてみる。見開き1つ分を詰めると、1ブロックあたり約70回になる。 */
    @Test(timeout = 20_000)
    fun `まとまりが続いても、空の見開きへ詰めてみるのはそのブロックだけ`() {
        val blocks = List(20_000) { whole(1) }
        val counted = CountingBlocks(blocks)
        paginateSpread(counted, leftPageHeight = 600f, firstPageHeight = 540f, blockSpacing = 8f)

        assertTrue("参照 ${counted.reads}", counted.reads <= READS_PER_BLOCK * blocks.size)
    }

    /** 要素数が最大の入力（→ lessons L52）。見出しとまとまりが2万あっても、判定を重ねずに終わる。 */
    @Test(timeout = 20_000)
    fun `まとまりが2万あっても、欠けず重ならずに組み終える`() {
        val blocks = List(20_000) { index ->
            when (index % 4) {
                0 -> heading(level = 1 + index / 4 % 3)
                1 -> lines(2)
                2 -> whole(3)
                else -> lines(1)
            }
        }
        val pages = paginateSpread(blocks, leftPageHeight = 100f, firstPageHeight = 60f, blockSpacing = 4f)

        assertCovered(blocks, pages)
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

    /**
     * 画面を離れて戻ると、読む位置は保存値から戻る。**留守の間の送り・別のノート・口の作り直しを、保存値と今の口を照らして見分ける。**
     * 保存値を戻すときに番号を今の番号で始めると、留守の間の送りを処理済みとみなして古い位置を出す。
     */
    @Test
    fun `保存した読む位置は、留守の間の送りと別のノートを見落とさずに今の口へ合わせる`() {
        val port = 7L
        val saved = SpreadReading("a", SpreadAnchor(40, charOffset = 120), parse = 1, port, seenRequest = 3)
        val list = { 0 }

        // 送りが無ければ文字の位置まで保つ。
        assertSame(saved, reconcileSpreadReading(saved, "a", 1, 200, port, BodyPositionRequest(3, 40, BodyPositionSource.SpreadTurn), list))
        // 留守の間の外からの送り（全画面から100で戻る）。同じブロックへの送りでも文字の位置は先頭へ戻る。
        assertEquals(SpreadAnchor(100), reconcileSpreadReading(saved, "a", 1, 200, port, BodyPositionRequest(4, 100, BodyPositionSource.External), list).anchor)
        assertEquals(SpreadAnchor(40), reconcileSpreadReading(saved, "a", 1, 200, port, BodyPositionRequest(4, 40, BodyPositionSource.External), list).anchor)
        // 別のノートを先頭から開いた。保存値の読む位置ではなく一覧の位置から始める。
        val other = reconcileSpreadReading(saved, "b", 1, 200, port, BodyPositionRequest(4, 0, BodyPositionSource.External)) { 0 }
        assertEquals(SpreadReading("b", SpreadAnchor(0), parse = 1, port, seenRequest = 4), other)
        // 送りが無くてもノートが違えば、前のノートの読む位置を持ち込まない。
        assertEquals(SpreadAnchor(5), reconcileSpreadReading(saved, "b", 1, 200, port, BodyPositionRequest(3, 40, BodyPositionSource.SpreadTurn)) { 5 }.anchor)
        // 口が作り直された（番号は最初から）。作った後に何も書いていなければ保ち、書いていれば送り先へ替える。
        val rebuilt = 9L
        assertEquals(SpreadAnchor(40, 120), reconcileSpreadReading(saved, "a", 1, 200, rebuilt, BodyPositionRequest(FIRST_BODY_REQUEST, 40, BodyPositionSource.External), list).anchor)
        assertEquals(SpreadAnchor(2), reconcileSpreadReading(saved, "a", 1, 200, rebuilt, BodyPositionRequest(1, 2, BodyPositionSource.External), list).anchor)
        // 照合した後は処理済み。もう一度照らしても替わらない。
        val reconciled = reconcileSpreadReading(saved, "a", 1, 200, port, BodyPositionRequest(4, 100, BodyPositionSource.External), list)
        assertSame(reconciled, reconcileSpreadReading(reconciled, "a", 1, 200, port, BodyPositionRequest(4, 100, BodyPositionSource.External), list))
    }

    /**
     * 同じノートで本文の解析結果が替わると、読む位置の文字の位置は古い表示文字列のもの。**画面の照合で0に戻す。**
     * 幅だけの組み直しは解析の鍵が替わらないので保つ。解析し直しと外からの送りが重なれば送りを採る。
     */
    @Test
    fun `保存した読む位置は、解析し直しでブロックを保って文字の位置を0に戻す`() {
        val port = 7L
        val saved = SpreadReading("a", SpreadAnchor(3, charOffset = 120), parse = 1, port, seenRequest = 3)
        val seen = BodyPositionRequest(3, 3, BodyPositionSource.SpreadTurn)
        val list = { 0 }

        assertSame(saved, reconcileSpreadReading(saved, "a", parse = 1, blockCount = 10, port, seen, list))
        assertEquals(SpreadReading("a", SpreadAnchor(3), parse = 2, port, seenRequest = 3), reconcileSpreadReading(saved, "a", parse = 2, blockCount = 10, port, seen, list))
        assertEquals(SpreadAnchor(1), reconcileSpreadReading(saved, "a", parse = 2, blockCount = 2, port, seen, list).anchor)
        val sent = BodyPositionRequest(4, 8, BodyPositionSource.External)
        assertEquals(SpreadAnchor(8), reconcileSpreadReading(saved, "a", parse = 2, blockCount = 10, port, sent, list).anchor)
    }

    /**
     * **保存値を戻すとき、ブロックはいつも一覧から取る**（→ features/note_spread.md §8 判断14）。保存値は見開きを組むまで消費されずに残るので、
     * その間にふだんの画面で読み進めた位置を、要求の番号や解析結果で「未処理」と見て古いほうへ戻さない。設計レビュー2回の順序を具体値で固定する。
     */
    @Test
    fun `保存値を戻すときは一覧のブロックから始め、条件を満たすときだけ文字の位置を借りる`() {
        val port = 7L
        val saved = SpreadReading("a", SpreadAnchor(40, charOffset = 120), parse = 1, port, seenRequest = 3)
        val turned = BodyPositionRequest(3, 40, BodyPositionSource.SpreadTurn)
        fun restore(listBlock: Int, request: BodyPositionRequest = turned, parse: Int = 1, blockCount: Int = 200, at: Long = port): SpreadAnchor {
            val base = restoreSpreadReading(saved, "a", parse, blockCount, at, request) { listBlock }
            return reconcileSpreadReading(base, "a", parse, blockCount, at, request) { listBlock }.anchor
        }

        // ふだんの画面で指で100まで読んだ（口を通らない）。
        assertEquals(SpreadAnchor(100), restore(listBlock = 100))
        // 縦の流しでリンクの送り（ブロック10）を適用した後に、指で100まで読んだ。送りは見開きにとって未処理でも、一覧のほうが新しい。
        assertEquals(SpreadAnchor(100), restore(listBlock = 100, request = BodyPositionRequest(4, 10, BodyPositionSource.External)))
        // 留守の間に同じノートを解析し直し、その後に指で100まで読んだ。
        assertEquals(SpreadAnchor(100), restore(listBlock = 100, parse = 2))
        // 見開きへ直接戻った（一覧は見開きが書き戻した40のまま）。文字の位置まで保つ。口を作り直した復元も同じ。
        assertEquals(SpreadAnchor(40, 120), restore(listBlock = 40))
        assertEquals(SpreadAnchor(40, 120), restore(listBlock = 40, request = BodyPositionRequest(FIRST_BODY_REQUEST, 40, BodyPositionSource.External), at = 9L))
        // 同じブロックへの新しい外からの送り（口は送りと一覧を同時に書く）。文字の位置は0。
        assertEquals(SpreadAnchor(40), restore(listBlock = 40, request = BodyPositionRequest(4, 40, BodyPositionSource.External)))
        // 全画面からの送り・同じノートを先頭で開き直す送りは、一覧も送り先になっているので送り先から。
        assertEquals(SpreadAnchor(100), restore(listBlock = 100, request = BodyPositionRequest(4, 100, BodyPositionSource.External)))
        assertEquals(SpreadAnchor(0), restore(listBlock = 0, request = BodyPositionRequest(4, 0, BodyPositionSource.External)))
        // 解析し直しで同じブロックに留まっても、文字の位置は古い表示文字列のものなので0。
        assertEquals(SpreadAnchor(40), restore(listBlock = 40, parse = 2))
        // 一覧がブロックの数を超えていれば範囲に収める。
        assertEquals(SpreadAnchor(1), restore(listBlock = 3, parse = 2, blockCount = 2))
    }

    /** 戻すときの規則は、見開きを組み始めたときだけ。保存値が無い・別のノートのときは照合に任せ、戻した値をもう一度照らしても替わらない。 */
    @Test
    fun `保存値を戻す規則は、保存値が無いか別のノートなら照合に任せる`() {
        val port = 7L
        val saved = SpreadReading("a", SpreadAnchor(40, charOffset = 120), parse = 1, port, seenRequest = 3)
        val request = BodyPositionRequest(3, 40, BodyPositionSource.SpreadTurn)
        assertNull(restoreSpreadReading(null, "a", 1, 200, port, request) { 100 })
        assertSame(saved, restoreSpreadReading(saved, "b", 1, 200, port, request) { 100 })
        assertSame(saved, restoreSpreadReading(saved, "a", 1, 200, port, request) { 40 })
        val restored = restoreSpreadReading(saved, "a", 1, 200, port, request) { 100 }
        assertSame(restored, reconcileSpreadReading(restored, "a", 1, 200, port, request) { 100 })
    }

    /**
     * 見開きを出している間に解析し直しで読む位置を範囲へ収めると（3→1）、見開きは一覧へ書き戻す。めくらずに離れて直接戻ると (1, 0)。
     * 書き戻しが無くても、戻すときの規則が範囲に収めるので古い3へは戻らない。
     */
    @Test
    fun `表示中に範囲へ収めた読む位置は、離れて直接戻っても保つ`() {
        val port = 7L
        val shown = SpreadReading("a", SpreadAnchor(3, charOffset = 120), parse = 1, port, seenRequest = 3)
        val request = BodyPositionRequest(3, 3, BodyPositionSource.SpreadTurn)
        val clamped = reconcileSpreadReading(shown, "a", parse = 2, blockCount = 2, port, request) { 3 }
        assertEquals(SpreadAnchor(1), clamped.anchor)
        val written = BodyPositionRequest(4, 1, BodyPositionSource.SpreadTurn)
        val back = restoreSpreadReading(clamped.copy(seenRequest = 4), "a", 2, 2, port, written) { 1 }
        assertEquals(SpreadAnchor(1), back?.anchor)
        assertEquals(SpreadAnchor(1), restoreSpreadReading(clamped, "a", 2, 2, port, request) { 3 }?.anchor)
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

    // ── 引用の中（→ docs/dev/system/markdown_rendering.md §8）──────────────────

    /**
     * 引用の子の単位をそのまま引用の単位にする。子の間の余白と子の余白は子の最初の単位に足し、
     * 子の途中から始まる切れ端に足す余白（子の余白）を持つ。表の行は割らずに1単位にする。
     */
    @Test
    fun `引用は子の単位を並べ、子の間と子の余白を最初の単位に足す`() {
        val header = SpreadBlock.Lines(listOf(SpreadLine(12f, 0)), textLength = 4)
        val code = SpreadBlock.Lines(List(3) { SpreadLine(10f, it * 5) }, textLength = 15, fragmentPadding = 24f)
        val table = SpreadBlock.Table(listOf(tableRow(listOf(2, 1), 10f, 6f, 0), tableRow(listOf(1, 1), 10f, 6f, 30)), textLength = 50)

        val (quote, layouts) = quoteSpreadBlock(listOf(header, lines(2), code, table), spacing = 8f, padding = 8f)

        assertEquals(
            listOf(
                SpreadLine(12f, 0),
                SpreadLine(18f, 5), SpreadLine(10f, 25),
                SpreadLine(42f, 46), SpreadLine(10f, 51), SpreadLine(10f, 56),
                SpreadLine(34f, 62), SpreadLine(16f, 92)
            ),
            quote.lines
        )
        assertEquals(listOf(0f, -8f, 0f, -8f, 24f, 24f, -8f, 0f), quote.startAdjust)
        assertEquals(112, quote.textLength)
        assertEquals(8f, quote.fragmentPadding)
        assertEquals(
            listOf(
                QuoteChildLayout(0, listOf(0), 4),
                QuoteChildLayout(1, listOf(0, 20), 40),
                QuoteChildLayout(3, listOf(0, 5, 10), 15),
                QuoteChildLayout(6, listOf(0, 30), 50)
            ),
            layouts
        )
    }

    @Test
    fun `入れ子の引用の途中から始まる切れ端は、外と内の子の余白を足す`() {
        // 内の引用（上下の余白8）の中に、コード（余白24）が入っている。
        val inner = SpreadBlock.Lines(List(3) { SpreadLine(10f, it * 5) }, textLength = 15, fragmentPadding = 8f, startAdjust = listOf(0f, 24f, 24f))

        val (outer, _) = quoteSpreadBlock(listOf(inner), spacing = 8f, padding = 8f)

        assertEquals(listOf(0f, 32f, 32f), outer.startAdjust)
    }

    @Test
    fun `空の引用は単位を持たず、ページに置かない`() {
        val (quote, layouts) = quoteSpreadBlock(emptyList(), spacing = 8f, padding = 8f)

        assertEquals(0, quote.textLength)
        assertTrue(layouts.isEmpty())
        assertEquals(listOf(SpreadPage(listOf(SpreadSlice(1, 0, 1, 0, 20, 10f)))), paginateSpread(listOf(quote, lines(1)), 100f, blockSpacing = 8f))
    }

    @Test
    fun `引用の切れ端から、子が出す単位と文字を子の側で数え直す`() {
        val layouts = listOf(
            QuoteChildLayout(0, listOf(0), 4),
            QuoteChildLayout(1, listOf(0, 20), 40),
            QuoteChildLayout(3, listOf(0, 5, 10), 15),
            QuoteChildLayout(6, listOf(0, 30), 50)
        )
        val slice = SpreadSlice(blockIndex = 2, unitStart = 2, unitEnd = 5, charStart = 25, charEnd = 56, height = 60f)

        assertEquals(
            listOf(null, SpreadSlice(2, 1, 2, 20, 40, 0f), SpreadSlice(2, 0, 2, 0, 10, 0f), null),
            layouts.map { quoteChildSlice(slice, it) }
        )
    }

    /** 子の途中から始まるページは、その子の余白をもう一度描くので、その分だけ早く次のページへ送る。 */
    @Test
    fun `子の途中から始まる切れ端は、子の余白を足してページへ詰める`() {
        val quote = SpreadBlock.Lines(List(6) { SpreadLine(10f, it * 10) }, textLength = 60, startAdjust = listOf(0f, 0f, 24f, 24f, 24f, 24f))

        val pages = paginateSpread(listOf(quote), leftPageHeight = 40f, blockSpacing = 8f)

        assertEquals(listOf(0 until 4, 4 until 5, 5 until 6), pages.map { page -> page.slices.single().let { it.unitStart until it.unitEnd } })
        assertEquals(listOf(40f, 34f, 34f), pages.map { it.slices.single().height })
    }

    /** 子の先頭から始まる切れ端は子の間の余白を描かないので、数えない（入れ子の子の先頭でも同じ）。 */
    @Test
    fun `子の先頭から始まる引用の切れ端は、描かれない子の間の余白を数えない`() {
        val (quote, _) = quoteSpreadBlock(
            listOf(SpreadBlock.Lines(listOf(SpreadLine(40f, 0)), 4), SpreadBlock.Lines(listOf(SpreadLine(20f, 0)), 4)),
            spacing = 8f,
            padding = 8f
        )

        val pages = paginateSpread(listOf(quote, SpreadBlock.Lines(listOf(SpreadLine(12f, 0)), 4)), leftPageHeight = 48f, blockSpacing = 8f)

        assertEquals(2, pages.size)
        assertEquals(28f, pages[1].slices.first().height)
        assertEquals(1, pages[1].slices.last().blockIndex)
    }

    @Test
    fun `入れ子の引用の子の先頭から始まる切れ端は、内の引用の余白だけを足す`() {
        val child = { height: Float -> SpreadBlock.Lines(listOf(SpreadLine(height, 0)), 4) }
        val (inner, _) = quoteSpreadBlock(listOf(child(10f), child(10f)), spacing = 8f, padding = 8f)

        val (outer, _) = quoteSpreadBlock(listOf(child(30f), inner), spacing = 8f, padding = 8f)

        // 外の単位: 外の1つ目の子・内の1つ目の子（外の子の間と内の上下の余白を含む）・内の2つ目の子（内の子の間を含む）。
        assertEquals(listOf(30f, 26f, 18f), outer.lines.map { it.height })
        // 内の引用の頭から: 外の子の間を描かない。内の2つ目の子から: 内の上下の余白を描き、内の子の間を描かない。
        assertEquals(listOf(0f, -8f, 0f), outer.startAdjust)
        val pages = paginateSpread(listOf(outer), leftPageHeight = 70f, blockSpacing = 8f)
        assertEquals(listOf(0 until 2, 2 until 3), pages.map { page -> page.slices.single().let { it.unitStart until it.unitEnd } })
        assertEquals(8f + 8f + 10f, pages[1].slices.single().height)
    }

    /** リストは項目の間の余白を2つ目以降の項目の最初の行に足し、その行から始まる切れ端では描かれないので引く。 */
    @Test
    fun `リストの項目の間の余白は、項目の先頭から始まる切れ端では数えない`() {
        val list = listSpreadBlock(listOf(listOf(SpreadLine(24f, 0)), listOf(SpreadLine(24f, 5), SpreadLine(24f, 8))), spacing = 4f, textLength = 12)

        assertEquals(listOf(SpreadLine(24f, 0), SpreadLine(28f, 5), SpreadLine(24f, 8)), list.lines)
        assertEquals(listOf(0f, -4f, 0f), list.startAdjust)
        // 本文に直に置いたリストも同じ。2項目目から始まるページは項目の文字の高さだけ。
        val pages = paginateSpread(listOf(list), leftPageHeight = 30f, blockSpacing = 8f)
        assertEquals(listOf(24f, 24f, 24f), pages.map { it.slices.single().height })
    }

    @Test
    fun `引用の中のリストの項目の先頭から始まる切れ端は、項目の間の余白を数えない`() {
        val list = listSpreadBlock(listOf(listOf(SpreadLine(24f, 0)), listOf(SpreadLine(24f, 5))), spacing = 4f, textLength = 9)
        val (quote, _) = quoteSpreadBlock(listOf(list), spacing = 8f, padding = 8f)

        val pages = paginateSpread(listOf(quote, SpreadBlock.Lines(listOf(SpreadLine(16f, 0)), 4)), leftPageHeight = 56f, blockSpacing = 8f)

        assertEquals(2, pages.size)
        assertEquals(32f, pages[1].slices.first().height)
        // 入れ子の引用の中でも、内の上下の余白を足して項目の間の余白を引く。
        val (inner, _) = quoteSpreadBlock(listOf(list), spacing = 8f, padding = 8f)
        assertEquals(listOf(0f, 4f), quoteSpreadBlock(listOf(inner), spacing = 8f, padding = 8f).first.startAdjust)
    }

    // ── 補助 ──────────────────────────────────────────────────────────

    private fun lines(count: Int, lineHeight: Float = 10f, charsPerLine: Int = 20) =
        SpreadBlock.Lines(List(count) { SpreadLine(lineHeight, it * charsPerLine) }, textLength = count * charsPerLine)

    private fun heading(lineHeight: Float = 10f, level: Int = 1) =
        SpreadBlock.Lines(listOf(SpreadLine(lineHeight, 0)), textLength = 4, keep = SpreadKeep.Heading(level))

    /** 見開きの境目で割らないまとまり（箇条・表・引用・コードの代わり）。 */
    private fun whole(count: Int) = lines(count).copy(keep = SpreadKeep.Whole)

    /** 組版がブロックを読む回数の上限（1ブロックあたり）。実測は見出しの連なりで6回、まとまりの連なりで7回。 */
    private val READS_PER_BLOCK = 20L

    /** ブロックを読んだ回数を数える本文。組版の手間を、時間ではなく参照の数で固定する。 */
    private class CountingBlocks(private val inner: List<SpreadBlock>) : AbstractList<SpreadBlock>() {
        var reads = 0L
        override val size: Int get() = inner.size
        override fun get(index: Int): SpreadBlock {
            reads++
            return inner[index]
        }
    }

    /** ブロック番号をページごとに並べる（空のページは空の並び）。 */
    private fun blocksByPage(pages: List<SpreadPage>): List<List<Int>> = pages.map { page -> page.slices.map { it.blockIndex } }

    /** 本文の先頭は段落で、続く見出し・段落・まとまり・画像・水平線を混ぜる。高さは行10・ページ100で数えやすくする。 */
    private fun randomKeepBlocks(random: Random): List<SpreadBlock> =
        listOf(lines(1 + random.nextInt(12))) + List(5 + random.nextInt(30)) {
            when (random.nextInt(6)) {
                0 -> heading(level = 1 + random.nextInt(3))
                1, 2 -> lines(1 + random.nextInt(12))
                3 -> whole(1 + random.nextInt(25))
                4 -> SpreadBlock.Image(30f + random.nextInt(120))
                else -> SpreadBlock.Rule(5f)
            }
        }

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
