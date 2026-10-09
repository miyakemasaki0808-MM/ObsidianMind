package com.example.newproject.ui

import androidx.compose.foundation.clickable
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **窓の形を替えて画面を作り直しても、NavHost 配下の保存値が同じ場所へ戻る**ことを、本番の [AppScaffold] で確かめる。
 *
 * ## なぜ要るか
 *
 * 外殻が本文をレールとバーで別の位置から呼んでいたころ、Fold を閉じると書きかけが消え、開くと古い書きかけへ戻った
 * （2026-10-01 の実機検証）。`rememberSaveable` は呼び出し位置で保存先を決めるので、形ごとに保存先が2つでき、
 * 使われなかった側の値が持ち越されていた。**画面単体のテストでは外殻を通らないので捕まらない。**
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
class AppScaffoldStateRestorationTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 窓の形を替えて作り直しても保存値が同じ場所へ戻る() {
        val restoration = StateRestorationTester(composeRule)
        // **状態にしない。** 作り直しの間にだけ替えたいので、見えている間の再組み立てを起こさない。
        var expanded = true
        restoration.setContent {
            Scaffolded(if (expanded) EXPANDED else COMPACT, rememberNavController())
        }

        tap()
        assertCount(1)

        // 開いた Fold → カバー画面
        expanded = false
        restoration.emulateSavedInstanceStateRestore()
        assertCount(1)

        // カバー画面で進めてから開き直す。**修正前は開いた側の古い値（1）へ戻った。**
        tap()
        assertCount(2)
        expanded = true
        restoration.emulateSavedInstanceStateRestore()
        assertCount(2)
    }

    /** タブ以外のルート（全画面・冊子）へ移って戻っても、ノートのルートの保存値が残る。 */
    @Test
    fun タブ以外のルートを往復しても保存値が残る() {
        lateinit var nav: NavHostController
        composeRule.setContent {
            nav = rememberNavController()
            Scaffolded(COMPACT, nav)
        }

        tap()
        assertCount(1)
        composeRule.runOnUiThread { nav.navigate(FULLSCREEN) }
        composeRule.waitForIdle()
        composeRule.onNodeWithText(FULLSCREEN_LABEL).assertExists()
        composeRule.runOnUiThread { nav.popBackStack() }
        composeRule.waitForIdle()

        // 修正前は外殻の呼び出し位置が替わって NavHost ごと作り直され、0 へ戻った。
        assertCount(1)
    }

    /**
     * **ノートタブで余白ペインが出ている間はレールを畳む。畳んでも出しても、NavHost 配下の保存値は同じ場所へ戻る**
     * （→ features/margin_pane.md §5.1）。レールは本文の後ろに組み立てるので、出し入れしても本文の呼び出し位置は変わらない。
     */
    @Test
    fun レールを畳んで戻しても保存値が残る() {
        var paneVisible by mutableStateOf(false)
        composeRule.setContent {
            Scaffolded(EXPANDED, rememberNavController(), notePaneVisible = paneVisible)
        }
        composeRule.onNodeWithText(AppDestination.Search.label).assertExists()
        tap()
        assertCount(1)

        paneVisible = true
        composeRule.waitForIdle()
        composeRule.onNodeWithText(AppDestination.Search.label).assertDoesNotExist()
        assertCount(1)

        paneVisible = false
        composeRule.waitForIdle()
        composeRule.onNodeWithText(AppDestination.Search.label).assertExists()
        assertCount(1)
    }

    @androidx.compose.runtime.Composable
    private fun Scaffolded(windowSizeClass: WindowSizeClass, nav: NavHostController, notePaneVisible: Boolean = false) {
        AppScaffold(
            windowSizeClass = windowSizeClass,
            navController = nav,
            snackbarHostState = remember { SnackbarHostState() },
            notePaneVisible = notePaneVisible
        ) { modifier ->
            NavHost(navController = nav, startDestination = AppDestination.Note.route, modifier = modifier) {
                composable(AppDestination.Note.route) {
                    var count by rememberSaveable { mutableIntStateOf(0) }
                    Text("数: $count", modifier = Modifier.clickable { count++ })
                }
                composable(FULLSCREEN) { Text(FULLSCREEN_LABEL) }
            }
        }
    }

    private fun tap() {
        composeRule.onNodeWithText("数:", substring = true).performClick()
        composeRule.waitForIdle()
    }

    private fun assertCount(expected: Int) {
        composeRule.onNodeWithText("数: $expected").assertExists()
    }

    private companion object {
        val EXPANDED = WindowSizeClass.calculateFromSize(DpSize(900.dp, 900.dp))
        val COMPACT = WindowSizeClass.calculateFromSize(DpSize(400.dp, 800.dp))
        const val FULLSCREEN = "note_fullscreen"
        const val FULLSCREEN_LABEL = "全画面"
    }
}
