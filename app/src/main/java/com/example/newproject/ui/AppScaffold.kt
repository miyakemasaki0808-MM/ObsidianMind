package com.example.newproject.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Badge
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.example.newproject.AnnotationState
import com.example.newproject.QuizState
import com.example.newproject.ui.theme.Aqua
import com.example.newproject.ui.theme.ButtonSecondary
import com.example.newproject.ui.theme.ErrorRed
import com.example.newproject.ui.theme.Indigo
import com.example.newproject.ui.theme.OnVibrant
import com.example.newproject.ui.theme.Panel

/** トップレベルのタブ。route は NavHost のルート名と一致させる。 */
enum class AppDestination(val route: String, val label: String, val emoji: String) {
    Note("note", "ノート", "📄"),
    Search("search", "さがす", "🔎"),
    Related("related", "関連", "🔗"),
    Ai("ai", "AI", "✨"),
    Options("options", "オプション", "⚙️")
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
    quizState: QuizState,
    annotationState: AnnotationState,
    snackbarHostState: SnackbarHostState,
    content: @Composable (Modifier) -> Unit
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isTabRoute = AppDestination.entries.any { it.route == currentRoute }
    val useRail = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            !isTabRoute -> {
                // 全画面ルート（Q&A・補記メモ）はバーなしで表示。
                content(Modifier.fillMaxSize())
            }
            useRail -> {
                Row(modifier = Modifier.fillMaxSize()) {
                    NavigationRail(containerColor = NavBarColor) {
                        AppDestination.entries.forEach { dest ->
                            NavigationRailItem(
                                selected = currentRoute == dest.route,
                                onClick = { navController.navigateToTab(dest) },
                                icon = { TabIcon(dest, quizState, annotationState) },
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
            }
            else -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f).fillMaxSize()) { content(Modifier.fillMaxSize()) }
                    NavigationBar(containerColor = NavBarColor) {
                        AppDestination.entries.forEach { dest ->
                            NavigationBarItem(
                                selected = currentRoute == dest.route,
                                onClick = { navController.navigateToTab(dest) },
                                icon = { TabIcon(dest, quizState, annotationState) },
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

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (isTabRoute && !useRail) 84.dp else 16.dp)
        )
    }
}

internal enum class AiTabBadgeState { None, Loading, Success, Error }

/** 1つの✨で複数AI機能を表すため、未確認の重要度順に1状態だけを返す。 */
internal fun resolveAiTabBadgeState(
    quizState: QuizState,
    annotationState: AnnotationState
): AiTabBadgeState {
    val hasUnreadError =
        (quizState is QuizState.Error && !quizState.isViewed) ||
            (annotationState is AnnotationState.Error && !annotationState.isViewed)
    val hasUnreadSuccess =
        (quizState is QuizState.Success && !quizState.isViewed) ||
            (annotationState is AnnotationState.Success && !annotationState.isViewed)
    val hasLoading =
        quizState is QuizState.Loading || annotationState is AnnotationState.Loading

    return when {
        hasUnreadError -> AiTabBadgeState.Error
        hasUnreadSuccess -> AiTabBadgeState.Success
        hasLoading -> AiTabBadgeState.Loading
        else -> AiTabBadgeState.None
    }
}

/** AIタブの意味は常に✨のまま保ち、右上の小さなバッジだけでAI全体の状態を知らせる。 */
@Composable
private fun TabIcon(
    dest: AppDestination,
    quizState: QuizState,
    annotationState: AnnotationState
) {
    if (dest != AppDestination.Ai) {
        Text(dest.emoji, fontSize = 20.sp)
        return
    }

    val badgeState = resolveAiTabBadgeState(quizState, annotationState)
    BadgedBox(
        badge = {
            when (badgeState) {
                AiTabBadgeState.Loading -> CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    color = Aqua,
                    strokeWidth = 1.5.dp
                )
                AiTabBadgeState.Success -> Badge(containerColor = ButtonSecondary) {
                    Text("✓", color = OnVibrant, fontSize = 9.sp)
                }
                AiTabBadgeState.Error -> Badge(containerColor = ErrorRed) {
                    Text("!", color = OnVibrant, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                AiTabBadgeState.None -> Unit
            }
        }
    ) {
        Text(dest.emoji, fontSize = 20.sp)
    }
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
