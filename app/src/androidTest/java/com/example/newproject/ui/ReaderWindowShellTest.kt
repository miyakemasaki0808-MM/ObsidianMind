package com.example.newproject.ui

import android.os.Build
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.roundToInt
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.newproject.ui.component.screenContentPadding
import com.example.newproject.ui.screen.ReaderChoice
import com.example.newproject.ui.screen.ReaderFold
import com.example.newproject.ui.screen.ReaderFoldInfo
import com.example.newproject.ui.screen.ReaderRegion
import com.example.newproject.ui.screen.ReaderWindowEffect
import com.example.newproject.ui.screen.canShowMarginPane
import com.example.newproject.ui.screen.readerWindowFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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

    /**
     * **外殻の値は、実際に組まれたレールの幅を除いた本文領域と一致する。** レールは最小80dpで、文字の倍率でラベルが広がると幅が増える
     * （→ features/margin_pane.md §5.4）。80dpとみなすと、倍率2倍のこの窓で外殻は机を出せる・本文は出せない、と食い違った。
     * レールを畳んでも（机・見開きの間）外殻の値は替わらない — 見えないまま組んだ同じレールで幅を測る。
     */
    @Test
    fun 文字の倍率を上げても_外殻の値と本文領域の机の可否が一致し_レールを畳んでも替わらない() {
        var fontScale by mutableStateOf(1f)
        var railHidden by mutableStateOf(false)
        var probe: ReaderRegion? = null
        var body: ReaderRegion? = null
        var boxLeft = 0f
        composeRule.setContent {
            val base = LocalDensity.current
            fun LayoutCoordinates.region() = ReaderRegion(
                widthDp = size.width / base.density,
                heightDp = size.height / base.density,
                startDp = positionInWindow().x / base.density
            )
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                Box(
                    modifier = Modifier
                        .requiredSize(width = 840.dp, height = 900.dp)
                        .onGloballyPositioned { boxLeft = it.positionInWindow().x / base.density }
                ) {
                    val nav = rememberNavController()
                    AppScaffold(
                        windowSizeClass = WindowSizeClass.calculateFromSize(DpSize(840.dp, 900.dp)),
                        navController = nav,
                        snackbarHostState = remember { SnackbarHostState() },
                        notePaneVisible = railHidden,
                        onReaderRegion = { probe = it }
                    ) { modifier ->
                        NavHost(navController = nav, startDestination = AppDestination.Note.route, modifier = modifier) {
                            // ノートの画面と同じ取り方の本文領域（→ NoteReaderTab）。
                            composable(AppDestination.Note.route) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .windowInsetsPadding(WindowInsets.safeDrawing.exclude(WindowInsets.ime))
                                        .screenContentPadding()
                                        .onGloballyPositioned { body = it.region() }
                                )
                            }
                        }
                    }
                }
            }
        }
        // 窓の中ほど（箱の左端から420dp）に縦の折り目。
        fun fold() = ReaderFold(isVertical = true, startDp = boxLeft + 420f, endDp = boxLeft + 420f)
        fun ReaderRegion.canShow() = canShowMarginPane(widthDp, heightDp, expandedWidth = true, fold = fold(), regionStartDp = startDp)

        listOf(1f, 2f).forEach { scale ->
            composeRule.runOnIdle {
                fontScale = scale
                railHidden = false
            }
            composeRule.waitForIdle()
            val shown = composeRule.runOnIdle { requireNotNull(probe) }
            composeRule.runOnIdle {
                assertEquals("倍率 $scale で外殻と本文が食い違う（外殻 $shown・本文 $body）", requireNotNull(body).canShow(), shown.canShow())
            }

            composeRule.runOnIdle { railHidden = true }
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                assertEquals("倍率 $scale でレールを畳むと外殻の値が替わった", shown.canShow(), requireNotNull(probe).canShow())
                assertEquals("倍率 $scale でレールを畳むと測った本文領域が替わった", shown.startDp, requireNotNull(probe).startDp, 0.5f)
            }
        }
    }

    /**
     * **レールを出している間、左の切り欠きはレールが覆う。** 本文と外殻の測る箱は、切り欠きの分を重ねて空けずにレールの右端から始まる
     * （カバー画面の横で、見出しの霞がレールから離れていた）。レールを畳んだ間（机・見開き）は本文が窓の左端から始まるので、
     * 切り欠きを避ける（→ features/margin_pane.md §5.4）。切り欠きは、本番の外殻が置かれた View へ Insets を配って作る。
     * **配った値が Compose へ届くのは API 30 以上だけ**（それより前の `WindowInsetsCompat.Builder` は切り欠きを作らない）なので、
     * それより前の端末では適用外として skip し、位置を比べる前に Compose が配った値を読んだことを確かめる。
     */
    @Test
    fun 左の切り欠きはレールが覆い_本文と測る箱はレールの右端から始まり_レールを畳めば本文が切り欠きを避ける() {
        assumeTrue("切り欠きの注入は API 30 以上でだけ Compose へ届く", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        var railHidden by mutableStateOf(false)
        var observedCutoutPx = -1
        var probe: ReaderRegion? = null
        var body: ReaderRegion? = null
        lateinit var view: View
        composeRule.setContent {
            view = LocalView.current
            observedCutoutPx = WindowInsets.displayCutout.getLeft(LocalDensity.current, LayoutDirection.Ltr)
            val density = LocalDensity.current.density
            fun LayoutCoordinates.region() = ReaderRegion(
                widthDp = size.width / density,
                heightDp = size.height / density,
                startDp = positionInWindow().x / density
            )
            Box(modifier = Modifier.requiredSize(width = 840.dp, height = 900.dp)) {
                val nav = rememberNavController()
                AppScaffold(
                    windowSizeClass = WindowSizeClass.calculateFromSize(DpSize(840.dp, 900.dp)),
                    navController = nav,
                    snackbarHostState = remember { SnackbarHostState() },
                    notePaneVisible = railHidden,
                    onReaderRegion = { probe = it }
                ) { modifier ->
                    NavHost(navController = nav, startDestination = AppDestination.Note.route, modifier = modifier) {
                        composable(AppDestination.Note.route) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .windowInsetsPadding(WindowInsets.safeDrawing.exclude(WindowInsets.ime))
                                    .screenContentPadding()
                                    .onGloballyPositioned { body = it.region() }
                            )
                        }
                    }
                }
            }
        }
        fun dispatchLeftCutout(dp: Float) {
            composeRule.runOnUiThread {
                val px = (dp * view.resources.displayMetrics.density).roundToInt()
                val insets = WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(px, 0, 0, 0))
                    .build()
                ViewCompat.dispatchApplyWindowInsets(view, insets)
            }
            composeRule.waitForIdle()
            val px = (dp * view.resources.displayMetrics.density).roundToInt()
            composeRule.runOnIdle { assertEquals("配った切り欠きを Compose が読んでいない", px, observedCutoutPx) }
        }
        fun bodyStart() = composeRule.runOnIdle { requireNotNull(body).startDp }
        fun probeStart() = composeRule.runOnIdle { requireNotNull(probe).startDp }

        dispatchLeftCutout(0f)
        val bodyWithout = bodyStart()
        val probeWithout = probeStart()
        dispatchLeftCutout(CUTOUT_DP)
        assertEquals("レールの横の本文が、切り欠きの分だけレールから離れた", bodyWithout, bodyStart(), 0.5f)
        assertEquals("外殻の測る箱が、切り欠きの分だけレールから離れた", probeWithout, probeStart(), 0.5f)
        assertEquals("外殻の測る箱と本文の左端が食い違う", bodyStart(), probeStart(), 0.5f)

        // レールを畳むと、本文は窓の左端から始まる。
        composeRule.runOnIdle { railHidden = true }
        composeRule.waitForIdle()
        val hiddenWith = bodyStart()
        dispatchLeftCutout(0f)
        assertEquals("レールを畳んだ本文が切り欠きを避けない", CUTOUT_DP, hiddenWith - bodyStart(), 0.5f)
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
        /** 左の切り欠きの深さ。レールの最小幅（80dp）より浅い。 */
        const val CUTOUT_DP = 40f
    }
}
