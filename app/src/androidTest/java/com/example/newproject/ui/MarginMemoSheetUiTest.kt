package com.example.newproject.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
 */
@RunWith(AndroidJUnit4::class)
class MarginMemoSheetUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val placeholder = "いま思ったこと"

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
                    onSave = { text ->
                        saved += text
                        // 実際の Controller と同じく、置けたら一覧へ足して status を進める。
                        val ready = state as MarginMemoState.Ready
                        state = ready.copy(
                            memos = listOf(MarginMemo(text, saved.size.toLong())) + ready.memos,
                            status = MemoSaveStatus.Saved
                        )
                    },
                    onDelete = {}
                )
            }
        }

        composeRule.onNodeWithText(placeholder).performTextInput("1件目")
        composeRule.onNodeWithText("置く").performClick()
        // **ここが本題。** 入力欄が空に戻っていなければ2件目を打てない。
        composeRule.onNodeWithText(placeholder).performTextInput("2件目")
        composeRule.onNodeWithText("置く").performClick()

        assertEquals(listOf("1件目", "2件目"), saved)
    }

    /** 置けなかった入力は**書き直せるよう戻す**（押した瞬間に空にしているため）。 */
    @Test
    fun 満杯で置けなかった入力は入力欄へ戻る() {
        composeRule.setContent {
            var state by remember {
                mutableStateOf<MarginMemoState>(MarginMemoState.Ready(memos = emptyList()))
            }
            AppTheme(darkTheme = false) {
                MarginMemoSheetContent(
                    state = state,
                    onSave = { text ->
                        state = (state as MarginMemoState.Ready).copy(
                            status = MemoSaveStatus.Full,
                            rejectedText = text
                        )
                    },
                    onDelete = {}
                )
            }
        }

        composeRule.onNodeWithText(placeholder).performTextInput("あふれた断片")
        composeRule.onNodeWithText("置く").performClick()

        composeRule.onNodeWithText("あふれた断片").assertExists()
    }

    /**
     * **保存を待っているあいだに書いた下書きを、前の要求の結果で上書きしない。**
     *
     * 戻す条件を「入力欄が空のときだけ」にしてあるのがその担保。
     */
    @Test
    fun 保存待ちに書いた下書きは置けなかった入力で上書きされない() {
        composeRule.setContent {
            var state by remember {
                mutableStateOf<MarginMemoState>(MarginMemoState.Ready(memos = emptyList()))
            }
            AppTheme(darkTheme = false) {
                MarginMemoSheetContent(
                    state = state,
                    onSave = { _ ->
                        // 結果が返る前に次を書き始めた、という順序を作る。
                        state = (state as MarginMemoState.Ready).copy(status = MemoSaveStatus.Saving)
                    },
                    onDelete = {}
                )
            }
        }

        composeRule.onNodeWithText(placeholder).performTextInput("1件目")
        composeRule.onNodeWithText("置く").performClick()
        composeRule.onNodeWithText(placeholder).performTextReplacement("待っているあいだに書いた")

        composeRule.onNodeWithText("待っているあいだに書いた").assertExists()
    }
}
