package com.example.newproject.ui

import com.example.newproject.ui.screen.MarginToggle
import com.example.newproject.ui.screen.MarginWindowShift
import com.example.newproject.ui.screen.marginToggleFor
import com.example.newproject.ui.screen.marginWindowShiftFor
import com.example.newproject.ui.screen.compactWhileTyping
import com.example.newproject.ui.screen.sectionLabel
import com.example.newproject.ui.screen.writeTargetNotice
import com.example.newproject.model.SectionRef
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test

// ✎ と窓の切り替わりで、どの余白を出すか（→ features/margin_pane.md §5.4）。
class MarginSurfaceTest {

    @Test
    fun `✎ は出ている余白をしまい、出ていなければその窓で出せる面を出す`() {
        // 出せる窓
        assertEquals(MarginToggle.ClosePane, marginToggleFor(canShowPane = true, paneOpen = true, sheetVisible = false))
        assertEquals(MarginToggle.OpenPane, marginToggleFor(canShowPane = true, paneOpen = false, sheetVisible = false))
        assertEquals(MarginToggle.HideSheet, marginToggleFor(canShowPane = true, paneOpen = false, sheetVisible = true))
        // 出せない窓では設定によらずシートを扱う
        assertEquals(MarginToggle.ShowSheet, marginToggleFor(canShowPane = false, paneOpen = true, sheetVisible = false))
        assertEquals(MarginToggle.ShowSheet, marginToggleFor(canShowPane = false, paneOpen = false, sheetVisible = false))
        assertEquals(MarginToggle.HideSheet, marginToggleFor(canShowPane = false, paneOpen = true, sheetVisible = true))
    }

    /** 閉じる設定でシートを出したまま Fold を開き、✎ を2回押す。**ペインとシートを同時に出さない。** */
    @Test
    fun `シートを出したまま出せる窓へ移ると、1回目はシートをしまい2回目でペインを出す`() {
        var paneOpen = false
        var sheetVisible = true

        assertEquals(
            "閉じる設定ならシートのまま移る",
            MarginWindowShift.None,
            marginWindowShiftFor(false, canShowPane = true, paneOpen = paneOpen, sheetVisible = sheetVisible, hasDraft = true)
        )

        val first = marginToggleFor(canShowPane = true, paneOpen = paneOpen, sheetVisible = sheetVisible)
        assertEquals(MarginToggle.HideSheet, first)
        sheetVisible = false

        val second = marginToggleFor(canShowPane = true, paneOpen = paneOpen, sheetVisible = sheetVisible)
        assertEquals(MarginToggle.OpenPane, second)
        paneOpen = true

        assertEquals(MarginToggle.ClosePane, marginToggleFor(canShowPane = true, paneOpen = paneOpen, sheetVisible = sheetVisible))
    }

    @Test
    fun `出せない窓から出せる窓へ移ると、開く設定ならシートをペインへ移す`() {
        assertEquals(
            MarginWindowShift.SheetToPane,
            marginWindowShiftFor(false, canShowPane = true, paneOpen = true, sheetVisible = true, hasDraft = false)
        )
        // シートが出ていなければ何もしない（ペインは設定どおりに出る）
        assertEquals(
            MarginWindowShift.None,
            marginWindowShiftFor(false, canShowPane = true, paneOpen = true, sheetVisible = false, hasDraft = true)
        )
    }

    @Test
    fun `出せる窓から出せない窓へ移ると、書きかけがあるときだけシートへ移す`() {
        assertEquals(
            MarginWindowShift.PaneToSheet,
            marginWindowShiftFor(true, canShowPane = false, paneOpen = true, sheetVisible = false, hasDraft = true)
        )
        assertEquals(
            MarginWindowShift.None,
            marginWindowShiftFor(true, canShowPane = false, paneOpen = true, sheetVisible = false, hasDraft = false)
        )
        // ペインが閉じていれば、書きかけは出ていた面に無い
        assertEquals(
            MarginWindowShift.None,
            marginWindowShiftFor(true, canShowPane = false, paneOpen = false, sheetVisible = false, hasDraft = true)
        )
    }

    /** 前の窓を知らないとき（起動直後）と、窓が替わっていないときは動かさない。 */
    @Test
    fun `前の窓を知らないか、窓が替わっていなければ何も移さない`() {
        listOf(null, true).forEach { previous ->
            assertEquals(
                MarginWindowShift.None,
                marginWindowShiftFor(previous, canShowPane = true, paneOpen = true, sheetVisible = true, hasDraft = true)
            )
        }
        assertEquals(
            MarginWindowShift.None,
            marginWindowShiftFor(false, canShowPane = false, paneOpen = true, sheetVisible = false, hasDraft = true)
        )
    }

    // ── 節の呼び名と書き込み先（→ features/margin_pane.md §5.2・§5.3）──────────────

    @Test
    fun `名前の無い節は見出しの有無で冒頭か全体と呼ぶ`() {
        assertEquals("ノートの冒頭", sectionLabel(SectionRef(null), hasHeadings = true))
        assertEquals("ノート全体", sectionLabel(SectionRef(null), hasHeadings = false))
    }

    /** 同名の見出しを名前だけで呼ぶと、書き込み先がどちらの節か見分けられない。 */
    @Test
    fun `同名の見出しの2つ目からは順番を添える`() {
        assertEquals("まとめ", sectionLabel(SectionRef("まとめ", 0), hasHeadings = true))
        assertEquals("まとめ（2つ目）", sectionLabel(SectionRef("まとめ", 1), hasHeadings = true))
    }

    @Test
    fun `書き込み先は今の本文の節と違うときだけ知らせる`() {
        val b = SectionRef("節B")
        val c = SectionRef("節C")

        assertEquals(b, writeTargetNotice(target = b, bodySection = c))
        assertNull(writeTargetNotice(target = b, bodySection = b))
        assertNull("書いていないのに知らせた", writeTargetNotice(target = null, bodySection = c))
        assertNull("本文の節が分からないのに知らせた", writeTargetNotice(target = b, bodySection = null))
    }

    /** **シートで、入力欄に触れていて、キーボードが出ているときだけ**畳む。キーボードを閉じれば元に戻る。 */
    @Test
    fun `書いている間に畳むのはシートでキーボードが出ているときだけ`() {
        assertEquals(true, compactWhileTyping(asSheet = true, inputFocused = true, imeVisible = true))
        assertEquals("キーボードを閉じたのに畳んだまま", false, compactWhileTyping(asSheet = true, inputFocused = true, imeVisible = false))
        assertEquals(false, compactWhileTyping(asSheet = true, inputFocused = false, imeVisible = true))
        assertEquals("ペインを畳んだ", false, compactWhileTyping(asSheet = false, inputFocused = true, imeVisible = true))
    }
}
