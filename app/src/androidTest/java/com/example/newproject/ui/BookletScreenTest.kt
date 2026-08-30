package com.example.newproject.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.newproject.model.BookletCover
import com.example.newproject.model.BookletEntry
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.state.BookletState
import com.example.newproject.ui.screen.BookletScreen
import com.example.newproject.ui.theme.AppTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **扉が実際に描かれ、押した先が正しいことを固定する。**
 *
 * 選定そのものは `BookletCoverLineTest`、束の作り方は `BookletControllerTest` が
 * JVMで押さえている。**そこから「画面がその値を描く」ことは観測できない**ので、
 * 描画と配線だけをここで見る（→ `ReadingTraceCardPanelTest` と同じ理由）。
 */
@RunWith(AndroidJUnit4::class)
class BookletScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 扉の代表文とタイトルが出る() {
        show(BookletState.Open(listOf(entry("ノートA", BookletCover.Ready("最初の文である。")))))

        composeRule.onNodeWithText("最初の文である。").assertIsDisplayed()
        composeRule.onNodeWithText("ノートA").assertIsDisplayed()
    }

    @Test
    fun これを読むでその1枚が渡る() {
        val opened = mutableListOf<BookletEntry>()
        val target = entry("ノートA", BookletCover.Ready("最初の文である。"))
        show(BookletState.Open(listOf(target)), onRead = { opened += it })

        composeRule.onNodeWithText("これを読む").performClick()

        assertEquals(listOf(target), opened)
    }

    /** 束を作った後に消えたノート。**そのページだけ**開けなくする。 */
    @Test
    fun 読めなかったページは開けない() {
        show(BookletState.Open(listOf(entry("消えたノート", BookletCover.Failed))))

        composeRule.onNodeWithText("このノートは開けませんでした。").assertIsDisplayed()
        composeRule.onNodeWithText("これを読む").assertIsNotEnabled()
    }

    @Test
    fun 読める扉なら開ける() {
        show(BookletState.Open(listOf(entry("ノートA", BookletCover.Ready("本文である。")))))

        composeRule.onNodeWithText("これを読む").assertIsEnabled()
    }

    /** 位置がスワイプでしか分からない画面なので、読み上げにも同じことを言わせる。 */
    @Test
    fun ページ位置は読み上げにも出る() {
        show(
            BookletState.Open(
                listOf(
                    entry("ノートA", BookletCover.Ready("一枚目。")),
                    entry("ノートB", BookletCover.Ready("二枚目。"))
                )
            )
        )

        composeRule.onNodeWithText("1 / 2").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("1/2ページ").assertIsDisplayed()
    }

    @Test
    fun ノートが無ければ引けないと伝える() {
        show(BookletState.Open(emptyList()))

        composeRule.onNodeWithText("引けるノートがありません。").assertIsDisplayed()
    }

    @Test
    fun 束を作れなければ理由を出す() {
        show(BookletState.Failed("走査に失敗しました。"))

        composeRule.onNodeWithText("走査に失敗しました。").assertIsDisplayed()
    }

    @Test
    fun 表示したページは先読みを要求する() {
        val settled = mutableListOf<Int>()
        show(
            BookletState.Open(listOf(entry("ノートA", BookletCover.Loading))),
            onPageSettled = { settled += it }
        )

        assertEquals(listOf(0), settled)
    }

    private fun entry(title: String, cover: BookletCover) = BookletEntry(
        ref = DocumentRef("content://fake/$title"),
        title = title,
        cover = cover
    )

    private fun show(
        state: BookletState,
        onPageSettled: (Int) -> Unit = {},
        onRead: (BookletEntry) -> Unit = {},
        onDrawAgain: () -> Unit = {},
        onExit: () -> Unit = {}
    ) {
        composeRule.setContent {
            AppTheme(darkTheme = false) {
                BookletScreen(
                    state = state,
                    onPageSettled = onPageSettled,
                    onRead = onRead,
                    onDrawAgain = onDrawAgain,
                    onExit = onExit
                )
            }
        }
    }
}
