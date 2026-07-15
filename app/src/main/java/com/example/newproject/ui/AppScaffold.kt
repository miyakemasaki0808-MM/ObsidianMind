package com.example.newproject.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.example.newproject.ui.theme.Indigo
import com.example.newproject.ui.theme.OnVibrant
import com.example.newproject.ui.theme.Panel

/** トップレベルのタブ。route は NavHost のルート名と一致させる。 */
enum class AppDestination(val route: String, val label: String, val emoji: String) {
    Note("note", "ノート", "📄"),
    Related("related", "関連", "🔗"),
    Ai("ai", "AI", "✨")
}

private val NavBarColor = Indigo

/**
 * 画面幅に応じてタブUIを切り替えるアプリの外殻。
 * Expanded（Fold展開など）は左サイドの NavigationRail、それ以外は下部の NavigationBar。
 * タブ（note/related/ai）以外のルート（quiz/annotation）ではバー/レールを出さない。
 *
 * Scaffold を使わず手動レイアウトにしているのは、各タブが `safeDrawingPadding()` で
 * インセットを処理するため、Scaffold の contentPadding と二重付与になるのを避ける狙い。
 * バー/レール自身は既定の windowInsets でシステムバーを避ける。
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun AppScaffold(
    windowSizeClass: WindowSizeClass,
    navController: NavHostController,
    content: @Composable (Modifier) -> Unit
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isTabRoute = AppDestination.entries.any { it.route == currentRoute }
    val useRail = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded

    if (!isTabRoute) {
        // 全画面ルート（Q&A・補記メモ）はバーなしで表示。
        content(Modifier.fillMaxSize())
        return
    }

    if (useRail) {
        Row(modifier = Modifier.fillMaxSize()) {
            NavigationRail(containerColor = NavBarColor) {
                AppDestination.entries.forEach { dest ->
                    NavigationRailItem(
                        selected = currentRoute == dest.route,
                        onClick = { navController.navigateToTab(dest) },
                        icon = { Text(dest.emoji, fontSize = 20.sp) },
                        label = { TabLabel(dest.label) },
                        colors = NavigationRailItemDefaults.colors(
                            selectedIconColor = OnVibrant,
                            selectedTextColor = OnVibrant,
                            unselectedIconColor = OnVibrant.copy(alpha = 0.6f),
                            unselectedTextColor = OnVibrant.copy(alpha = 0.6f),
                            indicatorColor = Panel.copy(alpha = 0.22f)
                        )
                    )
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxSize()) { content(Modifier.fillMaxSize()) }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxSize()) { content(Modifier.fillMaxSize()) }
            NavigationBar(containerColor = NavBarColor) {
                AppDestination.entries.forEach { dest ->
                    NavigationBarItem(
                        selected = currentRoute == dest.route,
                        onClick = { navController.navigateToTab(dest) },
                        icon = { Text(dest.emoji, fontSize = 20.sp) },
                        label = { TabLabel(dest.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = OnVibrant,
                            selectedTextColor = OnVibrant,
                            unselectedIconColor = OnVibrant.copy(alpha = 0.6f),
                            unselectedTextColor = OnVibrant.copy(alpha = 0.6f),
                            indicatorColor = Panel.copy(alpha = 0.22f)
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun TabLabel(text: String) {
    Text(text, fontSize = 11.sp, fontWeight = FontWeight.Medium)
}

/** タブ切替。バックスタックを積まず状態を保存/復元する標準構成。 */
private fun NavHostController.navigateToTab(dest: AppDestination) {
    navigate(dest.route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
