package com.example.newproject.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteFolder
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.RelatedNote
import com.example.newproject.model.state.TitleMatch
import com.example.newproject.model.state.TitleSearchState
import com.example.newproject.ui.screen.SearchTab
import com.example.newproject.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **Explore の画面が、モードと結果を実際に描くことを固定する**（→ features/ai_picker.md）。
 * 照合と状態の配線はJVM側が押さえているが、チップやボタンの有無と、確定キーで AI を走らせないことは画面でしか見えない。
 */
@RunWith(AndroidJUnit4::class)
class SearchTabTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val titleQueries = mutableListOf<String>()
    private val aiQueries = mutableListOf<String>()
    private val opened = mutableListOf<RelatedNote>()

    @Test
    fun 開いたときはタイトルのモードで_範囲もランダムも出ない() {
        show()

        composeRule.onNodeWithText("タイトルの一部").assertIsDisplayed()
        composeRule.onNodeWithText("すべて").assertDoesNotExist()
        composeRule.onNodeWithText("🔎 ことばでさがす").assertDoesNotExist()
        composeRule.onNodeWithText("🎰 ランダムに引く").assertDoesNotExist()
    }

    @Test
    fun タイトルは打つたびに照らし_確定キーではAIを走らせない() {
        show()

        input().performTextInput("習慣")
        input().performImeAction()

        assertEquals(listOf("習慣"), titleQueries)
        assertEquals(emptyList<String>(), aiQueries)
    }

    @Test
    fun タイトルの結果は全部並び_押すと開く() {
        val notes = (1..40).map { note("習慣$it.md") }
        show(NoteUiState(titleSearch = TitleSearchState.Matches(notes.map { TitleMatch(it) })))
        input().performTextInput("習慣")

        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("習慣40.md"))
        composeRule.onNodeWithText("習慣40.md").performClick()

        assertEquals(listOf(notes.last()), opened)
    }

    @Test
    fun 同じ名前のノートには場所を添える() {
        val matches = listOf(TitleMatch(note("メモ.md"), folderPath = "ideas/2026"))
        show(NoteUiState(titleSearch = TitleSearchState.Matches(matches)))
        input().performTextInput("メモ")

        composeRule.onNodeWithText("ideas/2026").assertIsDisplayed()
    }

    @Test
    fun タイトルが0件なら同じことばでAIへ移って探せる() {
        show(NoteUiState(titleSearch = TitleSearchState.Matches(emptyList())))
        input().performTextInput("習慣")

        composeRule.onNodeWithText("🔎 同じことばを AI でさがす").performClick()

        assertEquals(listOf("習慣"), aiQueries)
        composeRule.onNodeWithText("すべて").assertIsDisplayed()
    }

    @Test
    fun AIは範囲が最初からすべてで_打った言葉を保ち_ボタンを押したときだけ走る() {
        val folder = NoteFolder("ideas", "doc-ideas")
        show(NoteUiState(folders = listOf(folder)))
        composeRule.onNodeWithText("🔎 ことばでさがす").assertDoesNotExist()
        input().performTextInput("習慣")

        composeRule.onNodeWithText("AI").performClick()

        composeRule.onNodeWithText("すべて").assertIsDisplayed()
        composeRule.onNodeWithText("ideas").assertIsDisplayed()
        composeRule.onNodeWithText("習慣").assertIsDisplayed()
        composeRule.onNodeWithText("タイトルが近い40本から選びます。意味で探したいときはフォルダを選んでください。").assertIsDisplayed()
        input().performImeAction()
        assertEquals(emptyList<String>(), aiQueries)

        composeRule.onNodeWithText("🔎 ことばでさがす").assertIsEnabled().performClick()
        assertEquals(listOf("習慣"), aiQueries)
    }

    @Test
    fun AIは何も打っていなければ押せない() {
        show()

        composeRule.onNodeWithText("AI").performClick()

        composeRule.onNodeWithText("🔎 ことばでさがす").assertIsNotEnabled()
    }

    @Test
    fun フォルダを選ぶと注記を出さない() {
        val folder = NoteFolder("ideas", "doc-ideas")
        show(NoteUiState(folders = listOf(folder), selectedFolder = folder))

        composeRule.onNodeWithText("AI").performClick()

        composeRule.onNodeWithText("タイトルが近い40本から選びます。意味で探したいときはフォルダを選んでください。").assertDoesNotExist()
    }

    private fun input() = composeRule.onNode(hasSetTextAction())

    private fun show(initial: NoteUiState = NoteUiState()) {
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                SearchTab(
                    uiState = initial,
                    onSelectFolder = {},
                    onSearchTitle = { titleQueries += it },
                    onSearch = { aiQueries += it },
                    onOpenNote = { opened += it }
                )
            }
        }
    }

    private fun note(title: String) = RelatedNote(title, DocumentRef("content://fake/$title"), isWikilinked = false)
}
