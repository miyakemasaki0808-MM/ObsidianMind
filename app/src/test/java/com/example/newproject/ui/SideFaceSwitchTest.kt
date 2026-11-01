package com.example.newproject.ui

import com.example.newproject.ui.screen.sideLinkActionFor
import com.example.newproject.ui.screen.SideLinkAction
import com.example.newproject.domain.NoteLinkResolution
import com.example.newproject.ui.component.reunionBesideLine
import com.example.newproject.model.BesideNote
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

    /** 再会カードの名前の行。名前の前後を分けて持ち、押せるときの読み上げは押して起きることを言う（→ features/desk_memory.md §5・§9）。 */
    @Test
    fun `再会カードの名前の行は名前の前後を分け、押して起きることを読み上げる`() {
        val line = reunionBesideLine(BesideNote("refs/a.md", "習慣の科学"))

        assertEquals("前回は『習慣の科学』と並べて読んでいました", line.before + line.title + line.after)
        assertEquals("習慣の科学", line.title)
        assertEquals("『習慣の科学』を並べて開く", line.openLabel)
    }

    /** 見つからない相手は知らせるだけで、何も開かない。索引がまだ無ければ待ってもらう。 */
    @Test
    fun `再会カードの相手が見つからなければ知らせだけを出す`() {
        assertEquals(SideLinkAction.Notice("「習慣の科学」は見つかりませんでした"), sideLinkActionFor(NoteLinkResolution.Missing("習慣の科学"), null))
        assertTrue(sideLinkActionFor(NoteLinkResolution.NotReady, null) is SideLinkAction.Notice)
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
