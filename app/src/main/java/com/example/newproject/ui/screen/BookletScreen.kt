package com.example.newproject.ui.screen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.ui.graphics.TransformOrigin
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
import androidx.compose.ui.zIndex
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
import kotlin.math.PI
import kotlin.math.absoluteValue
import kotlin.math.sin

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
 * **画面の外に置いてあるのは、そうしないと素のJVMから観測できないため**（→ [openFromBooklet] と同じ）。
 */
internal fun alignPager(pagerState: PagerState, target: Int) {
    if (pagerState.currentPage != target) pagerState.requestScrollToPage(target)
}

/**
 * 天綴じの繰りの寸法と向き。**1箇所に集める**（→ features/booklet_mode.md 判断10）。
 *
 * **綴じは上。** 紙は上端を蝶番にして、下端が手前へ持ち上がり、上へ抜けて裏返る。
 * レポート用紙（リーガルパッド）の束と同じ向きで、[SHEET_HINGE] がその蝶番である。
 *
 * **手触りそのものの判定は実機検証のケース表が持つ**（→ system/bearing_channels.md §7）。
 * 集めてあるのは、次に触る人がここだけを見れば済むようにするためである。
 * **ただし向きと合成は値で観測できる** — 下の純関数がそこを引き受ける。
 *
 * **総量を上げるときは判断3（冊子で深く作業させない）に当てる。** めくりが重くなると、
 * 眺めて捨てる速さが失われる。**指への追従は 1:1 のまま**にし、
 * `flingBehavior`（送りの速さそのもの）には触らない。
 */
private const val FLIP_DEGREES = 180f

/** 紙が真横を向く角度。**ここで表と裏が入れ替わり、同時に次の紙が出揃う。** */
private const val EDGE_ON_DEGREES = FLIP_DEGREES / 2f

/** 蝶番。紙の上端の中央で、天綴じの綴じ位置そのもの。 */
private val SHEET_HINGE = TransformOrigin(0.5f, 0f)

/**
 * 遠近の強さ。**紙の高さの何倍だけカメラを離すか。**
 *
 * 公式の指示は「画面の半分より大きい面を回すなら、カメラ距離は面の高さより遠くする」。
 * **寸法を直に書かず紙の高さから導く**（端末の解像度と分割画面で高さが変わる）。
 * **1 を下回らない** — 下回ると指示の外へ出て、回した像に破綻が出る。
 *
 * **上限も要る。** 遠すぎると遠近が消え、**倒れた紙がただ縦に潰れる板になる**。
 * 実際にそうなっていた（→ [sheetCameraDistance]）。
 */
internal const val CAMERA_DISTANCE_FACTOR = 1.5f

/**
 * `cameraDistance` の単位はピクセルではない。**画面の物理的な長さ（インチ相当）である。**
 *
 * ## 単位を取り違えて、遠近が丸ごと消えていた（2026-09-05）
 *
 * Compose も `RenderNode` も KDoc には「expressed in pixels」と書いてある。**どちらも実装と違う。**
 * `View.setCameraDistance` は **`densityDpi` で割ってから** `RenderNode` へ渡す
 * （Android の実装で確認）。`View` の既定は「mdpi で 1280」で、1280 ÷ 160 = **8** —
 * Compose の `DefaultCameraDistance = 8.0f` と一致する。**つまり単位はインチ相当。**
 *
 * 当初は「ピクセルなら紙の高さより大きく」と読んで `size.height * 1.5`（≒2850）を渡していた。
 * **既定の約350倍遠い＝実質無限遠**で、**遠近は最初から効いていなかった。**
 * 倒れた紙はただ縦に潰れるだけの板で、**実機の「紙が曲がる感じが足りない」はここから来ていた。**
 *
 * **KDocではなく実装で確かめる**（→ CLAUDE.md 禁止事項・docs/dev/lessons.md L60）。
 *
 * ## 何を返すか
 *
 * **紙の高さをインチへ直し、その [CAMERA_DISTANCE_FACTOR] 倍。** 高さより遠く（指示を満たす）、
 * かつ既定と同じ桁に収まる。`density` は 1dp あたりのピクセル数なので、
 * 1インチ＝[DPI_PER_DENSITY] dp から画面の dpi が出る。
 */
internal fun sheetCameraDistance(sheetHeightPx: Float, density: Float): Float =
    sheetHeightPx / (density * DPI_PER_DENSITY) * CAMERA_DISTANCE_FACTOR

/** 1インチあたりの dp。Android の密度の定義そのもの（mdpi ＝ 160dpi ＝ 等倍）。 */
private const val DPI_PER_DENSITY = 160f

/**
 * 指を離したあと、紙が残りを倒れ切るまでの硬さ。**既定（400）より柔らかい 200。**
 *
 * **送りの速さは変えていないのに、速く感じた。** 横送り版と同じスナップのまま
 * 運ぶ変位だけを紙の2%の縮みから半回転へ増やしたので、**同じ時間に大きく動く**ことになった
 * （2026-09-05 の実機前検証）。判断10 は当初「`flingBehavior` に触らない」と書いていたが、
 * **その線は動きが極小だった前提で引かれている。**
 *
 * **触るのはスナップの硬さだけ。** 送り先の枚数（`PagerSnapDistance.atMost(1)`）と
 * 勢いの減衰は既定のまま — 変えると「1回のフリックで1枚」という送りの性質そのものが動く。
 *
 * **判断3（冊子で深く作業させない）との緊張は消えていない。** 柔らかくするほど1枚あたりの
 * 待ちが増え、眺めて捨てる速さが削られる。**判定は実機ケースが持つ。**
 */
private const val SNAP_STIFFNESS = Spring.StiffnessLow

/**
 * スナップを終わらせる距離（ピクセル）。**省略しない。**
 *
 * `spring` の既定のしきい値は 0.01 で、**ページャが運ぶのはピクセル**である。
 * 省略すると、目に見えないずれを追いかけてスナップが不必要に長引く。
 * ページャ自身の既定と同じ 1px を使う。
 */
private const val SNAP_VISIBILITY_THRESHOLD_PX = 1f

/**
 * 紙が残りを倒れ切るときの進み方。**指で送っても読み上げ操作で送っても、これ1つ。**
 *
 * **手触りの入力を1つにしても、時間が違えば同じ手触りにはならない。**
 * `animateScrollToPage` の既定は `spring()`＝硬さ 1500 で、指を離したときのスナップ（200）より
 * **7.5倍硬い**。半回転を運ぶようになってからは、**読み上げ操作では途中の紙が1〜2フレームしか見えず**、
 * 実機で「指と同じ手触りとは言えない」と出た（2026-09-05 の実機検証、読み上げ操作のケース）。
 *
 * **同じ値を同じ時間で動かして、はじめて「スワイプできない利用者にも同じ手触り」になる**
 * （→ features/booklet_mode.md 判断10・§9）。
 */
private val SHEET_SETTLE_SPEC: AnimationSpec<Float> = spring(
    stiffness = SNAP_STIFFNESS,
    visibilityThreshold = SNAP_VISIBILITY_THRESHOLD_PX
)

/**
 * 積み直りの傾き。**繰り切らない。**
 *
 * 同じ蝶番・同じ向きだが、[FLIP_DEGREES] まで倒すと「めくった」と言ってしまう。
 * 束が届いたのは*めくった*からではないので、**浮いて置き直される以上のことをしない**
 * （→ features/booklet_mode.md 判断10）。
 */
private const val RESTACK_TILT_DEGREES = 22f

private val SHEET_RESTING_SHADOW = 3.dp
private val SHEET_LIFTED_SHADOW = 6.dp

/**
 * 紙が倒れている角度。**繰りと積み直りを、ここで1つに合成する。**
 *
 * `turn` は「その紙が送り出される向きへどれだけ進んだか」で、**0 が定位置、1 で送り切り**。
 * **負は「これから出てくる側」**で、そちらは倒れない — 天綴じでは、めくられるのは
 * *いま手前にある紙*だけで、下から現れる紙は平らに置かれたまま待つ。
 * **符号を捨てない。** 絶対値にすると前後の見分けが消え、戻す操作でも次の紙が倒れる。
 *
 * `restack` は積み直りで、**1 が積み終わり**（水平）。
 *
 * **合成した角度が唯一の正である。** 表裏も影もこの値から決める（→ [sheetShowsFace]・[sheetStanding]）。
 * **2つを別々に持つと、同時に起きたときだけ食い違う** — 積み直りの最中に送ると、
 * 実際の紙面は真横を越えているのに表の文字が残り、鏡像になった
 * （2026-09-05 のレビュー。**共存しうる2つは合成してから使う**）。
 *
 * **足さずに、倒れ量の大きい側を採る。** 足すと、指を止めたまま積み直りが終わるだけで
 * **紙が最大22度*起き直る* — 指と逆へ動く**。しかも `-90°` を逆向きに横切れば表裏まで切り替わる
 * （2026-09-05 の再レビュー）。大きい側を採れば、**送りが傾きを上回った時点で指が角度を所有する**。
 * 傾きのほうが大きい区間（送りが 22 度ぶんに満たないうち）では積み直りが所有し、
 * **そこは「積み直りが終わる」ことそのもの**なので起き直ってよい。
 * 傾きは 22 度しかないので、**積み直りだけで表裏が入れ替わることは無い。**
 *
 * **半回転を越えない。** 越えると紙が裏から表へ戻り始め、抜けたはずの紙がもう一度現れる。
 * 大きい側を採る形では、どちらも半回転以下なので構造的に越えない。
 *
 * 返す角度が負なのは、**負の `rotationX` が下端を手前に持ち上げる**ため
 * （Compose の回転は蝶番より下の点を +Z＝奥へ送るので、下端を手前に出すには負を与える）。
 */
internal fun sheetAngleDegrees(turn: Float, restack: Float): Float {
    val flip = FLIP_DEGREES * turn.coerceIn(0f, 1f)
    val tilt = RESTACK_TILT_DEGREES * (1f - restack.coerceIn(0f, 1f))
    return -maxOf(flip, tilt)
}

/**
 * 表を見せるか。**真横を過ぎたら裏。**
 *
 * 裏は文字を載せない紙の面である。切り替えないと、真横を越えた紙に**鏡像の文字**が出る。
 * 切り替わる瞬間の紙は真横（高さ0）なので、入れ替わりは見えない。
 *
 * **入力は合成後の角度**であって送りの進み具合ではない（→ [sheetAngleDegrees]）。
 */
internal fun sheetShowsFace(angleDegrees: Float): Boolean = angleDegrees >= -EDGE_ON_DEGREES

/**
 * 束の縁を見せるか。**持ち上げられた紙は、束を置いていく。**
 *
 * 縁は「まだ積まれている残り」であって、手に取られた1枚の一部ではない。
 * **縁は回らない**ので、めくられる紙に付けたままにすると、
 * **不透明な面が定位置に残って次の紙を覆う**（2026-09-05 のレビュー `P2-1`）。
 *
 * **消えて見えることはない。** 送り始めた瞬間には下の紙が同じ位置に composed されていて、
 * **同じ形の縁を同じ場所に描いている。** 束の縁は紙ではなく束のものなので、これで辻褄が合う。
 */
internal fun sheetShowsStack(turn: Float): Boolean = turn <= 0f

/**
 * 紙を画面の定位置へ留め置くための付け替え量（ページ数）。
 *
 * ページャは紙を送るために動かすが、**天綴じでは紙は動かない。倒れるだけである。**
 * だからページャが与えた変位を打ち消して、定位置に置き直す。
 *
 * **打ち消すのは前後1枚まで。** それより遠い紙はページャが置いた場所に残し、
 * 主軸の切り抜きに任せる。**頭打ち（`coerceIn`）にはしない** — 半端に引き寄せると、
 * 1.5枚離れた紙が画面の下半分へ顔を出す。**切り替わる点の紙は、真上へ抜け切っているか、
 * 手前の紙に完全に覆われているかのどちらか**なので、段差は見えない。
 */
internal fun sheetSlotShift(turn: Float): Float = if (turn in -1f..1f) turn else 0f

/**
 * 紙がどれだけ立っているか（影の深さ）。**真横で最大、寝ていればゼロ。**
 *
 * **入力は合成後の角度**なので、繰りでも積み直りでも同じ尺度で深くなる。
 * どちらも紙が持ち上がっている状態なので、**影のチャネルを2つに割らない。**
 */
internal fun sheetStanding(angleDegrees: Float): Float =
    sin(angleDegrees * PI / 180.0).toFloat().absoluteValue

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
                        // **束の世代。** 位置を合わせ直す契機がこれ（→ [alignPager]）。
                        drawId = state.drawId,
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
    drawId: Long,
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
    // `initialPage` に優先する。実機の往復で1枚目へ戻ったのはこの層なので、
    // **保存・復元の挙動に依存せず**、束の値を唯一の正として当て直す。
    //
    // **鍵は束の世代。** `Unit` にすると、引き直しで画面が `Loading` を挟まなかったとき
    // （ノート一覧がキャッシュから同期で返る通常経路）に **ページャが旧い束の終端に残る** —
    // 束は新しいのに「もうN枚引く」が出たままになる（2026-09-03 のレビュー）。
    // **同じ束の中のページ送りと扉の読込では世代が変わらない**ので、位置は維持される。
    LaunchedEffect(drawId) {
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
                BookletPage(entry = entries[page], turn = turn, restack = restack, onRead = onRead)
            } else {
                DrawAgainPage(
                    drawnCount = entries.size,
                    turn = turn,
                    restack = restack,
                    onDrawAgain = onDrawAgain
                )
            }
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
 * ## 繰る手触り（[turn]）
 *
 * **天綴じ。紙は上端を蝶番に、下端が持ち上がって上へ抜ける**（→ features/booklet_mode.md 判断10）。
 * レポート用紙の束をめくる向きで、[SHEET_HINGE] がその綴じ位置である。
 * 送り出される紙だけが倒れ、下から現れる紙は平らに置かれたまま待つ。
 *
 * **縁そのものは動かさない。** 縁は紙の右下へずれて描かれ、その幅は
 * [STACK_EDGE_MAX] のぶんだけ余白で確保してある。天綴じでは**めくった紙が上へ抜ける**ので、
 * 縁は下と右に覗いたままでよく、**止まっている絵は1ピクセルも変えずに済む**
 * （判断9で実機確認した佇まいがそのまま残る）。
 *
 * **倒れるのは紙だけで、束の縁は倒れない。** 縁は「まだ積まれている残り」であって、
 * 手に取られた1枚ではない。だから回転は縁を含む [Box] ではなく紙の面へ掛ける。
 *
 * **手触りは意味を運ばない。** 「これは冊子だ」と言うのは形の役目で、動きは何も名乗らない
 * （→ system/bearing_channels.md §8）。
 */
@Composable
private fun BookletSheet(
    isBundleSheet: Boolean,
    turn: () -> Float,
    restack: () -> Float,
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
            // **束は持ち上がらない。置いていかれる。** 縁は回らないので、めくられる紙に
            // 付けたままにすると**不透明な面が定位置に残って次の紙を覆う**（→ [sheetShowsStack]）。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = if (sheetShowsStack(turn())) 1f else 0f }
            ) {
                // 奥から手前へ。**最大2枚**で頭打ちにする（枚数は数えない）。
                StackEdge(offset = STACK_EDGE_MAX)
                StackEdge(offset = STACK_EDGE_MAX / 2)
            }
        }
        Surface(
            modifier = Modifier
                .fillMaxSize()
                // **読むのはこのラムダの中だけ。** 関数の本体で [turn] を呼ぶと、
                // 指を動かしている間ずっと再コンポーズが走る（ここなら描画の直前に読まれる）。
                // 影もここが持つ。Surface の `shadowElevation` は組み立て時に決まるので、
                // **送りに追従させるにはこちら側へ移す必要がある**（静止時の値は 3dp のまま）。
                .graphicsLayer {
                    // **繰りと積み直りはここで1つの角度になる**（→ [sheetAngleDegrees]）。
                    // 影もこの角度から決める。別々に持つと、同時に起きたときだけ食い違う。
                    val angle = sheetAngleDegrees(turn(), restack())
                    rotationX = angle
                    transformOrigin = SHEET_HINGE
                    // **紙は画面の半分より大きい。** 既定のカメラ距離では回した像が破綻するので、
                    // 紙の高さから導く（→ [CAMERA_DISTANCE_FACTOR]）。
                    cameraDistance = sheetCameraDistance(size.height, density)
                    shadowElevation = lerp(
                        SHEET_RESTING_SHADOW,
                        SHEET_LIFTED_SHADOW,
                        sheetStanding(angle)
                    ).toPx()
                    shape = BrowsingSheetShape
                },
            color = Panel,
            shape = BrowsingSheetShape
        ) {
            // **裏返った紙には文字を載せない。** 面の色（`Panel`）だけが残り、それが紙の裏になる。
            // 切り替えは真横（高さ0）の一点で起きるので、**中間の値を取らない** —
            // 濃度で何かを言い始めた瞬間に、年代が持つ地色のチャネルへ手を出すことになる
            // （→ system/bearing_channels.md・判断10「色とアルファを動かさない」）。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // **判定は合成後の角度で行う。** 送りの進み具合だけを見ると、
                        // 積み直りが重なった分だけ表の文字が真横を越えて残る。
                        val angle = sheetAngleDegrees(turn(), restack())
                        alpha = if (sheetShowsFace(angle)) 1f else 0f
                    }
            ) { content() }
        }
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
private fun BookletPage(
    entry: BookletEntry,
    turn: () -> Float,
    restack: () -> Float,
    onRead: (BookletEntry) -> Unit
) {
    BookletSheet(isBundleSheet = true, turn = turn, restack = restack) {
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
private fun DrawAgainPage(
    drawnCount: Int,
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
