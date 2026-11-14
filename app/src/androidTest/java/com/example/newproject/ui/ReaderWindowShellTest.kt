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
import androidx.compose.ui.platform.LocalLayoutDirection
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
     * **レールを出している間、始まりの側の切り欠きはレールが覆う幅まで消費する。** 本文と外殻の測る箱は、窓の始まりから
     * `max(レールの幅, 切り欠き)` に画面の余白を足した位置で始まる — 浅い切り欠きはレールの右に重ねて空けず（カバー画面の横で、
     * 見出しの霞がレールから離れていた）、レールより深い切り欠きの残りは本文が避ける（既定のレールはシステムバーしか避けない）。
     * レールを畳んだ間（机・見開き）は本文が窓の端から始まるので切り欠きを避け、重ねたレールの出入りで本文は動かない
     * （→ features/margin_pane.md §5.4）。右から左の並びでも、始まりの側（右）で同じになる。
     *
     * 切り欠きは、本番の外殻が置かれた View へ Insets を配って作る。**配った値が Compose へ届くのは API 30 以上だけ**
     * （それより前の `WindowInsetsCompat.Builder` は切り欠きを作らない）なので、それより前の端末では適用外として skip し、
     * 位置を比べる前に Compose が配った値を読んだことを確かめる。
     */
    @Test
    fun 始まりの側の切り欠きはレールが覆う幅まで消費し_深い分は本文が避け_レールを畳めば本文が切り欠きを避ける() {
        cutoutRoundTrip(LayoutDirection.Ltr)
    }

    @Test
    fun 右から左の並びでも_切り欠きはレールが覆う幅まで消費する() {
        cutoutRoundTrip(LayoutDirection.Rtl)
    }

    private fun cutoutRoundTrip(direction: LayoutDirection) {
        assumeTrue("切り欠きの注入は API 30 以上でだけ Compose へ届く", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        val rtl = direction == LayoutDirection.Rtl
        var railHidden by mutableStateOf(false)
        var barShown by mutableStateOf(false)
        var observedCutoutPx = -1
        var boxLeft = 0f
        var boxRight = 0f
        var probe: ReaderRegion? = null
        var body: ReaderRegion? = null
        lateinit var view: View
        composeRule.setContent {
            view = LocalView.current
            val cutout = WindowInsets.displayCutout
            observedCutoutPx = if (rtl) cutout.getRight(LocalDensity.current, direction) else cutout.getLeft(LocalDensity.current, direction)
            val density = LocalDensity.current.density
            fun LayoutCoordinates.region() = ReaderRegion(
                widthDp = size.width / density,
                heightDp = size.height / density,
                startDp = positionInWindow().x / density
            )
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                Box(
                    modifier = Modifier
                        .requiredSize(width = 840.dp, height = 900.dp)
                        .onGloballyPositioned {
                            boxLeft = it.positionInWindow().x / density
                            boxRight = boxLeft + it.size.width / density
                        }
                ) {
                    val nav = rememberNavController()
                    AppScaffold(
                        windowSizeClass = WindowSizeClass.calculateFromSize(DpSize(840.dp, 900.dp)),
                        navController = nav,
                        snackbarHostState = remember { SnackbarHostState() },
                        notePaneVisible = railHidden,
                        noteBarShown = barShown,
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
        }
        fun dispatchCutout(dp: Float) {
            val px = (dp * view.resources.displayMetrics.density).roundToInt()
            composeRule.runOnUiThread {
                val insets = WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.displayCutout(), if (rtl) Insets.of(0, 0, px, 0) else Insets.of(px, 0, 0, 0))
                    .build()
                ViewCompat.dispatchApplyWindowInsets(view, insets)
            }
            composeRule.waitForIdle()
            composeRule.runOnIdle { assertEquals("配った切り欠きを Compose が読んでいない", px, observedCutoutPx) }
        }
        fun show(hidden: Boolean, bar: Boolean = false) {
            composeRule.runOnIdle {
                railHidden = hidden
                barShown = bar
            }
            composeRule.waitForIdle()
        }
        // 箱の始まりの側の縁から、本文領域の始まりまでの距離（dp）。
        fun ReaderRegion.inset() = if (rtl) boxRight - (startDp + widthDp) else startDp - boxLeft
        fun bodyInset() = composeRule.runOnIdle { requireNotNull(body).inset() }
        fun probeInset() = composeRule.runOnIdle { requireNotNull(probe).inset() }

        dispatchCutout(0f)
        val beside = bodyInset()
        show(hidden = true)
        val padding = bodyInset()
        val rail = beside - padding
        show(hidden = false)

        // 浅い切り欠き（レールの半分）は、レールの下に収まる。
        dispatchCutout(rail / 2)
        assertEquals("レールの横の本文が、浅い切り欠きの分だけレールから離れた", beside, bodyInset(), TOLERANCE_DP)
        assertEquals("外殻の測る箱と本文の始まりが食い違う（浅い切り欠き）", bodyInset(), probeInset(), TOLERANCE_DP)

        // レールより深い切り欠きは、レールを越えた分だけ本文が避ける。
        val deep = rail + DEEPER_DP
        dispatchCutout(deep)
        assertEquals("レールより深い切り欠きへ本文が食い込んだ", beside + DEEPER_DP, bodyInset(), TOLERANCE_DP)
        assertEquals("外殻の測る箱と本文の始まりが食い違う（深い切り欠き）", bodyInset(), probeInset(), TOLERANCE_DP)
        val probeWithRail = probeInset()

        // レールを畳むと、本文は窓の端から始まり、切り欠きを避ける。測る箱はレールがある状態のまま。
        show(hidden = true)
        assertEquals("レールを畳んだ本文が切り欠きを避けない", padding + deep, bodyInset(), TOLERANCE_DP)
        assertEquals("レールを畳むと測る箱が替わった", probeWithRail, probeInset(), TOLERANCE_DP)
        // 重ねたレールの出入りで、本文は動かない。
        show(hidden = true, bar = true)
        assertEquals("重ねたレールを出すと本文が動いた", padding + deep, bodyInset(), TOLERANCE_DP)
        show(hidden = true, bar = false)
        assertEquals("重ねたレールをしまうと本文が動いた", padding + deep, bodyInset(), TOLERANCE_DP)
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
        /** レールより深い切り欠きが、レールを越える幅。 */
        const val DEEPER_DP = 40f
        /** 切り欠きを px へ丸めた分の誤差を許す幅。 */
        const val TOLERANCE_DP = 1f
    }
}
