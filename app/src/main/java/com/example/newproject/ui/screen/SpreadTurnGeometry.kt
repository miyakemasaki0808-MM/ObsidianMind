package com.example.newproject.ui.screen

import kotlin.math.PI
import kotlin.math.sin

// ── 本文の見開きのめくり（→ features/note_spread.md 判断7）─────────────────
//
// **めくる1枚が綴じ目（折り目の溝の中央）を軸に、硬いまま立ち上がって倒れる。** 見開き j と j+1 の間の1枚は、
// めくり量 p = offset(j) = 1 + offset(j+1) で 180·p 度回る。表（j の右ページ）は 90 度まで j が描き、
// 裏（j+1 の左ページ）は 90 度から j+1 が描く。めくらないページは動かさない。
// 冊子から借りるのは仕組み（ページャを動力にして定位置へ置き直す・描く順・カメラ距離の単位）だけで、形は借りない。

/** 1ページの描き方。[rotationY] は `graphicsLayer` へそのまま渡す角度。[shown] が偽なら描かない。 */
internal data class SpreadFace(val rotationY: Float, val shown: Boolean) {
    /** 綴じ目を軸に回っているか。回っている間だけ影を落とす。 */
    val turning: Boolean get() = rotationY != 0f
}

/**
 * ページャの見開き1つの描き方（→ [spreadTurnLayers]）。
 * [slotShift] は見開きを定位置へ置き直す横の量（見開きの幅に対する割合）で、ページャが送るために与えた変位を打ち消す。
 * [onTop] はめくる1枚の表か裏を描いている見開きで、ほかの見開きより上に重ねる。
 */
internal data class SpreadTurnLayers(val slotShift: Float, val left: SpreadFace, val right: SpreadFace, val onTop: Boolean)

/**
 * ページャの見開きの [offset]（今の見開きからの距離。`currentPage - page + currentPageOffsetFraction`）から、2ページの描き方を決める。
 * **正はめくり出される側で、右ページ（表）が回る。負はめくって出てくる側で、左ページ（裏）が回る。** 次へめくっても前へめくっても同じ規則になる —
 * 前へめくるとき回る1枚は、表が今の左ページ、裏が前の見開きの右ページで、上の規則がそのまま当てはまる。
 * **90度ちょうどは表も裏も描かない**（綴じ目の上の線になる瞬間）。
 * **1つ以上離れた見開きは描かず、置き直しもしない** — 置き直すと画面の外にあるはずの見開きが今の見開きに重なり、
 * 読み上げや表示の判定から見えてしまう。
 */
internal fun spreadTurnLayers(offset: Float): SpreadTurnLayers {
    val still = SpreadFace(rotationY = 0f, shown = true)
    val hidden = SpreadFace(rotationY = 0f, shown = false)
    return when {
        offset >= 1f || offset <= -1f -> SpreadTurnLayers(slotShift = 0f, left = hidden, right = hidden, onTop = false)
        offset > 0f -> {
            val angle = 180f * offset
            SpreadTurnLayers(
                slotShift = offset,
                left = still,
                right = SpreadFace(rotationY = SPREAD_TURN_LIFT_SIGN * angle, shown = angle < 90f),
                onTop = angle < 90f
            )
        }
        offset < 0f -> {
            val angle = 180f * (1f + offset)
            SpreadTurnLayers(
                slotShift = offset,
                left = SpreadFace(rotationY = -SPREAD_TURN_LIFT_SIGN * (180f - angle), shown = angle > 90f),
                right = still,
                onTop = angle > 90f
            )
        }
        else -> SpreadTurnLayers(slotShift = 0f, left = still, right = still, onTop = false)
    }
}

/**
 * 綴じ目を手前へ持ち上げる向きの符号。**綴じ目を軸にした右ページは、負の `rotationY` で外側の縁が手前へ出る**
 * （ページャの立方体の変換で知られた向き、冊子の `rotationX` の実測と同じ回転の約束から導いた）。
 * **向きの意味は値に現れないので、描いた画素で確かめる**（`NoteReadingFlowTest` → docs/dev/lessons/L61.md）。
 */
internal const val SPREAD_TURN_LIFT_SIGN = -1f

/**
 * 回るページの `transformOrigin` の横の位置（ページの幅に対する割合）。**軸は溝の中央** — 右ページは自分の左端から溝の半分だけ左、
 * 左ページは自分の右端から溝の半分だけ右。どちらも同じ1本の線になるので、90度で表から裏へ移るときに跳ばず、
 * 倒れ終えた紙は向かいのページの位置にちょうど重なる。
 */
internal fun spreadSpinePivot(pageWidthPx: Float, gutterPx: Float, rightPage: Boolean): Float {
    if (pageWidthPx <= 0f) return if (rightPage) 0f else 1f
    val half = gutterPx / 2f / pageWidthPx
    return if (rightPage) -half else 1f + half
}

/**
 * `cameraDistance` へ渡す値。**持ち上がった縁が上下へ出る量が [liftRoomPx] に収まる、いちばん近い距離**（いちばん強い遠近）にする。
 * 縁が最も出るのは紙が立った90度で、外側の縁は `d / (d - W)` 倍に伸びる。上下へ [liftRoomPx] ずつまでなら、
 * `d = W (H + 2R) / 2R`。ページャは横を自分の縁で切り、上下は少しだけ切らずに残すので、その内側に [liftRoomPx] を取る。
 * 単位は冊子と同じく、渡した値の [CAMERA_UNIT_PX] 倍が画素（→ [sheetCameraDistance]）。
 */
internal fun spreadTurnCameraDistance(pageWidthPx: Float, pageHeightPx: Float, liftRoomPx: Float): Float {
    if (pageWidthPx <= 0f || pageHeightPx <= 0f || liftRoomPx <= 0f) return DEFAULT_CAMERA_DISTANCE
    return spreadTurnEyeDistancePx(pageWidthPx, pageHeightPx, liftRoomPx) / CAMERA_UNIT_PX
}

/** カメラから紙までの実効の距離（画素）。[spreadTurnCameraDistance] の中身で、検査が持ち上がりの量を数えるのに使う。 */
internal fun spreadTurnEyeDistancePx(pageWidthPx: Float, pageHeightPx: Float, liftRoomPx: Float): Float =
    pageWidthPx * (pageHeightPx + 2f * liftRoomPx) / (2f * liftRoomPx)

/** [angleDegrees] 度立った紙の外側の縁が、紙の上下の縁から出る量（画素、片側）。カメラは軸（綴じ目の縦の中央）の正面にある。 */
internal fun spreadTurnLiftPx(pageWidthPx: Float, pageHeightPx: Float, eyeDistancePx: Float, angleDegrees: Float): Float {
    val toward = pageWidthPx * sin(angleDegrees * PI.toFloat() / 180f)
    return pageHeightPx / 2f * (eyeDistancePx / (eyeDistancePx - toward) - 1f)
}

/** 回っている紙の影の高さ（dp）。立つほど深く、寝ている紙は落とさない。 */
internal fun spreadTurnShadowDp(rotationY: Float): Float =
    SPREAD_TURN_SHADOW_DP * sin(kotlin.math.abs(rotationY) * PI.toFloat() / 180f)

/**
 * 持ち上がった縁に取る上下の余地。ページャは横を自分の縁で切るが、上下は影のために 30dp だけ切らずに残す。その内側に取る。
 * **遠近の強さはこれで決まる**（→ [spreadTurnCameraDistance]）。強さは実機で見て決める（→ features/note_spread.md 判断7）。
 */
internal const val SPREAD_TURN_LIFT_ROOM_DP = 24f

/** 立った紙が落とす影のいちばん深い高さ。影の出し方は描いた画素で確かめてから決める（→ features/note_spread.md 判断7）。 */
internal const val SPREAD_TURN_SHADOW_DP = 8f

/** Compose の `graphicsLayer` の既定のカメラ距離。寸法が測れる前だけ使う。 */
private const val DEFAULT_CAMERA_DISTANCE = 8f
