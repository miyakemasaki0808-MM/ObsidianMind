package com.example.newproject.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.RelatedNote
import com.example.newproject.model.state.MarginMemoDraft
import com.example.newproject.model.state.MarginMemoState
import com.example.newproject.model.state.RebuttalSentence
import com.example.newproject.model.state.RelatedNotesState
import com.example.newproject.ui.screen.MarginMemoSheetContent
import com.example.newproject.ui.screen.PaneRelatedInputs
import com.example.newproject.ui.screen.RelatedTab
import com.example.newproject.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **反証の一文が、関連タブと余白ペインに実際に描かれることを固定する**（→ features/rebuttal_sentence.md §5.7）。
 *
 * 状態の配線（選ぶ・消す）はJVM側が押さえているが、**枠がその状態を描くことは純関数側からは観測できない。**
 * 出どころの行を押した先も、置き場で違う（関連タブはそのノートへ移り、ペインは右で開く）ので両方を見る。
 */
@RunWith(AndroidJUnit4::class)
class RebuttalPanelTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 関連タブでは一文と出どころが出て_押すと出どころへ移る() {
        val opened = mutableListOf<RelatedNote>()
        showRelatedTab(rebuttal = REBUTTAL, onOpenNote = { opened += it })

        composeRule.onNodeWithText(HEADING).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(CLAIM).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(SOURCE.title).performScrollTo().performClick()

        assertEquals(listOf(SOURCE), opened)
    }

    @Test
    fun 関連タブでは選べていなければ枠を出さない() {
        showRelatedTab(rebuttal = null)

        composeRule.onNodeWithText(OTHER.title).assertIsDisplayed()
        composeRule.onNodeWithText(HEADING).assertDoesNotExist()
    }

    /** ペインでは関連の一覧を畳んだままでも出す。畳んだ中に入れると机の画面では目に入らない。 */
    @Test
    fun ペインでは関連を畳んだままでも一文が出て_押すと右で開く() {
        val openedOnSide = mutableListOf<RelatedNote>()
        showPane(rebuttal = REBUTTAL, onOpen = { openedOnSide += it })

        composeRule.onNodeWithText("このノートの関連", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(OTHER.title).assertDoesNotExist()
        composeRule.onNodeWithText(CLAIM).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(SOURCE.title).performScrollTo().performClick()

        assertEquals(listOf(SOURCE), openedOnSide)
    }

    @Test
    fun ペインでは選べていなければ一文を出さない() {
        showPane(rebuttal = null)

        composeRule.onNodeWithText("このノートの関連", substring = true).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(HEADING).assertDoesNotExist()
    }

    private fun showRelatedTab(rebuttal: RebuttalSentence?, onOpenNote: (RelatedNote) -> Unit = {}) {
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                RelatedTab(
                    uiState = NoteUiState(
                        relatedNotesState = RelatedNotesState.Success(relatedNotes = listOf(OTHER), aiNotes = emptyList()),
                        rebuttalSentence = rebuttal
                    ),
                    onOpenNote = onOpenNote
                )
            }
        }
    }

    private fun showPane(rebuttal: RebuttalSentence?, onOpen: (RelatedNote) -> Unit = {}) {
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                MarginMemoSheetContent(
                    state = MarginMemoState.Ready(memos = emptyList()),
                    draft = MarginMemoDraft(),
                    section = null,
                    hasHeadings = false,
                    onJumpToSection = {},
                    arranged = null,
                    onEdit = {},
                    onSubmit = {},
                    onDelete = {},
                    related = PaneRelatedInputs(
                        candidates = listOf(OTHER),
                        expanded = false,
                        onToggle = {},
                        onOpen = onOpen,
                        rebuttal = rebuttal
                    )
                )
            }
        }
    }

    private companion object {
        const val HEADING = "反証の一文"
        const val CLAIM = "睡眠は朝の光では整わない。"
        val SOURCE = RelatedNote(title = "出どころのノート", ref = DocumentRef("content://vault/source.md"), isWikilinked = false)
        val OTHER = RelatedNote(title = "関連のノート", ref = DocumentRef("content://vault/other.md"), isWikilinked = false)
        val REBUTTAL = RebuttalSentence(text = CLAIM, source = SOURCE)
    }
}
