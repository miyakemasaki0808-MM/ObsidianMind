package com.example.newproject.ui

import com.example.newproject.ui.vigilith.VigilithNoteAction
import com.example.newproject.ui.vigilith.VigilithPresentation
import com.example.newproject.ui.vigilith.VigilithHost
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.example.newproject.model.state.RemarkState
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
 * タブ（note/related/ai）以外のルート（quiz等）ではバー/レールを出さない。
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
    remarkState: RemarkState,
    snackbarHostState: SnackbarHostState,
    vigilithPresentation: VigilithPresentation,
    vigilithNoteAction: VigilithNoteAction?,
    onVigilithTap: (() -> Unit)?,
    content: @Composable (Modifier) -> Unit
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isTabRoute = AppDestination.entries.any { it.route == currentRoute }
    val useRail = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            !isTabRoute -> {
                // 全画面ルート（Q&A等）はバーなしで表示。
                content(Modifier.fillMaxSize())
            }
            useRail -> {
                Row(modifier = Modifier.fillMaxSize()) {
                    NavigationRail(containerColor = NavBar) {
                        AppDestination.entries.forEach { dest ->
                            NavigationRailItem(
                                selected = currentRoute == dest.route,
                                onClick = { navController.navigateToTab(dest) },
                                icon = { TabIcon(dest, remarkState) },
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
                    Box(modifier = Modifier.weight(1f).fillMaxSize()) { content(Modifier.fillMaxSize()) }
                }
            }
            else -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.weight(1f).fillMaxSize()) { content(Modifier.fillMaxSize()) }
                    NavigationBar(containerColor = NavBar) {
                        AppDestination.entries.forEach { dest ->
                            NavigationBarItem(
                                selected = currentRoute == dest.route,
                                onClick = { navController.navigateToTab(dest) },
                                icon = { TabIcon(dest, remarkState) },
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
            }
        }

        VigilithHost(
            presentation = vigilithPresentation,
            useNavigationRail = useRail,
            isSnackbarVisible = snackbarHostState.currentSnackbarData != null,
            noteAction = vigilithNoteAction,
            onTap = onVigilithTap
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = if (isTabRoute && !useRail) 84.dp else 16.dp)
        )
    }
}

internal enum class AiTabBadgeState { None, Loading }

/**
 * AIタブのバッジ状態。**生成中だけを示す。**
 *
 * 旧補記は結果が Vault 内の `.md` にあり、一覧を開くまで存在に気づけなかったため
 * 「未確認」を `isViewed` で管理し、完了（✓）と失敗（!）のバッジを出していた。
 *
 * ひとことは結果を痕跡サイドカーへ永続化し、`RemarkScreen` を開くたび必ず復元する。
 * **見逃しても失われないので「まだ見ていない」を状態として持つ必要がない**（完了の通知は
 * 一度きりの Snackbar で足りる）。したがって**確認して消すバッジは対象ごと消えた**。
 * 判定軸は「結果がどこに出るか」ではなく**「後から結果へ辿り着けるか」**である
 * （→ system/background_ai_ux.md §4）。
 *
 * 生成中だけ残すのは、押してから読書へ戻る導線が実在するため
 * （Nano は1回数十秒かかる）。こちらは自動で消えるので確認管理を必要としない。
 *
 * 副産物として、**下部ナビ帯の上で判別できなかった塗りバッジ
 * （Success 1.61 / Error 1.04）が無くなった。** 残る生成中表示は塗りではなく
 * 線のインジケータなので、同じ問題を持たない。
 */
internal fun resolveAiTabBadgeState(
    remarkState: RemarkState
): AiTabBadgeState = when (remarkState) {
    is RemarkState.Loading -> AiTabBadgeState.Loading
    else -> AiTabBadgeState.None
}

/** AIタブの意味は常に✨のまま保ち、右上の小さなバッジだけでAIの状態を知らせる。 */
@Composable
private fun TabIcon(
    dest: AppDestination,
    remarkState: RemarkState
) {
    if (dest != AppDestination.Ai) {
        Text(dest.emoji, fontSize = 20.sp)
        return
    }

    BadgedBox(
        badge = {
            when (resolveAiTabBadgeState(remarkState)) {
                AiTabBadgeState.Loading -> CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    color = Aqua,
                    strokeWidth = 1.5.dp
                )
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
