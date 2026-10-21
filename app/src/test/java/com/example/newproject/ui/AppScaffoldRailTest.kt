package com.example.newproject.ui

import com.example.newproject.ui.screen.ReaderChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// 外殻がレールを出すか（→ features/margin_pane.md §5.1・system/tab_navigation.md）。
class AppScaffoldRailTest {

    /** **ノートタブで余白ペインが出ている間はレールを畳む。** レールが本文の列の幅を取り、本文がペインより細くなる。 */
    @Test
    fun `ノートタブで余白ペインが出ている間はレールを畳む`() {
        assertFalse(showsRail(isTabRoute = true, useRail = true, currentRoute = AppDestination.Note.route, notePaneVisible = true))
        assertTrue(showsRail(isTabRoute = true, useRail = true, currentRoute = AppDestination.Note.route, notePaneVisible = false))
    }

    /** 畳むのはノートタブにいる間だけ。ほかのタブへ移ったら、ノートタブのペインの状態によらずレールを出す。 */
    @Test
    fun `ほかのタブではノートタブのペインによらずレールを出す`() {
        AppDestination.entries.filterNot { it == AppDestination.Note }.forEach { dest ->
            assertTrue(dest.route, showsRail(isTabRoute = true, useRail = true, currentRoute = dest.route, notePaneVisible = true))
        }
    }

    /** 今までどおり、タブ以外のルートと広くない窓ではレールを出さない。 */
    @Test
    fun `タブ以外のルートと広くない窓ではレールを出さない`() {
        assertFalse(showsRail(isTabRoute = false, useRail = true, currentRoute = "note_fullscreen", notePaneVisible = false))
        assertFalse(showsRail(isTabRoute = true, useRail = false, currentRoute = AppDestination.Note.route, notePaneVisible = false))
    }

    /**
     * **机の形で帯が出ている間だけ、レールを重ねて出す**（→ features/note_spread.md §8 判断3）。見開きにはペインをしまう先が無く、
     * タブへの道がここしか無い。帯をしまうと消え、机の形でないとき・ほかのタブ・広くない窓では重ねない。
     */
    @Test
    fun `机の形で帯が出ている間だけレールを重ねて出す`() {
        val note = AppDestination.Note.route
        assertTrue(overlaysRail(isTabRoute = true, useRail = true, currentRoute = note, notePaneVisible = true, noteBarShown = true))
        assertFalse(overlaysRail(isTabRoute = true, useRail = true, currentRoute = note, notePaneVisible = true, noteBarShown = false))
        assertFalse(overlaysRail(isTabRoute = true, useRail = true, currentRoute = note, notePaneVisible = false, noteBarShown = true))
        assertFalse(overlaysRail(isTabRoute = true, useRail = false, currentRoute = note, notePaneVisible = true, noteBarShown = true))
        assertFalse(overlaysRail(isTabRoute = true, useRail = true, currentRoute = AppDestination.Search.route, notePaneVisible = true, noteBarShown = true))
    }

    /** **見開きは「ノート」のすぐ下で、見開きのタブを出す窓だけ。** 行き先の5つは並びも数も変えない（下のバーも5つのまま）。 */
    @Test
    fun `見開きのタブはノートのすぐ下に、出す窓でだけ並ぶ`() {
        val tabs = AppDestination.entries.map { RailItem.Tab(it) }
        assertEquals(tabs, railItems(spreadTab = false))
        assertEquals(listOf(tabs[0], RailItem.Spread) + tabs.drop(1), railItems(spreadTab = true))
    }

    /** ノートの行き先では、読む形が見開きなら見開きを、そうでなければノートを選んで見せる。ほかの行き先は今までどおり。 */
    @Test
    fun `レールで選んで見せる項目はノートの行き先の読む形で決まる`() {
        val note = AppDestination.Note.route
        assertTrue(railItemSelected(RailItem.Tab(AppDestination.Note), note, spreadChosen = false))
        assertFalse(railItemSelected(RailItem.Spread, note, spreadChosen = false))
        assertFalse(railItemSelected(RailItem.Tab(AppDestination.Note), note, spreadChosen = true))
        assertTrue(railItemSelected(RailItem.Spread, note, spreadChosen = true))
        assertFalse(railItemSelected(RailItem.Spread, AppDestination.Search.route, spreadChosen = true))
        assertTrue(railItemSelected(RailItem.Tab(AppDestination.Search), AppDestination.Search.route, spreadChosen = true))
    }

    /**
     * **ノートの行き先で「ノート」か「見開き」を押したら机をしまう**（重ねたレールは机と見開きの帯にだけ出る）。✎ と同じ扱いで、並べ読みも終える。
     * ほかのタブから押したときは机の開閉を変えない — ほかのタブとの往復では机へ戻る。
     */
    @Test
    fun `ノートの行き先でノートか見開きを押すと机をしまい、ほかのタブからでは机を保つ`() {
        val desk = ReaderChoice(paneOpen = true, spreadSelected = false)
        assertEquals(RailTap(ReaderChoice(false, true), closesDesk = true), readerChoiceAfterRailTap(desk, spread = true, fromNoteRoute = true))
        assertEquals(RailTap(ReaderChoice(false, false), closesDesk = true), readerChoiceAfterRailTap(desk, spread = false, fromNoteRoute = true))
        assertEquals(RailTap(ReaderChoice(true, true), closesDesk = false), readerChoiceAfterRailTap(desk, spread = true, fromNoteRoute = false))
        // 机を出していなければ、しまうものも無い（見開きの帯から「ノート」でふだんの画面へ）。
        val spread = ReaderChoice(paneOpen = false, spreadSelected = true)
        assertEquals(RailTap(ReaderChoice(false, false), closesDesk = false), readerChoiceAfterRailTap(spread, spread = false, fromNoteRoute = true))
    }
}
