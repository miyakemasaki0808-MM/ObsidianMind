package com.example.newproject.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

internal val Indigo = Color(0xFF4D3DFF)
internal val Aqua = Color(0xFF00C2FF)
internal val Coral = Color(0xFFFF6B8A)
internal val OnVibrant = Color.White
internal val OnVibrantMuted = Color(0xFFEAF7FF)
internal val OnSurface = Color(0xFF202124)
internal val Panel = Color(0xFFFDFEFF)
internal val CodePanel = Color(0xFFF1F4F8)
internal val LinkBlue = Color(0xFF2563EB)
internal val ButtonPrimary = Color(0xFFFF3D71)
internal val ButtonSecondary = Color(0xFF16B8A6)
internal val PanelTinted = Color(0xFFF7F3FF)
internal val ErrorRed = Color(0xFFCC0000)
internal val PanelBlue = Color(0xFFF0F4FF)      // AI要約・関連ノート等の薄青パネル
internal val PanelDivider = Color(0xFFD6DDF5)   // 薄青パネル内の区切り線

// 全画面共通の背景グラデーション（左下→右上）。
// 以前は4ファイルで個別定義されていたものを集約。
internal val AppGradient: Brush = Brush.linearGradient(
    colors = listOf(Indigo, Aqua, Coral),
    start = Offset(0f, Float.POSITIVE_INFINITY),
    end = Offset(Float.POSITIVE_INFINITY, 0f)
)
