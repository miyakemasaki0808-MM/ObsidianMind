package com.example.newproject.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.ui.theme.Aqua
import com.example.newproject.ui.theme.Coral
import com.example.newproject.ui.theme.Indigo
import com.example.newproject.ui.theme.OnSurface
import com.example.newproject.ui.theme.OnVibrant
import com.example.newproject.ui.theme.Panel

@Composable
fun OptionsScreen(
    vaultSelected: Boolean,
    onSelectVault: () -> Unit,
    onManageAnnotations: () -> Unit
) {
    val gradient = Brush.linearGradient(
        colors = listOf(Indigo, Aqua, Coral),
        start = Offset(0f, Float.POSITIVE_INFINITY),
        end = Offset(Float.POSITIVE_INFINITY, 0f)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(gradient)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp)
    ) {
        Text(
            text = "オプション",
            color = OnVibrant,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))

        OptionRow(
            emoji = "📁",
            title = "Vaultを変更",
            subtitle = if (vaultSelected) "Vaultフォルダ選択済み" else "Vaultフォルダが未選択です",
            onClick = onSelectVault
        )
        Spacer(modifier = Modifier.height(10.dp))

        OptionRow(
            emoji = "🗂",
            title = "AI補記メモを削除",
            subtitle = "保存済みの補記メモを管理・削除",
            onClick = onManageAnnotations
        )
    }
}

@Composable
private fun OptionRow(
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
                Text(subtitle, color = OnSurface.copy(alpha = 0.6f), fontSize = 12.sp)
            }
        }
    }
}
