package com.example.newproject.ui

import com.example.newproject.domain.NoteLinkResolution
import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.RelatedNote
import com.example.newproject.ui.screen.NoteLinkAction
import com.example.newproject.ui.screen.NoteStartBlock
import com.example.newproject.ui.screen.currentNoteScrollTarget
import com.example.newproject.ui.screen.noteLinkActionFor
import com.example.newproject.ui.screen.noteLinkHeadingMissingText
import com.example.newproject.ui.screen.noteStartBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 本文のリンクを押した後に画面がすること（→ features/note_links.md §5 の画面遷移）。 */
class NoteLinkActionsTest {

    private val note = RelatedNote(title = "B.md", ref = DocumentRef("ref:B"), isWikilinked = true)

    @Test
    fun `補助の面を出せる窓では補助の面で開く`() {
        assertEquals(
            NoteLinkAction.Open(note, "節", onSupporting = true),
            noteLinkActionFor(NoteLinkResolution.Open(note, "節"), canShowSupporting = true)
        )
    }

    @Test
    fun `補助の面を出せない窓では今のノートとして開く`() {
        assertEquals(
            NoteLinkAction.Open(note, null, onSupporting = false),
            noteLinkActionFor(NoteLinkResolution.Open(note, null), canShowSupporting = false)
        )
    }

    @Test
    fun `同じノートへのリンクはどの窓でも本文を送る`() {
        listOf(true, false).forEach { canShowSupporting ->
            assertEquals(
                NoteLinkAction.ScrollCurrent("節"),
                noteLinkActionFor(NoteLinkResolution.InCurrent("節"), canShowSupporting)
            )
        }
    }

    @Test
    fun `見つからないときと走査結果が無いときは知らせだけを出す`() {
        val missing = noteLinkActionFor(NoteLinkResolution.Missing("まだ無いノート"), canShowSupporting = true)
        assertEquals(NoteLinkAction.Notice("「まだ無いノート」は見つかりませんでした"), missing)
        assertTrue(noteLinkActionFor(NoteLinkResolution.NotReady, canShowSupporting = false) is NoteLinkAction.Notice)
    }

    @Test
    fun `見出しが無いときの知らせは開いたかどうかで分ける`() {
        assertEquals("見出し「節」が見つからないので、先頭から開きました", noteLinkHeadingMissingText("節", opened = true))
        assertEquals("見出し「節」が見つかりませんでした", noteLinkHeadingMissingText("節", opened = false))
    }

    private val blocks = listOf(
        MarkdownBlock.Paragraph("前置き"),
        MarkdownBlock.Heading(2, "節")
    )

    @Test
    fun `同じノートの見出しへ送る先は見出しのブロック`() {
        assertEquals(1, currentNoteScrollTarget(blocks, "節"))
    }

    @Test
    fun `見出しの無いリンクは先頭へ送る`() {
        assertEquals(0, currentNoteScrollTarget(blocks, null))
    }

    /**
     * 開いたノートの始まり。**見出しが無くても見つからなくても、決めた位置へ送る** — 補助の面で同じノートを開き直すと
     * 位置がノートごとに残っているので、送らないと途中から始まる。
     */
    @Test
    fun `開いたノートは見出しがあればそこから、無いか見つからなければ決めた位置から始める`() {
        assertEquals(NoteStartBlock(1, headingMissing = false), noteStartBlock(blocks, "節", fallback = 0))
        assertEquals(NoteStartBlock(0, headingMissing = false), noteStartBlock(blocks, null, fallback = 0))
        assertEquals(NoteStartBlock(0, headingMissing = true), noteStartBlock(blocks, "無い", fallback = 0))
        assertEquals(NoteStartBlock(7, headingMissing = false), noteStartBlock(blocks, null, fallback = 7))
    }

    @Test
    fun `見出しが見つからないときと解析が届く前は送らない`() {
        assertNull(currentNoteScrollTarget(blocks, "無い"))
        assertNull(currentNoteScrollTarget(null, null))
    }
}
