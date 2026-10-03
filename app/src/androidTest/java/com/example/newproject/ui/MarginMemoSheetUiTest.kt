package com.example.newproject.ui

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SheetState
import androidx.compose.material3.ExperimentalMaterial3Api
import org.junit.Assert.assertTrue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import com.example.newproject.ui.screen.MarginMemoSheetHost
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.Modifier
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.newproject.domain.ArrangedMemos
import com.example.newproject.domain.HeadingIndex
import com.example.newproject.domain.SubmissionCheck
import com.example.newproject.domain.arrangeMemos
import com.example.newproject.model.MarginMemo
import com.example.newproject.domain.edited
import com.example.newproject.domain.settled
import com.example.newproject.domain.submissionOf
import com.example.newproject.domain.submitted
import com.example.newproject.model.SectionRef
import com.example.newproject.model.state.MarginMemoState
import com.example.newproject.model.state.MemoSaveStatus
import com.example.newproject.model.state.MemoSubmission
import com.example.newproject.ui.screen.ComposeMarginMemoDrafts
import com.example.newproject.ui.screen.MarginMemoSheetContent
import com.example.newproject.ui.screen.MemoReveal
import com.example.newproject.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import com.example.newproject.model.state.MarginMemoDraft
import com.example.newproject.model.state.SectionSummary
import com.example.newproject.ui.screen.SummaryRowInputs
import com.example.newproject.ui.screen.pendingAfterReveal

/**
 * **入力欄の中身がいつ消え、いつ戻るか**を、本番と同じ書きかけの置き場（[ComposeMarginMemoDrafts]）で見る。
 *
 * ## なぜUI側でも見るか
 *
 * 受理と入力欄の規則そのものは `MarginMemoDraftRulesTest`・`MarginMemoControllerTest`（JVM）が持つ。
 * ここが見るのは、**その規則が入力欄へ同じフレームで届くこと**と、**表示部品を外す・組み直すあいだも
 * 書きかけが部品の外に残ること**。部品の中に何かを覚えると、JVM のテストは全部緑のまま壊れる。
 *
 * 保存そのもの（預かり・退避・合流）は `ReadingTraceControllerTest` が持つ。
 */
// シートの器（`MarginMemoSheetHost`）は Material3 の試験的な `SheetState` を受け取る。
@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class MarginMemoSheetUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * 入力欄の指し方。**プレースホルダーで探さない** — 入力が空のときしか組み立てられないので、
     * 1文字でも入れた時点で存在しなくなる。この画面で文字を受け取れる要素は1つだけなので、その性質で指す。
     */
    private val input get() = composeRule.onNode(hasSetTextAction())

    /** **空のときだけ出る。** だから「入力欄が空へ戻った」ことの証拠に使える。 */
    private val PLACEHOLDER = "いま思ったこと"

    /**
     * Controller の役目を、本番と同じ純関数で演じる。**送信の作り方と照合は本番のまま**で、
     * 保存の結果だけをテストが決める。
     */
    private class Harness {
        val drafts = ComposeMarginMemoDrafts()
        var state by mutableStateOf<MarginMemoState>(READY)
        var note by mutableStateOf(NOTE_A)
        /** 本文の節。テストが本文を進めたことにして替える。 */
        var section by mutableStateOf<SectionRef?>(SectionRef("節B"))
        /** 照合して並べたメモ。null は本文の解析の前で、全件を平らに出す。 */
        var arranged by mutableStateOf<ArrangedMemos?>(null)
        var reveal by mutableStateOf<MemoReveal?>(null)
        var focusIntent by mutableStateOf(false)
        /** この節に前回の読書の跡を出すなら、その訪問の日時。テストが節を移ったことにして替える。 */
        var previousReadingAt by mutableStateOf<Long?>(null)
        val sent = mutableListOf<MemoSubmission>()
        private var clock = 0L

        val draft get() = drafts.draft(note)

        fun edit(text: String) = drafts.update(note) { it.edited(text, section) }

        fun submit() {
            val submission = submissionOf(drafts.draft(note).text, null, ++clock) ?: return
            drafts.update(note) { it.submitted(submission) }
            sent += submission
            state = READY.copy(status = MemoSaveStatus.Saving)
        }

        fun settle(submission: MemoSubmission, check: SubmissionCheck, status: MemoSaveStatus) {
            drafts.update(NOTE_A) { it.settled(submission, check) }
            state = READY.copy(status = status)
        }
    }

    /** 画面のフォーカスを外す（キーボードの完了などで利用者が外したのと同じ）。 */
    private var focusClearer: () -> Unit = {}

    private fun setContent(harness: Harness, shown: () -> Boolean = { true }, inPane: () -> Boolean = { true }) {
        composeRule.setContent {
            val focusManager = LocalFocusManager.current
            focusClearer = { focusManager.clearFocus() }
            AppTheme(darkTheme = false) {
                if (shown()) {
                    val content: @androidx.compose.runtime.Composable () -> Unit = {
                        MarginMemoSheetContent(
                            state = harness.state,
                            draft = harness.draft,
                            section = harness.section,
                            hasHeadings = true,
                            onJumpToSection = { harness.section = it },
                            arranged = harness.arranged,
                            onEdit = harness::edit,
                            onSubmit = harness::submit,
                            onDelete = {},
                            reveal = harness.reveal,
                            onRevealHandled = { harness.reveal = null },
                            focusIntent = harness.focusIntent,
                            onFocusIntentChange = { harness.focusIntent = it },
                            previousReadingAt = harness.previousReadingAt
                        )
                    }
                    if (inPane()) Box { content() } else Column { content() }
                }
            }
        }
    }

    @Test
    fun 置いた後も同じシートで続けて書ける() {
        val harness = Harness()
        setContent(harness)

        input.performTextInput("1件目")
        composeRule.onNodeWithText("置く").performClick()
        composeRule.runOnIdle { harness.settle(harness.sent.last(), SubmissionCheck.Accepted, MemoSaveStatus.Saved) }
        // **ここが本題。** 受け取れたので入力欄が空へ戻る。
        composeRule.onNodeWithText(PLACEHOLDER).assertExists()
        input.performTextInput("2件目")
        composeRule.onNodeWithText("置く").performClick()

        assertEquals(listOf("1件目", "2件目"), harness.sent.map { it.raw })
    }

    /** 打った文字は**同じフレームのうちに**入力欄へ戻る。続けて打っても欠けない。 */
    @Test
    fun 続けて打った文字が欠けない() {
        val harness = Harness()
        setContent(harness)

        input.performTextInput("いま")
        input.performTextInput("思った")
        input.performTextInput("こと")

        composeRule.onNodeWithText("いま思ったこと").assertExists()
        assertEquals("いま思ったこと", harness.draft.text)
    }

    /** 置けなかった入力は**原文のまま手元に残る。** 押した瞬間に空にすると、切り詰めた後の文字列しか戻せない。 */
    @Test
    fun 満杯で置けなかった入力は原文のまま残る() {
        val long = "あ".repeat(400)
        val harness = Harness()
        setContent(harness)

        input.performTextInput(long)
        composeRule.onNodeWithText("置く").performClick()
        composeRule.runOnIdle { harness.settle(harness.sent.last(), SubmissionCheck.NotAccepted, MemoSaveStatus.Full) }

        composeRule.onNodeWithText(long).assertExists()
    }

    /** 読み込み中・保存中は押させない。押せると、入力だけが宙に浮く。 */
    @Test
    fun 読み込み中と保存中は置くを押せない() {
        val harness = Harness()
        harness.state = MarginMemoState.Loading
        setContent(harness)

        input.performTextInput("読み込み中に書いた")
        composeRule.onNodeWithText("置く").assertIsNotEnabled()

        composeRule.runOnIdle { harness.state = READY.copy(status = MemoSaveStatus.Saving) }
        composeRule.onNodeWithText("置く").assertIsNotEnabled()
        composeRule.onNodeWithText("読み込み中に書いた").assertExists()
    }

    /** **保存を待っているあいだに書いた下書きを、前の要求の結果で消さない。** */
    @Test
    fun 保存待ちに書いた下書きは前の要求の完了で消えない() {
        val harness = Harness()
        setContent(harness)

        input.performTextInput("1件目")
        composeRule.onNodeWithText("置く").performClick()
        input.performTextReplacement("待っているあいだに書いた")
        composeRule.runOnIdle { harness.settle(harness.sent.last(), SubmissionCheck.Accepted, MemoSaveStatus.Saved) }

        composeRule.onNodeWithText("待っているあいだに書いた").assertExists()
    }

    /**
     * **保存中に表示部品を外し、外している間に受理されても、戻したときに入力は空。**
     * 書きかけが部品の外にあるので、作り直した部品も受理を知っている（ペインを ✎ でしまったときの形）。
     */
    @Test
    fun 保存中に表示を外しても_受理されていれば戻したとき入力は空() {
        val harness = Harness()
        var shown by mutableStateOf(true)
        setContent(harness, shown = { shown })

        input.performTextInput("送信するメモ")
        composeRule.onNodeWithText("置く").performClick()
        composeRule.runOnIdle { shown = false }
        composeRule.runOnIdle { harness.settle(harness.sent.last(), SubmissionCheck.Accepted, MemoSaveStatus.Saved) }
        composeRule.runOnIdle { shown = true }

        composeRule.onNodeWithText(PLACEHOLDER).assertExists()
        assertEquals(1, harness.sent.size)
    }

    /** **ペインとシートの行き来**（別の場所へ組み直す）をまたいでも、書きかけはそのまま残る。 */
    @Test
    fun 別の場所へ組み直しても書きかけが残る() {
        val harness = Harness()
        var inPane by mutableStateOf(true)
        setContent(harness, inPane = { inPane })

        input.performTextInput("ペインで書きかけ")
        composeRule.runOnIdle { inPane = false }

        composeRule.onNodeWithText("ペインで書きかけ").assertExists()
    }

    /**
     * **窓が切り替わって面が組み替わっても、入力のフォーカスを引き継ぐ**（→ features/margin_pane.md §5.4）。
     * 組み替わりで外れたフォーカスは「書くのをやめた」ではないので、新しい面の入力欄へ戻す。
     */
    @Test
    fun 面が組み替わっても入力のフォーカスを引き継ぐ() {
        val harness = Harness()
        var inPane by mutableStateOf(true)
        setContent(harness, inPane = { inPane })

        input.performClick()
        input.assertIsFocused()
        composeRule.runOnIdle { inPane = false }

        input.assertIsFocused()
        composeRule.runOnIdle { assertEquals(true, harness.focusIntent) }
    }

    /**
     * **フォーカスを取ってよいのは出ている面だけ。** しまう途中のシートとペインが一瞬だけ両方組まれても、
     * ペインの入力欄がフォーカスを持ち、しまわれるシートが外れても書く意図は落ちない。
     */
    @Test
    fun しまう途中の面と出ている面が並んでも_出ている面がフォーカスを持つ() {
        val harness = Harness()
        harness.focusIntent = true
        var sheetShown by mutableStateOf(true)
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                Column {
                    listOf(PANE_TAG to true, SHEET_TAG to false).forEach { (tag, active) ->
                        if (active || sheetShown) {
                            Box(modifier = Modifier.testTag(tag)) {
                                MarginMemoSheetContent(
                                    state = harness.state,
                                    draft = harness.draft,
                                    section = harness.section,
                                    hasHeadings = true,
                                    onJumpToSection = {},
                                    arranged = null,
                                    onEdit = harness::edit,
                                    onSubmit = harness::submit,
                                    onDelete = {},
                                    focusIntent = harness.focusIntent,
                                    onFocusIntentChange = { harness.focusIntent = it },
                                    active = active
                                )
                            }
                        }
                    }
                }
            }
        }
        val paneInput = composeRule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag(PANE_TAG)))
        paneInput.assertIsFocused()

        composeRule.runOnIdle { sheetShown = false }
        composeRule.waitForIdle()

        paneInput.assertIsFocused()
        composeRule.runOnIdle { assertEquals("しまわれた面が書く意図を落とした", true, harness.focusIntent) }
    }

    /** 利用者がフォーカスを外したら（面は残っている）、書くのをやめたとして落とす。 */
    @Test
    fun 面が残ったままフォーカスを外したら書く意図を落とす() {
        val harness = Harness()
        setContent(harness)

        input.performClick()
        composeRule.runOnIdle { assertEquals(true, harness.focusIntent) }
        composeRule.runOnIdle { focusClearer() }

        composeRule.waitForIdle()
        composeRule.runOnIdle { assertEquals(false, harness.focusIntent) }
    }

    /** ノートごとに書きかけを持つ。**別のノートに前のノートの書きかけを出さず、戻れば元に戻る。** */
    @Test
    fun ノートを替えると別のノートの書きかけを描き_戻れば元に戻る() {
        val harness = Harness()
        setContent(harness)

        input.performTextInput("Aに書きかけ")
        composeRule.runOnIdle { harness.note = NOTE_B }
        composeRule.onNodeWithText(PLACEHOLDER).assertExists()

        composeRule.runOnIdle { harness.note = NOTE_A }
        composeRule.onNodeWithText("Aに書きかけ").assertExists()
    }

    /**
     * **確かめられない送信は、未保存と断定しない。** 整えた本文が同じ編集なら、ボタンは元の送信を確かめる役目。
     * 中身を変えたら、別のメモとして置く役目に戻る。
     */
    @Test
    fun 保存を確認できない間は_同じ本文なら確かめるボタンになる() {
        val harness = Harness()
        setContent(harness)

        input.performTextInput("確かめたいメモ")
        composeRule.onNodeWithText("置く").performClick()
        composeRule.runOnIdle {
            harness.settle(harness.sent.last(), SubmissionCheck.Unconfirmed, MemoSaveStatus.Unconfirmed)
        }

        composeRule.onNodeWithText("保存を確認できません").assertExists()
        composeRule.onNodeWithText("保存を確かめる").assertIsEnabled()

        // 末尾の空白を足しただけでは、別のメモとして置かない。
        input.performTextInput(" ")
        composeRule.onNodeWithText("保存を確かめる").assertExists()

        input.performTextReplacement("別の内容")
        composeRule.onNodeWithText("置く").assertExists()
    }

    /**
     * **書き込み先の知らせが出入りしても、入力欄の画面上の位置を動かさない**（→ features/margin_pane.md §5.2）。
     * 書いている最中に本文を進めると知らせが出るので、そのたびに入力欄が下がると打っている指の下がずれる。
     */
    @Test
    fun 書き込み先の知らせが出ても入力欄は動かない() {
        val harness = Harness()
        setContent(harness)

        input.performTextInput("節Bで書き始めた")
        val before = input.fetchSemanticsNode().boundsInRoot
        composeRule.runOnIdle { harness.section = SectionRef("節C") }

        composeRule.onNodeWithText("「節B」へ書き込み中").assertExists()
        assertEquals(before, input.fetchSemanticsNode().boundsInRoot)

        // 長い見出しの節へ移っても、見出しの行が増えて入力欄が下がらない。
        composeRule.runOnIdle { harness.section = SectionRef("長い見出し".repeat(20)) }
        assertEquals(before, input.fetchSemanticsNode().boundsInRoot)

        // 「本文を戻す」で本文が書き込み先へ戻れば、知らせは消える。
        composeRule.onNodeWithText("本文を戻す").performClick()
        composeRule.onNodeWithText("「節B」へ書き込み中").assertDoesNotExist()
    }

    /**
     * **前回の跡の行が出入りしても、書いている間は入力欄を動かさない**（→ features/margin_pane.md §5.2）。
     * 面の節は書いている間も本文についていくので、跡のある節と無い節を行き来する。
     */
    @Test
    fun 跡のある節で書き始めたら跡の無い節へ移っても入力欄は動かない() {
        val harness = Harness()
        harness.previousReadingAt = PREVIOUS_READING_AT
        setContent(harness)
        composeRule.onNodeWithText(PREVIOUS_READING, substring = true).assertExists()

        input.performTextInput("跡のある節で書き始めた")
        val before = input.fetchSemanticsNode().boundsInRoot
        composeRule.runOnIdle { harness.previousReadingAt = null }

        composeRule.onNodeWithText(PREVIOUS_READING, substring = true).assertDoesNotExist()
        assertEquals(before, input.fetchSemanticsNode().boundsInRoot)

        composeRule.runOnIdle { harness.previousReadingAt = PREVIOUS_READING_AT }
        composeRule.onNodeWithText(PREVIOUS_READING, substring = true).assertExists()
        assertEquals(before, input.fetchSemanticsNode().boundsInRoot)
    }

    /** 跡は読み込みで遅れて届くこともある。**書いている間は足さず、書き終えたら出す。** */
    @Test
    fun 跡の無いときに書き始めたら跡が届いても入力欄は動かず書き終えると出る() {
        val harness = Harness()
        setContent(harness)

        input.performTextInput("跡の無い節で書き始めた")
        val before = input.fetchSemanticsNode().boundsInRoot
        composeRule.runOnIdle { harness.previousReadingAt = PREVIOUS_READING_AT }

        composeRule.onNodeWithText(PREVIOUS_READING, substring = true).assertDoesNotExist()
        assertEquals(before, input.fetchSemanticsNode().boundsInRoot)

        composeRule.runOnIdle { focusClearer() }
        composeRule.onNodeWithText(PREVIOUS_READING, substring = true).assertExists()
    }

    /** ほかの節のメモは畳んで件数だけ。開くと節の名前が並び、押すと本文がその節へ飛ぶ。 */
    @Test
    fun ほかの節のメモは開くと節ごとに並び_節の名前で本文が飛ぶ() {
        val harness = Harness()
        val memos = listOf(MarginMemo("節Cのメモ", 1L, "節C"))
        harness.state = READY.copy(memos = memos)
        harness.arranged = arrangeMemos(memos, HeadingIndex(listOf("節B", "節C")), current = SectionRef("節B"))
        setContent(harness)

        composeRule.onNodeWithText("節Cのメモ").assertDoesNotExist()
        composeRule.onNodeWithText("▸ ほかの節のメモ 1件").performClick()
        composeRule.onNodeWithText("節Cのメモ").assertExists()

        composeRule.onNodeWithText("節C").performClick()
        composeRule.runOnIdle { assertEquals(SectionRef("節C"), harness.section) }
    }

    /**
     * **今の節と同名のほかの候補に共通のメモだけでも、他方の候補を開いて選べる。** 選んだ候補へ本文が移る。
     */
    @Test
    fun 同名の見出しに共通のメモだけでも_他方の候補を開いて選べる() {
        val harness = Harness()
        val memos = listOf(MarginMemo("まとめのメモ", 1L, "まとめ"))
        harness.section = SectionRef("まとめ", 0)
        harness.state = READY.copy(memos = memos)
        harness.arranged = arrangeMemos(memos, HeadingIndex(listOf("まとめ", "本論", "まとめ")), current = SectionRef("まとめ", 0))
        setContent(harness)

        composeRule.onNodeWithText("▸ ほかの節のメモ 1件").performClick()
        composeRule.onNodeWithText("まとめ（2つ目）").performClick()

        composeRule.runOnIdle { assertEquals(SectionRef("まとめ", 1), harness.section) }
    }

    /** 再会カードの「前回のメモを見る」から来たら、**ほかの節のメモも開いて**メモの並びまで送る。 */
    @Test
    fun 前回のメモを見るから来たら_ほかの節のメモを開いて送る() {
        val harness = Harness()
        val memos = listOf(MarginMemo("節Cのメモ", 1L, "節C"))
        harness.state = READY.copy(memos = memos)
        harness.arranged = arrangeMemos(memos, HeadingIndex(listOf("節B", "節C")), current = SectionRef("節B"))
        harness.reveal = MemoReveal.AllMemos
        setContent(harness)

        composeRule.onNodeWithText("節Cのメモ").assertExists()
        composeRule.runOnIdle { assertEquals("送った後も依頼が残った", null, harness.reveal) }
    }

    /**
     * **シートを出しても本文を操作できる**（→ features/margin_pane.md §5.5）。暗幕を出す
     * `ModalBottomSheet` では、暗幕を透明にしても本文を触れなかった。
     */
    @Test
    fun シートを出しても本文を押せる() {
        var bodyClicks = 0
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                MarginMemoSheetHost(
                    visible = true,
                    expandRequested = false,
                    onDismiss = {},
                    sheet = { Text("シートの中身") }
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Button(onClick = { bodyClicks++ }) { Text("本文のボタン") }
                    }
                }
            }
        }

        composeRule.onNodeWithText("シートの中身").assertIsDisplayed()
        composeRule.onNodeWithText("本文のボタン").performClick()
        composeRule.runOnIdle { assertEquals(1, bodyClicks) }
    }

    /**
     * **半分のシートを出したまま、本文の最後の段落をシートより上で読める**（→ features/margin_pane.md §5.5）。
     * 本文を全高で組むと、末尾はシートの背後でスクロールの限界に達し、読書の進捗も背後のブロックを数える。
     * キーボードが出たときは器が低くなるので、器の高さを替えて同じことを確かめる。
     */
    @Test
    fun 半分のシートを出したまま本文の最後の段落をシートより上で読める() {
        var height by mutableStateOf(800.dp)
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                Box(modifier = Modifier.fillMaxWidth().height(height)) {
                    MarginMemoSheetHost(
                        visible = true,
                        expandRequested = false,
                        onDismiss = {},
                        sheet = { Text("シートの中身", modifier = Modifier.height(300.dp)) }
                    ) {
                        LazyColumn(modifier = Modifier.fillMaxSize().testTag(BODY_TAG)) {
                            items(60) { Text("段落$it", modifier = Modifier.height(40.dp)) }
                        }
                    }
                }
            }
        }

        listOf(800.dp, 450.dp).forEach { containerHeight ->
            composeRule.runOnIdle { height = containerHeight }
            composeRule.onNodeWithTag(BODY_TAG).performScrollToIndex(59)
            composeRule.waitForIdle()

            val sheetTop = composeRule.onNodeWithText("シートの中身").fetchSemanticsNode().boundsInRoot.top
            val last = composeRule.onNodeWithText("段落59").fetchSemanticsNode().boundsInRoot
            val bodyBottom = composeRule.onNodeWithTag(BODY_TAG).fetchSemanticsNode().boundsInRoot.bottom
            assertTrue("$containerHeight: 最後の段落がシートの背後にある", last.bottom <= sheetTop)
            assertTrue("$containerHeight: 本文の表示域がシートの背後まで伸びている", bodyBottom <= sheetTop)
        }
    }

    /**
     * **印から来たら、行き先の節の組まで送る。** 面の節が行き先でないとき（短い節で本文が上端まで来ない）も、
     * ほかの節を開いてその組を見せる。別の節の組を行き先にしない。
     */
    @Test
    fun 印から来たら行き先の節の組まで送る() {
        val harness = Harness()
        val memos = (1..12).map { MarginMemo("節Bのメモ$it", 100L + it, "節B") } +
            listOf(MarginMemo("節Cのメモ", 2L, "節C"), MarginMemo("節Dのメモ", 1L, "節D"))
        harness.state = READY.copy(memos = memos)
        harness.arranged = arrangeMemos(memos, HeadingIndex(listOf("節B", "節C", "節D")), current = SectionRef("節B"))
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                Box(modifier = Modifier.fillMaxWidth().height(320.dp)) {
                    MarginMemoSheetContent(
                        state = harness.state,
                        draft = harness.draft,
                        section = harness.section,
                        hasHeadings = true,
                        onJumpToSection = {},
                        arranged = harness.arranged,
                        onEdit = harness::edit,
                        onSubmit = harness::submit,
                        onDelete = {},
                        reveal = harness.reveal,
                        onRevealHandled = { harness.reveal = null }
                    )
                }
            }
        }
        composeRule.onNodeWithText("節Dのメモ").assertDoesNotExist()

        composeRule.runOnIdle { harness.reveal = MemoReveal.Section(SectionRef("節D")) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("節Dのメモ").assertIsDisplayed()

        composeRule.runOnIdle { harness.reveal = MemoReveal.Section(SectionRef("節C")) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("節Cのメモ").assertIsDisplayed()
    }

    /**
     * **広げる依頼は、途中で止められても持ち越さず、次の依頼を別の依頼として受ける**（→ features/margin_pane.md §11）。
     * 正常に広げ終えたときだけ保留を外していたころは、広げる途中で払って半分へ戻すと保留が残り、後から押した印で広がらなかった。
     * 時計を止めて本番の器を通し、①通常 ②途中で半分へ戻した後の別の依頼 ③途中で閉じた後の ✎ ④依頼が先に消えた場合を順に見る。
     */
    @Test
    fun 広げる途中で止められても_次の送る依頼でまた広げ_閉じたら持ち越さない() {
        composeRule.mainClock.autoAdvance = false
        var visible by mutableStateOf(true)
        var expandRequested by mutableStateOf(false)
        lateinit var sheetState: SheetState
        lateinit var scope: CoroutineScope
        composeRule.setContent {
            sheetState = rememberStandardBottomSheetState(initialValue = SheetValue.Hidden, skipHiddenState = false)
            scope = rememberCoroutineScope()
            AppTheme(darkTheme = false) {
                Box(modifier = Modifier.fillMaxWidth().height(800.dp)) {
                    MarginMemoSheetHost(
                        visible = visible,
                        expandRequested = expandRequested,
                        onDismiss = { visible = false },
                        sheet = { Text("シートの中身", modifier = Modifier.height(700.dp)) },
                        sheetState = sheetState
                    ) { Box(modifier = Modifier.fillMaxSize()) }
                }
            }
        }
        fun settle() = composeRule.mainClock.advanceTimeBy(3_000)
        fun onUi(block: () -> Unit) = composeRule.runOnUiThread(block)
        fun backToHalf() {
            onUi { scope.launch { sheetState.partialExpand() } }
            settle()
        }
        settle()
        assertEquals(SheetValue.PartiallyExpanded, sheetState.currentValue)

        // ① 通常の依頼で画面いっぱいへ広がる。
        onUi { expandRequested = true }
        settle()
        assertEquals("①", SheetValue.Expanded, sheetState.currentValue)
        onUi { expandRequested = false }
        backToHalf()

        // ② 広げる途中で半分へ戻す。面が送り終えて依頼が消えた後、別の依頼でまた広がる。
        onUi { expandRequested = true }
        composeRule.mainClock.advanceTimeBy(100)
        onUi { expandRequested = false }
        backToHalf()
        assertEquals(SheetValue.PartiallyExpanded, sheetState.currentValue)
        onUi { expandRequested = true }
        settle()
        assertEquals("② 途中で止めた後の依頼で広がらない", SheetValue.Expanded, sheetState.currentValue)
        onUi { expandRequested = false }
        backToHalf()

        // ③ 広げる途中で閉じる。✎ で出し直すと半分で止まり、古い依頼で広がらない。
        onUi { expandRequested = true }
        composeRule.mainClock.advanceTimeBy(100)
        onUi {
            visible = false
            expandRequested = false
        }
        settle()
        assertEquals(SheetValue.Hidden, sheetState.currentValue)
        onUi { visible = true }
        settle()
        assertEquals("③ 閉じる前の依頼を持ち越した", SheetValue.PartiallyExpanded, sheetState.currentValue)

        // ④ 面が先に送り終えて依頼が消えても、始めた広げは止めない。
        onUi { expandRequested = true }
        composeRule.mainClock.advanceTimeByFrame()
        onUi { expandRequested = false }
        settle()
        assertEquals("④", SheetValue.Expanded, sheetState.currentValue)
    }

    /**
     * **要約の行へ送る動きを途中で止めても、依頼を残さず、同じ面のまま次の押下でまた送れる**（→ features/margin_pane.md §11）。
     * 送り終えたときだけ依頼を消していたころは、利用者のスクロールで止めると依頼が残り、押し直しても送れなかった。
     * 時計を止めて、送る動きの途中に指のドラッグを割り込ませる。
     */
    @Test
    fun 要約の行へ送る途中で止めても_次の押下でまた要約の行へ届く() {
        var pending by mutableStateOf<Long?>(null)
        val memos = (1..20).map { MarginMemo("下のメモ$it", writtenAtEpochMillis = it.toLong(), sectionTitle = "節B") }
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                Box(modifier = Modifier.fillMaxWidth().height(400.dp).testTag(PANE_TAG)) {
                    MarginMemoSheetContent(
                        state = MarginMemoState.Ready(memos = memos),
                        draft = MarginMemoDraft(),
                        section = SectionRef("節B"),
                        hasHeadings = true,
                        onJumpToSection = {},
                        arranged = null,
                        onEdit = {},
                        onSubmit = {},
                        onDelete = {},
                        summaryRow = SummaryRowInputs(
                            summary = SectionSummary(SectionRef("節B"), 1L, "節B", "本文", summary = "節Bの要約"),
                            onRequest = {},
                            onRetry = {},
                            onCancel = {}
                        ),
                        revealSummary = pending,
                        onSummaryRevealHandled = { handled -> pending = pendingAfterReveal(pending, handled) }
                    )
                }
            }
        }
        composeRule.onNodeWithText("下のメモ20").performScrollTo()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("節Bの要約").assertIsNotDisplayed()

        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread { pending = 1L }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag(PANE_TAG).performTouchInput {
            down(center)
            moveBy(Offset(0f, -viewConfiguration.touchSlop * 4))
            up()
        }
        composeRule.mainClock.advanceTimeBy(3_000)
        composeRule.runOnIdle { assertEquals("止めた依頼が残った", null, pending) }

        composeRule.runOnUiThread { pending = 2L }
        composeRule.mainClock.advanceTimeBy(3_000)
        composeRule.onNodeWithText("節Bの要約").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals("送り終えた依頼が残った", null, pending) }
    }

    /**
     * **閉じた面から、出す依頼と広げる依頼が同じフレームで届いても、シートが開いて広がる**（→ features/margin_pane.md §11）。
     * 閉じたシートで見出しの印や「前回のメモを見る」を押すと、この2つが同じ再構成で届く。
     * 保留を効果のキーにしていたころは、保留が立った変化で広げる効果が自分を取り消し、先に始めた partialExpand ごと止まってシートが開かなかった。
     */
    @Test
    fun 閉じた面から出す依頼と広げる依頼が同時に届いても_開いて広がる() {
        composeRule.mainClock.autoAdvance = false
        var visible by mutableStateOf(false)
        var expandRequested by mutableStateOf(false)
        lateinit var sheetState: SheetState
        composeRule.setContent {
            sheetState = rememberStandardBottomSheetState(initialValue = SheetValue.Hidden, skipHiddenState = false)
            AppTheme(darkTheme = false) {
                Box(modifier = Modifier.fillMaxWidth().height(800.dp)) {
                    MarginMemoSheetHost(
                        visible = visible,
                        expandRequested = expandRequested,
                        onDismiss = { visible = false },
                        sheet = { Text("シートの中身", modifier = Modifier.height(700.dp)) },
                        sheetState = sheetState
                    ) { Box(modifier = Modifier.fillMaxSize()) }
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(3_000)
        assertEquals(SheetValue.Hidden, sheetState.currentValue)

        composeRule.runOnUiThread {
            visible = true
            expandRequested = true
        }
        composeRule.mainClock.advanceTimeBy(3_000)

        assertEquals("閉じた面から開いて広がらない", SheetValue.Expanded, sheetState.currentValue)
    }

    /**
     * **閉じたシートを画面に描かない**（→ features/margin_pane.md §5.5）。閉じたシートは器のすぐ下に置かれ、
     * 面とハンドルは中身と別に常に組まれるので、描くと上端が下の余白へはみ出し、開いていないのに白い面が見えた。
     * 中身の有無ではなく、**器のすぐ下の余白に何が描かれたか**で確かめる。開く前と、開いて閉じた後の両方。
     */
    @Test
    fun 閉じたシートの面とハンドルは_器の下の余白に描かれない() {
        var visible by mutableStateOf(false)
        composeRule.setContent {
            Column(modifier = Modifier.fillMaxWidth().background(Color.Black)) {
                Box(modifier = Modifier.fillMaxWidth().height(600.dp)) {
                    MarginMemoSheetHost(
                        visible = visible,
                        expandRequested = false,
                        onDismiss = { visible = false },
                        sheet = { Text("シートの中身", modifier = Modifier.height(300.dp)) }
                    ) { Box(modifier = Modifier.fillMaxSize()) }
                }
                // 器のすぐ下の余白。ここ自身は何も描かない（はみ出したものだけが映る）。
                Box(modifier = Modifier.fillMaxWidth().height(48.dp).testTag(BELOW_TAG))
            }
        }
        fun assertBelowIsBlank(label: String) {
            val pixels = composeRule.onNodeWithTag(BELOW_TAG).captureToImage().toPixelMap()
            val drawn = (0 until pixels.width).sumOf { x -> (0 until pixels.height).count { y -> pixels[x, y] != Color.Black } }
            assertEquals("$label: 器の下の余白に閉じたシートが描かれた", 0, drawn)
        }

        composeRule.waitForIdle()
        assertBelowIsBlank("開く前")

        composeRule.runOnIdle { visible = true }
        composeRule.onNodeWithText("シートの中身").assertIsDisplayed()
        composeRule.runOnIdle { visible = false }
        composeRule.waitForIdle()
        assertBelowIsBlank("閉じた後")
    }

    /** 下へ払うほかに**閉じるボタン**で閉じられる。隠れたら中身を組まない（読み上げが隠れたメモへ移れない）。 */
    @Test
    fun 閉じるボタンでシートを閉じ_隠れたら中身を組まない() {
        var visible by mutableStateOf(true)
        val harness = Harness()
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                MarginMemoSheetHost(
                    visible = visible,
                    expandRequested = false,
                    onDismiss = { visible = false },
                    sheet = {
                        MarginMemoSheetContent(
                            state = harness.state,
                            draft = harness.draft,
                            section = harness.section,
                            hasHeadings = true,
                            onJumpToSection = {},
                            arranged = null,
                            onEdit = harness::edit,
                            onSubmit = harness::submit,
                            onDelete = {},
                            asSheet = true,
                            onClose = { visible = false }
                        )
                    }
                ) { Box(modifier = Modifier.fillMaxSize()) }
            }
        }

        composeRule.onNodeWithText("閉じる").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText(PLACEHOLDER).assertDoesNotExist()
    }

    private companion object {
        const val BODY_TAG = "本文"
        const val PANE_TAG = "ペイン"
        const val BELOW_TAG = "器の下"
        const val SHEET_TAG = "シート"
        const val NOTE_A = "content://vault-a/a.md"
        const val NOTE_B = "content://vault-a/b.md"
        /** 前回の跡の文言の頭。日付は今日によって年が付くかが変わるので、頭だけで探す。 */
        const val PREVIOUS_READING = "前回はここまで読んだ"
        const val PREVIOUS_READING_AT = 1_757_635_200_000L
        val READY = MarginMemoState.Ready(memos = emptyList())
    }
}
