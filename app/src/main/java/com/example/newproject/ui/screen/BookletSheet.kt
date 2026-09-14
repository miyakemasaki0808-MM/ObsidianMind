package com.example.newproject.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.example.newproject.ui.theme.BrowsingSheetShape
import com.example.newproject.ui.theme.Panel
import com.example.newproject.ui.theme.PanelRow

private val SHEET_RESTING_SHADOW = 3.dp
private val SHEET_LIFTED_SHADOW = 6.dp

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
 *
 * **`internal` なのは、画素を数える検査が本番の紙をそのまま描くため**
 * （→ `BookletSheetPerspectiveTest`）。
 *
 * **写しを描く検査は、本番のレイヤーが壊れても通る**（表と裏の切り抜きを片側ずつ落とす変異が素通りする）。
 * **検査が触れるのは本番の層でなければならない。**
 */
@Composable
internal fun BookletSheet(
    isBundleSheet: Boolean,
    turn: () -> Float,
    restack: () -> Float,
    // 紙の地色。**分野が決まっていないページは `Panel`**（既定値）で、いままでと同じ見た目になる。
    // 表と裏（めくれた折り返し）の両方へ同じ色を塗る — 片方だけだと、めくる途中に
    // 別の色が覗いて「別の紙をめくっている」ように見える。
    paper: Color = Panel,
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
        // **`clip` は影が消えたときのための保険である。**
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
                .background(paper)
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
                .background(paper)
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
