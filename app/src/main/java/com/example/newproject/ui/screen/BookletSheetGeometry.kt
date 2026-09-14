package com.example.newproject.ui.screen

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.absoluteValue
import kotlin.math.sin

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
 * `View.setCameraDistance` は `densityDpi` で割ってから `RenderNode` へ渡すが、
 * **Compose の `ViewLayer` はその割り算を打ち消すために
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
 * **受け取ると「密度で変わる」と読めてしまう**ためである。
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
 * 積み直りの傾き。**繰り切らない。**
 *
 * 半回転まで倒すと「めくった」と言ってしまう。
 * 束が届いたのは*めくった*からではないので、**浮いて置き直される以上のことをしない**
 * （→ features/booklet_mode.md 判断10）。
 */
private const val RESTACK_TILT_DEGREES = 22f

/**
 * 紙が倒れている角度。**いま倒すのは積み直りだけである。**
 *
 * **送りの進み具合は紙を倒さない。** めくりは**折り目が斜めに走る**形で表すので（→ [peelFlatPolygon]）、
 * 紙は置かれたまま、角だけが折り返る。
 *
 * **積み直りとめくりは別のチャネル（角度と折り目）を使う**ので、同時に起きても食い違わない。
 * 同じチャネルを2つが取り合うと、合成の規則（足すか、大きい側を採るか）をどう決めても破綻する。
 *
 * `restack` は積み直りで、**1 が積み終わり**（水平）。**繰り切らない** —
 * 束が届いたのは*めくった*からではないので、**浮いて置き直される以上のことをしない**
 * （[RESTACK_TILT_DEGREES]）。
 *
 * **正の角度が下端を手前に持ち上げる**（Compose の回転は蝶番より下の点を +Z ＝手前へ送る）。
 * 負にすると紙が束の中へ沈む向きになる。
 * **向きは値に現れない**ので `BookletSheetPerspectiveTest` が画素で見る（→ docs/dev/lessons.md L61）。
 */
internal fun sheetTiltDegrees(restack: Float): Float =
    RESTACK_TILT_DEGREES * (1f - restack.coerceIn(0f, 1f))

/**
 * 束の縁を見せるか。**持ち上げられた紙は、束を置いていく。**
 *
 * 縁は「まだ積まれている残り」であって、手に取られた1枚の一部ではない。
 * **縁は回らない**ので、めくられる紙に付けたままにすると、
 * **不透明な面が定位置に残って次の紙を覆う。**
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
 * ## 何に効くのか
 *
 * **めくりは回転ではない**（折り目が走る → [peelFlatPolygon]）ので、紙が倒れるのは
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
 * ## めくりは回転ではなく折り目である
 *
 * **紙全体を曲げても「めくった」にはならない**（→ features/booklet_mode.md 判断11）。
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
 * ## なぜ切るのか
 *
 * **積み直りの最中にめくると、折り返しから離れた大きな灰色の影が数フレーム出る。**
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
 * **切っても切り抜きは残る。** `clip = true` を別に置いてあるので、影を 0 にしても形は効く。
 *
 * **失うものは小さい。** 回っている間は**傾きと遠近**が「持ち上がっている」ことを言っており、
 * 影はもともと*置かれている*ことを言う道具である。
 */
internal fun sheetCastsShadow(restack: Float): Boolean = sheetTiltDegrees(restack) <= 0f
