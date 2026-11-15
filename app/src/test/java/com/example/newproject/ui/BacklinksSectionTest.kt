package com.example.newproject.ui

import com.example.newproject.model.DocumentRef
import com.example.newproject.model.RelatedNote
import com.example.newproject.model.state.BacklinkRow
import com.example.newproject.model.state.BacklinksState
import com.example.newproject.model.state.KnowledgeIndexProgress
import com.example.newproject.ui.screen.BacklinksSection
import com.example.newproject.ui.screen.backlinkOthersText
import com.example.newproject.ui.screen.backlinksExpandedAfter
import com.example.newproject.ui.screen.backlinksProgressText
import com.example.newproject.ui.screen.backlinksSectionFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 「このノートへのリンク」の節に何を出すか（→ features/backlinks.md §3・§5）。余白ペインと関連タブが同じ判定を使う。 */
class BacklinksSectionTest {

    private val row = BacklinkRow(
        note = RelatedNote(title = "日記.md", ref = DocumentRef("ref:日記"), isWikilinked = false),
        sentence = "[[習慣]] を読む。",
        otherCount = 2,
        sectionHeading = null,
        sectionHeadingOccurrence = 0
    )

    @Test
    fun `作り終えて1件以上あるときだけ行を出し、0件と引いている間とノートが無いときは出さない`() {
        val ready = KnowledgeIndexProgress.Ready
        assertEquals(BacklinksSection.Rows(listOf(row)), backlinksSectionFor(BacklinksState.Ready(listOf(row)), ready))
        assertEquals(BacklinksSection.Hidden, backlinksSectionFor(BacklinksState.Ready(emptyList()), ready))
        assertEquals(BacklinksSection.Hidden, backlinksSectionFor(BacklinksState.Loading, ready))
        assertEquals(BacklinksSection.Hidden, backlinksSectionFor(BacklinksState.Idle, KnowledgeIndexProgress.Building(1, 2)))
    }

    @Test
    fun `最初の作成の途中は、引けた行があっても進み具合を出す`() {
        val building = KnowledgeIndexProgress.Building(120, 500)
        assertEquals(BacklinksSection.Building(120, 500), backlinksSectionFor(BacklinksState.Ready(listOf(row)), building))
        assertEquals(BacklinksSection.Building(120, 500), backlinksSectionFor(BacklinksState.Loading, building))
    }

    /** 読めない走査や壊れた置き場では作成も進まない。進み具合を出し続けると「待てば出る」と誤って伝える。 */
    @Test
    fun `数えられないときは、作成の途中でも数えられないと出す`() {
        assertEquals(BacklinksSection.Unavailable, backlinksSectionFor(BacklinksState.Unavailable, KnowledgeIndexProgress.Building(0, 0)))
        assertEquals(BacklinksSection.Unavailable, backlinksSectionFor(BacklinksState.Unavailable, KnowledgeIndexProgress.Ready))
    }

    /** 作る側が止まった（→ knowledge_index.md §5）。照会が成功していても、作り終えていない索引の行は漏れなく数えたものではない。 */
    @Test
    fun `索引を作れなかったら、引けた行があっても数えられないと出す`() {
        val failed = KnowledgeIndexProgress.Failed
        assertEquals(BacklinksSection.Unavailable, backlinksSectionFor(BacklinksState.Ready(listOf(row)), failed))
        assertEquals(BacklinksSection.Unavailable, backlinksSectionFor(BacklinksState.Loading, failed))
        assertEquals(BacklinksSection.Hidden, backlinksSectionFor(BacklinksState.Idle, failed))
    }

    /** ノートを替えると畳んだ状態に戻る（→ features/backlinks.md §6）。開いたノートを覚えたままだと、A→B→A で開いたまま戻る。 */
    @Test
    fun `今のノートが開いたノートと違えば畳み、同じなら開いたままにする`() {
        assertEquals("同じノートのまま（右で開いて戻った・画面の作り直し）", "ref:A", backlinksExpandedAfter("ref:A", currentNoteUri = "ref:A"))
        assertNull("B へ移った", backlinksExpandedAfter("ref:A", currentNoteUri = "ref:B"))
        assertNull("読み込み中", backlinksExpandedAfter("ref:A", currentNoteUri = null))
        assertNull(backlinksExpandedAfter(null, currentNoteUri = "ref:A"))
    }

    @Test
    fun `進み具合は読む予定の数が分かるまで数を出さず、ほかの箇所は1つ以上のときだけ出す`() {
        assertEquals("索引を作っています（120 / 500）", backlinksProgressText(BacklinksSection.Building(120, 500)))
        assertEquals("索引を作っています", backlinksProgressText(BacklinksSection.Building(0, 0)))
        assertEquals("ほか 2 箇所", backlinkOthersText(row))
        assertNull(backlinkOthersText(row.copy(otherCount = 0)))
    }
}
