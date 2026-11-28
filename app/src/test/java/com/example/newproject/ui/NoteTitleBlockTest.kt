package com.example.newproject.ui

import com.example.newproject.domain.markdown.buildNoteSectionModel
import com.example.newproject.domain.markdown.frontmatterLines
import com.example.newproject.ui.markdown.TAG_MAX_ROWS
import com.example.newproject.ui.markdown.chipRowCount
import com.example.newproject.ui.markdown.NoteLocation
import com.example.newproject.ui.markdown.noteFolderLabel
import com.example.newproject.ui.markdown.noteLocationOf
import com.example.newproject.ui.markdown.tagChipText
import com.example.newproject.ui.markdown.tagOverflowText
import com.example.newproject.ui.markdown.visibleChipCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun `居場所はフォルダの道筋と拡張子を外したファイル名で、直下と場所の分からないノートはファイル名だけ`() {
        assertEquals(NoteLocation("reading / habits", "朝の30分"), noteLocationOf("reading/habits/朝の30分.md", "朝の30分.md"))
        assertEquals(NoteLocation(null, "直下のノート"), noteLocationOf("直下のノート.md", "直下のノート.md"))
        assertEquals(NoteLocation(null, "リンクで開いた"), noteLocationOf(null, "リンクで開いた.md"))
        // 拡張子だけを外し、名前の中の「.md」は残す。
        assertEquals(NoteLocation(null, "a.md.b"), noteLocationOf(null, "a.md.b.md"))
    }

    @Test
    fun `札の文字は # を付け、原文の # は重ねない`() {
        assertEquals("#習慣", tagChipText("習慣"))
        assertEquals("#習慣", tagChipText("#習慣"))
    }

    /** 幅の一覧 [widths] を [visibleChipCount] に渡す。 */
    private fun visible(widths: List<Float>, available: Float, overflowWidth: (Int) -> Float = { 20f }): Int =
        visibleChipCount(widths.size, { widths[it] }, gap = 6f, available = available, maxRows = TAG_MAX_ROWS, overflowWidth = overflowWidth)

    @Test
    fun `札は上限の行に収まれば全部出し、収まらなければ残りの数の札と合わせて上限に収める`() {
        assertEquals(2, TAG_MAX_ROWS)
        // 2行に収まる4つは全部出す。
        assertEquals(4, visible(List(4) { 40f }, available = 86f))
        // 6つは3行になるので、2行目に残りの数の札（20）を入れて 3つまで（40 40 / 40 20）。
        assertEquals(3, visible(List(6) { 40f }, available = 86f))
        // 1つで幅を超える札ばかりなら、1行目に1つ、2行目に残りの数の札。
        assertEquals(1, visible(List(60) { 500f }, available = 100f))
        assertEquals(0, visible(emptyList(), available = 100f))
        assertEquals("+57", tagOverflowText(57))
    }

    @Test
    fun `残りの数の桁が増えて札が広がっても、上限の行に収める`() {
        // 1行に40の札が2つ入る。13件なら4つ出すと残り9、3つ出すと残り10で札が広がる。
        val byDigits: (Int) -> Float = { rest -> if (rest >= 10) 50f else 20f }
        assertEquals(3, visible(List(13) { 40f }, available = 100f, overflowWidth = byDigits))
        // 桁が増えた札が2行目に入らなければ、さらに1つ減らす（40 40 / 40 60 は幅を超える）。
        val wider: (Int) -> Float = { rest -> if (rest >= 10) 60f else 20f }
        assertEquals(2, visible(List(13) { 40f }, available = 100f, overflowWidth = wider))
    }

    @Test
    fun `タグが何件あっても、幅は上限の行が埋まるまでしか問い合わせない`() {
        var widthCalls = 0
        var overflowCalls = 0
        val shown = visibleChipCount(
            10_000,
            { widthCalls++; 500f },
            gap = 6f,
            available = 100f,
            maxRows = TAG_MAX_ROWS,
            overflowWidth = { overflowCalls++; 20f }
        )
        assertEquals(1, shown)
        assertTrue("幅の問い合わせ $widthCalls 回", widthCalls <= 10)
        assertTrue("残りの数の札の問い合わせ $overflowCalls 回", overflowCalls <= 10)
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
