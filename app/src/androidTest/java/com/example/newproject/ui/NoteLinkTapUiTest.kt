package com.example.newproject.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.newproject.domain.NoteLink
import com.example.newproject.ui.markdown.MarkdownNoteContent
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
}
