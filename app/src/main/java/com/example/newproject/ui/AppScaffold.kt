package com.example.newproject.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import com.example.newproject.ui.screen.DESK_BAR_HIDE_MILLIS
import com.example.newproject.ui.screen.DESK_MOTION_MILLIS
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.example.newproject.ui.theme.OnVibrantMuted
import com.example.newproject.ui.theme.Aqua
import com.example.newproject.ui.theme.NavBar
import com.example.newproject.ui.theme.NavIndicator
import com.example.newproject.ui.theme.OnVibrant

/** トップレベルのタブ。route は NavHost のルート名と一致させる。 */
enum class AppDestination(val route: String, val label: String, val emoji: String) {
    Note("note", "ノート", "📄"),
    Search("search", "さがす", "🔎"),
    Related("related", "関連", "🔗"),
    Ai("ai", "AI", "✨"),
    Options("options", "オプション", "⚙️")
}

/**
 * 画面幅に応じてタブUIを切り替えるアプリの外殻。
 * Expanded（Fold展開など）は左サイドの NavigationRail、それ以外は下部の NavigationBar。
 * タブ以外のルート（全画面・冊子など）ではバー/レールを出さない。
 * ノートタブで机の形（余白ペイン・本文の見開き）が出ている間はレールを畳み、帯が出ている間だけ重ねて出す（→ [showsRail]・[overlaysRail]）。
 *
 * **[content] は、どの形でも「最初の子」として同じ位置で呼ぶ。** `rememberSaveable` は呼び出し位置で保存先を
 * 決め、その位置には**前にある兄弟グループの数**も入る。形ごとに呼ぶ場所を分けたり、条件付きのレールを前に
 * 置いたりすると、Fold の開閉（Activity の作り直し）で NavHost 配下の保存値が形ごとに別々に戻り、
 * 全画面へ移るときは NavHost ごと作り直されて各ルートの保存値が消える（→ features/margin_pane.md §11）。
 * レールとバーは [content] の**後ろ**に組み立て、並べる位置は [ScaffoldLayout] が決める。
 *
 * Scaffold を使わず手動レイアウトにしているのは、各タブが `safeDrawingPadding()` で
 * インセットを処理するため、Scaffold の contentPadding と二重付与になるのを避ける狙い。
 * バー/レール自身は既定の windowInsets でシステムバーを避ける。
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
internal fun AppScaffold(
    windowSizeClass: WindowSizeClass,
    navController: NavHostController,
    snackbarHostState: SnackbarHostState,
    /** ノートタブが机の形（余白ペイン・本文の見開き）を出しているか。出ている間はレールを畳む。 */
    notePaneVisible: Boolean,
    /** 机の形で、つまみで呼ぶ帯が出ているか。出ている間はレールを左端に重ねて出す（→ [overlaysRail]）。 */
    noteBarShown: Boolean = false,
    /** 重ねて出したレールでタブを押した。帯をしまわせる。 */
    onOverlayRailTab: () -> Unit = {},
    content: @Composable (Modifier) -> Unit
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isTabRoute = AppDestination.entries.any { it.route == currentRoute }
    val useRail = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded

    Box(modifier = Modifier.fillMaxSize()) {
        ScaffoldLayout(
            body = { content(Modifier.fillMaxSize()) },
            rail = if (showsRail(isTabRoute, useRail, currentRoute, notePaneVisible)) {
                { TabRail(currentRoute) { dest -> navController.navigateToTab(dest) } }
            } else null,
            // 帯と同時に、左の縁から滑り込む（→ features/note_spread.md §8 判断3）。しまうのも帯と同じ速さ。
            overlayRail = if (isTabRoute && useRail && currentRoute == AppDestination.Note.route) {
                {
                    AnimatedVisibility(
                        visible = overlaysRail(isTabRoute, useRail, currentRoute, notePaneVisible, noteBarShown),
                        enter = slideInHorizontally(
                            initialOffsetX = { -it },
                            animationSpec = tween(DESK_MOTION_MILLIS, easing = FastOutSlowInEasing)
                        ),
                        exit = slideOutHorizontally(
                            targetOffsetX = { -it },
                            animationSpec = tween(DESK_BAR_HIDE_MILLIS, easing = FastOutLinearInEasing)
                        )
                    ) {
                        TabRail(currentRoute) { dest ->
                            onOverlayRailTab()
                            navController.navigateToTab(dest)
                        }
                    }
                }
            } else null,
            bottomBar = if (isTabRoute && !useRail) {
                {
                    NavigationBar(containerColor = NavBar) {
                        AppDestination.entries.forEach { dest ->
                            NavigationBarItem(
                                selected = currentRoute == dest.route,
                                onClick = { navController.navigateToTab(dest) },
                                icon = { TabIcon(dest) },
                                label = { TabLabel(dest.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = OnVibrant,
                                    selectedTextColor = OnVibrant,
                                    unselectedIconColor = OnVibrantMuted,
                                    unselectedTextColor = OnVibrantMuted,
                                    indicatorColor = NavIndicator
                                )
                            )
                        }
                    }
                }
            } else null
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (isTabRoute && !useRail) 84.dp else 16.dp)
        )
    }
}

/**
 * レールを本文の横に並べて出すか。**ノートタブで机の形（余白ペイン・本文の見開き）が出ている間は畳む**（→ features/margin_pane.md §5.1）。
 * レールが本文の列の幅を取り、折り目で割った本文がペインより細くなる。畳めば本文とペイン（見開きの左右のページ）がほぼ等しくなる。
 * 見出しの行の操作は本文の列の上にあって幅を取らないので、畳まない。
 * 畳んでいる間は下のバーも出さない — タブへの道は、帯を出すと重ねて出すレール（→ [overlaysRail]）。
 */
internal fun showsRail(isTabRoute: Boolean, useRail: Boolean, currentRoute: String?, notePaneVisible: Boolean): Boolean =
    isTabRoute && useRail && !(currentRoute == AppDestination.Note.route && notePaneVisible)

/**
 * レールを本文の上に重ねて出すか。**机の形でレールを畳んでいる間、帯が出ているときだけ**（→ features/note_spread.md §8 判断3）。
 * 見開きにはペインをしまう先が無く、タブへの道がここしか無い。重ねるだけで本文の幅は変えない（帯を出すたびに並べ直さない）。
 */
internal fun overlaysRail(isTabRoute: Boolean, useRail: Boolean, currentRoute: String?, notePaneVisible: Boolean, noteBarShown: Boolean): Boolean =
    isTabRoute && useRail && currentRoute == AppDestination.Note.route && notePaneVisible && noteBarShown

/** Material3 の `NavigationRail` の幅。重ねて出したレールが帯を隠さないよう、帯の中身はこの幅だけ右から始める。 */
internal val RailWidth = 80.dp

@Composable
private fun TabRail(currentRoute: String?, onSelect: (AppDestination) -> Unit) {
    NavigationRail(containerColor = NavBar) {
        AppDestination.entries.forEach { dest ->
            NavigationRailItem(
                selected = currentRoute == dest.route,
                onClick = { onSelect(dest) },
                icon = { TabIcon(dest) },
                label = { TabLabel(dest.label) },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = OnVibrant,
                    selectedTextColor = OnVibrant,
                    unselectedIconColor = OnVibrantMuted,
                    unselectedTextColor = OnVibrantMuted,
                    indicatorColor = NavIndicator
                )
            )
        }
    }
}

/**
 * 左にレール、下にバー、残りに本文を置く。**組み立ての順は本文が先**で、並べる位置だけをここで決める
 * （→ [AppScaffold]）。レールとバーはどちらか一方か、どちらも無い。
 * 重ねるレール（[overlayRail]）は本文の幅を取らず、本文の上の左端に置く。
 */
@Composable
private fun ScaffoldLayout(
    body: @Composable () -> Unit,
    rail: (@Composable () -> Unit)?,
    overlayRail: (@Composable () -> Unit)?,
    bottomBar: (@Composable () -> Unit)?
) {
    Layout(
        content = {
            Box(modifier = Modifier.layoutId(SLOT_CONTENT)) { body() }
            if (rail != null) Box(modifier = Modifier.layoutId(SLOT_RAIL)) { rail() }
            if (bottomBar != null) Box(modifier = Modifier.layoutId(SLOT_BAR)) { bottomBar() }
            if (overlayRail != null) Box(modifier = Modifier.layoutId(SLOT_OVERLAY_RAIL)) { overlayRail() }
        },
        modifier = Modifier.fillMaxSize()
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val railPlaceable = measurables.firstOrNull { it.layoutId == SLOT_RAIL }
            ?.measure(Constraints(maxWidth = width, minHeight = height, maxHeight = height))
        val barPlaceable = measurables.firstOrNull { it.layoutId == SLOT_BAR }
            ?.measure(Constraints(minWidth = width, maxWidth = width, maxHeight = height))
        val railWidth = railPlaceable?.width ?: 0
        val barHeight = barPlaceable?.height ?: 0
        val contentPlaceable = measurables.first { it.layoutId == SLOT_CONTENT }
            .measure(Constraints.fixed((width - railWidth).coerceAtLeast(0), (height - barHeight).coerceAtLeast(0)))
        val overlayPlaceable = measurables.firstOrNull { it.layoutId == SLOT_OVERLAY_RAIL }
            ?.measure(Constraints(maxWidth = width, minHeight = height, maxHeight = height))
        layout(width, height) {
            railPlaceable?.placeRelative(0, 0)
            contentPlaceable.placeRelative(railWidth, 0)
            barPlaceable?.placeRelative(0, height - barHeight)
            // 本文より後に置く — 後に置いたものが上に描かれる。
            overlayPlaceable?.placeRelative(0, 0)
        }
    }
}

private const val SLOT_CONTENT = "content"
private const val SLOT_RAIL = "rail"
private const val SLOT_BAR = "bar"
private const val SLOT_OVERLAY_RAIL = "overlayRail"

/** AIタブの意味は常に✨のまま保つ。 */
@Composable
private fun TabIcon(dest: AppDestination) {
    Text(dest.emoji, fontSize = 20.sp)
}

@Composable
private fun TabLabel(text: String) {
    Text(text, fontSize = 11.sp, fontWeight = FontWeight.Medium)
}

/**
 * タブ切替。バックスタックを積まず状態を保存/復元する標準構成。
 * MainActivity 側（検索・関連からノートを開く導線）でも共用する。
 */
internal fun NavHostController.navigateToTab(dest: AppDestination) {
    navigate(dest.route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
