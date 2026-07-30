package com.example.newproject.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.ui.theme.GradientHeaderScrim
import com.example.newproject.ui.theme.OnGradientHeaderSubtitle
import com.example.newproject.ui.theme.OnGradientHeaderTitle

/**
 * グラデーション直上に置く画面見出し。**背景を自分で持つ**のが要点。
 *
 * ライトのグラデーション上では白文字が読めない（Aqua停止色に対し 2.07、副題 1.89）。
 * 28sp Bold は大文字扱いで3:1に緩むが、それすら満たさない。
 *
 * **解き方を「暗くする」から「白で霞ませる」へ反転させた。** 暗幕は帯として重く出て、
 * 角丸を付けるとカードにしか見えない。白を薄く重ねると停止色が持ち上がり、そこへ
 * 濃い文字を置ける。白文字と違い、濃い側は最も暗い Indigo 停止色でも余裕がある
 * （α=0.35 でタイトル 6.15／副題 5.12）。
 *
 * ダークは何も敷かない。暗いグラデーションでは白文字が元から基準を満たしており、
 * 霞ませる意味が無いため。**したがって面と文字は明暗で反転する。**
 *
 * 文字領域とフェードは別の要素に分ける。1つの縦グラデーションで兼ねると、一定の
 * 濃さを保つ割合が要素全体の高さに対する比になるため、**副題が2行に折り返した瞬間に
 * フェードの開始位置が文字へ食い込む**。
 *
 * 面はメイン領域の**全幅**へ広げる（[horizontalBleed]）。角丸を付けず端まで抜くことで、
 * 「上に乗ったカード」ではなく「上部が霞んだ背景」として読ませる。
 */
@Composable
internal fun GradientHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    titleSize: TextUnit = 28.sp,
    horizontalBleed: Dp = DEFAULT_HORIZONTAL_BLEED,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    val scrim = GradientHeaderScrim
    Column(modifier = modifier.fillMaxWidth().bleedHorizontally(horizontalBleed)) {
        // 文字領域。濃さは一様で、高さは中身に任せる。
        // 内側の余白を bleed と同じにすることで、外へ広げても文字の位置は動かない。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(scrim)
                .padding(horizontal = horizontalBleed, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            leading?.invoke()
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = OnGradientHeaderTitle,
                    fontSize = titleSize,
                    fontWeight = FontWeight.Bold
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        color = OnGradientHeaderSubtitle,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            trailing?.invoke()
        }
        // フェード。文字は載らないので高さを固定できる。
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(SCRIM_FADE_HEIGHT)
                .background(Brush.verticalGradient(listOf(scrim, Color.Transparent)))
        )
    }
}

/**
 * 親が与えた左右の余白の外側まで広がる。
 *
 * 各画面は本文用に左右20dpを持っており、その内側で背景を描くと帯が浮いたカードに
 * 見える。見出しの面だけは端まで抜きたいので、測定時に横幅を広げて負のオフセットで
 * 戻す。**[bleed] は呼び出し側の水平パディングと一致していなければならない**
 * （既定値は現在の全画面共通の20dp）。
 */
private fun Modifier.bleedHorizontally(bleed: Dp) = this.layout { measurable, constraints ->
    val extra = bleed.roundToPx() * 2
    val widened = constraints.copy(
        minWidth = constraints.minWidth + extra,
        maxWidth = if (constraints.maxWidth == Constraints.Infinity) {
            Constraints.Infinity
        } else {
            constraints.maxWidth + extra
        }
    )
    val placeable = measurable.measure(widened)
    // 親には元の幅で報告する。広げた分は描画だけに使い、後続の配置をずらさない。
    layout(placeable.width - extra, placeable.height) {
        placeable.place(-bleed.roundToPx(), 0)
    }
}

/** 各画面が本文へ与えている水平パディング。 */
private val DEFAULT_HORIZONTAL_BLEED = 20.dp

/** 帯の下端で面を透明へ抜く区間の高さ。文字は載らない。 */
internal val SCRIM_FADE_HEIGHT = 10.dp
