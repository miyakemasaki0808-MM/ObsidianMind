package com.example.newproject.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.newproject.model.BookletCover
import com.example.newproject.model.BookletEntry
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.state.BookletState
import com.example.newproject.ui.screen.BookletScreen
import com.example.newproject.ui.screen.openFromBooklet
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

    /**
     * **まだ読めていない扉は開けない。** 押せると、開けるか分からないノートへ先に遷移し、
     * ページ内に留めるはずの失敗が通常表示側の読込エラーに化ける。
     */
    @Test
    fun 読み込み中の扉は開けない() {
        val opened = mutableListOf<BookletEntry>()
        show(
            BookletState.Open(listOf(entry("ノートA", BookletCover.Loading))),
            onRead = { opened += it }
        )

        composeRule.onNodeWithText("これを読む").assertIsNotEnabled()
        composeRule.onNodeWithText("これを読む").performClick()
        assertEquals(emptyList<BookletEntry>(), opened)
    }

    /**
     * 束は `min(10, 利用可能数)` なので、終端の文言を10枚と決め打たない。
     *
     * **ページ送りにセマンティック操作を使う。** スワイプより決定的で、
     * 同時に「読み上げ操作でめくれる」契約そのものも押さえられる。
     */
    @Test
    fun 終端は実際の枚数を出す() {
        val entries = (1..3).map { entry("ノート$it", BookletCover.Ready("$it 枚目。")) }
        show(BookletState.Open(entries))

        repeat(entries.size) { turnPage("次のページへ") }

        composeRule.onNodeWithText("ここまでの3枚でした。").assertIsDisplayed()
    }

    @Test
    fun 前のページへ戻れる() {
        val entries = (1..2).map { entry("ノート$it", BookletCover.Ready("$it 枚目。")) }
        show(BookletState.Open(entries))

        turnPage("次のページへ")
        composeRule.onNodeWithContentDescription("2/2ページ").assertIsDisplayed()

        turnPage("前のページへ")
        composeRule.onNodeWithContentDescription("1/2ページ").assertIsDisplayed()
    }

    /** 読み上げ操作（スイッチアクセス等）から1ページ動かす。 */
    private fun turnPage(label: String) {
        val actions = composeRule
            .onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
            .fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
        composeRule.runOnUiThread { actions.first { it.label == label }.action() }
        composeRule.waitForIdle()
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

    // ── 冊子から本文へ渡す境界 ───────────────────────────────────────────────

    /**
     * **渡した先は必ず本文の先頭から始まる。**
     *
     * `noteListState` は Activity 生存で共有され、ノート切替ではリセットされない。
     * 呼び出しの有無ではなく、**実際のスクロール位置**で確かめる。
     */
    @Test
    fun 冊子から渡すと本文は先頭から始まる() {
        lateinit var listState: LazyListState
        composeRule.setContent {
            listState = rememberLazyListState(initialFirstVisibleItemIndex = 3)
            LazyColumn(state = listState, modifier = Modifier.height(120.dp)) {
                items(30) { index -> Text("行$index", modifier = Modifier.height(40.dp)) }
            }
        }
        composeRule.waitForIdle()
        assertEquals(3, listState.firstVisibleItemIndex)

        composeRule.runOnUiThread { openFromBooklet(listState, open = {}, navigateToNote = {}) }
        composeRule.waitForIdle()

        assertEquals(0, listState.firstVisibleItemIndex)
        assertEquals(0, listState.firstVisibleItemScrollOffset)
    }

    /** 渡す順序も固定する。**読込を始めてから遷移する**（逆だと表示が先に切り替わる）。 */
    @Test
    fun 冊子から渡すと読込を始めてから遷移する() {
        val calls = mutableListOf<String>()
        lateinit var listState: LazyListState
        composeRule.setContent {
            listState = rememberLazyListState()
            LazyColumn(state = listState) { items(3) { Text("行$it") } }
        }

        composeRule.runOnUiThread {
            openFromBooklet(
                listState,
                open = { calls += "open" },
                navigateToNote = { calls += "navigate" }
            )
        }

        assertEquals(listOf("open", "navigate"), calls)
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
