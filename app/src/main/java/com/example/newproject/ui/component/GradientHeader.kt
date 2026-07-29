package com.example.newproject.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.example.newproject.ui.theme.GradientHeaderScrim
import com.example.newproject.ui.theme.OnVibrant
import com.example.newproject.ui.theme.OnVibrantMuted

/**
 * グラデーション直上に置く画面見出し。**背景を自分で持つ**のが要点。
 *
 * 白文字はライトのグラデーション上で読めない（Aqua停止色に対し 2.07、副題は 1.89）。
 * 28sp Bold は大文字扱いで3:1に緩むが、それすら満たさない。**白より明るい文字は
 * 無いので色では解けず**、背後を暗くするしかない。
 *
 * 各画面で個別に暗幕を敷くとどこかで必ず忘れるので、
 * **見出しの体裁と暗幕と文字色を1つの部品が同時に持つ**形にした。
 * 画面側は文字列とスロットを渡すだけで、`OnVibrant` に触れない。
 *
 * 暗幕は下端でフェードアウトさせる。矩形のまま切ると帯の境目が線として見えて、
 * 「グラデーションの上に別の面が乗っている」ように読めてしまうため。
 * フェード区間には文字を置かない（[SCRIM_SOLID_FRACTION] までが本文域）。
 *
 * **水平方向のパディングは持たない。** 呼び出し側の画面が既に左右20dpを与えており、
 * ここで足すと二重になって文字だけが内側へ寄る。帯はその20dpの内側に収まる
 * 角丸の帯として意図的に見せる（画面端まで抜くと全面スクリムと変わらなくなる）。
 *
 * ダークでは暗幕が透明になる。暗いグラデーションでは白文字も副題も元から基準を
 * 満たしており、暗い矩形を重ねる意味が無いため。
 */
@Composable
internal fun GradientHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    titleSize: TextUnit = 28.sp,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    val scrim = GradientHeaderScrim
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                Brush.verticalGradient(
                    // 本文域は一定の濃さ、その下だけで透明へ抜く。
                    0f to scrim,
                    SCRIM_SOLID_FRACTION to scrim,
                    1f to Color.Transparent
                )
            )
            .padding(
                top = 12.dp,
                // フェード分の余白。ここに文字は載らない。
                bottom = 28.dp
            )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            leading?.invoke()
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = OnVibrant,
                    fontSize = titleSize,
                    fontWeight = FontWeight.Bold
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        color = OnVibrantMuted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            trailing?.invoke()
        }
    }
}

/** 暗幕を一定の濃さで保つ縦方向の割合。ここから下がフェード区間。 */
internal const val SCRIM_SOLID_FRACTION = 0.72f
