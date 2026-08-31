package com.example.newproject.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.model.BookletCover
import com.example.newproject.model.BookletEntry
import com.example.newproject.model.state.BookletState
import com.example.newproject.ui.component.GradientHeader
import com.example.newproject.ui.component.IconPill
import com.example.newproject.ui.theme.AccentText
import com.example.newproject.ui.theme.ButtonOutlineOnGradient
import com.example.newproject.ui.theme.ButtonPrimary
import com.example.newproject.ui.theme.ButtonSecondary
import com.example.newproject.ui.theme.ErrorText
import com.example.newproject.ui.theme.OnButtonPrimary
import com.example.newproject.ui.theme.OnButtonSecondary
import com.example.newproject.ui.theme.OnSurface
import com.example.newproject.ui.theme.OnSurfaceMuted
import com.example.newproject.ui.theme.Panel
import com.example.newproject.ui.theme.ReadingGradient
import kotlinx.coroutines.launch

/**
 * 冊子から本文へ渡す境界。**先頭から開くことをここで保証する。**
 *
 * `noteListState` は Activity 生存で共有され、**ノート切替ではリセットされない**ので、
 * 何もしないと選んだ本文が前のノートの途中から開く（→ features/booklet_mode.md §10）。
 *
 * 関数として切り出しているのは、`MainActivity` のラムダの中にあると
 * **描画テストからも素のJVMからも観測できない**ため。ここに置けば
 * 「渡すと先頭から始まる」ことをそのまま確かめられる。
 */
internal fun openFromBooklet(
    noteListState: LazyListState,
    open: () -> Unit,
    navigateToNote: () -> Unit
) {
    open()
    noteListState.requestScrollToItem(0)
    navigateToNote()
}

/**
 * 冊子（10枚の束をめくる面）。
 *
 * **小さな通常リーダーにしない。** ここに出るのは扉（代表文1行）だけで、
 * 本文・蒸留・クイズ・セクションチャットは載せない。訪問記録もAIも走らない
 * （→ features/booklet_mode.md 判断3）。
 *
 * **非タブルートなので下部ナビが出ない。** ZINE は余計な枠が無いほうがよく、
 * ルート化するだけでそうなる（→ 判断2）。
 */
@Composable
internal fun BookletScreen(
    state: BookletState,
    onPageSettled: (Int) -> Unit,
    onRead: (BookletEntry) -> Unit,
    onDrawAgain: () -> Unit,
    onExit: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ReadingGradient)
            .safeDrawingPadding()
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp)
    ) {
        GradientHeader(
            title = "冊子",
            subtitle = "めくって、読みたい1枚を選ぶ。",
            trailing = { IconPill(symbol = "✕", contentDescription = "冊子を閉じる") { onExit() } }
        )

        when (state) {
            is BookletState.Open ->
                if (state.entries.isEmpty()) {
                    BookletNotice("引けるノートがありません。")
                } else {
                    BookletPager(
                        entries = state.entries,
                        // **束が覚えているページから開く。** 画面ローカルに持つと、
                        // 通常表示へ渡って戻る往復でここだけ1枚目へ戻る。
                        initialPage = state.page,
                        onPageSettled = onPageSettled,
                        onRead = onRead,
                        onDrawAgain = onDrawAgain
                    )
                }
            is BookletState.Failed -> BookletNotice(state.message, isError = true)
            // Idle は「開いたが束がまだ無い」＝プロセス復元で束だけ消えた場合を含む。
            // 呼び出し側がノートタブへ戻すので、ここでは待ち表示のままでよい。
            BookletState.Idle, BookletState.Loading -> BookletLoading()
        }
    }
}

/**
 * ページャ本体。
 *
 * **ページ位置は束が持つ（[initialPage]）。** 冊子ルートはバックスタックに残るが、
 * `rememberPagerState` だけに置いた実装は**実機の往復で1枚目へ戻った**（2026-08-31）。
 * 「戻れば同じ10枚が同じページ位置」は束とページ位置の2つで1つの条件なので、
 * 寿命の同じ場所へ揃える（→ 判断6・[BookletState.Open]）。
 *
 * ここから先の操作は [onPageSettled] で束へ返す。**状態から毎フレーム駆動はしない** —
 * 指で送っている最中に外から位置を当てると、めくりと競合する。
 */
@Composable
private fun ColumnScope.BookletPager(
    entries: List<BookletEntry>,
    initialPage: Int,
    onPageSettled: (Int) -> Unit,
    onRead: (BookletEntry) -> Unit,
    onDrawAgain: () -> Unit
) {
    // 末尾の1ページは「もう10枚引く」。**自動では継ぎ足さない**（→ 判断6）。
    val pageCount = entries.size + 1
    val pagerState = rememberPagerState(
        // 束が覚えている位置が範囲外になることは無いが、束の作り直しと
        // すれ違った場合に備えて丸める。
        initialPage = initialPage.coerceIn(0, pageCount - 1),
        pageCount = { pageCount }
    )
    val scope = rememberCoroutineScope()

    // **束が覚えている位置へ、入るたびに合わせ直す。**
    // `rememberPagerState` は `rememberSaveable` なので、復元された値が
    // `initialPage` に優先する。実機の往復で1枚目へ戻ったのはこの層なので、
    // **保存・復元の挙動に依存せず**、束の値を唯一の正として当て直す。
    LaunchedEffect(Unit) {
        val target = initialPage.coerceIn(0, pageCount - 1)
        if (pagerState.currentPage != target) pagerState.scrollToPage(target)
    }

    // LaunchedEffect は長寿命なので、外から来たラムダは必ず現在値を通す（→ lessons L34）。
    val settled by rememberUpdatedState(onPageSettled)
    LaunchedEffect(pagerState.currentPage, entries.size) {
        settled(pagerState.currentPage)
    }

    HorizontalPager(
        state = pagerState,
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .padding(top = 16.dp)
            // スワイプ以外でもめくれるようにする。スイッチアクセスや読み上げ操作では
            // 横スワイプがページ送りにならないため、これが無いと最初の1枚から動けない。
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction("次のページへ") {
                        val next = pagerState.currentPage + 1
                        if (next >= pageCount) false
                        else {
                            scope.launch { pagerState.animateScrollToPage(next) }
                            true
                        }
                    },
                    CustomAccessibilityAction("前のページへ") {
                        val previous = pagerState.currentPage - 1
                        if (previous < 0) false
                        else {
                            scope.launch { pagerState.animateScrollToPage(previous) }
                            true
                        }
                    }
                )
            }
    ) { page ->
        if (page < entries.size) {
            BookletPage(entry = entries[page], onRead = onRead)
        } else {
            DrawAgainPage(drawnCount = entries.size, onDrawAgain = onDrawAgain)
        }
    }

    BookletPageIndicator(page = pagerState.currentPage, total = entries.size)
}

/** 1枚の扉。**代表文と、これを読むボタンだけ。** */
@Composable
private fun BookletPage(entry: BookletEntry, onRead: (BookletEntry) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 8.dp),
        color = Panel,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (val cover = entry.cover) {
                BookletCover.Loading -> CircularProgressIndicator(color = AccentText)
                is BookletCover.Ready -> Text(
                    text = cover.line,
                    color = OnSurface,
                    fontSize = 20.sp,
                    lineHeight = 32.sp,
                    textAlign = TextAlign.Center
                )
                // 束を作った後に消えた／改名されたノート。**このページだけ**を失敗にする。
                BookletCover.Failed -> Text(
                    text = "このノートは開けませんでした。",
                    color = ErrorText,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = entry.title,
                color = OnSurfaceMuted,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = { onRead(entry) },
                // **読めた扉のときだけ押せる。** Loading のまま押せると、
                // まだ開けるか分からないノートへ先に遷移し、ページ内に留めるはずの
                // 失敗が通常表示側の読込エラーに化ける。
                enabled = entry.cover is BookletCover.Ready,
                modifier = Modifier
                    .height(48.dp)
                    // **どのノートを開くボタンかを名前で言う。**
                    // 読み上げでは全ページが「これを読む」になり、めくっても区別が付かない。
                    // ページャは隣のページも同時に持つので、**同名のボタンが複数存在する**
                    // （テストが表示中の1件を選べなかったのもこれ）。
                    .semantics { contentDescription = "「${entry.title}」を読む" },
                colors = ButtonDefaults.buttonColors(
                    containerColor = ButtonPrimary,
                    contentColor = OnButtonPrimary
                ),
                shape = RoundedCornerShape(24.dp)
            ) { Text("これを読む") }
        }
    }
}

/**
 * 束の最後に置く1ページ。
 *
 * **自動で継ぎ足さない。** 無限に流れると手が止まる箇所が無くなり、
 * 「次々飛ばす使い方」そのものになる。明示の1タップが唯一の歯止め（→ 判断6）。
 */
@Composable
private fun DrawAgainPage(drawnCount: Int, onDrawAgain: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 8.dp),
        color = Panel,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                // **10枚と決め打たない。** 束は `min(10, 利用可能数)` なので、
                // ノートが9本以下のVaultでは画面と実際の枚数が食い違う。
                text = "ここまでの${drawnCount}枚でした。",
                color = OnSurface,
                fontSize = 17.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onDrawAgain,
                modifier = Modifier.height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ButtonSecondary,
                    contentColor = OnButtonSecondary
                ),
                border = BorderStroke(1.dp, ButtonOutlineOnGradient),
                shape = RoundedCornerShape(24.dp)
            ) { Text("もう10枚引く") }
        }
    }
}

/**
 * いま何枚目か。
 *
 * **読み上げにも出す。** 位置がスワイプでしか分からない画面なので、
 * 見えている「3 / 10」と同じことを音声でも言えるようにする（→ §9）。
 */
@Composable
private fun BookletPageIndicator(page: Int, total: Int) {
    val isDrawAgain = page >= total
    val label = if (isDrawAgain) "おわり" else "${page + 1} / $total"
    val spoken = if (isDrawAgain) "最後のページ" else "${page + 1}/${total}ページ"
    // **グラデーション直上に裸の文字を置かない。** 停止色で明るさが動くので、
    // 自前の面（Panel）を持たせてからその上の色を使う（→ VibrantTextUsageTest）。
    Box(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(color = Panel, shape = RoundedCornerShape(12.dp)) {
            Text(
                text = label,
                color = OnSurfaceMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .semantics { contentDescription = spoken },
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun BookletLoading() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = AccentText)
    }
}

@Composable
private fun BookletNotice(message: String, isError: Boolean = false) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Surface(color = Panel, shape = RoundedCornerShape(12.dp)) {
            Text(
                text = message,
                color = if (isError) ErrorText else OnSurface,
                fontSize = 15.sp,
                modifier = Modifier.padding(20.dp)
            )
        }
    }
}
