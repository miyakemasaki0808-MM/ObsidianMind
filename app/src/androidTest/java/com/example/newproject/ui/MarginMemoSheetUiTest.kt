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
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import com.example.newproject.domain.consumedBy
import org.junit.Assert.assertFalse
import android.view.KeyEvent as AndroidKeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.test.pressKey
import androidx.compose.foundation.text.input.TextFieldState
import com.example.newproject.ui.screen.MemoField
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.newproject.domain.ArrangedMemos
import com.example.newproject.domain.HeadingIndex
import com.example.newproject.domain.SubmissionCheck
import com.example.newproject.domain.arrangeMemos
import com.example.newproject.model.MarginMemo
import com.example.newproject.ui.screen.DRAFT_QUOTE_PENDING
import com.example.newproject.domain.quoteAbandoned
import com.example.newproject.domain.quoteRequested
import org.junit.Assert.assertNull
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.hasContentDescription
import com.example.newproject.model.state.DraftQuote
import com.example.newproject.domain.quoted
import com.example.newproject.domain.unquoted
import com.example.newproject.domain.choiceDismissed
import com.example.newproject.domain.markdown.NoteSectionModel
import com.example.newproject.ui.screen.quoteChoiceQuestion
import com.example.newproject.ui.screen.DRAFT_QUOTE_DESTINATION
import com.example.newproject.ui.screen.DRAFT_QUOTE_CUT_NOTICE
import com.example.newproject.ui.screen.QuoteCandidateFinder
import com.example.newproject.ui.screen.DraftQuoteActions
import com.example.newproject.model.QuoteContext
import com.example.newproject.model.MemoPassage
import com.example.newproject.domain.markdown.buildNoteSectionModel
import com.example.newproject.domain.layoutMemos
import com.example.newproject.domain.locateMemos
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
import com.example.newproject.model.state.SectionChatProblem
import com.example.newproject.model.state.AiStatusNotice
import com.example.newproject.model.state.AiNoticeAction
import androidx.compose.ui.test.hasText
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
        /** 引用した段落か書いた頃の位置へ送ったブロック。 */
        var jumpedBlock by mutableStateOf<Int?>(null)
        /** 入力欄の編集の状態の置き場（机の画面と同じく面の外で持つ）。 */
        val field = MemoField()
        /** 引用の候補を探す口。null は本文の解析の前。 */
        var finder by mutableStateOf<QuoteCandidateFinder?>(null)
        /** 付ける場で選んだ候補のブロックと、候補を探した解析結果。 */
        val chosen = mutableListOf<Pair<Int, NoteSectionModel>>()
        /** 引用の欄の操作も、本番と同じ書きかけの規則で演じる。 */
        val quoteActions = DraftQuoteActions(
            onDetach = { drafts.update(note) { it.unquoted() } },
            onChoose = { _, block, model -> chosen += block to model },
            onDismissChoice = { drafts.update(note) { it.choiceDismissed() } }
        )
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

    /** 画面の根の View。キーボードと同じ口（InputConnection）を開くのに使う。 */
    private var rootView: View? = null

    private fun setContent(harness: Harness, shown: () -> Boolean = { true }, inPane: () -> Boolean = { true }) {
        composeRule.setContent {
            val focusManager = LocalFocusManager.current
            focusClearer = { focusManager.clearFocus() }
            rootView = LocalView.current
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
                            onJumpToBlock = { harness.jumpedBlock = it },
                            onEdit = harness::edit,
                            onSubmit = harness::submit,
                            onDelete = {},
                            reveal = harness.reveal,
                            onRevealHandled = { harness.reveal = null },
                            focusIntent = harness.focusIntent,
                            onFocusIntentChange = { harness.focusIntent = it },
                            previousReadingAt = harness.previousReadingAt,
                            quoteCandidates = harness.finder,
                            quoteActions = harness.quoteActions,
                            memoField = harness.field
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

    /**
     * **引用は本文の上に出し、確定していれば押すとその段落へ、書いた頃の位置は「書いた頃の位置へ」でその位置へ本文を送る**（→ features/margin_quote.md §5.5）。
     * 特定できない引用は押せず、似た一節の数を添える。照らし直しと並べ方は本番の関数を通す。
     */
    @Test
    fun 引用と書いた頃の位置から本文の段落へ飛べ_特定できない引用はそう添える() {
        val harness = Harness()
        val model = buildNoteSectionModel(
            "## 節B\n\n意志の力に頼ると、三日で元に戻る。\n\n結論として、この方法は有効である。ただし条件Aでは使わない。\n\n同じ一節\n\n同じ一節"
        )
        val memos = listOf(
            MarginMemo("位置のメモ", 3L, "節B", MemoPassage.Seen("意志の力に頼ると、三日で元に戻る。")),
            MarginMemo("引用のメモ", 2L, "節B", MemoPassage.Quoted("この方法は有効である。", QuoteContext("結論として、", "ただし条件Aでは使わな"), truncated = true)),
            MarginMemo("特定できないメモ", 1L, "節B", MemoPassage.Quoted("同じ一節", context = null, truncated = false))
        )
        harness.state = READY.copy(memos = memos)
        harness.arranged = arrangeMemos(layoutMemos(memos, model.headingIndex, locateMemos(memos, model.passages, model.headingIndex)), SectionRef("節B"))
        setContent(harness)

        composeRule.onNodeWithText("書いた頃の位置へ").performClick()
        composeRule.runOnIdle { assertEquals(1, harness.jumpedBlock) }
        composeRule.onNodeWithText("この方法は有効である。…").performClick()
        composeRule.runOnIdle { assertEquals(2, harness.jumpedBlock) }
        composeRule.onNodeWithText("引用した場所を特定できません · 似た一節 2 か所", substring = true).assertExists()
        composeRule.onNodeWithText("同じ一節").performClick()
        composeRule.runOnIdle { assertEquals("特定できない引用で本文を送った", 2, harness.jumpedBlock) }
    }

    /**
     * **引用の欄は、引用・入らない部分・行き先と未保存を見せ、✕ で引用だけを外す**（→ features/margin_quote.md §5.2・§5.3）。
     * 引用があって言葉が空なら、入力欄が添えることを促す（引用だけでは置けない → 判断2）。
     */
    @Test
    fun 引用の欄は引用と入らない部分と行き先を見せ_バツで引用だけを外す() {
        val harness = Harness()
        harness.drafts.update(NOTE_A) {
            it.quoted(DraftQuote(MemoPassage.Quoted("引いた一節", QuoteContext("", ""), truncated = true), cut = "入らない残り"), SectionRef("節B"))
        }
        setContent(harness)

        composeRule.onNodeWithText("引いた一節").assertExists()
        composeRule.onNodeWithText("$DRAFT_QUOTE_CUT_NOTICE：入らない残り").assertExists()
        composeRule.onNodeWithText(DRAFT_QUOTE_DESTINATION).assertExists()
        composeRule.onNodeWithText("引用にひとこと添えて置く").assertExists()

        composeRule.onNode(hasContentDescription("引用を外す")).performClick()

        composeRule.onNodeWithText("引いた一節").assertDoesNotExist()
        composeRule.onNodeWithText(PLACEHOLDER).assertExists()
        composeRule.runOnIdle { assertNull(harness.draft.quote) }
    }

    /**
     * **面が外れて組み直されても、入力欄のカーソルと選択を保つ**（→ features/margin_quote.md §5.6）。並べ読みの右を資料に替えると入力欄は面ごと外れる。
     * 文中のカーソルでは次の1文字がその位置へ入り、選択した範囲ではその範囲だけが置き換わる。
     */
    // 選択を置く試験の口（performTextInputSelection）は試験的な API。
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun 面が外れて戻っても入力欄のカーソルと選択を保つ() {
        val harness = Harness()
        var shown by mutableStateOf(true)
        setContent(harness, shown = { shown })
        input.performTextInput("abcdef")

        input.performTextInputSelection(TextRange(3))
        composeRule.runOnIdle { shown = false }
        composeRule.runOnIdle { shown = true }
        composeRule.waitForIdle()
        input.performTextInput("X")
        composeRule.runOnIdle { assertEquals("abcXdef", harness.draft.text) }

        input.performTextInputSelection(TextRange(4, 6))
        composeRule.runOnIdle { shown = false }
        composeRule.runOnIdle { shown = true }
        composeRule.waitForIdle()
        input.performTextInput("Y")
        composeRule.runOnIdle { assertEquals("abcXYf", harness.draft.text) }
    }

    /**
     * **描き直しの前に続けて届く編集を落とさない**（→ features/margin_quote.md §5.6）。描いた時点の文字へ戻す編集でも、
     * 直前に反映した文字と違えば書きかけへ渡し、次の描画と置いたメモもその文字になる。
     * 2つの通知を UI スレッドの1回の実行の中で送り、間に描き直しを挟まない。
     */
    // 選択を置く試験の口（performTextInputSelection）は試験的な API。
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun 描き直しの前に足して戻しても最後の編集が書きかけと描画と置いたメモに残る() {
        val harness = Harness()
        setContent(harness)
        input.performTextInput("abc")
        // 描いた値とカーソルを違えておく。値まで同じ通知は、入力欄の部品自身が捨てる。
        input.performTextInputSelection(TextRange(1))
        val setText = checkNotNull(input.fetchSemanticsNode().config[SemanticsActions.SetText].action)

        composeRule.runOnUiThread {
            setText(AnnotatedString("abcd"))
            setText(AnnotatedString("abc"))
        }

        composeRule.runOnIdle { assertEquals("abc", harness.draft.text) }
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("abc")))
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(3)))

        // 描いた文字へ戻らない連続の編集も、最後の値で終わる。
        composeRule.runOnUiThread {
            setText(AnnotatedString("abcd"))
            setText(AnnotatedString("abcde"))
        }
        composeRule.runOnIdle { assertEquals("abcde", harness.draft.text) }
        composeRule.runOnUiThread { setText(AnnotatedString("abc")) }

        composeRule.onNodeWithText("置く").performClick()
        assertEquals(listOf("abc"), harness.sent.map { it.raw })
    }

    /**
     * **描き直しの前に足して戻し、カーソルまで描いた位置へ戻っても、最後の編集を落とさない**（→ features/margin_quote.md §5.6）。
     * キーボードと同じ口（InputConnection）から2つの編集を UI スレッドの1回の実行の中で送り、間に描き直しを挟まない。
     * 最後の値は描いた値と文字・選択・変換中の範囲まで同じになる — `TextFieldValue` 版の入力欄はこの通知を捨てていた。
     */
    @Test
    fun 足して戻しカーソルまで描いた位置へ戻っても_最後の編集が書きかけと描画と置いたメモに残る() {
        val harness = Harness()
        setContent(harness)
        input.performTextInput("abc")
        val drawn = composeRule.runOnIdle { harness.field.state.edited() }
        assertEquals(Triple("abc", TextRange(3), null), drawn)
        val connection = checkNotNull(composeRule.runOnUiThread { checkNotNull(rootView).onCreateInputConnection(EditorInfo()) })

        composeRule.runOnUiThread {
            connection.commitText("d", 1)
            assertEquals("描き直しを待たないと書きかけへ届かない", "abcd", harness.draft.text)
            connection.deleteSurroundingText(1, 0)
            assertEquals(drawn, harness.field.state.edited())
        }

        composeRule.runOnIdle { assertEquals("abc", harness.draft.text) }
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("abc")))
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(3)))
        composeRule.onNodeWithText("置く").performClick()
        assertEquals(listOf("abc"), harness.sent.map { it.raw })
    }

    /** **変換中の文字を確定して1文字消しても、最後の編集を落とさない。** 確定と削除を描き直しを挟まずに送る（→ features/margin_quote.md §5.6）。 */
    @Test
    fun 変換中の文字を確定して1文字消しても_最後の編集が書きかけと置いたメモに残る() {
        val harness = Harness()
        setContent(harness)
        input.performClick()
        val connection = checkNotNull(composeRule.runOnUiThread { checkNotNull(rootView).onCreateInputConnection(EditorInfo()) })
        composeRule.runOnUiThread { connection.setComposingText("abc", 1) }
        composeRule.runOnIdle { assertEquals(Triple("abc", TextRange(3), TextRange(0, 3)), harness.field.state.edited()) }

        composeRule.runOnUiThread {
            connection.commitText("abcd", 1)
            connection.deleteSurroundingText(1, 0)
        }

        composeRule.runOnIdle {
            assertEquals(Triple("abc", TextRange(3), null), harness.field.state.edited())
            assertEquals("abc", harness.draft.text)
        }
        composeRule.onNodeWithText("置く").performClick()
        assertEquals(listOf("abc"), harness.sent.map { it.raw })
    }

    /**
     * **元に戻す・やり直すは、その場で書きかけへ渡る**（→ features/margin_quote.md §5.6）。入力欄に任せると変換の口を通らないので、
     * キー操作を横取りして行う。キーと確かめを UI スレッドの1回の実行の中で行い、描き直しも観測も待たない。
     */
    @Test
    fun 元に戻す操作とやり直す操作はその場で書きかけへ渡る() {
        val harness = Harness()
        setContent(harness)
        input.performTextInput("abcd")
        val connection = checkNotNull(composeRule.runOnUiThread { checkNotNull(rootView).onCreateInputConnection(EditorInfo()) })
        composeRule.runOnUiThread { connection.deleteSurroundingText(1, 0) }
        composeRule.runOnIdle { assertEquals("abc", harness.draft.text) }

        composeRule.runOnUiThread {
            pressCtrl(AndroidKeyEvent.KEYCODE_Z)
            assertEquals("abcd", harness.field.state.text.toString())
            assertEquals("abcd", harness.draft.text)
            pressCtrl(AndroidKeyEvent.KEYCODE_Z, shift = true)
            assertEquals("abc", harness.field.state.text.toString())
            assertEquals("abc", harness.draft.text)
            pressCtrl(AndroidKeyEvent.KEYCODE_Z)
            pressCtrl(AndroidKeyEvent.KEYCODE_Y)
            assertEquals("abc", harness.field.state.text.toString())
            assertEquals("abc", harness.draft.text)
        }
    }

    /** **元に戻してすぐ「置く」を押しても、入力欄に見えている文字で送る。** */
    @Test
    fun 元に戻してすぐ置いても_入力欄の文字で送る() {
        val harness = Harness()
        setContent(harness)
        input.performTextInput("abcd")
        val connection = checkNotNull(composeRule.runOnUiThread { checkNotNull(rootView).onCreateInputConnection(EditorInfo()) })
        composeRule.runOnUiThread { connection.deleteSurroundingText(1, 0) }

        composeRule.runOnUiThread {
            pressCtrl(AndroidKeyEvent.KEYCODE_Z)
            harness.submit()
        }

        composeRule.runOnIdle { assertEquals(listOf(harness.field.state.text.toString()), harness.sent.map { it.raw }) }
        assertEquals(listOf("abcd"), harness.sent.map { it.raw })
    }

    /**
     * **送って結果を待つ間に元に戻すと、前の送信の受理で書きかけを消さない**（→ features/margin_quote.md §5.3「受け取れた」）。
     * 戻した文字は送ったものと違うので、受理は今の書きかけを消費しない。戻してすぐ受理が届く順と、描き直してから届く順の両方。
     */
    @Test
    fun 送って待つ間に元に戻すと_すぐ届いた受理で書きかけが消えない() = undoWhileSaving(idleBeforeSettle = false)

    @Test
    fun 送って待つ間に元に戻すと_描き直した後に届いた受理でも書きかけが消えない() = undoWhileSaving(idleBeforeSettle = true)

    private fun undoWhileSaving(idleBeforeSettle: Boolean) {
        val harness = Harness()
        setContent(harness)
        input.performTextInput("abcd")
        val connection = checkNotNull(composeRule.runOnUiThread { checkNotNull(rootView).onCreateInputConnection(EditorInfo()) })
        composeRule.runOnUiThread { connection.deleteSurroundingText(1, 0) }
        composeRule.onNodeWithText("置く").performClick()
        assertEquals(listOf("abc"), harness.sent.map { it.raw })

        val settle = {
            assertFalse("戻した書きかけを前の送信が消費した", harness.draft.consumedBy(harness.sent.last()))
            harness.settle(harness.sent.last(), SubmissionCheck.Accepted, MemoSaveStatus.Saved)
            assertEquals("abcd", harness.draft.text)
        }
        // すぐ届く順は、キーと受理を UI スレッドの1回の実行の中で行う。分けると、その間に Main の待ち行列が進む。
        if (idleBeforeSettle) {
            composeRule.runOnUiThread { pressCtrl(AndroidKeyEvent.KEYCODE_Z) }
            composeRule.waitForIdle()
            composeRule.runOnUiThread(settle)
        } else {
            composeRule.runOnUiThread {
                pressCtrl(AndroidKeyEvent.KEYCODE_Z)
                settle()
            }
        }

        composeRule.runOnIdle {
            assertEquals("abcd", harness.draft.text)
            assertEquals("abcd", harness.field.state.text.toString())
        }
        composeRule.onNodeWithText(PLACEHOLDER).assertDoesNotExist()
    }

    /** **置いて受け取れた文字を、元に戻す操作で入力欄へ呼び戻さない。** 受け取れて空にしたときに元に戻す履歴も消える。 */
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun 置いて受け取れた文字を元に戻す操作で呼び戻さない() {
        val harness = Harness()
        setContent(harness)
        input.performTextInput("置いた")
        composeRule.onNodeWithText("置く").performClick()
        composeRule.runOnIdle { harness.settle(harness.sent.last(), SubmissionCheck.Accepted, MemoSaveStatus.Saved) }
        composeRule.onNodeWithText(PLACEHOLDER).assertExists()

        input.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }

        composeRule.onNodeWithText(PLACEHOLDER).assertExists()
        composeRule.runOnIdle { assertEquals("", harness.draft.text) }
    }

    /** **受け取れて空になった後に、前と同じ文字を打っても書きかけへ渡る。** 入力欄が持つ値は、描くときに空へ合わせてある。 */
    @Test
    fun 受け取れて空になった後に前と同じ文字を打っても置ける() {
        val harness = Harness()
        setContent(harness)

        input.performTextInput("同じ")
        composeRule.onNodeWithText("置く").performClick()
        composeRule.runOnIdle { harness.settle(harness.sent.last(), SubmissionCheck.Accepted, MemoSaveStatus.Saved) }
        composeRule.onNodeWithText(PLACEHOLDER).assertExists()
        input.performTextInput("同じ")
        composeRule.runOnIdle { assertEquals("同じ", harness.draft.text) }
        composeRule.onNodeWithText("置く").performClick()

        assertEquals(listOf("同じ", "同じ"), harness.sent.map { it.raw })
    }

    /** **引用の結果を待つ間は置けず、そのことを引用の欄に出す**（→ features/margin_quote.md §5.3）。届けば置ける。 */
    @Test
    fun 引用の結果を待つ間は置けず_その旨を出す() {
        val harness = Harness()
        harness.drafts.update(NOTE_A) { it.edited("ひとこと", SectionRef("節B")).quoteRequested(1L) }
        setContent(harness)

        composeRule.onNodeWithText(DRAFT_QUOTE_PENDING).assertExists()
        composeRule.onNodeWithText("置く").assertIsNotEnabled()

        composeRule.runOnIdle { harness.drafts.update(NOTE_A) { it.quoteAbandoned(1L) } }
        composeRule.onNodeWithText(DRAFT_QUOTE_PENDING).assertDoesNotExist()
        composeRule.onNodeWithText("置く").assertIsEnabled()
    }

    /** **本文に同じ一節が2か所以上あれば、付ける場で前後の文字つきの候補から選んでもらう**（→ 判断13）。選ばずに閉じても引用は付いたまま。 */
    @Test
    fun 付ける場で候補から引用元を選べ_選ばずに閉じても引用は残る() {
        val harness = Harness()
        val model = buildNoteSectionModel("## 節A\n\n前の同じ一節\n\n## 節B\n\n同じ一節の後")
        harness.finder = QuoteCandidateFinder(model, Dispatchers.Default)
        harness.drafts.update(NOTE_A) {
            it.quoted(DraftQuote(MemoPassage.Quoted("同じ一節", context = null, truncated = false), choosing = true), SectionRef("節B"))
        }
        setContent(harness)

        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText(quoteChoiceQuestion(2)).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("同じ一節の後…").performClick()
        composeRule.runOnIdle { assertEquals(listOf(3 to model), harness.chosen.map { (block, from) -> block to from }) }

        composeRule.onNodeWithText("選ばずに閉じる").performClick()
        composeRule.onNodeWithText(quoteChoiceQuestion(2)).assertDoesNotExist()
        composeRule.onNodeWithText("同じ一節").assertExists()
    }

    /** **特定できない引用は押すと候補を並べ、選んだ段落へ本文を送る。選んだものは覚えない**（→ §5.5・判断13）。 */
    @Test
    fun メモの行の特定できない引用を押すと候補が並び_選んだ段落へ飛ぶ() {
        val harness = Harness()
        val model = buildNoteSectionModel("## 節B\n\n前の同じ一節\n\n同じ一節の後")
        val memos = listOf(MarginMemo("特定できないメモ", 1L, "節B", MemoPassage.Quoted("同じ一節", context = null, truncated = false)))
        harness.state = READY.copy(memos = memos)
        harness.arranged = arrangeMemos(layoutMemos(memos, model.headingIndex, locateMemos(memos, model.passages, model.headingIndex)), SectionRef("節B"))
        harness.finder = QuoteCandidateFinder(model, Dispatchers.Default)
        setContent(harness)

        composeRule.onNodeWithText("同じ一節").performClick()
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText("…前の同じ一節").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText("…前の同じ一節").performClick()

        composeRule.runOnIdle { assertEquals(1, harness.jumpedBlock) }
        composeRule.onNodeWithText("…前の同じ一節").assertDoesNotExist()
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
                            onCancel = {},
                            onNoticeShown = {}
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
     * **閉じた後、隠す途中で指が触れても、隠し終える**（→ features/margin_pane.md §11）。
     * 隠す動きは、シートに指で触れると取り消される。1度頼むだけだったころは、閉じた扱いのシートが画面に残り、
     * 「閉じる」は閉じた扱いを閉じ直すだけ、戻る操作はランチャーまで抜けた。時計を止めて、隠す途中にハンドルの辺りを掴んで離す。
     */
    @Test
    fun 閉じた後に隠す途中で指が触れても_隠し終える() {
        var visible by mutableStateOf(true)
        lateinit var sheetState: SheetState
        composeRule.setContent {
            sheetState = rememberStandardBottomSheetState(initialValue = SheetValue.Hidden, skipHiddenState = false)
            AppTheme(darkTheme = false) {
                Box(modifier = Modifier.fillMaxWidth().height(800.dp)) {
                    MarginMemoSheetHost(
                        visible = visible,
                        expandRequested = false,
                        onDismiss = { visible = false },
                        sheet = { Text("シートの中身", modifier = Modifier.height(700.dp).testTag(SHEET_TAG)) },
                        sheetState = sheetState
                    ) { Box(modifier = Modifier.fillMaxSize()) }
                }
            }
        }
        composeRule.waitForIdle()
        assertEquals(SheetValue.PartiallyExpanded, sheetState.currentValue)

        composeRule.mainClock.autoAdvance = false
        composeRule.runOnUiThread { visible = false }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.onNodeWithTag(SHEET_TAG).performTouchInput {
            down(topCenter)
            moveBy(Offset(0f, -80f))
        }
        composeRule.mainClock.advanceTimeBy(300)
        composeRule.onNodeWithTag(SHEET_TAG).performTouchInput { up() }
        composeRule.mainClock.advanceTimeBy(3_000)

        assertEquals("閉じた扱いのシートが画面に残った", SheetValue.Hidden, sheetState.currentValue)
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

    // ── 端末AIが使えない理由を「見せた」と伝える時機（→ features/margin_pane.md §5.4）──────────────

    /**
     * **見せたことは、理由の行が組まれたときに一度だけ伝える。** ペインの形（書いていても行を畳まない）で、
     * 状態確認中の要求に理由が届くと、その要求の番号で1回だけ伝わる。見せた後に行を組み直しても2回目を数えない。
     */
    @Test
    fun 理由の行が組まれると_見せたことを一度だけ伝える() {
        val harness = NoticeHarness()
        var rowShown by mutableStateOf(true)
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                NoticeFace(harness, asSheet = false, focusIntent = false, onFocusIntentChange = {}, rowShown = rowShown)
            }
        }
        composeRule.waitForIdle()
        assertEquals("理由が届く前に見せたことにした", emptyList<Long>(), harness.shown)

        composeRule.runOnIdle { harness.arrive() }
        composeRule.onNodeWithText(NOTICE_MESSAGE).assertIsDisplayed()
        assertEquals(listOf(NOTICE_REQUEST), harness.shown)

        rowShown = false
        composeRule.waitForIdle()
        rowShown = true
        composeRule.waitForIdle()
        assertEquals("行を組み直すたびに見せたことにした", listOf(NOTICE_REQUEST), harness.shown)
    }

    /**
     * **シートで書いている間に理由が届いても、行が畳まれているので見せたことにしない。** 書くのをやめて行が出たときに
     * 一度だけ伝える。面が出ているだけで見せたことにすると、見ていない理由が消費される。
     *
     * 順序をこのとおりに作る — 状態確認中に書き始め、畳まれたのを確かめてから理由を届ける。最初から理由を渡すと、
     * 入力欄にフォーカスが入る前の最初の描画で行が組まれ、正しく伝わったものまで数えてしまう。
     */
    @Test
    fun シートで書いている間は_理由を見せたことにせず_行が出てから伝える() {
        val harness = NoticeHarness()
        var focusIntent by mutableStateOf(true)
        composeRule.setContent {
            val focusManager = LocalFocusManager.current
            focusClearer = { focusManager.clearFocus() }
            AppTheme(darkTheme = false) {
                NoticeFace(harness, asSheet = true, focusIntent = focusIntent, onFocusIntentChange = { focusIntent = it })
            }
        }
        awaitCompactWhileTyping()
        assertEquals("理由が届く前に見せたことにした", emptyList<Long>(), harness.shown)

        composeRule.runOnIdle { harness.arrive() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(NOTICE_MESSAGE).assertDoesNotExist()
        assertEquals("畳まれた行の理由を見せたことにした", emptyList<Long>(), harness.shown)

        composeRule.runOnIdle { focusClearer() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(NOTICE_MESSAGE).assertIsDisplayed()
        assertEquals(listOf(NOTICE_REQUEST), harness.shown)

        // もう一度書き始めて畳み、やめて行を組み直しても、見せた理由を2回目には数えない。
        focusIntent = true
        awaitCompactWhileTyping()
        composeRule.runOnIdle { focusClearer() }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(NOTICE_MESSAGE).assertIsDisplayed()
        assertEquals(listOf(NOTICE_REQUEST), harness.shown)
    }

    /** 前提: 入力欄にフォーカスしてキーボードが出ると、要約とメモは1行に畳まれる。出なければこの検査は前提が立たない。 */
    private fun awaitCompactWhileTyping() {
        composeRule.waitUntil(KEYBOARD_TIMEOUT_MILLIS) {
            composeRule.onAllNodes(hasText("要約とメモ", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * 面を出さずに頼んだ要求の状態を、Controller と同じ形で動かす。最初は状態確認中で理由はまだ無い。
     * [arrive] で端末AIが使えない理由が届き、見せたと伝わったら要求の番号で印を下ろす（→ `SectionChatController.acknowledgeNotice`）。
     */
    private class NoticeHarness {
        var summary by mutableStateOf(
            SectionSummary(
                SectionRef("節A"),
                requestId = NOTICE_REQUEST,
                sectionTitle = "節A",
                sectionContext = "本文",
                isSummaryLoading = true,
                noticePending = true
            )
        )
        val shown = mutableListOf<Long>()

        fun arrive() {
            summary = summary.copy(
                isSummaryLoading = false,
                summaryProblem = SectionChatProblem.AiStatus(
                    AiStatusNotice(message = NOTICE_MESSAGE, action = AiNoticeAction.None, canTryAgainLater = false)
                )
            )
        }

        fun acknowledge(requestId: Long) {
            shown += requestId
            if (summary.requestId == requestId) summary = summary.copy(noticePending = false)
        }
    }

    /** [harness] の要約を持つ節の面。[rowShown] が偽なら要約の行を渡さない（行を組み直すために使う）。 */
    @androidx.compose.runtime.Composable
    private fun NoticeFace(
        harness: NoticeHarness,
        asSheet: Boolean,
        focusIntent: Boolean,
        onFocusIntentChange: (Boolean) -> Unit,
        rowShown: Boolean = true
    ) {
        MarginMemoSheetContent(
            state = READY,
            draft = MarginMemoDraft(),
            section = SectionRef("節A"),
            hasHeadings = true,
            onJumpToSection = {},
            arranged = null,
            onEdit = {},
            onSubmit = {},
            onDelete = {},
            asSheet = asSheet,
            focusIntent = focusIntent,
            onFocusIntentChange = onFocusIntentChange,
            summaryRow = if (rowShown) {
                SummaryRowInputs(
                    summary = harness.summary,
                    onRequest = {},
                    onRetry = {},
                    onCancel = {},
                    onNoticeShown = harness::acknowledge
                )
            } else {
                null
            }
        )
    }

    private companion object {
        const val NOTICE_REQUEST = 7L
        const val NOTICE_MESSAGE = "この端末では、この部分の要約を使えません"
        const val KEYBOARD_TIMEOUT_MILLIS = 5_000L
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

    /** Ctrl つきのキーを画面の根から押して離す。UI スレッドで呼ぶ。 */
    private fun pressCtrl(keyCode: Int, shift: Boolean = false) {
        val meta = AndroidKeyEvent.META_CTRL_ON or AndroidKeyEvent.META_CTRL_LEFT_ON or
            if (shift) AndroidKeyEvent.META_SHIFT_ON or AndroidKeyEvent.META_SHIFT_LEFT_ON else 0
        val view = checkNotNull(rootView)
        for (action in listOf(AndroidKeyEvent.ACTION_DOWN, AndroidKeyEvent.ACTION_UP)) {
            view.dispatchKeyEvent(AndroidKeyEvent(0L, 0L, action, keyCode, 0, meta))
        }
    }

    /** 入力欄が持つ文字・選択・変換中の範囲。 */
    private fun TextFieldState.edited() = Triple(text.toString(), selection, composition)

}
