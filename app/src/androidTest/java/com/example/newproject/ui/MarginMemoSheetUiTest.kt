package com.example.newproject.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.newproject.domain.SubmissionCheck
import com.example.newproject.domain.edited
import com.example.newproject.domain.settled
import com.example.newproject.domain.submissionOf
import com.example.newproject.domain.submitted
import com.example.newproject.model.state.MarginMemoState
import com.example.newproject.model.state.MemoSaveStatus
import com.example.newproject.model.state.MemoSubmission
import com.example.newproject.ui.screen.ComposeMarginMemoDrafts
import com.example.newproject.ui.screen.MarginMemoSheetContent
import com.example.newproject.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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
        val sent = mutableListOf<MemoSubmission>()
        private var clock = 0L

        val draft get() = drafts.draft(note)

        fun edit(text: String) = drafts.update(note) { it.edited(text, null) }

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

    private fun setContent(harness: Harness, shown: () -> Boolean = { true }, inPane: () -> Boolean = { true }) {
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                if (shown()) {
                    val content: @androidx.compose.runtime.Composable () -> Unit = {
                        MarginMemoSheetContent(
                            state = harness.state,
                            draft = harness.draft,
                            onEdit = harness::edit,
                            onSubmit = harness::submit,
                            onDelete = {}
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

    private companion object {
        const val NOTE_A = "content://vault-a/a.md"
        const val NOTE_B = "content://vault-a/b.md"
        val READY = MarginMemoState.Ready(memos = emptyList())
    }
}
