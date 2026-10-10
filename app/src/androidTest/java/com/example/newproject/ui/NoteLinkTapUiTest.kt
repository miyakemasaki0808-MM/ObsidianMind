package com.example.newproject.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.newproject.domain.NoteLink
import com.example.newproject.domain.markdown.buildNoteSectionModel
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.RelatedNote
import com.example.newproject.model.state.SideReadingState
import com.example.newproject.ui.markdown.MarkdownNoteContent
import com.example.newproject.ui.screen.SideLinkStart
import com.example.newproject.ui.screen.SideReadingPane
import com.example.newproject.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 本文のリンクを押す（→ features/note_links.md）。**純関数からは観測できない指の扱い**だけをここへ置く。
 *
 * どのリンクが押せるか・押したときにどの面で開くかは JVM 側（`InlineMarkdownTest`・`NoteLinksTest`・`NoteLinkActionsTest`）が持つ。
 * ここで確かめるのは、本文の容器の中でリンクが軽い押下にだけ応え、長押し（文字の選択）とスクロールでは開かないこと。
 */
@RunWith(AndroidJUnit4::class)
class NoteLinkTapUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun showBody(content: String, opened: MutableList<NoteLink>) {
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                MarkdownNoteContent(content = content, modifier = Modifier.fillMaxSize(), onLink = { opened += it })
            }
        }
    }

    /** リンクの行の後ろに、スクロールできるだけの本文を足す。 */
    private val longBody = buildString {
        append("[[ノートA]]\n\n")
        repeat(80) { append("本文の段落 $it。\n\n") }
    }

    @Test
    fun 軽く押すとリンクを開く() {
        val opened = mutableListOf<NoteLink>()
        showBody(longBody, opened)

        composeRule.onNodeWithText("ノートA").performClick()

        composeRule.runOnIdle { assertEquals(listOf(NoteLink.ByName("ノートA", null)), opened) }
    }

    /** 長押しは文字の選択に使われる。リンクは長押しを受け取らないので、容器の見張りが止める。 */
    @Test
    fun 長押しではリンクを開かない() {
        val opened = mutableListOf<NoteLink>()
        showBody(longBody, opened)

        composeRule.onNodeWithText("ノートA").performTouchInput { longClick() }

        composeRule.runOnIdle { assertEquals(emptyList<NoteLink>(), opened) }
    }

    @Test
    fun リンクの上から縦にスクロールしても開かない() {
        val opened = mutableListOf<NoteLink>()
        showBody(longBody, opened)

        composeRule.onNodeWithText("ノートA").performTouchInput { swipeUp() }

        composeRule.runOnIdle { assertEquals(emptyList<NoteLink>(), opened) }
    }

    /** 見張りは押下ごとに下ろすので、長押しの後の軽い押下は開く。 */
    @Test
    fun 長押しの後でも軽く押せばリンクを開く() {
        val opened = mutableListOf<NoteLink>()
        showBody(longBody, opened)

        composeRule.onNodeWithText("ノートA").performTouchInput { longClick() }
        composeRule.onNodeWithText("ノートA").performClick()

        composeRule.runOnIdle { assertEquals(listOf(NoteLink.ByName("ノートA", null)), opened) }
    }

    // ── 補助の面で開いたリンクの始まり ───────────────────────────────────────

    /** 補助の面のノートの本文の段落。番号はブロックの位置と同じ。10ごとに見出しを置く。 */
    private val sideBody = (0 until 40).joinToString("\n\n") { if (it % 10 == 0) "## 見出し$it" else "右の段落$it の本文です。" }
    private val sideNote = RelatedNote(title = "右のノート", ref = DocumentRef("content://vault/side.md"), isWikilinked = true)
    private var sideLink by mutableStateOf<SideLinkStart?>(null)

    @androidx.compose.runtime.Composable
    private fun SidePane(missing: MutableList<String>) {
        Box(modifier = Modifier.requiredSize(width = 400.dp, height = 600.dp)) {
            SideReadingPane(
                state = SideReadingState.Ready(sideNote),
                blocks = buildNoteSectionModel(sideBody).blocks,
                imageLoader = null,
                onBack = {},
                onMove = { _, _ -> },
                onRetry = {},
                linkStart = sideLink,
                onHeadingMissing = { missing += it }
            )
        }
    }

    private fun scrollSideTo(index: Int) {
        composeRule.onNode(hasScrollToIndexAction() and hasAnyDescendant(hasText("右の段落", substring = true)))
            .performScrollToIndex(index)
        composeRule.waitForIdle()
    }

    private fun linkAgain(heading: String?) {
        composeRule.runOnIdle { sideLink = SideLinkStart(sideNote.ref.value, heading, (sideLink?.request ?: 0L) + 1) }
        composeRule.waitForIdle()
    }

    /** **同じノートを開き直すと位置がノートごとに残っている**ので、見出しの無いリンクでも先頭へ送る。 */
    @Test
    fun 補助の面で同じノートの見出しの無いリンクを押し直すと先頭へ戻る() {
        val missing = mutableListOf<String>()
        composeRule.setContent { AppTheme(darkTheme = false) { SidePane(missing) } }
        linkAgain("見出し20")
        composeRule.onNodeWithText("見出し20").assertIsDisplayed()
        scrollSideTo(25)

        linkAgain(null)

        composeRule.onNodeWithText("右の段落1 の本文です。").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(emptyList<String>(), missing) }
    }

    @Test
    fun 補助の面で同じノートの見つからない見出しへのリンクを押し直すと先頭へ戻って知らせる() {
        val missing = mutableListOf<String>()
        composeRule.setContent { AppTheme(darkTheme = false) { SidePane(missing) } }
        linkAgain("見出し20")
        scrollSideTo(25)

        linkAgain("無い見出し")

        composeRule.onNodeWithText("右の段落1 の本文です。").assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(listOf("無い見出し"), missing) }
    }

    /** 送った要求のまま画面を作り直しても、読み進めた位置を見出しへ戻さない。 */
    @Test
    fun 補助の面で送った後に画面を作り直しても読み進めた位置を保つ() {
        val restoration = StateRestorationTester(composeRule)
        val missing = mutableListOf<String>()
        restoration.setContent { AppTheme(darkTheme = false) { SidePane(missing) } }
        linkAgain("見出し10")
        scrollSideTo(30)

        restoration.emulateSavedInstanceStateRestore()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("見出し30").assertIsDisplayed()
    }
}
