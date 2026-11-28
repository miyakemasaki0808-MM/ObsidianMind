package com.example.newproject.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.domain.markdown.NoteSectionModel
import com.example.newproject.domain.markdown.buildNoteSectionModel
import com.example.newproject.ui.markdown.NoteImageMeasurement
import com.example.newproject.ui.markdown.NoteImageMeasurements
import com.example.newproject.ui.screen.BodyPosition
import com.example.newproject.ui.screen.NoteSpread
import com.example.newproject.ui.theme.AppTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **組版の途中に操作を挟んでも、古い組版の結果を表示・読む位置・読書の記録へ出さない**（→ features/note_spread.md §10 の順序の表）。
 * 組版と操作の前後は、測る前に待つ口（`measureGate`）で固定する。時計や実時間に頼ると、端末の速さで途中の形が変わる。
 * 止め方は2つ — 何も組めていない形は最初のブロックの前で止め（[startHeldBeforeAny]）、途中まで出た形は途中の組版を出させてから止める
 * （[startHeldAfterFirstSpread]）。途中まで出た形では操作の前に正当な記録が出うるので、記録は操作の後の分だけを見る。
 *
 * JVM では書けない — 確かめるのは、組版のジョブと保存値と一覧の位置が実際の合成の中でどう交わるかである（純関数の規則は `SpreadPaginationTest` が持つ）。
 * 縦の流しの送りの途中に見開きへ替わる順序だけは、読書画面の配線を通すので `NoteReadingFlowTest` が持つ。
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SpreadInterleavingTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val listState = LazyListState()
    private val position = BodyPosition(listState)
    private val hold = MeasureHold()
    private val reports = mutableListOf<Report>()
    private val measurements = NoteImageMeasurements()

    private var note by mutableStateOf(Note(NOTE_A, buildNoteSectionModel(body("A", 200))))
    private var shown by mutableStateOf(true)
    private var card by mutableStateOf(false)
    private var pageWidth by mutableStateOf(392.dp)
    private var fontScale by mutableStateOf(1f)

    /** 見開きへ渡す本文の位置の口と、見開きを初めから組み直すための世代（対照を組むときに替える）。 */
    private var spreadPosition by mutableStateOf(position)
    private var generation by mutableStateOf(0)

    /**
     * 見開きは 800dp の箱で組むので、狭い窓では箱が中央へ寄って左ページが窓の外へ出る。表示の確認と見えている段落の取り出しが
     * 窓の幅で落ちるので、机を出す幅の窓でだけ流す（→ docs/dev/system/instrumentation_testing.md 判断11）。
     */
    @Before
    fun requireDeskWindow() {
        assumeDeskWindow()
    }

    /** ブロックの番号から段落の番号を引く数。画像を先頭に置いたノートでは1つずれる。 */
    private var blocksBeforeParagraphs = 0

    /** 組版Aを始める → 別のノートBを開く → Aが終わる。 */
    @Test
    fun 組版の途中に別のノートを開くと_前のノートの結果を表示にも位置にも記録にも出さない() {
        startHeldAfterFirstSpread("A")

        val other = buildNoteSectionModel(body("B", 60))
        val mark = composeRule.runOnIdle {
            hold.holdAt = null
            note = Note(NOTE_B, other)
            position.moveTo(0)
            reports.size
        }
        hold.open()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(paragraph("B", 0)).assertIsDisplayed()
        composeRule.onAllNodesWithText("A段落", substring = true).assertCountEquals(0)
        composeRule.runOnIdle {
            assertEquals("別のノートの読む位置が先頭でない", 0, listState.firstVisibleItemIndex)
            val after = reports.drop(mark)
            assertTrue("読書の記録が届いていない", after.isNotEmpty())
            assertTrue("前のノートの記録を送った: $after", after.all { it.total == other.blocks.size })
        }
    }

    /** 組版を始める → 同じノートの解析結果が替わる（短くなる）→ 古い組版が終わる。ブロックは新しい範囲へ収める。 */
    @Test
    fun 組版の途中に同じノートの解析結果が替わると_新しい本文だけを出し_読む位置を範囲へ収める() {
        position.moveTo(150)
        startHeldBeforeAny()

        val reparsed = buildNoteSectionModel(body("改", 60))
        composeRule.runOnIdle {
            hold.holdAt = null
            note = Note(NOTE_A, reparsed)
        }
        hold.open()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(paragraph("改", 59)).assertIsDisplayed()
        composeRule.onAllNodesWithText("A段落", substring = true).assertCountEquals(0)
        composeRule.runOnIdle {
            assertEquals("読む位置を新しい範囲へ収めていない", 59, listState.firstVisibleItemIndex)
            assertTrue("古い解析結果の記録を送った: $reports", reports.all { it.total == reparsed.blocks.size })
        }
    }

    /** 組版を始める → 別の場所へのリンク・続きから読む → 古い読む位置を持つ組版が終わる。 */
    @Test
    fun 組版の途中に外から送ると_送り先の見開きを出し_古い位置の記録を送らない() {
        startHeldAfterFirstSpread("A")

        val mark = composeRule.runOnIdle {
            position.moveTo(120)
            reports.size
        }
        hold.holdAt = null
        hold.open()
        composeRule.waitForIdle()

        assertSentTo(120, mark)
    }

    /**
     * 同じ順序で、組版の途中に再会カードを畳んで左ページの高さが変わる。**カードを出して高さを測らせてから止める** —
     * 最初のブロックの前で止めると「組んでいます」のまま操作が終わり、カードを置く処理にも高さを受け取る処理にも届かないので、
     * 測った高さが畳んだ後の組版に残る不具合を見逃す。結果は、同じ送り先・畳んだ後の寸法で初めから組んだ対照と、左右のページの段落で比べる。
     */
    @Test
    fun 組版の途中に外から送ってカードを畳んでも_送り先の見開きを畳んだ高さで出す() {
        card = true
        startHeldAfterFirstSpread("A")
        composeRule.onNodeWithTag(CARD).assertIsDisplayed().assertHeightIsEqualTo(120.dp)

        val mark = composeRule.runOnIdle {
            position.moveTo(120)
            card = false
            reports.size
        }
        // カードの高さが替わるだけなら組版は止まったまま続く。
        hold.holdAt = null
        hold.open()
        composeRule.waitForIdle()

        assertSentTo(120, mark)
        val interleaved = shownPages()

        // 対照: 畳んだ後の寸法・同じ送り先で、新しい見開きとして初めから組む。
        composeRule.runOnIdle {
            spreadPosition = BodyPosition(LazyListState(120))
            generation++
        }
        composeRule.waitForIdle()
        assertEquals("畳んだ高さで初めから組んだ対照と、左右のページの段落が違う", shownPages(), interleaved)
    }

    /** 画像の寸法が届く → 組み直しを始める → めくりが決まる → 組み直しが終わる。 */
    @Test
    fun 組み直しの途中にめくっても_めくった位置を保ち_古い見開きへ戻さない() {
        setImageNote()
        setSpread()
        composeRule.waitForIdle()
        val turned = sendNextSpread()

        // 途中の組版を出してから止める。出していないと、めくれる見開きが無い。
        hold.publishAt = PUBLISH_BLOCK
        hold.holdAt = PUBLISH_BLOCK + 1
        val before = hold.reached
        measureImage()
        composeRule.waitUntil(WAIT_MILLIS) { hold.reached > before }
        composeRule.waitForIdle()

        val next = sendNextSpread()
        assertTrue("組み直しの途中にめくれていない（$turned → $next）", next > turned)
        hold.holdAt = null
        hold.open()
        composeRule.waitForIdle()

        assertSpreadAt(next)
    }

    /** 逆の順。めくりが決まる → 画像の寸法が届く → 組み直しが終わる。 */
    @Test
    fun めくった後に画像の寸法が届いて組み直しても_めくった位置を保つ() {
        setImageNote()
        setSpread()
        composeRule.waitForIdle()
        val turned = sendNextSpread()

        hold.holdAt = 40
        val before = hold.reached
        measureImage()
        composeRule.waitUntil(WAIT_MILLIS) { hold.reached > before }
        hold.holdAt = null
        hold.open()
        composeRule.waitForIdle()

        assertSpreadAt(turned)
    }

    /**
     * 組版の途中に見開きを離れる → 古い結果が終わる。閉じる・横持ち・✎・全画面はどれも、読書画面から見開きを外す操作なので、
     * ここでは外すことまでを見る（どの操作が外すかは `NoteReadingFlowTest` が持つ）。
     */
    @Test
    fun 組版の途中に見開きを離れると_古い結果が一覧の位置を書かず_記録も送らない() {
        startHeldAfterFirstSpread("A")

        val mark = composeRule.runOnIdle {
            shown = false
            reports.size
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle { position.moveTo(77) }
        hold.holdAt = null
        hold.open()
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals("離れた後に古い結果が一覧の位置を書いた", 77, listState.firstVisibleItemIndex)
            assertTrue("離れた後に古い結果の記録を送った: ${reports.drop(mark)}", reports.size == mark)
        }
    }

    /**
     * 文字の倍率・幅・カードの高さを続けて変える。最新の寸法だけで組み、読む位置を保つ。
     * 結果は、最後の寸法で初めから組んだ対照と、見えている段落の並びで比べる（カードの高さを無視した組版や、途中の寸法が残った組版は並びがずれる）。
     */
    @Test
    fun 幅と文字の倍率とカードを続けて変えても_最後の寸法で初めから組んだ対照と同じに出す() {
        setSpread()
        composeRule.waitForIdle()
        val turned = sendNextSpread()

        hold.holdAt = 40
        var before = hold.reached
        composeRule.runOnIdle { pageWidth = NARROW_PAGE }
        composeRule.waitUntil(WAIT_MILLIS) { hold.reached > before }
        before = hold.reached
        composeRule.runOnIdle { fontScale = LARGE_FONT }
        composeRule.waitUntil(WAIT_MILLIS) { hold.reached > before }
        composeRule.runOnIdle { card = true }
        hold.holdAt = null
        hold.open()
        composeRule.waitForIdle()

        assertSpreadAt(turned)
        val interleaved = shownParagraphs()

        // 対照: 同じ最後の寸法・同じ読む位置で、新しい見開きとして初めから組む。
        composeRule.runOnIdle {
            spreadPosition = BodyPosition(LazyListState(turned))
            generation++
        }
        composeRule.waitForIdle()
        assertEquals("最後の寸法で初めから組んだ対照と、見えている段落が違う", shownParagraphs(), interleaved)
    }

    /**
     * **カードの高さは左ページの組版から引く。** 対照と比べるだけだと、カードの高さを無視する組版は対照にも同じように効くので、
     * カードを出す前後の見開きを独立に確かめる。左ページの段落が減るだけでは足りない — 組版がカードを無視しても、描く側でカードが
     * 高さを取るので下の段落が切れて減って見える。組み直したときだけ、左から押し出した段落が右ページの先頭へ移り、境目で番号が続く。
     */
    @Test
    fun カードを出すと左ページに入る段落が減り_見開きの境目が変わる() {
        setSpread()
        composeRule.waitForIdle()
        val before = shownPages()
        assertEquals("カードを出す前から境目で番号が飛んでいる（$before）", before.left.last() + 1, before.right.first())

        composeRule.runOnIdle { card = true }
        composeRule.waitForIdle()
        val after = shownPages()

        assertTrue("カードを出しても左ページの段落が減らない（$before → $after）", after.left.size < before.left.size)
        assertEquals("左ページの先頭が替わった", before.left.first(), after.left.first())
        assertTrue("右ページの先頭が前へ移らない（$before → $after）", after.right.first() < before.right.first())
        assertEquals("左右の境目で段落の番号が飛んだ（$after）", after.left.last() + 1, after.right.first())
        val pushed = (after.left.last() + 1)..before.left.last()
        assertTrue("左ページから押し出した段落 $pushed が右ページに見えない（$after）", pushed.all { it in after.right })
    }

    /** 左右のページに見えている段落の番号を、上から順に（ページャの中央で左右を分ける）。 */
    private fun shownPages(): ShownPages {
        val center = composeRule.onNode(PAGER).fetchSemanticsNode().boundsInRoot.center.x
        val nodes = composeRule.onAllNodesWithText("A段落", substring = true)
        val shown = (0 until nodes.fetchSemanticsNodes().size)
            .map { nodes[it] }
            .filter { it.isDisplayed() }
            .map { it.fetchSemanticsNode() }
            .sortedBy { it.boundsInRoot.top }
        fun numbers(onLeft: Boolean) = shown
            .filter { (it.boundsInRoot.right <= center) == onLeft }
            .map { node -> paragraphNumber(node.config[SemanticsProperties.Text].joinToString("")) }
        return ShownPages(left = numbers(onLeft = true), right = numbers(onLeft = false))
    }

    /** いま見えている段落の文字を、上から順に。 */
    private fun shownParagraphs(): List<String> {
        val nodes = composeRule.onAllNodesWithText("A段落", substring = true)
        return (0 until nodes.fetchSemanticsNodes().size)
            .map { nodes[it] }
            .filter { it.isDisplayed() }
            .map { it.fetchSemanticsNode() }
            .sortedWith(compareBy({ it.boundsInRoot.left }, { it.boundsInRoot.top }))
            .map { it.config[SemanticsProperties.Text].joinToString("") }
    }

    private fun setImageNote() {
        note = Note(NOTE_A, buildNoteSectionModel("![](assets/picture.png)\n\n" + body("A", 200)))
        blocksBeforeParagraphs = 1
    }

    private fun measureImage() {
        composeRule.runOnIdle {
            val image = note.model.blocks.filterIsInstance<MarkdownBlock.Image>().single()
            measurements.record(image, NoteImageMeasurement.Measured(IMAGE_WIDTH, IMAGE_HEIGHT))
        }
    }

    private fun setSpread() {
        composeRule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                AppTheme(darkTheme = false) {
                    Box(modifier = Modifier.requiredSize(width = 800.dp, height = 600.dp)) {
                        if (shown) key(generation) {
                            NoteSpread(
                                noteKey = note.key,
                                sectionModel = note.model,
                                position = spreadPosition,
                                paperColor = Color.White,
                                imageLoader = null,
                                imageMeasurements = measurements,
                                headingAccessory = null,
                                onLink = null,
                                onReadingProgress = { block, _, total, _ -> reports += Report(block, total) },
                                pageWidth = pageWidth,
                                gutter = 16.dp,
                                // 中身の無い Box は幅の最小0で測られ、表示の確認で落ちる。本物のカードと同じくページの幅に広げる。
                                leftPageTop = if (card) ({ Box(modifier = Modifier.fillMaxWidth().height(120.dp).testTag(CARD)) }) else null,
                                measureGate = hold::gate
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * 最初のブロックを測る前で止めて組み始め、まだ何も組めていないことを確かめる。組版の途中に操作を挟めたことの前提。
     * 止める位置は組み立てる前に決める（組み立てた後だと、組版の始まりと競り合う）。
     */
    private fun startHeldBeforeAny() {
        hold.holdAt = 0
        setSpread()
        composeRule.waitUntil(WAIT_MILLIS) { hold.reached > 0 }
        composeRule.onNodeWithText("組んでいます").assertIsDisplayed()
    }

    /**
     * 最初の見開きを組める分まで測らせ、途中の組版を出させてから止める。最初の見開きが出ていることを確かめる。
     * 古い結果が操作の後に残らないことを、見えている結果から確かめられる形にする。
     */
    private fun startHeldAfterFirstSpread(note: String) {
        hold.publishAt = FIRST_SPREAD_BLOCKS
        hold.holdAt = FIRST_SPREAD_BLOCKS + 1
        setSpread()
        composeRule.waitUntil(WAIT_MILLIS) { hold.reached > 0 }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(paragraph(note, 0)).assertIsDisplayed()
    }

    /** 読み上げの操作で次の見開きへ送り、止まった後の読む位置のブロックを返す。 */
    private fun sendNextSpread(): Int {
        val next = composeRule.onNode(PAGER).fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == "次の見開きへ" }
        composeRule.runOnUiThread { assertTrue(next.action()) }
        composeRule.waitForIdle()
        return composeRule.runOnIdle { listState.firstVisibleItemIndex }
    }

    /** 送り先の見開きが出て、送った後の記録が送り先より手前を指さない（[mark] より前の記録は送る前の正当なもの）。 */
    private fun assertSentTo(block: Int, mark: Int) {
        assertSpreadAt(block)
        composeRule.runOnIdle {
            val after = reports.drop(mark)
            assertTrue("送り先より手前の記録を送った: $after", after.all { it.block >= block })
        }
    }

    private fun assertSpreadAt(block: Int) {
        composeRule.runOnIdle { assertEquals("読む位置が替わった", block, spreadPosition.listState.firstVisibleItemIndex) }
        composeRule.onNodeWithText(paragraph("A", block - blocksBeforeParagraphs)).assertIsDisplayed()
    }

    /** 組版を止める口。[holdAt] のブロックの前で [open] まで待ち、[publishAt] の前では組版が途中を出すだけの時間を過ごす。 */
    private class MeasureHold {
        @Volatile var holdAt: Int? = null
        @Volatile var publishAt: Int? = null
        @Volatile var reached = 0
        private val release = CompletableDeferred<Unit>()

        suspend fun gate(block: Int) {
            if (block == publishAt) Thread.sleep(MEASURE_SLICE_MILLIS)
            if (block == holdAt) {
                reached++
                release.await()
            }
        }

        fun open() {
            release.complete(Unit)
        }
    }

    private data class Note(val key: String, val model: NoteSectionModel)
    private data class Report(val block: Int, val total: Int)
    private data class ShownPages(val left: List<Int>, val right: List<Int>)

    private companion object {
        const val NOTE_A = "content://vault/a.md"
        const val NOTE_B = "content://vault/b.md"
        const val CARD = "再会カード"
        const val WAIT_MILLIS = 5_000L

        /** 組版は約4msごとに途中を出すので、それより長く過ごす。 */
        const val MEASURE_SLICE_MILLIS = 6L

        /** 途中を出すブロック。次の見開きへ送れるだけ組めている。 */
        const val PUBLISH_BLOCK = 150

        /** 最初の見開きを組める段落の数（1行の段落40個で3ページになる）。 */
        const val FIRST_SPREAD_BLOCKS = 40
        const val IMAGE_WIDTH = 1_200
        const val IMAGE_HEIGHT = 1_600
        const val LARGE_FONT = 1.3f
        val NARROW_PAGE = 320.dp
        val PAGER = SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)

        fun paragraph(note: String, index: Int) = "${note}段落$index の本文です。"
        fun paragraphNumber(text: String) = Regex("段落(\\d+) ").find(text)!!.groupValues[1].toInt()
        fun body(note: String, count: Int) = (0 until count).joinToString("\n\n") { paragraph(note, it) }
    }
}
