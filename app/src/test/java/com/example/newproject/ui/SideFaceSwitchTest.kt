package com.example.newproject.ui

import com.example.newproject.model.DocumentRef
import com.example.newproject.model.RelatedNote
import com.example.newproject.model.state.SideFace
import com.example.newproject.model.state.SideReadingState
import com.example.newproject.ui.screen.besideShownNote
import com.example.newproject.ui.screen.showsSideFaceSwitch
import com.example.newproject.ui.screen.sideReferenceLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 並べ読みの間の「書きかけ｜資料」の切替（→ features/margin_quote.md §5.6）。 */
class SideFaceSwitchTest {

    /** **右が書きかけのときは必ず出す** — 書きかけを空にしても右のノートへ戻る入口を消さない。資料のときは書きかけがあるときだけ。 */
    @Test
    fun `右が書きかけなら必ず出し、資料なら書きかけがあるときだけ出す`() {
        assertTrue(showsSideFaceSwitch(reading = true, face = SideFace.Draft, hasDraft = false))
        assertTrue(showsSideFaceSwitch(reading = true, face = SideFace.Reference, hasDraft = true))
        assertFalse(showsSideFaceSwitch(reading = true, face = SideFace.Reference, hasDraft = false))
        assertFalse(showsSideFaceSwitch(reading = false, face = SideFace.Draft, hasDraft = true))
    }

    @Test
    fun `資料の側には右のノートの題名を添える`() {
        assertEquals("資料（習慣の科学）", sideReferenceLabel("習慣の科学"))
        assertEquals("資料", sideReferenceLabel(null))
        assertEquals("資料", sideReferenceLabel(" "))
    }

    /**
     * **並べた相手として数えるのは、右に資料として見えているときだけ**（→ features/desk_memory.md §5）。
     * 読み込み中・失敗・書きかけを出している間・右の面が隠れている間は数えない。
     */
    @Test
    fun `並べた相手として数えるのは右に資料として見えているときだけ`() {
        val note = RelatedNote("習慣の科学", DocumentRef("content://vault/a.md"), isWikilinked = false)

        assertEquals(note, besideShownNote(SideReadingState.Ready(note, SideFace.Reference), supportingShown = true))
        assertNull(besideShownNote(SideReadingState.Ready(note, SideFace.Reference), supportingShown = false))
        assertNull(besideShownNote(SideReadingState.Ready(note, SideFace.Draft), supportingShown = true))
        assertNull(besideShownNote(SideReadingState.Loading(note), supportingShown = true))
        assertNull(besideShownNote(SideReadingState.Failed(note, "読めない"), supportingShown = true))
        assertNull(besideShownNote(SideReadingState.Idle, supportingShown = true))
    }
}
