package com.example.newproject.ui

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
}
