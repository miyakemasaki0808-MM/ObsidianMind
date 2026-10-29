package com.example.newproject.ui

import com.example.newproject.model.state.SideFace
import com.example.newproject.ui.screen.showsSideFaceSwitch
import com.example.newproject.ui.screen.sideReferenceLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
