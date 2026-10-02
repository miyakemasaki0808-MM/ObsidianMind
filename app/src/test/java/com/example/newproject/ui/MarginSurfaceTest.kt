package com.example.newproject.ui

import com.example.newproject.ui.screen.MarginToggle
import com.example.newproject.ui.screen.MarginWindowShift
import com.example.newproject.ui.screen.marginToggleFor
import com.example.newproject.ui.screen.marginWindowShiftFor
import com.example.newproject.ui.screen.MemoReveal
import com.example.newproject.ui.screen.MemoRevealStop
import com.example.newproject.ui.screen.ReaderLayout
import com.example.newproject.ui.screen.compactWhileTyping
import com.example.newproject.ui.screen.hidesReaderControls
import com.example.newproject.ui.screen.memoRevealStop
import com.example.newproject.ui.screen.sheetCoveredHeight
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
            marginWindowShiftFor(false, canShowPane = true, paneOpen = paneOpen, sheetVisible = sheetVisible, writing = true)
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
            marginWindowShiftFor(false, canShowPane = true, paneOpen = true, sheetVisible = true, writing = false)
        )
        // シートが出ていなければ何もしない（ペインは設定どおりに出る）
        assertEquals(
            MarginWindowShift.None,
            marginWindowShiftFor(false, canShowPane = true, paneOpen = true, sheetVisible = false, writing = true)
        )
    }

    @Test
    fun `出せる窓から出せない窓へ移ると、書きかけがあるときだけシートへ移す`() {
        assertEquals(
            MarginWindowShift.PaneToSheet,
            marginWindowShiftFor(true, canShowPane = false, paneOpen = true, sheetVisible = false, writing = true)
        )
        assertEquals(
            MarginWindowShift.None,
            marginWindowShiftFor(true, canShowPane = false, paneOpen = true, sheetVisible = false, writing = false)
        )
        // ペインが閉じていれば、書きかけは出ていた面に無い
        assertEquals(
            MarginWindowShift.None,
            marginWindowShiftFor(true, canShowPane = false, paneOpen = false, sheetVisible = false, writing = true)
        )
    }

    /** 前の窓を知らないとき（起動直後）と、窓が替わっていないときは動かさない。 */
    @Test
    fun `前の窓を知らないか、窓が替わっていなければ何も移さない`() {
        listOf(null, true).forEach { previous ->
            assertEquals(
                MarginWindowShift.None,
                marginWindowShiftFor(previous, canShowPane = true, paneOpen = true, sheetVisible = true, writing = true)
            )
        }
        assertEquals(
            MarginWindowShift.None,
            marginWindowShiftFor(false, canShowPane = false, paneOpen = true, sheetVisible = false, writing = true)
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

    /** 本文はシートが覆う分だけ低くする。**隠れているシートは器の下端にあるので0。** 組まれる前も覆っていない。 */
    @Test
    fun `シートが覆う高さは器の下端からシートの上端まで`() {
        assertEquals(400, sheetCoveredHeight(layoutHeight = 800, sheetOffset = 400f))
        assertEquals("隠れているのに覆った", 0, sheetCoveredHeight(layoutHeight = 800, sheetOffset = 800f))
        assertEquals("組まれる前に覆った", 0, sheetCoveredHeight(layoutHeight = 800, sheetOffset = null))
        assertEquals("画面いっぱいを超えて覆った", 800, sheetCoveredHeight(layoutHeight = 800, sheetOffset = -10f))
    }

    /**
     * 印から来たら**行き先の節**へ送る。面の節が行き先ならこの節のメモ、違えばほかの節を開いてその組へ。
     * 本文を上端まで送れない短い節でも、面の節が追いつくことを当てにしない。
     */
    @Test
    fun `印の行き先が面の節でなければ、ほかの節を開いてその組へ送る`() {
        val b = SectionRef("節B")
        val a = SectionRef("節A")

        assertEquals(MemoRevealStop.CurrentMemos, memoRevealStop(MemoReveal.Section(b), current = b))
        assertEquals("別の節のメモを行き先にした", MemoRevealStop.OtherGroup(b), memoRevealStop(MemoReveal.Section(b), current = a))
        assertEquals(MemoRevealStop.OtherGroup(null), memoRevealStop(MemoReveal.AllMemos, current = a))
    }

    /**
     * **縦積みの窓でシートに書いている間だけ、上の操作を隠す。** キーボードとシートが出ると、残る高さを操作が使い切って本文が消えた。
     * 左右2列は操作が横の列にあるので隠さない。キーボードを閉じれば（書いている間でなくなれば）戻る。
     */
    @Test
    fun `上の操作を隠すのは縦積みでシートに書いている間だけ`() {
        val writing = compactWhileTyping(asSheet = true, inputFocused = true, imeVisible = true)
        val keyboardClosed = compactWhileTyping(asSheet = true, inputFocused = true, imeVisible = false)

        assertEquals(true, hidesReaderControls(ReaderLayout.Stacked, sheetWriting = writing))
        assertEquals("キーボードを閉じても隠したまま", false, hidesReaderControls(ReaderLayout.Stacked, sheetWriting = keyboardClosed))
        assertEquals(false, hidesReaderControls(ReaderLayout.SideBySide, sheetWriting = writing))
        assertEquals(false, hidesReaderControls(ReaderLayout.MarginPane(400f, 16f), sheetWriting = writing))
    }
}
