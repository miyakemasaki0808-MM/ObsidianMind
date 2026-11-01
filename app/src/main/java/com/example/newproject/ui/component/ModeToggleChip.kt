package com.example.newproject.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.ui.theme.ButtonOutlineOnGradient
import com.example.newproject.ui.theme.ButtonSecondary
import com.example.newproject.ui.theme.OnButtonSecondary
import com.example.newproject.ui.theme.OnSurfaceFaint
import com.example.newproject.ui.theme.OnSurfaceMuted
import com.example.newproject.ui.theme.PanelChip

/**
 * グラデーションの上で2つのモードを切り替える片方。選んでいる方だけ塗る。
 * **塗りに `ButtonPrimary` を使わない** — 同じ画面の主役のボタンと並ぶので、同じ色を取ると主役が2つになる。
 */
@Composable
internal fun ModeToggleChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
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
