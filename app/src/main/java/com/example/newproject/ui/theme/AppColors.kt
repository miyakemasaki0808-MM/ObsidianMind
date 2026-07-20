package com.example.newproject.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

internal val Indigo = Color(0xFF4D3DFF)
internal val Aqua = Color(0xFF00C2FF)
internal val Coral = Color(0xFFFF6B8A)
internal val LogoNavy = Color(0xFF050E1D)
internal val LogoPurple = Color(0xFF9A5CE6)   // 起動OPの背面発光（Aqua/Indigoと3色で使う）
internal val OnVibrant = Color.White
internal val OnVibrantMuted = Color(0xFFEAF7FF)
internal val OnSurface = Color(0xFF202124)
internal val Panel = Color(0xFFFDFEFF)
internal val CodePanel = Color(0xFFF1F4F8)
internal val LinkBlue = Color(0xFF2563EB)
// ボタン配色の3役ルール（これ以外の色をボタンに使わない）:
//   ButtonPrimary（ピンク）＝画面の主アクション
//   ButtonSecondary（緑）＝補助・代替アクション
//   ButtonAi（Indigo）＝AI生成系アクション
//   ※ナビ（戻る等）はOutlinedButtonで控えめに
//   ※同一画面にAI系ボタンが複数並ぶ場合は、主たる方をButtonPrimaryにして識別性を優先する
internal val ButtonPrimary = Color(0xFFFF3D71)
internal val ButtonSecondary = Color(0xFF16B8A6)
internal val ButtonAi = Indigo
internal val PanelTinted = Color(0xFFF7F3FF)
internal val ErrorRed = Color(0xFFCC0000)
internal val PanelBlue = Color(0xFFF0F4FF)      // AI要約・関連ノート等の薄青パネル
internal val PanelDivider = Color(0xFFD6DDF5)   // 薄青パネル内の区切り線

// 「読む」画面用の低彩度版（Indigo/Aqua/Coralの彩度を3〜4割落としたもの）。
// 長時間滞在する閲覧画面では背景より本文を前に出す。
internal val MutedIndigo = Color(0xFF6E63E3)
internal val MutedAqua = Color(0xFF54B5D4)
internal val MutedCoral = Color(0xFFE28D9F)

// 全画面共通の背景グラデーション（左下→右上）。
// 以前は4ファイルで個別定義されていたものを集約。
internal val AppGradient: Brush = Brush.linearGradient(
    colors = listOf(Indigo, Aqua, Coral),
    start = Offset(0f, Float.POSITIVE_INFINITY),
    end = Offset(Float.POSITIVE_INFINITY, 0f)
)

// 「読む」画面（ノート閲覧・AI補記閲覧）用グラデーション。
// 「触る」画面（さがす・AIアシスト等）は鮮やかなAppGradientを維持し、画面の性質で出し分ける。
internal val ReadingGradient: Brush = Brush.linearGradient(
    colors = listOf(MutedIndigo, MutedAqua, MutedCoral),
    start = Offset(0f, Float.POSITIVE_INFINITY),
    end = Offset(Float.POSITIVE_INFINITY, 0f)
)
