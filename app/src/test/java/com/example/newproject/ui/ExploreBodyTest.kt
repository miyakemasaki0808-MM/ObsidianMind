package com.example.newproject.ui

import com.example.newproject.model.DocumentRef
import com.example.newproject.model.RelatedNote
import com.example.newproject.model.state.TitleMatch
import com.example.newproject.model.state.TitleSearchState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExploreBodyTest {

    private val hit = TitleSearchState.Matches(
        listOf(TitleMatch(RelatedNote("習慣.md", DocumentRef("ref"), isWikilinked = false)))
    )

    @Test
    fun `打っていなければ、どの状態でも今日読んだノートを出す`() {
        listOf(TitleSearchState.Idle, TitleSearchState.Scanning, hit, TitleSearchState.Error("失敗")).forEach { state ->
            assertEquals(TitleBody.History, titleBodyFor("  ", state))
        }
    }

    @Test
    fun `打った直後に結果が届くまでは読み込み中を挟まない`() {
        assertEquals(TitleBody.History, titleBodyFor("習", TitleSearchState.Idle))
    }

    @Test
    fun `打った後は状態に合わせて出す`() {
        assertEquals(TitleBody.Scanning, titleBodyFor("習", TitleSearchState.Scanning))
        assertEquals(TitleBody.Failed, titleBodyFor("習", TitleSearchState.Error("失敗")))
        assertEquals(TitleBody.NoMatch, titleBodyFor("習", TitleSearchState.Matches(emptyList())))
        assertEquals(TitleBody.Matches, titleBodyFor("習", hit))
    }

    @Test
    fun `場所は同じ名前のノートにだけ出し、直下は言葉にする`() {
        assertNull(titleMatchLocation(null))
        assertEquals("ルート直下", titleMatchLocation(""))
        assertEquals("ideas/2026", titleMatchLocation("ideas/2026"))
    }
}
