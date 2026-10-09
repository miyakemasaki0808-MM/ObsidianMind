package com.example.newproject.ui

import com.example.newproject.ui.screen.MarginToggle
import com.example.newproject.ui.screen.MarginWindowShift
import com.example.newproject.ui.screen.PreviousReadingRow
import com.example.newproject.ui.screen.previousReadingLabel
import com.example.newproject.ui.screen.previousReadingRowFor
import com.example.newproject.ui.screen.marginToggleFor
import com.example.newproject.ui.screen.marginWindowShiftFor
import com.example.newproject.ui.screen.MemoReveal
import com.example.newproject.ui.screen.MemoRevealStop
import com.example.newproject.ui.screen.ReaderLayout
import com.example.newproject.ui.screen.SummaryEntry
import com.example.newproject.ui.screen.summaryEntryFor
import com.example.newproject.ui.screen.PendingNotice
import com.example.newproject.ui.screen.pendingNoticeFor
import com.example.newproject.model.state.AiNoticeAction
import com.example.newproject.model.state.AiStatusNotice
import com.example.newproject.model.state.SectionChatProblem
import com.example.newproject.model.state.SectionSummary
import com.example.newproject.ui.screen.compactWhileTyping
import com.example.newproject.ui.screen.dropsHiddenSheet
import com.example.newproject.ui.screen.pendingAfterReveal
import com.example.newproject.ui.screen.hidesReaderControls
import com.example.newproject.ui.screen.memoRevealStop
import com.example.newproject.ui.screen.sheetCoveredHeight
import com.example.newproject.ui.screen.sectionLabel
import com.example.newproject.ui.screen.writeTargetNotice
import com.example.newproject.model.SectionRef
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.RelatedNote
import com.example.newproject.model.state.RelatedNotesState
import com.example.newproject.ui.screen.endsSideReading
import com.example.newproject.ui.screen.PaneRelatedList
import com.example.newproject.ui.screen.PaneRelatedListSaver
import com.example.newproject.ui.screen.paneRelatedListFor
import androidx.compose.runtime.saveable.SaverScope
import com.example.newproject.ui.screen.paneRelatedCandidates
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

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

    /** 見出しの要約ボタン。**出ている面があればそこに出す**（面を2つにしない）。 */
    @Test
    fun `要約ボタンは出ている面に出す`() {
        for (requested in listOf(false, true)) {
            assertEquals(SummaryEntry.Pane, summaryEntryFor(paneVisible = true, sheetVisible = false, requested = requested))
            assertEquals(SummaryEntry.Sheet, summaryEntryFor(paneVisible = false, sheetVisible = true, requested = requested))
        }
    }

    /**
     * **面が出ていなければ、まだ頼んでいない節は始めるだけで面を出さない。** 頼んだ節は記号が状態を示しているので、
     * 押すのは見に行くときで、シートで見せる（ペインの設定は書き換えない）。
     */
    @Test
    fun `面が出ていなければ、頼んでいない節は始めるだけで、頼んだ節はシートで見せる`() {
        assertEquals(SummaryEntry.Background, summaryEntryFor(paneVisible = false, sheetVisible = false, requested = false))
        assertEquals(SummaryEntry.Sheet, summaryEntryFor(paneVisible = false, sheetVisible = false, requested = true))
    }

    /**
     * 面を出さずに頼んだ要約。**端末AIが使えない理由が届いていて、まだ見せていなければ面を開く** — 理由は失敗として数えないので、
     * 記号が 💬 のまま変わらず、押しても何も起きないように見える。生成中・完成・生成の失敗は記号が示すので開かない。
     */
    @Test
    fun `面を出さずに頼んだ要約は、端末AIが使えない理由が届いてまだ見せていないときだけ面を開く`() {
        val requested = SectionSummary(SectionRef("A"), requestId = 1, sectionTitle = "A", sectionContext = "", isSummaryLoading = true, noticePending = true)
        val noticed = requested.copy(isSummaryLoading = false, summaryProblem = NOTICE)

        assertEquals(PendingNotice.Open, pendingNoticeFor(noticed, rowVisible = false, paneVisible = false))
        assertEquals(PendingNotice.None, pendingNoticeFor(requested, rowVisible = false, paneVisible = false))
        assertEquals(PendingNotice.None, pendingNoticeFor(noticed.copy(noticePending = false), rowVisible = false, paneVisible = false))
        assertEquals(
            PendingNotice.None,
            pendingNoticeFor(requested.copy(isSummaryLoading = false, summary = "要約"), rowVisible = false, paneVisible = false)
        )
        assertEquals(
            PendingNotice.None,
            pendingNoticeFor(
                requested.copy(isSummaryLoading = false, summaryProblem = SectionChatProblem.GenerationFailed("x")),
                rowVisible = false,
                paneVisible = false
            )
        )
        assertEquals(PendingNotice.None, pendingNoticeFor(summary = null, rowVisible = false, paneVisible = false))
    }

    /**
     * 要約の行が出ている面があれば、理由はそこに見えているので、見せたことだけを伝える。
     * **ペインが並べ読みを映している間は待つ** — シートを重ねられず、行も無い。余白へ戻ると行に見える。
     */
    @Test
    fun `理由は、行が見えていれば見せたことにし、並べ読みの間は待つ`() {
        val noticed = SectionSummary(SectionRef("A"), requestId = 1, sectionTitle = "A", sectionContext = "", summaryProblem = NOTICE, noticePending = true)

        assertEquals(PendingNotice.Acknowledge, pendingNoticeFor(noticed, rowVisible = true, paneVisible = false))
        assertEquals(PendingNotice.Acknowledge, pendingNoticeFor(noticed, rowVisible = true, paneVisible = true))
        assertEquals(PendingNotice.None, pendingNoticeFor(noticed, rowVisible = false, paneVisible = true))
    }

    /**
     * 全画面のシートで書いたまま戻ると、シートが出ている扱いのままペインの窓へ来る。
     * 落とさないと ✎ の1回目が見えないシートをしまうだけになる。**窓の情報が揃う前は落とさない。**
     */
    @Test
    fun `ペインが出ている窓では、シートが出ている扱いを落とす`() {
        assertEquals(true, dropsHiddenSheet(windowKnown = true, paneVisible = true, sheetVisible = true))
        assertEquals(false, dropsHiddenSheet(windowKnown = false, paneVisible = true, sheetVisible = true))
        assertEquals(false, dropsHiddenSheet(windowKnown = true, paneVisible = false, sheetVisible = true))
        assertEquals(false, dropsHiddenSheet(windowKnown = true, paneVisible = true, sheetVisible = false))
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
     * 送る依頼は、送り終えたときも止められたときも、**その依頼だけを消す**。古い依頼の終わりで後から来た依頼を消すと、
     * 押し直した要約ボタンや印が働かない。
     */
    @Test
    fun `送り終えた依頼だけを消し、後から来た依頼は残す`() {
        assertNull(pendingAfterReveal(pending = 1L, handled = 1L))
        assertEquals(2L, pendingAfterReveal(pending = 2L, handled = 1L))
        assertNull(pendingAfterReveal<Long>(pending = null, handled = 1L))
        val section = MemoReveal.Section(SectionRef("節C"))
        assertNull(pendingAfterReveal<MemoReveal>(pending = MemoReveal.Section(SectionRef("節C")), handled = section))
        assertEquals(MemoReveal.AllMemos, pendingAfterReveal<MemoReveal>(pending = MemoReveal.AllMemos, handled = section))
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
     * **入力欄に触れてキーボードが出ている間だけ、本文の列の上の操作を隠す。** 縦積みはシートで書いているとき、
     * 余白ペインはペインで書いているとき。キーボードとシート、または再会カードで本文が消えた。
     * 左右2列は操作が横の列にあるので隠さない。キーボードを閉じれば戻る。
     */
    @Test
    fun `上の操作を隠すのは本文の列の上に操作がある形で、キーボードを出して書いている間だけ`() {
        assertEquals(true, hidesReaderControls(ReaderLayout.Stacked, sheetVisible = true, writingWithKeyboard = true))
        assertEquals("シートを出していない縦積みで隠した", false, hidesReaderControls(ReaderLayout.Stacked, sheetVisible = false, writingWithKeyboard = true))
        assertEquals("キーボードを閉じても隠したまま", false, hidesReaderControls(ReaderLayout.Stacked, sheetVisible = true, writingWithKeyboard = false))
        assertEquals("ペインで書いている間に隠さない", true, hidesReaderControls(ReaderLayout.MarginPane(400f, 16f), sheetVisible = false, writingWithKeyboard = true))
        assertEquals(false, hidesReaderControls(ReaderLayout.MarginPane(400f, 16f), sheetVisible = false, writingWithKeyboard = false))
        assertEquals(false, hidesReaderControls(ReaderLayout.SideBySide, sheetVisible = true, writingWithKeyboard = true))
    }

    /**
     * 前回の読書の跡の行（→ features/margin_pane.md §5.2）。**書いている間は、書き始めたときの行の有無を保つ** —
     * 跡は遅れて届き、面の節は書いている間も本文についていくので、そのまま出し入れすると入力欄が上下に動く。
     */
    @Test
    fun `書いている間は、書き始めたときの前回の跡の行の有無を保つ`() {
        // 書いていなければ、跡がある節でだけ出す
        assertEquals(PreviousReadingRow.Shown(9L), previousReadingRowFor(9L, compact = false, heldAtFocus = null))
        assertEquals(PreviousReadingRow.Hidden, previousReadingRowFor(null, compact = false, heldAtFocus = null))
        // 行があるときに書き始めた → 跡の無い節へ移っても高さを取っておく
        assertEquals(PreviousReadingRow.Shown(9L), previousReadingRowFor(9L, compact = false, heldAtFocus = true))
        assertEquals("跡の無い節で行を詰めた", PreviousReadingRow.Reserved, previousReadingRowFor(null, compact = false, heldAtFocus = true))
        // 行が無いときに書き始めた → 跡が届いても、跡のある節へ移っても足さない
        assertEquals("書いている間に行を足した", PreviousReadingRow.Hidden, previousReadingRowFor(9L, compact = false, heldAtFocus = false))
        assertEquals(PreviousReadingRow.Hidden, previousReadingRowFor(null, compact = false, heldAtFocus = false))
    }

    /** シートで書いている間は畳む（→ §5.5）。残すのは見出し・書き込み先・入力欄・置くボタン・保存の状態だけ。 */
    @Test
    fun `シートで書いている間は前回の跡を出さない`() {
        assertEquals(PreviousReadingRow.Hidden, previousReadingRowFor(9L, compact = true, heldAtFocus = true))
        assertEquals(PreviousReadingRow.Hidden, previousReadingRowFor(null, compact = true, heldAtFocus = true))
    }

    /** **「止まった」と言わない。** 記録は前回いちばん深く読んだところで、読み戻って離れても下がらない（→ §5.7）。 */
    @Test
    fun `前回の跡は「ここまで読んだ」と日付で言い、今年でなければ年を添える`() {
        val zone = ZoneId.of("Asia/Tokyo")
        fun at(year: Int, month: Int, day: Int) =
            LocalDate.of(year, month, day).atTime(21, 30).atZone(zone).toInstant().toEpochMilli()
        val now = at(2026, 10, 3)

        assertEquals("前回はここまで読んだ · 9/12", previousReadingLabel(at(2026, 9, 12), now, zone))
        assertEquals("前回はここまで読んだ · 2025/12/31", previousReadingLabel(at(2025, 12, 31), now, zone))
        assertEquals(false, previousReadingLabel(at(2026, 9, 12), now, zone).contains("止まった"))
    }

    // ── 並べ読み（→ features/margin_pane.md §5.9）─────────────────────────────

    private fun related(title: String) = RelatedNote(title = title, ref = DocumentRef("content://vault/$title.md"), isWikilinked = false)

    @Test
    fun `関連の候補は、リンクの候補の後にAIの推薦を並べる`() {
        val state = RelatedNotesState.Success(relatedNotes = listOf(related("A"), related("B")), aiNotes = listOf(related("C")))

        assertEquals(listOf("A", "B", "C"), paneRelatedCandidates(null, state)?.map { it.title })
    }

    /**
     * **AI の推薦が後から届いても、開いている一覧の並びを入れ替えない。** モデルの準備が済むと関連ノートを読み直し、
     * 読み込み中を挟んでリンクの候補とAIの推薦が一緒に届く。読み込み中で一覧を消さず、届いた新しい候補は下へ足す。
     */
    @Test
    fun `後から届いた候補は並びを入れ替えず下へ足す`() {
        val first = paneRelatedCandidates(null, RelatedNotesState.Success(listOf(related("A"), related("B")), emptyList()))
        val reloading = paneRelatedCandidates(first, RelatedNotesState.Loading)
        val withAi = paneRelatedCandidates(
            reloading,
            RelatedNotesState.Success(relatedNotes = listOf(related("B"), related("A")), aiNotes = listOf(related("C")))
        )

        assertEquals(listOf("A", "B"), reloading?.map { it.title })
        assertEquals(listOf("A", "B", "C"), withAi?.map { it.title })
    }

    @Test
    fun `関連ノートがまだ届いていなければ候補を持たない`() {
        assertNull(paneRelatedCandidates(null, RelatedNotesState.Idle))
        assertNull(paneRelatedCandidates(null, RelatedNotesState.Loading))
    }

    @Test
    fun `並べ読みは余白ペインでない並べ方になったら終える`() {
        assertEquals(true, endsSideReading(windowKnown = true, paneVisible = false, reading = true))
        assertEquals(false, endsSideReading(windowKnown = true, paneVisible = true, reading = true))
        assertEquals(false, endsSideReading(windowKnown = true, paneVisible = false, reading = false))
    }

    /** 画面の作り直しの直後は、仮に余白ペインでない並べ方で組まれることがある。その間に終えると、閉じていないのに消える。 */
    @Test
    fun `窓の情報が揃う前は並べ読みを終えない`() {
        assertEquals(false, endsSideReading(windowKnown = false, paneVisible = false, reading = true))
    }

    // ── 関連の一覧の保存値（→ features/margin_pane.md §11「段5で決めたこと」）──────────

    /** 同じノートで組み直したとき、並びと開閉を保ち、読み込み中でも候補を失わない。 */
    @Test
    fun `同じノートの関連の一覧は、読み込み中も並びと開閉を保つ`() {
        val saved = PaneRelatedList(owner = "a.md", candidates = listOf(related("A"), related("B")), expanded = true)

        val restored = paneRelatedListFor(saved, "a.md", RelatedNotesState.Loading)
        val withAi = paneRelatedListFor(
            restored,
            "a.md",
            RelatedNotesState.Success(relatedNotes = listOf(related("B"), related("A")), aiNotes = listOf(related("C")))
        )

        assertEquals(saved, restored)
        assertEquals(listOf("A", "B", "C"), withAi?.candidates?.map { it.title })
        assertEquals(true, withAi?.expanded)
    }

    /** **別のノートへ持ち越さない。** 保存値は別のノートを開いた状態で復元されることがある。 */
    @Test
    fun `別のノートでは関連の一覧の並びも開閉も持ち越さない`() {
        val saved = PaneRelatedList(owner = "a.md", candidates = listOf(related("A")), expanded = true)

        assertEquals(PaneRelatedList("b.md", null, false), paneRelatedListFor(saved, "b.md", RelatedNotesState.Loading))
        assertEquals(
            PaneRelatedList("b.md", listOf(related("X")), false),
            paneRelatedListFor(saved, "b.md", RelatedNotesState.Success(listOf(related("X")), emptyList()))
        )
    }

    @Test
    fun `ノートが決まらない間は関連の一覧を保存値のまま返す`() {
        val saved = PaneRelatedList(owner = "a.md", candidates = listOf(related("A")), expanded = true)

        assertEquals(saved, paneRelatedListFor(saved, null, RelatedNotesState.Loading))
    }

    /** 画面の保存値を経ても、持ち主・開閉・候補の並びと欄が戻る。スニペットは持たない。 */
    @Test
    fun `関連の一覧は画面の保存値を経て同じ形に戻る`() {
        val scope = SaverScope { true }
        val list = PaneRelatedList(
            owner = "content://vault/a.md",
            candidates = listOf(
                RelatedNote("A", DocumentRef("content://vault/A.md"), isWikilinked = true, lastModified = 42L, snippet = "冒頭"),
                RelatedNote("B", DocumentRef("content://vault/B.md"), isWikilinked = false, lastModified = null)
            ),
            expanded = true
        )
        val waiting = list.copy(candidates = null, expanded = false)

        fun roundTrip(value: PaneRelatedList?) =
            with(PaneRelatedListSaver) { scope.save(value) }?.let { PaneRelatedListSaver.restore(it) }

        assertEquals(list.copy(candidates = list.candidates!!.map { it.copy(snippet = null) }), roundTrip(list))
        assertEquals(waiting, roundTrip(waiting))
        assertNull(roundTrip(null))
    }

    private companion object {
        val NOTICE = SectionChatProblem.AiStatus(
            AiStatusNotice(message = "この端末では使えません", action = AiNoticeAction.None, canTryAgainLater = false)
        )
    }
}
