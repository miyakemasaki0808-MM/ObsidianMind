package com.example.newproject.ui

import androidx.compose.runtime.saveable.SaverScope
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteLoadId
import com.example.newproject.model.NoteUiStateStore
import com.example.newproject.model.RelatedNote
import com.example.newproject.model.state.BacklinkRow
import com.example.newproject.model.state.BacklinksState
import com.example.newproject.model.state.KnowledgeIndexProgress
import com.example.newproject.ui.screen.BacklinksOpenedAtSaver
import com.example.newproject.ui.screen.BacklinksSection
import com.example.newproject.ui.screen.backlinkOthersText
import com.example.newproject.ui.screen.backlinksExpanded
import com.example.newproject.ui.screen.backlinksProgressText
import com.example.newproject.ui.screen.backlinksSectionFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「このノートへのリンク」の節に何を出すか（→ features/backlinks.md §3・§5）。余白ペインと関連タブが同じ判定を使う。 */
class BacklinksSectionTest {

    private val row = BacklinkRow(
        note = RelatedNote(title = "日記.md", ref = DocumentRef("ref:日記"), isWikilinked = false),
        sentence = "[[習慣]] を読む。",
        otherCount = 2,
        sectionHeading = null,
        sectionHeadingOccurrence = 0,
        hasMemo = false
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

    /**
     * ノートを替えると畳んだ状態に戻る（→ features/backlinks.md §6）。**開いたときのノートの表示の番号で比べる** —
     * ノートの参照で比べると A→B→A で同じ値に戻り、ノートの画面が組まれていない間（全画面）の切り替えを見逃す。
     */
    @Test
    fun `節は開いたときのノートの表示の番号が今と同じときだけ開いている`() {
        val third = NoteLoadId(issuer = 11L, count = 3L)
        assertTrue("同じ表示のまま（右で開いて戻った・画面の作り直し）", backlinksExpanded(openedAt = third, noteLoadId = third))
        assertFalse("A→B→A で番号が進んだ", backlinksExpanded(openedAt = third, noteLoadId = third.copy(count = 5L)))
        assertFalse(backlinksExpanded(openedAt = null, noteLoadId = third))
    }

    /**
     * プロセスが作り直されると、Store は番号を0から数え直すが、画面の保存値は前のプロセスの番号のまま戻る。
     * 前のプロセスの A で開いた値は、新しいプロセスの B が同じ数の番号になっても一致しない。
     */
    @Test
    fun `作り直したプロセスで同じ数の番号になっても、前のプロセスで開いた節は開かない`() {
        val before = NoteUiStateStore(loadIssuer = 1L).apply { beginNoteLoad() }.value.noteLoadId
        val after = NoteUiStateStore(loadIssuer = 2L)

        after.beginNoteLoad()
        assertEquals("前のプロセスの最初のノートと同じ数", before.count, after.value.noteLoadId.count)
        assertFalse(backlinksExpanded(openedAt = before, noteLoadId = after.value.noteLoadId))
        repeat(5) { after.beginNoteLoad() }
        assertFalse(backlinksExpanded(openedAt = before, noteLoadId = after.value.noteLoadId))
    }

    /** 保存値は発行元の印ごと戻る。落とすと、作り直したプロセスの同じ数の番号と一致する。 */
    @Test
    fun `開いたときの番号の保存値は発行元の印ごと戻る`() {
        val opened = NoteLoadId(issuer = -7L, count = 4L)
        val scope = SaverScope { true }
        val saved = with(BacklinksOpenedAtSaver) { scope.save(opened) }

        assertEquals(opened, BacklinksOpenedAtSaver.restore(saved!!))
        assertNull("閉じていれば保存しない", with(BacklinksOpenedAtSaver) { scope.save(null) })
    }

    @Test
    fun `進み具合は読む予定の数が分かるまで数を出さず、ほかの箇所は1つ以上のときだけ出す`() {
        assertEquals("索引を作っています（120 / 500）", backlinksProgressText(BacklinksSection.Building(120, 500)))
        assertEquals("索引を作っています", backlinksProgressText(BacklinksSection.Building(0, 0)))
        assertEquals("ほか 2 箇所", backlinkOthersText(row))
        assertNull(backlinkOthersText(row.copy(otherCount = 0)))
    }
}
