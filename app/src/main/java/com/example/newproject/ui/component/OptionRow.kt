package com.example.newproject.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.ui.theme.OnSurface
import com.example.newproject.ui.theme.OnSurfaceFaint
import com.example.newproject.ui.theme.Panel

/**
 * 設定系の画面で「押すと次の画面へ行く」1行。
 *
 * オプションとデータ管理の2画面が同じ形を使うので、片方の private から出してある。
 */
@Composable
internal fun OptionRow(
    emoji: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = Panel,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(emoji, fontSize = 22.sp)
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(title, color = OnSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                // 任意のαで薄めない。12sp は AA の大文字例外に入らないので、
                // 弱い文字は名前付きトークンの範囲から選ぶ。
                Text(subtitle, color = OnSurfaceFaint, fontSize = 12.sp)
            }
        }
    }
}
