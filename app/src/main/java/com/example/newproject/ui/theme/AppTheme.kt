package com.example.newproject.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * 画面が参照する色の一式。明暗で差し替わるのはこの型の中身だけ。
 *
 * ブランドパレット（Indigo/Aqua/Coral など「その色であること」に意味がある値）は
 * 含めない。明暗で置き換える対象ではないため。
 */
internal class AppColorScheme(
    // 面
    val panel: Color,
    val codePanel: Color,
    val panelTinted: Color,
    val panelBlue: Color,
    val panelChip: Color,
    val panelRow: Color,
    val panelBubble: Color,
    val skeletonBase: Color,
    val skeletonHighlight: Color,
    // 罫線
    val panelDivider: Color,
    val panelDividerStrong: Color,
    val chatDivider: Color,
    val contentDivider: Color,
    val checkboxOutline: Color,
    // 文字。弱さは3段階だけ持つ（本文に次ぐ／弱い／最も弱い）。
    // ライトは白面に対し4.5:1が要るため、最も弱い側は #757575 より暗くできない。
    // この床があるので4段階以上は「意味の違う名前で同じ濃さ」になる。
    val onSurface: Color,
    val onSurfaceMuted: Color,
    val onSurfaceSubtle: Color,
    val onSurfaceFaint: Color,
    val onSurfaceMetaBlue: Color,
    val onVibrant: Color,
    val onVibrantMuted: Color,
    val linkText: Color,
    // 意味色
    val errorText: Color,
    // 塗りとして使うエラー色は文字色と別に持つ。errorText を Badge の containerColor に
    // 流用すると、暗所で「明るい赤に白文字」という読めない組み合わせが生まれる。
    val errorSurface: Color,
    val onErrorSurface: Color,
    val dangerAction: Color,
    val onDangerAction: Color,
    val successMark: Color,
    val failureMark: Color,
    val relatedHeading: Color,
    val aiHeading: Color,
    // アクセント（AI・強調）。ライトはブランドのIndigoそのもの、ダークは明度を上げた版。
    // 文字は4.5:1が要るため、塗り(accentSurface)より明るい値を使う。
    val accentText: Color,
    val accentSurface: Color,
    val onAccentSurface: Color,
    // 半透明のガラス面（全画面FAB・Vigilithのラベル）。ダークは透過だと沈むので不透明。
    val accentGlass: Color,
    // 下部ナビ／レール
    val navBar: Color,
    val navIndicator: Color,
    // Vigilithの背後に敷く淡い光。ライトは透明（不要）、ダークだけ効かせる。
    val vigilithHalo: Color,
    // ボタン（塗りとラベルは必ず対で持つ）
    val buttonPrimary: Color,
    val buttonSecondary: Color,
    val buttonAi: Color,
    val onButtonPrimary: Color,
    val onButtonSecondary: Color,
    val onButtonAi: Color,
    // グラデーション直上に置くボタンの輪郭線。塗り自身では境界を出せないため使う
    // （理由は LightAppColors のコメント）。ダークは塗りが足りているので透明。
    val buttonOutlineOnGradient: Color,
    // 背景
    // 背景グラデーションは**停止色のリストだけ**を受け取り、`Brush` はここで組み立てる。
    // 以前は `Brush` を直接渡していたため、色を検証したいテストは同じ値を自前で
    // 書き写すしかなく、実際に停止色とテストの参照先が食い違ったまま全緑になった
    // （テストは最も暗い停止色だけを測っていた）。`Brush` から色は取り出せないので、
    // **単一ソースにするには色の側を持つしかない**。
    val appGradientStops: List<Color>,
    val readingGradientStops: List<Color>
) {
    val appGradient: Brush = diagonalGradient(appGradientStops)
    val readingGradient: Brush = diagonalGradient(readingGradientStops)
}

/** 左下から右上へ流す。全画面で向きを揃えるため1箇所に閉じる。 */
private fun diagonalGradient(stops: List<Color>): Brush = Brush.linearGradient(
    colors = stops,
    start = Offset(0f, Float.POSITIVE_INFINITY),
    end = Offset(Float.POSITIVE_INFINITY, 0f)
)

internal val LocalAppColors = staticCompositionLocalOf { LightAppColors }

/**
 * アプリ全体のテーマ。`setContent{}` の直下に1枚だけ置く。
 *
 * OSのダークモードには追従しない（[isSystemInDarkTheme] を使わない）。
 * このアプリの配色はMaterialの動的配色ではなくブランド由来の独自配色で、
 * OS設定に自動で引きずられると、ユーザーの意図しないタイミングで画面が別物になる。
 * 切り替えの主導権はオプション画面に置く。
 *
 * `MaterialTheme` の `colorScheme` は、独自の3役ボタンを primary/secondary/tertiary へ
 * 写像しない。意味が対応しないうえ、`onPrimary` などが Button・Surface の既定へ波及して
 * 画面ごとの見た目を壊すため。ここで渡すのは **M3コンポーネントが自前で描く面**
 * （Snackbar・BottomSheet・AlertDialog・Badge）が暗所で浮かないようにするための最小限。
 */
@Composable
internal fun AppTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkAppColors else LightAppColors
    val material = if (darkTheme) {
        darkColorScheme(
            surface = colors.panel,
            onSurface = colors.onSurface,
            surfaceContainer = colors.panel,
            surfaceContainerHigh = colors.panelChip,
            background = colors.panel,
            onBackground = colors.onSurface,
            outline = colors.contentDivider,
            error = colors.errorText,
            onError = Color.Black
        )
    } else {
        lightColorScheme()
    }
    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(colorScheme = material, content = content)
    }
}
