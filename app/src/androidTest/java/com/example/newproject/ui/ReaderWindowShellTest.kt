package com.example.newproject.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.newproject.ui.screen.ReaderChoice
import com.example.newproject.ui.screen.ReaderFoldInfo
import com.example.newproject.ui.screen.ReaderRegion
import com.example.newproject.ui.screen.ReaderWindowEffect
import com.example.newproject.ui.screen.readerWindowFor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * **外殻が本文領域を測り、出せない窓で机を閉じ、出せる窓へ戻っても開かない**ことを、本番の [AppScaffold] と [ReaderWindowEffect] で確かめる
 * （→ features/margin_pane.md §5.4・features/fold_experience.md §6 の初期状態）。
 *
 * 横幅の区分（Expanded）と折り目を保ったまま、高さだけを下げて戻す。2つの入力だけで決めると、下げても閉じず、戻したときに机が自動で出る。
 * ノートの画面の表示中と、さがすの画面の表示中の両方で通す — ノートの画面が組まれていない間にも閉じる。
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
class ReaderWindowShellTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun ノートの画面で高さだけを下げて戻すと_下げた時点で机を閉じ戻しても開かない() {
        heightRoundTrip(onSearch = false)
    }

    @Test
    fun さがすの画面で高さだけを下げて戻しても_机を閉じたままにする() {
        heightRoundTrip(onSearch = true)
    }

    /** 折り目の報告が届く前（作り直しの直後の1フレーム）は判定しない。低い窓でも閉じない。 */
    @Test
    fun 折り目の報告が届く前は_低い窓でも机を閉じない() {
        val shell = setShell(foldKnown = false)
        shell.height = LOW
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertTrue("窓の情報が揃う前に閉じた", shell.choice.paneOpen) }
    }

    private fun heightRoundTrip(onSearch: Boolean) {
        val shell = setShell(foldKnown = true)
        if (onSearch) {
            composeRule.runOnUiThread { shell.nav.navigateToTab(AppDestination.Search) }
            composeRule.waitForIdle()
            composeRule.onNodeWithText(SEARCH_SCREEN).assertExists()
        }
        composeRule.runOnIdle { assertTrue("出せる窓で机を閉じた", shell.choice.paneOpen) }

        shell.height = LOW
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertFalse("出せない高さで机を閉じない", shell.choice.paneOpen) }

        shell.height = TALL
        composeRule.waitForIdle()
        composeRule.runOnIdle { assertFalse("高さを戻しただけで机が開いた", shell.choice.paneOpen) }
    }

    /** 本番の外殻（測る箱）と窓の値の効果を、幅を固定した箱の中に組む。机は開いた状態から始める。 */
    private fun setShell(foldKnown: Boolean): Shell {
        val shell = Shell()
        composeRule.setContent {
            shell.nav = rememberNavController()
            Box(modifier = Modifier.requiredSize(width = 1000.dp, height = shell.height)) {
                var region by remember { mutableStateOf<ReaderRegion?>(null) }
                val window = readerWindowFor(region, expandedWidth = true, foldInfo = ReaderFoldInfo(isKnown = foldKnown, fold = null))
                ReaderWindowEffect(window, shell.choice) { shell.choice = it }
                AppScaffold(
                    windowSizeClass = EXPANDED,
                    navController = shell.nav,
                    snackbarHostState = remember { SnackbarHostState() },
                    notePaneVisible = false,
                    onReaderRegion = { region = it }
                ) { modifier ->
                    NavHost(navController = shell.nav, startDestination = AppDestination.Note.route, modifier = modifier) {
                        composable(AppDestination.Note.route) { Text(NOTE_SCREEN) }
                        composable(AppDestination.Search.route) { Text(SEARCH_SCREEN) }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        return shell
    }

    private class Shell {
        var height by mutableStateOf(TALL)
        var choice by mutableStateOf(ReaderChoice(paneOpen = true, spreadSelected = false))
        lateinit var nav: NavHostController
    }

    private companion object {
        const val NOTE_SCREEN = "ノートの画面"
        const val SEARCH_SCREEN = "さがすの画面"
        val TALL = 900.dp
        /** 横長で480dpを割る高さ。本文領域は左右2列になり、ペインを出せない。 */
        val LOW = 400.dp
        val EXPANDED = WindowSizeClass.calculateFromSize(DpSize(1000.dp, 900.dp))
    }
}
