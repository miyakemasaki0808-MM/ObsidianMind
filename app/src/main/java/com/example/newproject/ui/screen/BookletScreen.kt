package com.example.newproject.ui.screen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.newproject.model.BookletCover
import com.example.newproject.model.BookletEntry
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteFieldClassification
import com.example.newproject.model.NoteField
import com.example.newproject.model.state.BookletMode
import com.example.newproject.model.state.BookletState
import com.example.newproject.model.state.WeaveBlockedReason
import com.example.newproject.model.state.WeaveState
import com.example.newproject.model.state.canWeave
import com.example.newproject.model.state.showsModeToggle
import com.example.newproject.model.state.visibleBundle
import com.example.newproject.ui.component.GradientHeader
import com.example.newproject.ui.component.IconPill
import com.example.newproject.ui.theme.AccentText
import com.example.newproject.ui.theme.ButtonOutlineOnGradient
import com.example.newproject.ui.theme.ButtonPrimary
import com.example.newproject.ui.theme.ButtonSecondary
import com.example.newproject.ui.theme.ErrorText
import com.example.newproject.ui.theme.OnButtonPrimary
import com.example.newproject.ui.theme.OnButtonSecondary
import com.example.newproject.ui.theme.noteFieldPaper
import com.example.newproject.ui.theme.OnSurface
import com.example.newproject.ui.theme.OnSurfaceFaint
import com.example.newproject.ui.theme.OnSurfaceMuted
import com.example.newproject.ui.theme.Panel
import com.example.newproject.ui.theme.PanelChip
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
 * ページャを指定の位置へ合わせ直す。**送りではなく、位置の付け替えである。**
 *
 * `scrollToPage` ではなく `requestScrollToPage` を使う。理由は2つ。
 *
 * - **まだ測っていないページャにも効く。** 復元直後は寸法が決まっておらず、
 *   測ってから動かす経路は待たされる。要求として置けば次の測定で適用される
 *   （本文へ渡すときの [openFromBooklet] が `requestScrollToItem` を使うのと同じ理由）。
 * - **積み直りと競合しない。** 時間で進まないので、演出を打ち切らないし打ち切られもしない。
 *
 * **ページ番号が合っていても必ず要求する。** **ページ番号は位置の一部でしかない** — めくり途中の紙は
 * 同じ `currentPage` のまま `currentPageOffsetFraction` を持ち、指を離した後の送りも走っている。
 * **モード切替は任意の時点で起こせる**ので、番号だけで判定すると、前の束のめくり量と走行中の送りを
 * 引き継いでしまう。
 * 呼び出し元は `LaunchedEffect(bundleId)` だけなので、**要求が起きるのは束が変わったときだけ**である。
 *
 * **画面の外に置いてあるのは、そうしないと素のJVMから観測できないため**（→ [openFromBooklet] と同じ）。
 */
internal fun alignPager(pagerState: PagerState, target: Int) {
    pagerState.requestScrollToPage(target)
}

/**
 * めくりが残りを走り切るときの進み方。**指で送っても読み上げ操作で送っても、これ1つ。**
 *
 * **手触りの入力を1つにしても、時間が違えば同じ手触りにはならない。**
 * `animateScrollToPage` の既定は `spring()`＝硬さ 1500 で、指を離したときのスナップより
 * はるかに硬く、**読み上げ操作では途中の紙が1〜2フレームしか見えない。**
 *
 * **同じ値を同じ時間で動かして、はじめて「スワイプできない利用者にも同じ手触り」になる**
 * （→ features/booklet_mode.md 判断10・§9）。
 */
private val SHEET_SETTLE_SPEC: AnimationSpec<Float> = tween(
    durationMillis = SHEET_SETTLE_MILLIS,
    easing = FastOutSlowInEasing
)

/**
 * 積み直りを再生するかどうかだけを決める。**見るのは束の世代だけ。**
 *
 * ## なぜ「`Loading` を観測できたか」で決めないのか
 *
 * 📖 は**束を作り始めてから**冊子ルートへ遷移する。ノート一覧が60秒キャッシュから
 * 同期で返ると、`Loading` は次の `Open` に上書きされて**画面には一度も届かない**。
 * 初回だけの問題でもない — 終端の「もう10枚引く」も同じ経路を通るので、
 * **キャッシュが効いている間の引き直しでは演出が出ない。**
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
    private var seenBundleId: Long? = null

    /**
     * 束が画面へ届くたびに呼ぶ。**true を返したときだけ**積み直りを1回再生する。
     *
     * **入力は「表示中の束の世代」**なので、引き直しだけでなく
     * **引く⇄編むの切り替えでも再生される**（→ features/booklet_mode.md 判断12）。
     * 両者の紙面は同じ形なので、無音で入れ替わると切り替わったことを見落とす。
     */
    fun onBundle(bundleId: Long): Boolean {
        val previous = seenBundleId
        seenBundleId = bundleId
        return previous != null && previous != bundleId
    }
}

/**
 * 新しい束が積み上がるまで。**指が起こす動きではないので、送りより気持ち長い。**
 *
 * **時間で進むので、OSの「アニメーションを無効」設定に従って潰れる**
 * （→ features/booklet_mode.md 判断10・`BookletRestackTest`）。`internal` なのは、
 * その契約を検査が同じ値で確かめるため。
 *
 * **時間で進むのはここだけではない。** 読み上げのカスタム操作は `animateScrollToPage` で送るので、
 * 同じく倍率0では途中が省かれる。指が進める変化だけが設定の対象外である。
 */
internal const val RESTACK_MILLIS = 320

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
    // 索引A（ノート → 分野）。**冊子は `ref` で引くだけ**で、走査もAI生成も起こさない
    // （→ features/note_field_color.md 判断10・判断17）。未準備なら空の地図が来て、
    // 全ページが無彩色で開く。**待たせない。**
    noteFields: Map<DocumentRef, NoteFieldClassification>,
    onPageSettled: (Int) -> Unit,
    onRead: (BookletEntry) -> Unit,
    onDrawAgain: () -> Unit,
    onModeChange: (BookletMode) -> Unit,
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
            if (current is BookletState.Open && rule.onBundle(current.visibleBundle.bundleId)) {
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
            subtitle = "めくって、読みたい1枚",
            trailing = { IconPill(symbol = "✕", contentDescription = "冊子を閉じる") { onExit() } }
        )

        when (state) {
            is BookletState.Open -> {
                // **種が無いときだけ出さない。** 行き先が1つしか無いのに選択肢を見せない。
                if (state.showsModeToggle) BookletModeToggle(state = state, onModeChange = onModeChange)
                val bundle = state.visibleBundle
                if (bundle.entries.isEmpty()) {
                    BookletNotice("引けるノートがありません。")
                } else {
                    BookletPager(
                        noteFields = noteFields,
                        entries = bundle.entries,
                        // **束が覚えているページから開く。** 画面ローカルに持つと、
                        // 通常表示へ渡って戻る往復でここだけ1枚目へ戻る。
                        // **束ごとに別々**なので、引く⇄編むを行き来しても双方の位置が残る。
                        initialPage = bundle.page,
                        // **束の世代。** 位置を合わせ直す契機がこれ（→ [alignPager]）。
                        bundleId = bundle.bundleId,
                        mode = state.mode,
                        weaveSeedTitle = (state.weave as? WeaveState.Ready)?.seedTitle,
                    redrawError = state.redrawError,
                        // **値ではなく読み方を渡す。** ここで `restack.value` を読むと
                        // アニメーションの毎フレームで画面全体が再コンポーズになる。
                        restack = { restack.value },
                        onPageSettled = onPageSettled,
                        onRead = onRead,
                        onDrawAgain = onDrawAgain
                    )
                }
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
 * `rememberPagerState` だけに置くと**ノートとの往復で1枚目へ戻る**。
 * 「戻れば同じ10枚が同じページ位置」は束とページ位置の2つで1つの条件なので、
 * 寿命の同じ場所へ揃える（→ 判断6・[BookletState.Open]）。
 *
 * ここから先の操作は [onPageSettled] で束へ返す。**状態から毎フレーム駆動はしない** —
 * 指で送っている最中に外から位置を当てると、めくりと競合する。
 */
@Composable
private fun ColumnScope.BookletPager(
    entries: List<BookletEntry>,
    noteFields: Map<DocumentRef, NoteFieldClassification>,
    initialPage: Int,
    bundleId: Long,
    mode: BookletMode,
    weaveSeedTitle: String?,
    redrawError: String?,
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

    // **束が覚えている位置へ、入るたび・束が変わるたびに合わせ直す。**
    // `rememberPagerState` は `rememberSaveable` なので、復元された値が
    // `initialPage` に優先する。往復で1枚目へ戻るのはこの層なので、
    // **保存・復元の挙動に依存せず**、束の値を唯一の正として当て直す。
    //
    // **鍵は束の世代。** `Unit` にすると、引き直しで画面が `Loading` を挟まなかったとき
    // （ノート一覧がキャッシュから同期で返る通常経路）に **ページャが旧い束の終端に残る** —
    // 束は新しいのに「もうN枚引く」が出たままになる。
    // **同じ束の中のページ送りと扉の読込では世代が変わらない**ので、位置は維持される。
    // **引く⇄編むの切り替えもここを通る** — 世代は束ごとに別なので、
    // 切り替えた先が覚えている位置へ合う（→ 判断12）。
    LaunchedEffect(bundleId) {
        alignPager(pagerState, initialPage.coerceIn(0, pageCount - 1))
    }

    // LaunchedEffect は長寿命なので、外から来たラムダは必ず現在値を通す（→ lessons L34）。
    val settled by rememberUpdatedState(onPageSettled)
    LaunchedEffect(pagerState.currentPage, entries.size) {
        settled(pagerState.currentPage)
    }

    // **天綴じ。** 下端を上へ送ると次の紙が出る（→ features/booklet_mode.md 判断6）。
    // `VerticalPager` の既定（上スワイプで次へ）が手の動きとそのまま一致するので、反転させない。
    VerticalPager(
        state = pagerState,
        // **指を離したあとの倒れ切りだけを柔らかくする**（→ [SNAP_STIFFNESS]）。
        // 送り先の枚数と勢いの減衰は既定のまま。
        flingBehavior = PagerDefaults.flingBehavior(
            state = pagerState,
            snapAnimationSpec = SHEET_SETTLE_SPEC
        ),
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .padding(top = 16.dp)
            // スワイプ以外でもめくれるようにする。スイッチアクセスや読み上げ操作では
            // スワイプがページ送りにならないため、これが無いと最初の1枚から動けない。
            .semantics {
                // **ページ位置は読み上げにだけ残す**（→ [bookletPagePosition]）。
                // 画面には出さないが、**送ったあとに「いま何枚目か」を言えないと、
                // スワイプできない利用者は自分がどこにいるか分からない。**
                stateDescription = bookletPagePosition(pagerState.currentPage, entries.size)
                customActions = listOf(
                    CustomAccessibilityAction("次のページへ") {
                        val next = pagerState.currentPage + 1
                        if (next >= pageCount) false
                        else {
                            scope.launch { pagerState.animateScrollToPage(next, animationSpec = SHEET_SETTLE_SPEC) }
                            true
                        }
                    },
                    CustomAccessibilityAction("前のページへ") {
                        val previous = pagerState.currentPage - 1
                        if (previous < 0) false
                        else {
                            scope.launch {
                                pagerState.animateScrollToPage(
                                    previous,
                                    animationSpec = SHEET_SETTLE_SPEC
                                )
                            }
                            true
                        }
                    }
                )
            }
    ) { page ->
        // **手触りの入力はこれ1つ。** その紙が定位置からどれだけ・どちら向きに離れているか。
        // 指のドラッグでも読み上げのカスタム操作（`animateScrollToPage`）でも同じ値が動くので、
        // **スワイプできない利用者にも同じ手触りが出る**（→ 判断10・§9）。
        //
        // **正は「送り出される側」、負は「これから出てくる側」。** 絶対値にすると向きが消え、
        // 戻す操作でも次の紙が倒れる（→ [sheetAngleDegrees]）。
        val turn: () -> Float = {
            (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                // **めくられる紙が上に来る。** ページャは紙を番号順に置くので、既定のままだと
                // *手前の紙が次の紙の下*に描かれ、倒しても何も起きていないように見える。
                // 番号が小さいほど上へ持ち上げると、送りでも戻しでも常に正しい重なりになる。
                // （`Modifier.zIndex` はページャの子でも効く — ノードのzは
                //  内側と全modifierのzの和で決まるため。ここが効かなければ天綴じは成立しない）
                .zIndex(-page.toFloat())
                // **紙は動かない。倒れるだけ。** ページャが送るために与えた変位を打ち消して、
                // 定位置へ置き直す（→ [sheetSlotShift]）。
                .graphicsLayer { translationY = size.height * sheetSlotShift(turn()) }
        ) {
            if (page < entries.size) {
                BookletPage(
                    entry = entries[page],
                    field = noteFields[entries[page].ref]?.field,
                    turn = turn,
                    restack = restack,
                    onRead = onRead
                )
            } else if (mode == BookletMode.Weave) {
                // **編む側に「もう10枚編む」は無い。** 編みは決定的なので同じ10枚が出る
                // （→ features/booklet_mode.md 判断12）。数だけを最後に1回言う。
                WeaveEndPage(
                    seedTitle = weaveSeedTitle,
                    wovenCount = entries.size,
                    turn = turn,
                    restack = restack
                )
            } else {
                DrawAgainPage(
                    drawnCount = entries.size,
                    redrawError = redrawError,
                    turn = turn,
                    restack = restack,
                    onDrawAgain = onDrawAgain
                )
            }
        }
    }

}

/**
 * 引く⇄編むのトグル。**ヘッダの直下・紙の上に置く。**
 *
 * ## なぜここなのか
 *
 * 紙の面の中へ入れると、[booklet_mode 判断9] で決めた「眺める面の形」へ操作子を足すことになり、
 * **区別を担うチャネル（面の形）を取り合う**（→ system/bearing_channels.md）。
 * ヘッダの ✕ の隣も採らない — 閉じるボタンと並ぶと誤タップの形になり、
 * **種のノート名を出す幅が無い**。名前が出ないと「何から編むのか」が画面から消える。
 *
 * ## 3通りの見せ方
 *
 * 種が無ければ**そもそも呼ばれない**（[BookletState.Open.showsModeToggle]）。
 * 種はあるが編めないときは**出すが押せない**うえで、理由を1行添える —
 * 出さないと「なぜ押せないのか」がどこにも無い。
 *
 * **グラデーション直上なので輪郭線を必ず描く**（→ NoteActionButtons と同じ理由）。
 * **塗りに `ButtonPrimary` を使わない** — 紙の上の「これを読む」が主なので、
 * ここが同じ色を取ると主役が2つになる。
 */
@Composable
private fun BookletModeToggle(
    state: BookletState.Open,
    onModeChange: (BookletMode) -> Unit
) {
    val weave = state.weave
    val seedTitle = when (weave) {
        is WeaveState.Blocked -> weave.seedTitle
        is WeaveState.Ready -> weave.seedTitle
        WeaveState.NoSeed -> return
    }
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BookletModeChip(
                label = "引く",
                selected = state.mode == BookletMode.Draw,
                enabled = true,
                modifier = Modifier.weight(1f)
            ) { onModeChange(BookletMode.Draw) }
            BookletModeChip(
                label = "${seedTitle}から編む",
                selected = state.mode == BookletMode.Weave,
                enabled = state.canWeave,
                modifier = Modifier.weight(1f)
            ) { onModeChange(BookletMode.Weave) }
        }
        if (weave is WeaveState.Blocked) {
            Text(
                text = weaveBlockedMessage(weave.reason),
                color = OnSurfaceMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

@Composable
private fun BookletModeChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .height(40.dp)
            // **選ばれているかを読み上げにも出す。** 色と塗りだけだと、
            // 見えない利用者にはどちらを見ているのか分からない。
            .semantics { stateDescription = if (selected) "選択中" else "未選択" },
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) ButtonSecondary else PanelChip,
            contentColor = if (selected) OnButtonSecondary else OnSurfaceMuted,
            // 無効時の既定はテーマ由来のαなので、明示して不透明に保つ。
            disabledContainerColor = PanelChip,
            disabledContentColor = OnSurfaceFaint
        ),
        border = BorderStroke(1.dp, ButtonOutlineOnGradient),
        shape = RoundedCornerShape(20.dp)
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            maxLines = 1,
            // 長いノート名でもトグルの高さを変えない。名前の頭は残る。
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 編めない理由。**「探している最中」と「見つからなかった」を同じ文にしない** —
 * 待てば変わるかどうかで、利用者の次の行動が違う。
 *
 * **走行中だけは、次の行動まで書く。** 種は📖を押した一瞬のコピーなので、
 * **この冊子を開いたまま待っても編めるようにはならない**（→ 判断12）。
 * 「探しています」だけだと待てば編めると読めるので、**開き直す**ところまで言う。
 */
private fun weaveBlockedMessage(reason: WeaveBlockedReason): String = when (reason) {
    WeaveBlockedReason.Pending -> "関連ノートをまだ探しています。冊子を開き直すと編めます。"
    WeaveBlockedReason.Empty -> "関連するノートが見つかりませんでした。"
}

/** 1枚の扉。**代表文と、これを読むボタンだけ。** */
@Composable
private fun BookletPage(
    entry: BookletEntry,
    field: NoteField?,
    turn: () -> Float,
    restack: () -> Float,
    onRead: (BookletEntry) -> Unit
) {
    BookletSheet(
        isBundleSheet = true,
        turn = turn,
        restack = restack,
        paper = noteFieldPaper(field)
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
            // **色以外の手がかり（WCAG 1.4.1）。** 紙の色だけで分野を伝えると、
            // 色を灰色にした瞬間に情報が消える（→ ui_design_principles §1）。
            // **分野が無いページには何も出さない** — 「未分類」と書くと、
            // 判定がまだなのか該当なしなのかを内部状態として見せることになる。
            if (field != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = field.label,
                    color = OnSurfaceMuted,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )
            }
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
private fun DrawAgainPage(
    drawnCount: Int,
    redrawError: String?,
    turn: () -> Float,
    restack: () -> Float,
    onDrawAgain: () -> Unit
) {
    // **これは束の紙ではない。** 10枚のどれでもない別種のページなので縁を持たない。
    // **「後ろに何も無いから」ではない** — その理由で分けると、最後の1枚も縁を失い、
    // 残数を形で数えることになる（→ features/booklet_mode.md 判断9）。
    BookletSheet(isBundleSheet = false, turn = turn, restack = restack) {
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
            if (redrawError != null) {
                // **理由と再試行を同じ紙に置く。** 引き直しを押したのはこのページなので、
                // 失敗もここで受け取るのが最短である（→ features/booklet_mode.md 判断12）。
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = redrawError,
                    color = ErrorText,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
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
 * 編む束の最後に置く1ページ。**ボタンは無い。**
 *
 * **「もう10枚編む」を置かない**のは、編みが決定的だからである — 押しても同じ10枚が出る
 * （→ features/booklet_mode.md 判断12）。置くのは数だけで、引く側の
 * 「ここまでの N 枚でした。」と対になる。
 *
 * **数を出すこと自体には意味がある。** 水増ししない設計なので束は10枚未満になり得るが、
 * 終端で数を言わないと「ここで関連が切れた」のか「まだ読み込んでいる」のかが分からない。
 */
@Composable
private fun WeaveEndPage(
    seedTitle: String?,
    wovenCount: Int,
    turn: () -> Float,
    restack: () -> Float
) {
    // **これは束の紙ではない。** 10枚のどれでもない別種のページなので縁を持たない
    // （→ [DrawAgainPage] と同じ理由）。
    BookletSheet(isBundleSheet = false, turn = turn, restack = restack) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                // 種の名前が取れない経路は無いが、**文が壊れるより数を優先する。**
                text = if (seedTitle.isNullOrBlank()) "編めたのは${wovenCount}枚でした。"
                else "「${seedTitle}」から編んだ${wovenCount}枚でした。",
                color = OnSurface,
                fontSize = 17.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * いま何枚目か。**読み上げにだけ出す文言。**
 *
 * ## 画面には出さない
 *
 * 「5 / 10」の表示は**冊子には要らない**。冊子は**選ぶ前の前動作**であって
 * 眺めて捨てる場なので、**途中で数えさせない**（→ features/booklet_mode.md 判断9）。
 * **数は最後に1回だけ言う** — 終端の「ここまでの N 枚でした。」がそれである。
 *
 * ## それでも読み上げには残す
 *
 * **位置がスワイプでしか分からない画面**なので、消すと
 * **スワイプできない利用者は自分がどこにいるか分からなくなる。**
 * 見た目を外すことと、位置を伝えないことは別である。
 * ページャの `stateDescription` に載せるので、**送ったあとに読み上げられる。**
 *
 * **純関数にしてあるのは、文言を素のJVMから確かめられるようにするため。**
 */
internal fun bookletPagePosition(page: Int, total: Int): String =
    if (page >= total) "最後のページ" else "${page + 1}/${total}ページ"

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
