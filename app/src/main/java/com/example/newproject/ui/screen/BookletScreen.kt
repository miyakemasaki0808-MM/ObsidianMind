package com.example.newproject.ui.screen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import com.example.newproject.model.BookletCover
import com.example.newproject.model.BookletEntry
import com.example.newproject.model.state.BookletState
import com.example.newproject.ui.component.GradientHeader
import com.example.newproject.ui.component.IconPill
import com.example.newproject.ui.theme.AccentText
import com.example.newproject.ui.theme.BrowsingSheetShape
import com.example.newproject.ui.theme.ButtonOutlineOnGradient
import com.example.newproject.ui.theme.ButtonPrimary
import com.example.newproject.ui.theme.ButtonSecondary
import com.example.newproject.ui.theme.ErrorText
import com.example.newproject.ui.theme.OnButtonPrimary
import com.example.newproject.ui.theme.OnButtonSecondary
import com.example.newproject.ui.theme.OnSurface
import com.example.newproject.ui.theme.OnSurfaceMuted
import com.example.newproject.ui.theme.Panel
import com.example.newproject.ui.theme.PanelRow
import com.example.newproject.ui.theme.ReadingGradient
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

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
 * 積み直りを再生するかどうかだけを決める。**見るのは束の世代だけ。**
 *
 * ## なぜ「`Loading` を観測できたか」で決めないのか
 *
 * 📖 は**束を作り始めてから**冊子ルートへ遷移する。ノート一覧が60秒キャッシュから
 * 同期で返ると、`Loading` は次の `Open` に上書きされて**画面には一度も届かない**。
 * 初回だけの問題でもない — 終端の「もう10枚引く」も同じ経路を通るので、
 * **キャッシュが効いている間の引き直しでは演出が出ない**（2026-09-03 のレビュー `P2-1`）。
 * **中間状態は届かないことがある。最終状態だけで判定する。**
 *
 * ## なぜ中身の比較にしないのか
 *
 * 引き直した結果が**同じ並びになることがある**（3本しかないVaultでは必ず起きる）。
 * 束が入れ替わったことは、中身ではなく世代でしか分からない。
 *
 * ## 初回と往復で再生しないこと
 *
 * 最初に見た束は「届いた」ではなく「もう在った」なので再生しない。
 * ノートから戻る往復では composition ごと作り直されるため、このオブジェクトも作り直され、
 * **戻ってきた束が「最初に見た束」になる。** 同じ理由で静かに出る。
 */
internal class BookletRestackRule {
    private var seenDrawId: Long? = null

    /** 束が画面へ届くたびに呼ぶ。**true を返したときだけ**積み直りを1回再生する。 */
    fun onBundle(drawId: Long): Boolean {
        val previous = seenDrawId
        seenDrawId = drawId
        return previous != null && previous != drawId
    }
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
    // **束が届いた瞬間だけ、紙が一度浮いて置き直される**（→ features/booklet_mode.md 判断10）。
    // 1 が「積み終わった」。
    val restack = remember { Animatable(1f) }
    // **再生するのは「この画面で束を見たあと、別の束が届いたとき」だけ。**
    // 冊子ルートへの入りは「出来事の強度」の側で、手触りの担当ではない
    // （→ system/bearing_channels.md §8）。判定そのものは [BookletRestackRule] が持つ。
    val currentState by rememberUpdatedState(state)
    // **状態を鍵にしたLaunchedEffectにしない。** ページを送るたびに `state` は別インスタンスになるので、
    // 鍵にすると**送った瞬間に効果が作り直され、アニメーションが打ち切られて紙が浮いたまま止まる。**
    // 効果は張りっぱなしにして、中で状態の移り変わりを見る。
    LaunchedEffect(Unit) {
        val rule = BookletRestackRule()
        snapshotFlow { currentState }.collect { current ->
            if (current is BookletState.Open && rule.onBundle(current.drawId)) {
                restack.snapTo(0f)
                restack.animateTo(1f, animationSpec = tween(RESTACK_MILLIS))
            }
        }
    }

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
                        // **値ではなく読み方を渡す。** ここで `restack.value` を読むと
                        // アニメーションの毎フレームで画面全体が再コンポーズになる。
                        restack = { restack.value },
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
    restack: () -> Float,
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
        // **手触りの入力はこれ1つ。** その紙が定位置からどれだけ離れているか。
        // 指のドラッグでも読み上げのカスタム操作（`animateScrollToPage`）でも同じ値が動くので、
        // **スワイプできない利用者にも同じ手触りが出る**（→ 判断10・§9）。
        // 束を作り直した直後は、まだ積み終わっていないぶんを同じ値として混ぜる。
        val lift: () -> Float = {
            val distance = ((pagerState.currentPage - page) + pagerState.currentPageOffsetFraction)
                .absoluteValue
            maxOf(distance, 1f - restack()).coerceIn(0f, 1f)
        }
        if (page < entries.size) {
            BookletPage(entry = entries[page], lift = lift, onRead = onRead)
        } else {
            DrawAgainPage(drawnCount = entries.size, lift = lift, onDrawAgain = onDrawAgain)
        }
    }

    BookletPageIndicator(page = pagerState.currentPage, total = entries.size)
}

/**
 * 冊子の紙1枚。**「眺める面」の形はここだけが決める。**
 *
 * ## なぜ通常表示と形を変えるのか
 *
 * 構造では「眺める」と「読む」を分けてあるのに、画面がその分離を見せていなかった
 * （通常表示と同じ角丸・同じ面・同じボタン形で、違うのは文字の量と下部ナビの有無だけ）。
 * **区別を担うチャネルは形**と決めてある（色は年代が持ち切る）
 * → docs/dev/system/bearing_channels.md。
 *
 * ## 3つで1つの形
 *
 * - **ほぼ直角の角** — 断ち切った紙。UIカードは丸い
 * - **四辺の余白と、下に残す地** — 画面を満たさないので「続く面」ではなく「手に持った1枚」になる
 * - **背後に控える紙の縁** — 冊子とは10枚の束で、いままで9枚を一切見せていなかった
 *
 * **縁が言うのは「この紙は束の1枚である」ことだけで、残りが何枚あるかではない**（[isBundleSheet]）。
 * だから**束の10枚には位置によらず同じ縁が出る**。最後の1枚で縁が消えると、
 * 形が残数という**別の意味**も運び始め、**形＝面の役割というチャネル割り当てが崩れる**
 * （→ docs/dev/system/bearing_channels.md）。残数はページインジケータの文字が持つ。
 *
 * **地色は触らない。** 紙の面は現行のまま、縁だけ一段沈む面を使う。
 *
 * ## 繰る手触り（[lift]）
 *
 * **判断9で置いたこの形を、そのまま時間方向へ延ばす**（→ features/booklet_mode.md 判断10）。
 * 新しい形も色も足さない。送っている最中だけ**紙が浮いて（影）わずかに縮み（大きさ）**、
 * 定まると戻る。**縮むぶん背後の縁が広く覗く**ので、束がほどけて見える。
 *
 * **縁そのものは動かさない。** 縁は紙の右下へずれて描かれ、その幅は
 * [STACK_EDGE_MAX] のぶんだけ余白で確保してある。開く向きへ動かすには余白を広げるしかなく、
 * **広げると静止時の紙の幅と中央合わせが変わる** — 実機で確認済みの佇まい（判断9）が動く。
 * **止まっている絵は1ピクセルも変えない**方を採った。
 *
 * **手触りは意味を運ばない。** 「これは冊子だ」と言うのは形の役目で、動きは何も名乗らない
 * （→ system/bearing_channels.md §8）。
 */
@Composable
private fun BookletSheet(
    isBundleSheet: Boolean,
    lift: () -> Float,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            // 四辺を見せる。下を厚くして、紙の下に地を残す。
            // **右と下は縁のぶんだけ余分に空ける** — 縁は紙より右下へ出るので、
            // ここが足りないとページの外へはみ出して隣の紙と重なる。
            // 右を `STACK_EDGE_MAX + 10dp` にすることで、紙と縁を合わせた塊が画面の中央に来る。
            .padding(
                start = 10.dp,
                end = 10.dp + STACK_EDGE_MAX,
                top = 4.dp,
                bottom = 22.dp
            )
    ) {
        if (isBundleSheet) {
            // 奥から手前へ。**最大2枚**で頭打ちにする（枚数は数えない）。
            StackEdge(offset = STACK_EDGE_MAX)
            StackEdge(offset = STACK_EDGE_MAX / 2)
        }
        Surface(
            modifier = Modifier
                .fillMaxSize()
                // **読むのはこのラムダの中だけ。** 関数の本体で [lift] を呼ぶと、
                // 指を動かしている間ずっと再コンポーズが走る（ここなら描画の直前に読まれる）。
                // 影もここが持つ。Surface の `shadowElevation` は組み立て時に決まるので、
                // **送りに追従させるにはこちら側へ移す必要がある**（静止時の値は 3dp のまま）。
                .graphicsLayer {
                    val motion = lift()
                    val scale = 1f - (1f - SHEET_LIFTED_SCALE) * motion
                    scaleX = scale
                    scaleY = scale
                    shadowElevation = lerp(SHEET_RESTING_SHADOW, SHEET_LIFTED_SHADOW, motion).toPx()
                    shape = BrowsingSheetShape
                },
            color = Panel,
            shape = BrowsingSheetShape,
            content = content
        )
    }
}

/**
 * 繰る手触りの寸法。**1箇所に集める**（→ features/booklet_mode.md 判断10）。
 *
 * **これは検査の代わりではない。** 手触りは時間の中にしかなく、値を1つ取り出しても手触りにならないので、
 * **判定は実機検証のケース表が持つ**（→ system/bearing_channels.md §7）。
 * したがって**この値を直書きへ戻す後退は、実機で触るまで誰も気づけない。**
 * 集めてあるのは、次に触る人がここだけを見れば済むようにするためである。
 *
 * **総量を上げるときは判断3（冊子で深く作業させない）に当てる。** めくりが重くなると、
 * 眺めて捨てる速さが失われる。**指への追従は 1:1 のまま**にし、
 * `flingBehavior`（送りの速さそのもの）には触らない。
 */
private const val SHEET_LIFTED_SCALE = 0.98f
private val SHEET_RESTING_SHADOW = 3.dp
private val SHEET_LIFTED_SHADOW = 6.dp

/**
 * 新しい束が積み上がるまで。**指が起こす動きではないので、送りより気持ち長い。**
 *
 * **これは時間で進む唯一の経路**なので、OSの「アニメーションを無効」設定に従って潰れる
 * （→ features/booklet_mode.md 判断10・`BookletRestackTest`）。`internal` なのは、
 * その契約を検査が同じ値で確かめるため。
 */
internal const val RESTACK_MILLIS = 320

/**
 * 背後の紙が覗く幅。**紙の余白より大きくしない。**
 *
 * 縁は紙の右下へずれて描かれるので、[BookletSheet] の右と下の余白がこれを下回ると
 * ページの外へ出て隣の紙と重なる。**値を上げるなら余白も一緒に上げる。**
 */
private val STACK_EDGE_MAX = 8.dp

/**
 * 束の背後に覗く1枚の縁。**右下へずらして、重なりがあることだけを見せる。**
 *
 * 文字を載せないので、この面はコントラスト検査の対象にならない
 * （情報を持たない装飾。枚数はインジケータの文字が持つ）。
 *
 * **明暗で分岐しない。** ここで引く面トークンは、明暗どちらでも紙の面より暗い側にある。
 */
@Composable
private fun BoxScope.StackEdge(offset: Dp) {
    Surface(
        modifier = Modifier
            .matchParentSize()
            .padding(start = offset, top = offset)
            .offset(x = offset, y = offset),
        color = PanelRow,
        shape = BrowsingSheetShape,
        shadowElevation = 1.dp
    ) {}
}

/** 1枚の扉。**代表文と、これを読むボタンだけ。** */
@Composable
private fun BookletPage(entry: BookletEntry, lift: () -> Float, onRead: (BookletEntry) -> Unit) {
    BookletSheet(isBundleSheet = true, lift = lift) {
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
private fun DrawAgainPage(drawnCount: Int, lift: () -> Float, onDrawAgain: () -> Unit) {
    // **これは束の紙ではない。** 10枚のどれでもない別種のページなので縁を持たない。
    // **「後ろに何も無いから」ではない** — その理由で分けると、最後の1枚も縁を失い、
    // 残数を形で数えることになる（→ features/booklet_mode.md 判断9）。
    BookletSheet(isBundleSheet = false, lift = lift) {
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
