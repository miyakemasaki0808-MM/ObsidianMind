package com.example.newproject.ui.screen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.newproject.model.BookletCover
import com.example.newproject.model.BookletEntry
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
import com.example.newproject.ui.theme.BrowsingSheetShape
import com.example.newproject.ui.theme.ButtonOutlineOnGradient
import com.example.newproject.ui.theme.ButtonPrimary
import com.example.newproject.ui.theme.ButtonSecondary
import com.example.newproject.ui.theme.ErrorText
import com.example.newproject.ui.theme.OnButtonPrimary
import com.example.newproject.ui.theme.OnButtonSecondary
import com.example.newproject.ui.theme.OnSurface
import com.example.newproject.ui.theme.OnSurfaceFaint
import com.example.newproject.ui.theme.OnSurfaceMuted
import com.example.newproject.ui.theme.Panel
import com.example.newproject.ui.theme.PanelChip
import com.example.newproject.ui.theme.PanelRow
import com.example.newproject.ui.theme.ReadingGradient
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.hypot
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
 * **ページ番号が合っていても必ず要求する。** 以前は `currentPage != target` のときだけ
 * 動かしていたが、**ページ番号は位置の一部でしかない** — めくり途中の紙は
 * 同じ `currentPage` のまま `currentPageOffsetFraction` を持ち、指を離した後の送りも走っている。
 * 引き直しは必ず終端→先頭でページ番号が変わるので露呈しなかったが、
 * **モード切替は任意の時点で起こせる**ので、同じ番号のまま前の束のめくり量と走行中の送りを
 * 引き継いでしまう（2026-09-07 のレビュー `P2-3`）。
 * 呼び出し元は `LaunchedEffect(bundleId)` だけなので、**要求が起きるのは束が変わったときだけ**である。
 *
 * **画面の外に置いてあるのは、そうしないと素のJVMから観測できないため**（→ [openFromBooklet] と同じ）。
 */
internal fun alignPager(pagerState: PagerState, target: Int) {
    pagerState.requestScrollToPage(target)
}

// ── 天綴じのめくり（→ features/booklet_mode.md 判断10・判断11）─────────────
//
// **綴じは上。** 紙は置かれたまま、**右下の角が折り返り、折り目が左上へ走って**めくれていく。
// レポート用紙（リーガルパッド）の束を、右手で角からめくる向きである。
// **手触りそのものの判定は実機検証のケース表が持つ**（→ system/bearing_channels.md §7）。
// 寸法をここへ集めてあるのは、次に触る人がここだけを見れば済むようにするためである。

/**
 * 蝶番。紙の上端の中央で、天綴じの綴じ位置そのもの。
 *
 * `internal` なのは、**倒れる向きの検査が本番と同じ蝶番で回す**ため
 * （→ `BookletSheetPerspectiveTest`）。写しを持つと、蝶番だけ動いたときに検査が付いてこない。
 */
internal val SHEET_HINGE = TransformOrigin(0.5f, 0f)

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
 * `cameraDistance` の単位は**画素でも画面のインチでもない。渡した値の 72 倍が画素になる。**
 *
 * ## 2度取り違えた（2026-09-05）
 *
 * | 版 | 渡していた値 | 実効距離（紙の高さ比） |
 * |---|---|---|
 * | 当初 | 紙の高さ（画素） | **約 350 倍**（実質無限遠。遠近が丸ごと消えていた） |
 * | 1度目の訂正 | 高さをインチへ直した値 | **約 0.28 倍**（近すぎて、23度を越えると紙の下端がカメラの裏へ回る） |
 * | 現在 | 高さ × [CAMERA_DISTANCE_FACTOR] ÷ 72 | **1.5 倍**（設計どおり） |
 *
 * **1度目の訂正は、`View.setCameraDistance` が `densityDpi` で割ってから `RenderNode` へ渡すことを
 * 根拠にしていた。** そこまでは正しいが、**Compose の `ViewLayer` はその割り算を打ち消すために
 * 渡す前に `densityDpi` を掛けている** ので、`View` の実装から Compose の単位は決まらない。
 * 素通しする `RenderNodeLayer` のほうが本筋で、そこから先は `RenderNode` の中である。
 *
 * **だから実測した**（`BookletSheetPerspectiveTest`）。倒した面の投影された高さから実効距離を逆算すると、
 * **渡した値の 72 倍が画素**になる。**画面の密度を 390 から 320 へ変えても実効距離は同じ画素数**だったので、
 * 密度には依らない（Compose の既定 8 が 576 画素にあたる）。
 *
 * **KDocも他所の実装も根拠にならない。画素を数えるまで単位は決まらない**
 * （→ docs/dev/lessons.md L60）。
 *
 * ## 何を返すか
 *
 * **紙の高さの [CAMERA_DISTANCE_FACTOR] 倍が実効距離になる値。** 密度を受け取らないのは、
 * **受け取ると「密度で変わる」と読めてしまう**ためである（1度目の訂正はそう書いてあった）。
 */
internal fun sheetCameraDistance(sheetHeightPx: Float): Float =
    sheetHeightPx * CAMERA_DISTANCE_FACTOR / CAMERA_UNIT_PX

/**
 * `cameraDistance` の 1 が何画素になるか。**実測値**（→ [sheetCameraDistance]）。
 *
 * `internal` なのは、**検査が同じ値で「実効距離が高さの指定倍になる」ことを確かめる**ため。
 */
internal const val CAMERA_UNIT_PX = 72f

/**
 * 指を離したあと、めくりが残りを走り切るまでの時間。**ゆっくり、紙をめくる速さ。**
 *
 * ## 3度直している
 *
 * | 版 | 進み方 | 実機で言われたこと |
 * |---|---|---|
 * | 横送り | ページャ既定（硬さ 400） | — |
 * | 天綴じ・半回転 | 同じ既定のまま | **「捲れる速さが早すぎる」**（運ぶ変位だけ増やしたため） |
 * | 柔らかいばね | 硬さ 200 | まだ速い |
 * | 現在 | **時間を決めた [SHEET_SETTLE_MILLIS] ミリ秒** | 「**ゆっくりにしたい。紙を捲るイメージ**」 |
 *
 * **ばねから時間へ変えた。** ばねは「どれだけ残っているか」で速さが決まるので、
 * **少しだけ送って離したときと、半分送って離したときで、かかる時間が変わる。**
 * めくりは*紙をめくる動作*なので、**どこで離しても同じ速さで走り切るほうが紙らしい。**
 *
 * **減速して終わる**（`FastOutSlowInEasing`）。紙が置かれるときは止まり際が遅い。
 *
 * **触るのはスナップの進み方だけ。** 送り先の枚数（`PagerSnapDistance.atMost(1)`）と
 * 勢いの減衰は既定のまま — 変えると「1回のフリックで1枚」という送りの性質そのものが動く。
 *
 * **判断3（冊子で深く作業させない）との緊張は消えていない。** 遅くするほど1枚あたりの
 * 待ちが増え、眺めて捨てる速さが削られる。**判定は実機ケースが持つ。**
 */
internal const val SHEET_SETTLE_MILLIS = 620

/**
 * めくりが残りを走り切るときの進み方。**指で送っても読み上げ操作で送っても、これ1つ。**
 *
 * **手触りの入力を1つにしても、時間が違えば同じ手触りにはならない。**
 * `animateScrollToPage` の既定は `spring()`＝硬さ 1500 で、指を離したときのスナップより
 * はるかに硬い。半回転を運ぶようになってからは、**読み上げ操作では途中の紙が1〜2フレームしか見えず**、
 * 実機で「指と同じ手触りとは言えない」と出た（2026-09-05）。
 *
 * **同じ値を同じ時間で動かして、はじめて「スワイプできない利用者にも同じ手触り」になる**
 * （→ features/booklet_mode.md 判断10・§9）。
 */
private val SHEET_SETTLE_SPEC: AnimationSpec<Float> = tween(
    durationMillis = SHEET_SETTLE_MILLIS,
    easing = FastOutSlowInEasing
)

/**
 * 積み直りの傾き。**繰り切らない。**
 *
 * 半回転まで倒すと「めくった」と言ってしまう。
 * 束が届いたのは*めくった*からではないので、**浮いて置き直される以上のことをしない**
 * （→ features/booklet_mode.md 判断10）。
 */
private const val RESTACK_TILT_DEGREES = 22f

private val SHEET_RESTING_SHADOW = 3.dp
private val SHEET_LIFTED_SHADOW = 6.dp

/**
 * 紙が倒れている角度。**いま倒すのは積み直りだけである。**
 *
 * ## 繰りは角度を持たなくなった（2026-09-06）
 *
 * めくりは**折り目が斜めに走る**形になったので（→ [peelFlatPolygon]）、
 * **送りの進み具合はもう紙を倒さない。** 紙は置かれたまま、角だけが折り返る。
 *
 * **これで、繰りと積み直りが1つの値を取り合う構図そのものが消えた。**
 * 2026-09-05 に「足すか、大きい側を採るか」で2度直した合成は、
 * **同じチャネル（角度）を2つが使っていたこと**が原因だった。いまは別のチャネルなので、
 * 積み直りの傾きとめくりの折り目は**同時に起きても食い違わない。**
 *
 * `restack` は積み直りで、**1 が積み終わり**（水平）。**繰り切らない** —
 * 束が届いたのは*めくった*からではないので、**浮いて置き直される以上のことをしない**
 * （[RESTACK_TILT_DEGREES]）。
 *
 * **正の角度が下端を手前に持ち上げる**（Compose の回転は蝶番より下の点を +Z ＝手前へ送る）。
 * 負にすると紙が束の中へ沈む向きになる — **2026-09-05 に実機でそうなっていた**。
 * **向きは値に現れない**ので `BookletSheetPerspectiveTest` が画素で見る（→ docs/dev/lessons.md L61）。
 */
internal fun sheetTiltDegrees(restack: Float): Float =
    RESTACK_TILT_DEGREES * (1f - restack.coerceIn(0f, 1f))

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
 * 倒れた紙が枠から出ないための縮小率。**遠近で広がったぶんだけ、絵ごと引く。**
 *
 * ## 何に効くのか（2026-09-06 以降）
 *
 * **めくりは回転ではなくなった**（折り目が走る → [peelFlatPolygon]）ので、紙が倒れるのは
 * **積み直りの傾きだけ**である。それでも 22 度で 1.33 倍に広がり、画面の外へ出る。
 *
 * 手前へ出た点は `d / (d - z)` 倍に広がる。紙の高さを 1 とすると `d` は
 * [CAMERA_DISTANCE_FACTOR]（1.5）で、紙の左右に空いている余白は合わせて 1.05 倍ぶんしかない。
 * **定数の調整では出口が無い** — 収めるには [CAMERA_DISTANCE_FACTOR] を 15 前後まで上げることになり、
 * それは docs/dev/lessons.md L60 で直したばかりの「遠すぎて遠近が消える」側へ戻るだけである。
 *
 * ## 何を返すか
 *
 * `(d - z) / d`。掛けると**投影後の最大幅がちょうど静止時の幅になる**ので、
 * 紙の影は静止時の footprint を横へ出ない。**端末の幅も分割画面も見ない。**
 *
 * **縮小は回転より外側に置く**（[BookletSheet] の枠）。投影された絵をそのまま縮めるので、
 * **台形の比は変わらない** — 遠近の見え方は変わらず、大きさだけが枠に入る。
 * **静止時は 1** — 判断9 で実機確認した絵を1ピクセルも変えない。
 */
internal fun sheetFitScale(angleDegrees: Float): Float =
    (CAMERA_DISTANCE_FACTOR - sheetStanding(angleDegrees)) / CAMERA_DISTANCE_FACTOR

/**
 * めくりの進み具合。**0 で角に触れておらず、1 で紙が渡り切る。**
 *
 * 送り出される側だけがめくれる（負は「これから出てくる紙」で、平らに置かれたまま待つ）。
 */
internal fun sheetPeel(turn: Float): Float = turn.coerceIn(0f, 1f)

/**
 * 折り目より手前 — **まだめくれていない側の紙の形。**
 *
 * ## めくりは回転ではなく折り目である（2026-09-06）
 *
 * **紙をどう曲げても「めくった」にはならなかった。** 前の版は紙全体を弓なりにして上端を軸に倒したが、
 * オーナーの体感は「**紙をカールさせるのではなく、捲ったときにカールさせたい。
 * 右下から左上にめがけて捲るイメージ**」だった（→ features/booklet_mode.md 判断11）。
 *
 * **本のページをめくるとき、紙は全体としては曲がらない。** 角を持ち上げると
 * **そこだけが折り返り、折り目が紙を斜めに横切って走っていく。**
 * 折り目より向こうは机に置かれたまま動かない。
 *
 * だから、めくりは**折り目1本の位置**で表せる。
 * 折り目は**右下の角から左上の角へ**、対角線に沿って進む（[peelFoldDistance]）。
 *
 * ## 返すもの
 *
 * 折り目の向こう側（まだめくれていない側）に残る紙の形を、**多角形の頂点**で返す。
 * ここに紙の表（文字）を描く。**角丸は形の側が持つ**ので、ここでは扱わない。
 *
 * `peel` が 0 なら紙全体、1 なら空になる。
 */
internal fun peelFlatPolygon(width: Float, height: Float, peel: Float): List<Offset> {
    val fold = peelFoldDistance(width, height, peel) ?: return sheetCorners(width, height)
    return clipToHalfPlane(sheetCorners(width, height)) { point ->
        foldOffset(point, width, height) - fold
    }
}

/**
 * 折り目の手前 — **めくれて裏返った角の形。**
 *
 * **折り返した紙は、折り目を鏡にした像である。** めくれた領域を折り目で反転させると、
 * それがそのまま「持ち上がって裏を見せている紙」になる。
 * **裏なので文字は載らない**（→ features/booklet_mode.md 判断10「色を動かさない」）。
 *
 * **紙の枠から出た分は落とす。** 実際の本ならページは外へはみ出すが、
 * 冊子の紙は画面の中に置かれた1枚なので、**はみ出しはそのまま画面外への流出になる**
 * （→ [sheetFitScale] と同じ理由）。落としても、そこはもう次の紙が見えている。
 */
internal fun peelFlapPolygon(width: Float, height: Float, peel: Float): List<Offset> {
    val fold = peelFoldDistance(width, height, peel) ?: return emptyList()
    val peeled = clipToHalfPlane(sheetCorners(width, height)) { point ->
        fold - foldOffset(point, width, height)
    }
    if (peeled.size < 3) return emptyList()

    val (ux, uy) = peelDirection(width, height)
    val mirrored = peeled.map { point ->
        val over = fold - foldOffset(point, width, height)
        Offset(point.x + 2f * over * ux, point.y + 2f * over * uy)
    }
    return clipToSheet(mirrored, width, height)
}

/**
 * 折り目がどれだけ進んだか（右下の角からの距離、画素）。
 *
 * **対角線の長さを 1 として進む。** 紙の縦横比が変わっても「角から角へ」は保たれる。
 * 紙に面積が無いとき（測る前）は `null` で、そのときは何もめくれていないものとして扱う。
 */
internal fun peelFoldDistance(width: Float, height: Float, peel: Float): Float? {
    val diagonal = hypot(width, height)
    if (diagonal <= 0f) return null
    return sheetPeel(peel) * diagonal
}

/** 折り目が進む向き。**右下の角から左上の角へ**（単位ベクトル）。 */
private fun peelDirection(width: Float, height: Float): Pair<Float, Float> {
    val diagonal = hypot(width, height)
    return -width / diagonal to -height / diagonal
}

/** その点が、右下の角から見て折り目の進む向きにどれだけ離れているか。 */
private fun foldOffset(point: Offset, width: Float, height: Float): Float {
    val (ux, uy) = peelDirection(width, height)
    return (point.x - width) * ux + (point.y - height) * uy
}

/** 紙の4隅。左上から時計回り。 */
private fun sheetCorners(width: Float, height: Float): List<Offset> =
    listOf(Offset(0f, 0f), Offset(width, 0f), Offset(width, height), Offset(0f, height))

/**
 * 多角形を半平面で切る（`inside` が 0 以上の側を残す）。
 *
 * **凸多角形しか出てこない**ので、切った結果も凸である
 * （影の輪郭は凸でなければ描けない → `PeelShape`）。
 */
private fun clipToHalfPlane(polygon: List<Offset>, inside: (Offset) -> Float): List<Offset> {
    if (polygon.isEmpty()) return polygon
    val clipped = mutableListOf<Offset>()
    polygon.forEachIndexed { index, current ->
        val next = polygon[(index + 1) % polygon.size]
        val currentSide = inside(current)
        val nextSide = inside(next)
        if (currentSide >= 0f) clipped += current
        if ((currentSide >= 0f) != (nextSide >= 0f)) {
            val ratio = currentSide / (currentSide - nextSide)
            clipped += Offset(
                current.x + (next.x - current.x) * ratio,
                current.y + (next.y - current.y) * ratio
            )
        }
    }
    return clipped
}

/** 紙の枠で切る。4辺ぶんの半平面を順に当てる。 */
private fun clipToSheet(polygon: List<Offset>, width: Float, height: Float): List<Offset> =
    clipToHalfPlane(polygon) { it.x }
        .let { clipToHalfPlane(it) { point -> width - point.x } }
        .let { clipToHalfPlane(it) { point -> point.y } }
        .let { clipToHalfPlane(it) { point -> height - point.y } }

/**
 * めくれた角がどれだけ持ち上がっているか（影の深さ）。**折り目が長いほど深い。**
 *
 * 折り目の長さは角から対角線の半分で最大になり、そこから短くなる。
 * **持ち上がっている紙の量そのもの**なので、影もそれに従う。
 */
internal fun peelLift(peel: Float): Float {
    val progress = sheetPeel(peel)
    return sin(progress * PI).toFloat()
}

/**
 * 紙が影を落としてよいか。**3D で回っている間は落とさない。**
 *
 * ## なぜ切るのか（2026-09-07 の実機レビュー `P2-1`）
 *
 * **積み直りの最中にめくると、折り返しから離れた大きな灰色の影が数フレーム出る。**
 * 実機で 0.80〜0.90 秒付近に再現し、同じ操作で2回とも出た。
 *
 * 材料は2つあり、**両方が揃ったときだけ**壊れる。
 *
 * | 材料 | いつ揃うか |
 * |---|---|
 * | 親が**遠近つきで3Dに回っている** | 積み直りの 320ms のあいだだけ |
 * | 子の輪郭が**多角形（`Outline.Generic`）で、影を出している** | めくっている最中だけ |
 *
 * Android の影は輪郭を親の空間へ変換して作るが、**遠近の入った変換は射影**なので、
 * パスから作る影はそこで崩れる。**束の縁は角丸矩形（`Outline.Rounded`）なので同じ形にならない** —
 * だから切るのは**めくりの2層だけ**でよい。
 *
 * **切っても切り抜きは残る。** `clip = true` を別に置いてあるので、影を 0 にしても形は効く
 * （2026-09-06 に「影が消えたときの保険」として入れた。**その保険が翌日効いた**）。
 *
 * **失うものは小さい。** 回っている間は**傾きと遠近**が「持ち上がっている」ことを言っており、
 * 影はもともと*置かれている*ことを言う道具である。
 */
internal fun sheetCastsShadow(restack: Float): Boolean = sheetTiltDegrees(restack) <= 0f

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
    // `initialPage` に優先する。実機の往復で1枚目へ戻ったのはこの層なので、
    // **保存・復元の挙動に依存せず**、束の値を唯一の正として当て直す。
    //
    // **鍵は束の世代。** `Unit` にすると、引き直しで画面が `Loading` を挟まなかったとき
    // （ノート一覧がキャッシュから同期で返る通常経路）に **ページャが旧い束の終端に残る** —
    // 束は新しいのに「もうN枚引く」が出たままになる（2026-09-03 のレビュー）。
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
                BookletPage(entry = entries[page], turn = turn, restack = restack, onRead = onRead)
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
    WeaveBlockedReason.Failed -> "関連ノートを取れませんでした。"
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
/**
 * **`internal` なのは、画素を数える検査が本番の紙をそのまま描くため**
 * （→ `BookletSheetPerspectiveTest`）。
 *
 * **写しを描く検査は、本番のレイヤーが壊れても通る。** 実際、表と裏の切り抜きを
 * 片側ずつ落とす変異が、JVM走査でも描画テストでも素通りした
 * （2026-09-06 のレビュー `P2-1`）。**検査が触れるのは本番の層でなければならない。**
 */
@Composable
internal fun BookletSheet(
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
            // **積み直りで倒れたぶんだけ、絵ごと引く**（→ [sheetFitScale]）。
            // **回転より外側**なので台形の比は変わらない — 見え方は変わらず、枠に入るだけである。
            // 束の縁も同じ枠の中にあるので一緒に引かれ、**紙と縁がずれない**。
            .graphicsLayer {
                val fit = sheetFitScale(sheetTiltDegrees(restack()))
                scaleX = fit
                scaleY = fit
                // 綴じ位置は動かない。**縮むのは綴じから下だけ**（→ [SHEET_HINGE]）。
                transformOrigin = SHEET_HINGE
            }
            // **積み直りの傾きはここが持つ。** めくりは倒さないので、回るのはこの1つだけ。
            .graphicsLayer {
                rotationX = sheetTiltDegrees(restack())
                transformOrigin = SHEET_HINGE
                // **紙は画面の半分より大きい。** 既定のカメラ距離では回した像が破綻するので、
                // 紙の高さから導く（→ [CAMERA_DISTANCE_FACTOR]）。
                cameraDistance = sheetCameraDistance(size.height)
            }
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
        // **紙の表。折り目の向こう側だけを残す**（→ [peelFlatPolygon]）。
        // 形で切るので、**文字はページの中で切れたところで途切れる** — 折り返した紙の裏へ
        // 文字が回り込まないのはそのためである。
        //
        // **`clip` は影が消えたときのための保険である**（2026-09-06 に実測、**2026-09-07 に効いた**）。
        // Compose は `clip` が false でも**影のために輪郭を要求する**ので
        // （`outlineNeeded = outline != null && (clipToOutline || elevation > 0f)`）、
        // **影が出ている限り `clip` を落としても画素は1つも変わらない。**
        // だが `shadowElevation` を 0 にした瞬間に切り抜きごと消え、紙が丸ごと残る
        // （両方落として実測した）。**影は見え方の都合で動かしうるので、切り抜きをそこへ預けない。**
        Box(
            modifier = Modifier
                .fillMaxSize()
                // **読むのはこのラムダの中だけ。** 関数の本体で [turn] を呼ぶと、
                // 指を動かしている間ずっと再コンポーズが走る（ここなら描画の直前に読まれる）。
                .graphicsLayer {
                    shape = PeelShape(peel = sheetPeel(turn()), flap = false)
                    clip = true
                    // **回っている間は影を出さない**（→ [sheetCastsShadow]）。
                    shadowElevation =
                        if (sheetCastsShadow(restack())) SHEET_RESTING_SHADOW.toPx() else 0f
                }
                .background(Panel)
        ) { content() }
        // **めくれて裏返った角**（→ [peelFlapPolygon]）。文字は載らない。
        // **影が「持ち上がっている」ことを言う** — 折り目に沿って落ちる影が、
        // 角が浮いていることそのものになる（→ 判断11）。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val peel = sheetPeel(turn())
                    shape = PeelShape(peel = peel, flap = true)
                    clip = true
                    // **回っている間は影を出さない**（→ [sheetCastsShadow]）。
                    shadowElevation = if (sheetCastsShadow(restack())) {
                        lerp(SHEET_RESTING_SHADOW, SHEET_LIFTED_SHADOW, peelLift(peel)).toPx()
                    } else {
                        0f
                    }
                }
                .background(Panel)
        )
    }
}

/**
 * 折り目で切った紙の形。**表の側と、めくれて裏返った側の2つを作る。**
 *
 * 幾何は純関数が持つ（→ [peelFlatPolygon]・[peelFlapPolygon]）。ここがやるのは
 * **多角形を輪郭へ移すことと、静止時に紙の形をそのまま返すこと**だけである。
 *
 * **静止時は [BrowsingSheetShape] をそのまま返す。** 多角形にすると角丸が失われ、
 * 判断9 で実機確認した佇まいが変わる。**止まっている絵は1ピクセルも変えない。**
 *
 * **凸のまま保つ。** 影の輪郭は凸でなければ描けない（Android の制約）。
 * 紙の矩形を半平面で切った形も、それを折り目で鏡にした形も凸である。
 */
internal class PeelShape(private val peel: Float, private val flap: Boolean) : Shape {

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        if (sheetPeel(peel) <= 0f) {
            return if (flap) Outline.Generic(Path())
            else BrowsingSheetShape.createOutline(size, layoutDirection, density)
        }

        val points = if (flap) peelFlapPolygon(size.width, size.height, peel)
        else peelFlatPolygon(size.width, size.height, peel)

        return Outline.Generic(points.toPath())
    }

    private fun List<Offset>.toPath(): Path = Path().apply {
        if (size < 3) return@apply
        moveTo(first().x, first().y)
        drop(1).forEach { lineTo(it.x, it.y) }
        close()
    }

    override fun equals(other: Any?): Boolean =
        other is PeelShape && other.peel == peel && other.flap == flap

    override fun hashCode(): Int = peel.hashCode() * 31 + flap.hashCode()
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
 * ## 画面からは外した（2026-09-06、オーナー判断）
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
