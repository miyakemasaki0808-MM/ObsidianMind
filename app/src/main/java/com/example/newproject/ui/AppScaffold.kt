package com.example.newproject.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import com.example.newproject.ui.component.screenContentPadding
import com.example.newproject.ui.screen.DESK_BAR_HIDE_MILLIS
import com.example.newproject.ui.screen.DESK_MOTION_MILLIS
import com.example.newproject.ui.screen.ReaderChoice
import com.example.newproject.ui.screen.ReaderRegion
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
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

/** トップレベルのタブ。route は NavHost のルート名と一致させる。**行き先の単一の定義元**で、見開きはここに足さない（→ [RailItem]）。 */
enum class AppDestination(val route: String, val label: String, val emoji: String) {
    Note("note", "ノート", "📄"),
    Search("search", "さがす", "🔎"),
    Related("related", "関連", "🔗"),
    Ai("ai", "AI", "✨"),
    Options("options", "オプション", "⚙️")
}

/**
 * レールの項目。行き先のタブと、ノートの行き先の中の読む形を選ぶ「見開き」（→ features/note_spread.md §8 判断13）。
 * 見開きは NavHost の行き先ではない — ノートのタブと本文の一覧の状態と位置の口を1組のまま共有する。
 */
internal sealed interface RailItem {
    data class Tab(val destination: AppDestination) : RailItem
    data object Spread : RailItem
}

/** 見開きのタブの名前と記号。冊子のボタンは 📚 を使う（→ features/note_spread.md §5「見開きのタブ」）。 */
internal const val SPREAD_TAB_LABEL = "見開き"
internal const val SPREAD_TAB_EMOJI = "📖"

/** レールの並び。**見開きは「ノート」のすぐ下で、見開きのタブを出す窓だけ**（→ `canShowSpreadTab`）。下のバーは行き先の5つのまま。 */
internal fun railItems(spreadTab: Boolean): List<RailItem> = AppDestination.entries.flatMap { dest ->
    if (dest == AppDestination.Note && spreadTab) listOf(RailItem.Tab(dest), RailItem.Spread) else listOf(RailItem.Tab(dest))
}

/** レールで選んでいる項目か。ノートの行き先では、読む形が見開きなら見開き、そうでなければノート。 */
internal fun railItemSelected(item: RailItem, currentRoute: String?, spreadChosen: Boolean): Boolean = when (item) {
    is RailItem.Tab -> currentRoute == item.destination.route && !(item.destination == AppDestination.Note && spreadChosen)
    RailItem.Spread -> currentRoute == AppDestination.Note.route && spreadChosen
}

/** レールで「ノート」か「見開き」を押した後の選択（→ [readerChoiceAfterRailTap]）。[closesDesk] は利用者が机をしまったか（並べ読みも終える）。 */
internal data class RailTap(val choice: ReaderChoice, val closesDesk: Boolean)

/**
 * レールの「ノート」（[spread] が偽）か「見開き」を押した。読む形をそれに替える。
 * **ノートの行き先で押したら机をしまう**（重ねたレールは机と見開きの帯にだけ出る → features/margin_pane.md §5.4）。✎ でしまうのと同じ扱いで、並べ読みも終える。
 * ほかのタブから押したときは机の開閉を変えない — ほかのタブとの往復では机へ戻る。
 */
internal fun readerChoiceAfterRailTap(choice: ReaderChoice, spread: Boolean, fromNoteRoute: Boolean): RailTap {
    val closesDesk = fromNoteRoute && choice.paneOpen
    return RailTap(ReaderChoice(paneOpen = choice.paneOpen && !closesDesk, spreadSelected = spread), closesDesk)
}

/**
 * 画面幅に応じてタブUIを切り替えるアプリの外殻。
 * Expanded（Fold展開など）は左サイドの NavigationRail、それ以外は下部の NavigationBar。
 * タブ以外のルート（全画面・冊子など）ではバー/レールを出さない。
 * ノートタブで机の形（余白ペイン・本文の見開き）が出ている間はレールを畳み、帯が出ている間だけ重ねて出す（→ [showsRail]・[overlaysRail]）。
 * 縦の折り目のある開いた窓のレールには、ノートのすぐ下に見開きのタブを足す（→ [railItems]）。本文領域を測る見えない箱も置き、
 * ペインを出せる窓と見開きのタブを出す窓はその寸法で決める（→ [ReaderRegion]）。
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
    /** レールに見開きのタブを出す窓か（→ `canShowSpreadTab`）。 */
    spreadTab: Boolean = false,
    /** ノートの行き先の読む形が見開きか。 */
    spreadSelected: Boolean = false,
    /** レールの「ノート」（偽）か「見開き」（真）を押した。[fromNoteRoute] はノートの行き先で押したか（→ [readerChoiceAfterRailTap]）。 */
    onSelectReading: (spread: Boolean, fromNoteRoute: Boolean) -> Unit = { _, _ -> },
    /** 本文領域を測った（→ [ReaderRegion]）。ペインを出せる窓と見開きのタブを出す窓は、外殻がこれで決める。 */
    onReaderRegion: (ReaderRegion) -> Unit = {},
    /**
     * レールの右端を測った（窓の座標、dp）。レールを出していても畳んでいても、レールがある状態の本文領域の左端の位置で測る。
     * 重ねて出すレールも同じ項目で同じ幅なので、ノートの画面は帯の中身をここから始める（→ features/margin_pane.md §5.1）。
     */
    onRailEnd: (Float) -> Unit = {},
    content: @Composable (Modifier) -> Unit
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isTabRoute = AppDestination.entries.any { it.route == currentRoute }
    val useRail = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded
    val items = railItems(spreadTab)
    val spreadChosen = spreadSelected && spreadTab
    val selectRailItem: (RailItem) -> Unit = { item ->
        val onNote = currentRoute == AppDestination.Note.route
        when (item) {
            is RailItem.Tab -> {
                if (item.destination == AppDestination.Note) onSelectReading(false, onNote)
                navController.navigateToTab(item.destination)
            }
            RailItem.Spread -> {
                onSelectReading(true, onNote)
                navController.navigateToTab(AppDestination.Note)
            }
        }
    }
    val density = LocalDensity.current

    Box(modifier = Modifier.fillMaxSize()) {
        ScaffoldLayout(
            body = { content(Modifier.fillMaxSize()) },
            rail = if (showsRail(isTabRoute, useRail, currentRoute, notePaneVisible)) {
                { TabRail(items, currentRoute, spreadChosen, selectRailItem) }
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
                        TabRail(items, currentRoute, spreadChosen) { item ->
                            onOverlayRailTab()
                            selectRailItem(item)
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
                                icon = { TabIcon(dest.emoji) },
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
            } else null,
            // レールを出していない間（机・見開き・タブ以外のルート）も、出したときと同じ項目のレールを見えないまま組んで幅だけ測る。
            // 定数の80dpでは足りない — レールは最小80dpで、ラベルの幅（文字の倍率）や左の Insets で広がる（→ features/margin_pane.md §5.4）。
            railMeasure = if (useRail) {
                { TabRail(items, currentRoute, spreadChosen) {} }
            } else null,
            // ノートの画面と同じ取り方で本文領域を測る（レールがある状態・キーボードを除く → features/margin_pane.md §5.4）。
            probe = {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing.exclude(WindowInsets.ime))
                        .screenContentPadding()
                        .onGloballyPositioned { coordinates ->
                            with(density) {
                                onReaderRegion(
                                    ReaderRegion(
                                        widthDp = coordinates.size.width.toDp().value,
                                        heightDp = coordinates.size.height.toDp().value,
                                        startDp = coordinates.positionInWindow().x.toDp().value
                                    )
                                )
                            }
                        }
                )
            },
            onProbeStart = { x -> onRailEnd(with(density) { x.toDp().value }) }
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

/**
 * Material3 の `NavigationRail` の最小幅。**実際の幅は文字の倍率や左の Insets で広がる**ので、寸法は外殻が測った値を使う。
 * これは外殻の無い組み立て（画面だけのテスト）と、測る前にだけ使う（→ `deskBarRailInsetDp`）。
 */
internal val RailWidth = 80.dp

@Composable
private fun TabRail(items: List<RailItem>, currentRoute: String?, spreadChosen: Boolean, onSelect: (RailItem) -> Unit) {
    NavigationRail(containerColor = NavBar) {
        items.forEach { item ->
            NavigationRailItem(
                selected = railItemSelected(item, currentRoute, spreadChosen),
                onClick = { onSelect(item) },
                icon = { TabIcon(if (item is RailItem.Tab) item.destination.emoji else SPREAD_TAB_EMOJI) },
                label = { TabLabel(if (item is RailItem.Tab) item.destination.label else SPREAD_TAB_LABEL) },
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
 * [probe] は本文領域を測る見えない箱で、**レールの有無によらずレールがある状態の位置と幅**に置く。レールを出していない間は
 * [railMeasure]（同じ項目のレール）を測るだけ測って置かず、その幅を除く。
 * [onProbeStart] は測る箱を置いた左端（窓の座標、px）で、レールの右端と同じ位置になる。
 */
@Composable
private fun ScaffoldLayout(
    body: @Composable () -> Unit,
    rail: (@Composable () -> Unit)?,
    overlayRail: (@Composable () -> Unit)?,
    bottomBar: (@Composable () -> Unit)?,
    railMeasure: (@Composable () -> Unit)?,
    probe: @Composable () -> Unit,
    onProbeStart: (Float) -> Unit
) {
    Layout(
        content = {
            Box(modifier = Modifier.layoutId(SLOT_CONTENT)) { body() }
            if (rail != null) Box(modifier = Modifier.layoutId(SLOT_RAIL)) { rail() }
            if (bottomBar != null) Box(modifier = Modifier.layoutId(SLOT_BAR)) { bottomBar() }
            if (overlayRail != null) Box(modifier = Modifier.layoutId(SLOT_OVERLAY_RAIL)) { overlayRail() }
            if (rail == null && railMeasure != null) {
                // 測るだけで置かない（描かず、指も受けない）。読み上げにも出さない。
                Box(modifier = Modifier.layoutId(SLOT_RAIL_MEASURE).clearAndSetSemantics {}) { railMeasure() }
            }
            Box(modifier = Modifier.layoutId(SLOT_PROBE).onGloballyPositioned { onProbeStart(it.positionInWindow().x) }) { probe() }
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
        // レールがある状態の幅。出しているレールがあればその幅、無ければ見えないまま組んだレールの幅（どちらも同じ項目）。
        val probeRail = railPlaceable?.width
            ?: measurables.firstOrNull { it.layoutId == SLOT_RAIL_MEASURE }
                ?.measure(Constraints(maxWidth = width, minHeight = height, maxHeight = height))?.width
            ?: 0
        val probePlaceable = measurables.first { it.layoutId == SLOT_PROBE }
            .measure(Constraints.fixed((width - probeRail).coerceAtLeast(0), (height - barHeight).coerceAtLeast(0)))
        layout(width, height) {
            probePlaceable.placeRelative(probeRail, 0)
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
private const val SLOT_PROBE = "probe"
private const val SLOT_RAIL_MEASURE = "railMeasure"

/** AIタブの意味は常に✨のまま保つ。 */
@Composable
private fun TabIcon(emoji: String) {
    Text(emoji, fontSize = 20.sp)
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
