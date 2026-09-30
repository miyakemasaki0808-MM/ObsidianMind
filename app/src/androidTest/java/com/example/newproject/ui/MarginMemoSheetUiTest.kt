package com.example.newproject.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.newproject.model.MarginMemo
import com.example.newproject.model.state.MarginMemoState
import com.example.newproject.model.state.MemoSaveStatus
import com.example.newproject.ui.screen.MarginMemoSheetContent
import com.example.newproject.ui.screen.rememberMarginMemoInput
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.example.newproject.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **連続して置けることがこの機能の要点**なので、下書きの扱いをここで固定する。
 *
 * ## なぜUI側でしか捕まらないか
 *
 * かつては「置けたら入力欄を空にする」を**状態から導いていた**。
 * `status` は保存後も `Saved` のまま残るので、**再コンポーズのたびに条件が成立し、
 * 2件目に書いた文字が入力するそばから消えた。** Controller のテストは
 * 状態しか見ないので全部緑のまま通る — 下書きは画面側にしか無い。
 *
 * ## ここで見ないもの
 *
 * 保存そのもの（預かり・退避・合流）は `ReadingTraceControllerTest` が持つ。
 * ここは**入力欄の中身がいつ消え、いつ戻るか**だけを見る。
 *
 * 書きかけは本番と同じ [rememberMarginMemoInput] で持つ。照合の規則そのものは
 * `MarginMemoInputTest`（JVM）が持ち、ここは**表示部品を外す・作り直す・画面を復元する**経路を通す。
 */
@RunWith(AndroidJUnit4::class)
class MarginMemoSheetUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * 入力欄の指し方。**プレースホルダーで探さない。**
     *
     * プレースホルダーは**入力が空のときしか組み立てられない**ので、
     * 1文字でも入れた時点で存在しなくなる。押しても入力欄を空にしない設計へ変えた結果、
     * 保存中の入力欄が非空のままになり、**検証したい操作へ到達する前に落ちるようになった。**
     * この画面で文字を受け取れる要素は1つだけなので、その性質で指す。
     */
    private val input get() = composeRule.onNode(hasSetTextAction())

    /** **空のときだけ出る。** だから「入力欄が空へ戻った」ことの証拠に使える。 */
    private val PLACEHOLDER = "いま思ったこと"

    @Test
    fun 置いた後も同じシートで続けて書ける() {
        val saved = mutableListOf<String>()
        composeRule.setContent {
            var state by remember(saved) {
                mutableStateOf<MarginMemoState>(MarginMemoState.Ready(memos = emptyList()))
            }
            AppTheme(darkTheme = false) {
                MarginMemoSheetContent(
                    state = state,
                    draft = rememberMarginMemoInput(NOTE_A, state),
                    onSave = { text ->
                        saved += text
                        // 実際の Controller と同じく、置けたら一覧へ足して status を進める。
                        val ready = state as MarginMemoState.Ready
                        state = ready.copy(
                            memos = listOf(MarginMemo(text, saved.size.toLong())) + ready.memos,
                            status = MemoSaveStatus.Saved,
                            acceptedCount = ready.acceptedCount + 1
                        )
                    },
                    onDelete = {}
                )
            }
        }

        input.performTextInput("1件目")
        composeRule.onNodeWithText("置く").performClick()
        // **ここが本題。** 受け取れたので入力欄が空へ戻る
        // （プレースホルダーは空のときだけ出るので、戻ったことの証拠になる）。
        composeRule.onNodeWithText(PLACEHOLDER).assertExists()
        input.performTextInput("2件目")
        composeRule.onNodeWithText("置く").performClick()

        assertEquals(listOf("1件目", "2件目"), saved)
    }

    /**
     * 置けなかった入力は**原文のまま手元に残る。**
     *
     * 受理の件数が増えたときだけ消すので、失敗は何もしなくても残る。
     * 押した瞬間に空にしていたころは、上限で切り詰めた後の文字列しか戻せなかった。
     */
    @Test
    fun 満杯で置けなかった入力は原文のまま残る() {
        val long = "あ".repeat(400)
        composeRule.setContent {
            var state by remember {
                mutableStateOf<MarginMemoState>(MarginMemoState.Ready(memos = emptyList()))
            }
            AppTheme(darkTheme = false) {
                MarginMemoSheetContent(
                    state = state,
                    draft = rememberMarginMemoInput(NOTE_A, state),
                    onSave = {
                        // 受理していないので acceptedCount は増やさない。
                        state = (state as MarginMemoState.Ready).copy(status = MemoSaveStatus.Full)
                    },
                    onDelete = {}
                )
            }
        }

        input.performTextInput(long)
        composeRule.onNodeWithText("置く").performClick()

        composeRule.onNodeWithText(long).assertExists()
    }

    /** 読み込み中・失敗中は押させない。押せると、入力だけが宙に浮く。 */
    @Test
    fun 読み込み中は置くを押せない() {
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                MarginMemoSheetContent(
                    state = MarginMemoState.Loading,
                    draft = rememberMarginMemoInput(NOTE_A, MarginMemoState.Loading),
                    onSave = { error("読み込み中に保存を要求した") },
                    onDelete = {}
                )
            }
        }

        input.performTextInput("読み込み中に書いた")
        composeRule.onNodeWithText("置く").assertIsNotEnabled()
        composeRule.onNodeWithText("読み込み中に書いた").assertExists()
    }

    /**
     * **保存を待っているあいだに書いた下書きを、前の要求の結果で上書きしない。**
     *
     * 戻す条件を「入力欄が空のときだけ」にしてあるのがその担保。
     */
    @Test
    fun 保存待ちに書いた下書きは前の要求の完了で消えない() {
        lateinit var finish: (MemoSaveStatus) -> Unit
        composeRule.setContent {
            var state by remember {
                mutableStateOf<MarginMemoState>(MarginMemoState.Ready(memos = emptyList()))
            }
            finish = { status ->
                val ready = state as MarginMemoState.Ready
                state = ready.copy(
                    status = status,
                    acceptedCount = if (status == MemoSaveStatus.Saved) {
                        ready.acceptedCount + 1
                    } else {
                        ready.acceptedCount
                    }
                )
            }
            AppTheme(darkTheme = false) {
                MarginMemoSheetContent(
                    state = state,
                    draft = rememberMarginMemoInput(NOTE_A, state),
                    onSave = { _ ->
                        state = (state as MarginMemoState.Ready).copy(status = MemoSaveStatus.Saving)
                    },
                    onDelete = {}
                )
            }
        }

        input.performTextInput("1件目")
        composeRule.onNodeWithText("置く").performClick()
        // 結果が返る前に次を書き始める。
        input.performTextReplacement("待っているあいだに書いた")

        // 1件目が受理されても、書き直した下書きは消えない。
        composeRule.runOnIdle { finish(MemoSaveStatus.Saved) }
        composeRule.onNodeWithText("待っているあいだに書いた").assertExists()

        // 1件目が失敗した場合も同じ（下書きは触られない）。
        composeRule.runOnIdle { finish(MemoSaveStatus.Full) }
        composeRule.onNodeWithText("待っているあいだに書いた").assertExists()
    }

    /**
     * **保存中に表示部品を外し、外している間に受理されても、戻したときに入力は空。**
     *
     * 送った記録を表示部品の中に持っていたころは、作り直した部品が何を送ったかを知らず、
     * 受理済みの文字が残って押し直せた（ペインを ✎ でしまったときの形）。
     */
    @Test
    fun 保存中に表示を外しても_受理されていれば戻したとき入力は空() {
        var shown by mutableStateOf(true)
        var state by mutableStateOf<MarginMemoState>(MarginMemoState.Ready(memos = emptyList()))
        val saved = mutableListOf<String>()
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                val draft = rememberMarginMemoInput(NOTE_A, state)
                if (shown) {
                    MarginMemoSheetContent(
                        state = state,
                        draft = draft,
                        onSave = { text ->
                            saved += text
                            state = (state as MarginMemoState.Ready).copy(status = MemoSaveStatus.Saving)
                        },
                        onDelete = {}
                    )
                }
            }
        }

        input.performTextInput("送信するメモ")
        composeRule.onNodeWithText("置く").performClick()
        composeRule.runOnIdle { shown = false }
        composeRule.runOnIdle {
            val ready = state as MarginMemoState.Ready
            state = ready.copy(
                memos = listOf(MarginMemo("送信するメモ", 1L)),
                status = MemoSaveStatus.Saved,
                acceptedCount = ready.acceptedCount + 1
            )
        }
        composeRule.runOnIdle { shown = true }

        composeRule.onNodeWithText(PLACEHOLDER).assertExists()
        assertEquals(listOf("送信するメモ"), saved)
    }

    /** **ペインとシートの行き来**（別の場所へ組み直す）をまたいで受理されても同じ。 */
    @Test
    fun 保存中に別の場所へ組み直しても_受理されれば入力は空() {
        var inPane by mutableStateOf(true)
        var state by mutableStateOf<MarginMemoState>(MarginMemoState.Ready(memos = emptyList()))
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                val draft = rememberMarginMemoInput(NOTE_A, state)
                val content: @androidx.compose.runtime.Composable () -> Unit = {
                    MarginMemoSheetContent(
                        state = state,
                        draft = draft,
                        onSave = { state = (state as MarginMemoState.Ready).copy(status = MemoSaveStatus.Saving) },
                        onDelete = {}
                    )
                }
                if (inPane) Box { content() } else Column { content() }
            }
        }

        input.performTextInput("送信するメモ")
        composeRule.onNodeWithText("置く").performClick()
        composeRule.runOnIdle { inPane = false }
        composeRule.runOnIdle {
            val ready = state as MarginMemoState.Ready
            state = ready.copy(status = MemoSaveStatus.Saved, acceptedCount = ready.acceptedCount + 1)
        }

        composeRule.onNodeWithText(PLACEHOLDER).assertExists()
    }

    /**
     * **画面を戻したとき、ノートが替わっていたら前のノートの書きかけを出さない。**
     *
     * 見えていない間（ほかのタブにいる間）にVaultやノートを替えると、戻したときに初めて今のノートを知る。
     * 比較の鍵を外から渡すだけでは、戻した値を今のノートの初期値として受け入れてしまった。
     */
    @Test
    fun 画面を戻したときノートが替わっていれば前の書きかけを出さない() {
        val restoration = StateRestorationTester(composeRule)
        // **状態にしない。** 見えている間に替えると、見えている間の切替として照合されてしまう。
        var note = NOTE_A
        restoration.setContent {
            AppTheme(darkTheme = false) {
                MarginMemoSheetContent(
                    state = READY,
                    draft = rememberMarginMemoInput(note, READY),
                    onSave = {},
                    onDelete = {}
                )
            }
        }

        input.performTextInput("Aにだけ書きかけたメモ")
        note = NOTE_B
        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText(PLACEHOLDER).assertExists()
        composeRule.onNodeWithText("Aにだけ書きかけたメモ").assertDoesNotExist()
    }

    /** 対照。**同じノートへ戻すなら**（Fold の開閉・回転）書きかけは残る。 */
    @Test
    fun 同じノートへ画面を戻したときは書きかけが残る() {
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            AppTheme(darkTheme = false) {
                MarginMemoSheetContent(
                    state = READY,
                    draft = rememberMarginMemoInput(NOTE_A, READY),
                    onSave = {},
                    onDelete = {}
                )
            }
        }

        input.performTextInput("Aに書きかけ")
        restoration.emulateSavedInstanceStateRestore()

        composeRule.onNodeWithText("Aに書きかけ").assertExists()
    }

    private companion object {
        const val NOTE_A = "content://vault-a/a.md"
        const val NOTE_B = "content://vault-b/b.md"
        val READY: MarginMemoState = MarginMemoState.Ready(memos = emptyList())
    }
}
