package com.example.newproject.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.newproject.model.BookletCover
import com.example.newproject.model.BookletEntry
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.state.BookletState
import com.example.newproject.ui.screen.BookletScreen
import com.example.newproject.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **実際の NavHost を往復して、冊子の状態が残ることを確かめる。**
 *
 * ## なぜ画面単体では足りなかったか
 *
 * `BookletScreenTest` はページ送りを、`BookletRouteContractTest` は
 * `navigate("note")` を書いていることを、**それぞれ別々に**見ていた。
 * どちらも通っていたが、**実機では `冊子 → ノート → 戻る` でページ位置が1枚目へ戻った**
 * （2026-08-31 の実機検証で再現）。**往復そのものを通す面がどこにも無かった。**
 *
 * ここで作るのは本番と同じ形の最小の NavHost で、
 * **ページ位置を束が覚えている**ことだけを往復で観測する。
 */
@RunWith(AndroidJUnit4::class)
class BookletNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun ノートから戻ると同じページが開く() {
        val nav = showBookletAndNote()

        turnPage("次のページへ")
        turnPage("次のページへ")
        composeRule.onNodeWithContentDescription("3/10ページ").assertIsDisplayed()

        // **ページャは隣のページも同時に持つ**ので、「これを読む」だけでは一意にならない。
        // どのノートを開くボタンかを名前で指す（実機で `performClick()` が
        // 単一ノードを選べず往復の手前で止まった → 2026-08-31）。
        composeRule.onNodeWithContentDescription("「ノート3」を読む").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText(NOTE_LABEL).assertIsDisplayed()

        composeRule.runOnUiThread { nav.popBackStack() }
        composeRule.waitForIdle()

        // 修正前はここが「1/10ページ」だった。
        composeRule.onNodeWithContentDescription("3/10ページ").assertIsDisplayed()
    }

    /** 1枚目のまま渡した場合は、戻っても1枚目のまま（覚える側が余計なことをしない）。 */
    @Test
    fun 先頭から渡せば先頭へ戻る() {
        val nav = showBookletAndNote()

        composeRule.onNodeWithContentDescription("「ノート1」を読む").performClick()
        composeRule.waitForIdle()
        composeRule.runOnUiThread { nav.popBackStack() }
        composeRule.waitForIdle()

        composeRule.onNodeWithContentDescription("1/10ページ").assertIsDisplayed()
    }

    /**
     * 本番と同じ形の最小 NavHost。
     *
     * **束とページ位置は1つの状態に持つ** — 本番では `BookletController` が
     * `BookletState.Open` を更新する。ここではその役だけを担わせる。
     */
    private fun showBookletAndNote(): NavHostController {
        lateinit var navController: NavHostController
        composeRule.setContent {
            navController = rememberNavController()
            var booklet by remember {
                mutableStateOf(
                    BookletState.Open(
                        (1..10).map { index ->
                            BookletEntry(
                                ref = DocumentRef("content://fake/ノート$index"),
                                title = "ノート$index",
                                cover = BookletCover.Ready("$index 枚目の代表文である。")
                            )
                        }
                    )
                )
            }
            AppTheme(darkTheme = false) {
                NavHost(navController = navController, startDestination = "booklet") {
                    composable("booklet") {
                        BookletScreen(
                            state = booklet,
                            onPageSettled = { page -> booklet = booklet.copy(page = page) },
                            // スクロール位置の先頭戻しは openFromBooklet 側の責務なので、
                            // ここでは遷移だけを本番と同じ形で行う。
                            onRead = { navController.navigate("note") },
                            onDrawAgain = {},
                            onExit = {}
                        )
                    }
                    composable("note") { Text(NOTE_LABEL) }
                }
            }
        }
        composeRule.waitForIdle()
        return navController
    }

    /** 読み上げ操作で1ページ動かす。スワイプより決定的。 */
    private fun turnPage(label: String) {
        val actions = composeRule
            .onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
            .fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
        composeRule.runOnUiThread { actions.first { it.label == label }.action() }
        composeRule.waitForIdle()
    }

    private companion object {
        const val NOTE_LABEL = "通常のノート表示"
    }
}
