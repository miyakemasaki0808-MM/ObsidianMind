package com.example.newproject.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.example.newproject.model.NotePaperTone

/**
 * 画面が参照する色の一式。明暗で差し替わるのはこの型の中身だけ。
 *
 * ブランドパレット（Indigo/Aqua/Coral など「その色であること」に意味がある値）は
 * 含めない。明暗で置き換える対象ではないため。
 */
/**
 * ノート本文を載せる紙の地色。放置期間の段階（[NotePaperTone]）から引く。
 *
 * **上限は `panelChip` の相対輝度（0.8772）。** 弱い文字トークンの基準面が
 * 「文字が載る面のうち最も暗い `panelChip`」に置かれているため、ここがそれより暗くなると
 * 既存の全テキストトークンが一斉に基準を割る。→ `docs/dev/features/note_age_paper.md` §5
 *
 * [uniform] は全段階を同じ色にしたもので、**演出を無効化する形**にあたる
 * （ダーク時と、オプションでオフのとき）。
 */
internal class NotePaperTones(
    val fresh: Color,
    val settling: Color,
    val aged: Color,
    val weathered: Color
) {
    fun color(tone: NotePaperTone): Color = when (tone) {
        NotePaperTone.Fresh -> fresh
        NotePaperTone.Settling -> settling
        NotePaperTone.Aged -> aged
        NotePaperTone.Weathered -> weathered
    }

    companion object {
        fun uniform(color: Color) = NotePaperTones(color, color, color, color)
    }
}

internal class AppColorScheme(
    // 面
    val panel: Color,
    // 紙の地色。Fresh は panel と同値にする（既定オフのときと見た目を一致させるため）。
    val notePaper: NotePaperTones,
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
    // ステータスバッジ（AIタブの ✓ / !）の中の記号。**塗りの上のラベルとは基準が違う。**
    // ボタンのラベルは読む文字なので WCAG 1.4.3 の 4.5:1 が要るが、バッジの中身は
    // 状態を示す記号なので 1.4.11（非テキスト）の 3:1 で足りる。同じ塗り
    // （buttonSecondary）の上に載るのに前景が別値になるのはこのため。
    // Success/Error の両バッジで共有する（塗りは違うが、どちらも同じ基準で通る）。
    val onStatusBadge: Color,
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
    // グラデーション見出し。ライトは白のヘイズ＋濃色の文字、ダークは何も敷かず白文字。
    // 明暗で**反転する**ので、面と文字を必ず3つ揃いで持つ。
    val gradientHeaderScrim: Color,
    val onGradientHeaderTitle: Color,
    val onGradientHeaderSubtitle: Color,
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
 * 紙の地色の演出が有効かどうか。**[AppTheme] だけが提供する。**
 *
 * 段階そのもの（`NoteUiState.notePaperTone`）と分けて持つのは、オプションの切替を
 * ノートを開き直さずに反映させるため。段階は状態に載ったままで、色へ写す係だけが切り替わる。
 */
private val LocalNotePaperAging = staticCompositionLocalOf { false }

/**
 * 放置期間の段階を実際の色へ写す唯一の窓口。**画面はこれだけを呼ぶ。**
 *
 * 無効時（オプションでオフ／ダーク）は現行の `panel` を返すので、
 * 呼び出し側に「演出が効いているか」の分岐を書かせない。
 */
@Composable
@ReadOnlyComposable
internal fun notePaperColor(tone: NotePaperTone): Color {
    val colors = LocalAppColors.current
    return if (LocalNotePaperAging.current) colors.notePaper.color(tone) else colors.panel
}

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
internal fun AppTheme(
    darkTheme: Boolean,
    notePaperAging: Boolean = false,
    content: @Composable () -> Unit
) {
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
    // ダークでは常に無効。暗所配色は「明るい画面を暗くしたもの」ではなく別配色として
    // 設計されており、そこへ黄ばみ（光の当たった紙の比喩）を持ち込むと前提と衝突する。
    // → docs/dev/features/note_age_paper.md 判断5
    CompositionLocalProvider(
        LocalAppColors provides colors,
        LocalNotePaperAging provides (notePaperAging && !darkTheme)
    ) {
        MaterialTheme(colorScheme = material, content = content)
    }
}
