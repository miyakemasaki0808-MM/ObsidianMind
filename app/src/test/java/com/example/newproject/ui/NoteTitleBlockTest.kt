package com.example.newproject.ui

import com.example.newproject.domain.markdown.buildNoteSectionModel
import com.example.newproject.domain.markdown.frontmatterLines
import com.example.newproject.ui.markdown.chipRowCount
import com.example.newproject.ui.markdown.noteFolderLabel
import com.example.newproject.ui.markdown.tagChipText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 題の欄（→ `docs/dev/features/note_typesetting.md` §5.3）。 */
class NoteTitleBlockTest {

    @Test
    fun `道筋は親フォルダをつなぎ、Vault の直下と場所の分からないノートは出さない`() {
        assertEquals("reading / habits", noteFolderLabel("reading/habits/朝の30分.md"))
        assertEquals("0500_Reading", noteFolderLabel("0500_Reading/本.md"))
        assertNull(noteFolderLabel("直下のノート.md"))
        assertNull(noteFolderLabel(null))
        assertNull(noteFolderLabel(""))
    }

    @Test
    fun `札の文字は # を付け、原文の # は重ねない`() {
        assertEquals("#習慣", tagChipText("習慣"))
        assertEquals("#習慣", tagChipText("#習慣"))
    }

    @Test
    fun `札は入らなければ次の行へ送る`() {
        assertEquals(0, chipRowCount(emptyList(), gap = 6f, available = 100f))
        assertEquals(1, chipRowCount(listOf(40f, 40f), gap = 6f, available = 86f))
        assertEquals(2, chipRowCount(listOf(40f, 40f), gap = 6f, available = 85f))
        // 1つで幅を超える札も1行を取り、次の札は次の行に置く。
        assertEquals(2, chipRowCount(listOf(150f, 20f), gap = 6f, available = 100f))
        assertEquals(3, chipRowCount(listOf(60f, 60f, 60f), gap = 6f, available = 100f))
    }

    @Test
    fun `タグは本文の解析と一緒に frontmatter から読む`() {
        val model = buildNoteSectionModel("---\ntags: [習慣, 振り返り]\n---\n本文")
        assertEquals(listOf("習慣", "振り返り"), model.tags)
        assertEquals(emptyList<String>(), buildNoteSectionModel("本文だけ").tags)
        // 頭の BOM は frontmatter の印を隠さない。
        assertEquals(listOf("読書"), buildNoteSectionModel("﻿---\ntags:\n  - 読書\n---\n本文").tags)
    }

    @Test
    fun `frontmatter は閉じていなければ読まない`() {
        assertNull(frontmatterLines("---\ntags: [a]\n本文"))
        assertNull(frontmatterLines("本文\n---\ntags: [a]\n---"))
        assertEquals(listOf("tags: [a]"), frontmatterLines("---\ntags: [a]\n---\n本文"))
    }
}
