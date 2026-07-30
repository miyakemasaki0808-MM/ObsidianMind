package com.example.newproject.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
 * 暗幕は**文字領域とフェードを別の要素に分ける**。1つの縦グラデーションで兼ねると、
 * 一定の濃さを保つ割合が要素全体の高さに対する比になるため、**副題が2行に折り返した
 * 瞬間にフェードの開始位置が文字へ食い込む**。文字領域は一様な濃さの面、
 * フェードは固定高さの [Spacer] とし、両者を独立させる。
 *
 * フェードを入れるのは、矩形のまま切ると帯の下端が線として見えて
 * 「グラデーションの上に別の面が乗っている」ように読めてしまうため。
 *
 * **水平方向は帯の内側だけに余白を持つ。** 呼び出し側の画面が既に左右20dpを与えて
 * いるので、ここで同じ幅を足すと二重になる。帯はその20dpの内側に収まる角丸として
 * 意図的に見せる（画面端まで抜くと全面スクリムと変わらなくなる）。
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
    Column(modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))) {
        // 文字領域。濃さは一様で、高さは中身に任せる。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(scrim)
                .padding(horizontal = 12.dp, vertical = 12.dp),
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
        // フェード。文字は載らないので高さを固定できる。
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(SCRIM_FADE_HEIGHT)
                .background(Brush.verticalGradient(listOf(scrim, Color.Transparent)))
        )
    }
}

/** 帯の下端で暗幕を透明へ抜く区間の高さ。文字は載らない。 */
internal val SCRIM_FADE_HEIGHT = 24.dp
