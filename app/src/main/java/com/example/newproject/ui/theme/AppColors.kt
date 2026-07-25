package com.example.newproject.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------------------
// 1. ブランドパレット（色そのもの）
//    グラデーションやキャラクターの発光など「その色であること」に意味がある値。
//    役割トークン（§3）と違い、明暗で置き換わらない。
// ---------------------------------------------------------------------------

internal val Indigo = Color(0xFF4D3DFF)
internal val Aqua = Color(0xFF00C2FF)
internal val Coral = Color(0xFFFF6B8A)
internal val LogoNavy = Color(0xFF050E1D)
internal val VigilithSlate = Color(0xFF314158) // アイコン／起動面。黒曜石を背景から分離する月光色
internal val LogoPurple = Color(0xFF9A5CE6)   // 起動OPの背面発光（Aqua/Indigoと3色で使う）

// 「読む」画面用の低彩度版（Indigo/Aqua/Coralの彩度を3〜4割落としたもの）。
// 長時間滞在する閲覧画面では背景より本文を前に出す。
internal val MutedIndigo = Color(0xFF6E63E3)
internal val MutedAqua = Color(0xFF54B5D4)
internal val MutedCoral = Color(0xFFE28D9F)

// ---------------------------------------------------------------------------
// 2. 明暗それぞれの実体
//    値を書くのはここだけ。§3のトークンは、現在のテーマからこれを引くだけの窓口。
// ---------------------------------------------------------------------------

internal val LightAppColors = AppColorScheme(
    panel = Color(0xFFFDFEFF),
    codePanel = Color(0xFFF1F4F8),
    panelTinted = Color(0xFFF7F3FF),
    panelBlue = Color(0xFFF0F4FF),
    panelChip = Color(0xFFEEF0FF),
    panelRow = Color(0xFFF3F4FB),
    panelBubble = Color(0xFFF0F2FB),
    skeletonBase = Color(0xFFECEEF6),
    skeletonHighlight = Color(0xFFF6F7FB),
    panelDivider = Color(0xFFD6DDF5),
    panelDividerStrong = Color(0xFFB0BBEE),
    chatDivider = Color(0xFFE6E9F5),
    contentDivider = Color(0xFFCCCCCC),
    checkboxOutline = Color(0xFFAAAAAA),
    onSurface = Color(0xFF202124),
    onSurfaceMuted = Color(0xFF555555),
    onSurfaceSubtle = Color(0xFF666666),
    onSurfaceFaint = Color(0xFF777777),
    onSurfaceHint = Color(0xFF888888),
    onSurfaceDisabled = Color(0xFF999999),
    onSurfaceMetaBlue = Color(0xFF8A90A8),
    onVibrant = Color.White,
    onVibrantMuted = Color(0xFFEAF7FF),
    linkText = Color(0xFF2563EB),
    errorText = Color(0xFFCC0000),
    errorSurface = Color(0xFFCC0000),
    onErrorSurface = Color.White,      // #CC0000 上で 5.89
    dangerAction = Color(0xFFD32F2F),
    onDangerAction = Color.White,      // #D32F2F 上で 4.98
    successMark = Color(0xFF4CAF50),
    failureMark = Color(0xFFEF5350),
    relatedHeading = Color(0xFF7B6FFF),
    aiHeading = Color(0xFF16B8A6),
    accentText = Indigo,
    accentSurface = Indigo,
    onAccentSurface = Color.White,
    accentGlass = Indigo.copy(alpha = 0.55f),
    navBar = Indigo,
    navIndicator = Aqua,
    vigilithHalo = Color.Transparent,
    buttonPrimary = Color(0xFFFF3D71),
    buttonSecondary = Color(0xFF16B8A6),
    buttonAi = Indigo,
    // ラベルは塗りごとに変える。省略するとMaterial3の既定 onPrimary（純白）が効き、
    // ピンク3.41／緑2.49までコントラストが落ちる（AAは4.5:1）。Indigoだけ白が正しい。
    onButtonPrimary = Color(0xFF000000),   // #FF3D71 上で 6.16
    onButtonSecondary = Color(0xFF000000), // #16B8A6 上で 8.44
    onButtonAi = Color.White,              // #4D3DFF 上で 6.13
    appGradient = Brush.linearGradient(
        colors = listOf(Indigo, Aqua, Coral),
        start = Offset(0f, Float.POSITIVE_INFINITY),
        end = Offset(Float.POSITIVE_INFINITY, 0f)
    ),
    readingGradient = Brush.linearGradient(
        colors = listOf(MutedIndigo, MutedAqua, MutedCoral),
        start = Offset(0f, Float.POSITIVE_INFINITY),
        end = Offset(Float.POSITIVE_INFINITY, 0f)
    )
)

/**
 * ダーク。土台は `QuizScreen` が単独で持っていた暗色配色（背景 #1A1C2E ／面 #2A2D45）。
 * 机上で新しい暗色を起こすより、既に実装され目視を通っている値から広げるほうが安全。
 *
 * 文字・アイコンは面に対して4.5:1、塗りボタンは3:1（輪郭）＋ラベル4.5:1を満たす。
 * 数値は `AppColorContrastTest` で固定している。
 */
internal val DarkAppColors = AppColorScheme(
    panel = Color(0xFF2A2D45),
    codePanel = Color(0xFF202234),
    panelTinted = Color(0xFF2B2740),
    panelBlue = Color(0xFF22273F),
    panelChip = Color(0xFF2E3350),
    panelRow = Color(0xFF262A40),
    panelBubble = Color(0xFF242840),
    skeletonBase = Color(0xFF242840),
    skeletonHighlight = Color(0xFF30354F),
    panelDivider = Color(0xFF3A3F5F),
    panelDividerStrong = Color(0xFF4A5178),
    chatDivider = Color(0xFF343A56),
    contentDivider = Color(0xFF444A66),
    checkboxOutline = Color(0xFF6E7699),
    onSurface = Color(0xFFE6EAF2),
    onSurfaceMuted = Color(0xFFC3C9DA),
    onSurfaceSubtle = Color(0xFFB4BACD),
    onSurfaceFaint = Color(0xFFA6ADC2),
    onSurfaceHint = Color(0xFF99A1B8),
    onSurfaceDisabled = Color(0xFF949CB4),
    onSurfaceMetaBlue = Color(0xFF9AA2BC),
    onVibrant = Color.White,
    onVibrantMuted = Color(0xFFC6D3E6),
    linkText = Color(0xFF7BA6FF),
    errorText = Color(0xFFFF6B6B),
    // 暗所の赤は明るいので、その上に載る文字は黒。白のままだと 2.78 まで落ちる。
    errorSurface = Color(0xFFFF6B6B),
    onErrorSurface = Color(0xFF000000), // #FF6B6B 上で 7.57
    dangerAction = Color(0xFFFF7A7A),
    onDangerAction = Color(0xFF000000), // #FF7A7A 上で 8.32
    successMark = Color(0xFF66BB6A),
    failureMark = Color(0xFFEF5350),
    relatedHeading = Color(0xFFA79EFF),
    aiHeading = Color(0xFF3FD9C6),
    // 文字は塗りより明るくする必要がある（#8A80FF は暗面で 4.24 で、塗りの3:1は満たすが
    // 文字の4.5:1には届かない）。テキスト用は #A79EFF（5.76）。
    accentText = Color(0xFFA79EFF),
    accentSurface = Color(0xFF8A80FF),
    onAccentSurface = Color(0xFF000000),
    // 透過のままだと暗い面に沈んで輪郭が出ないため、不透明の濃紫にする（白文字で 8.64）。
    accentGlass = Color(0xFF4A4480),
    navBar = Color(0xFF232640),
    navIndicator = Aqua,
    vigilithHalo = LogoPurple.copy(alpha = 0.22f),
    // ピンクと緑は暗面でも基準を満たすため据え置き。IndigoだけAAを割る（2.83）ので
    // 色相245・彩度100を保ったまま明度を62%→75%へ上げた明るい版を使う。
    buttonPrimary = Color(0xFFFF3D71),
    buttonSecondary = Color(0xFF16B8A6),
    buttonAi = Color(0xFF8A80FF),
    // 暗面では塗りが明るくなるため、3役ともラベルは黒で統一できる。
    onButtonPrimary = Color(0xFF000000),
    onButtonSecondary = Color(0xFF000000),
    onButtonAi = Color(0xFF000000),
    // グラデーションは「背景」から降格させ、3色の色相だけを暗所へ残す。
    // 明るい面のまま暗くすると濁るため、彩度ではなく明度で沈める。
    appGradient = Brush.linearGradient(
        colors = listOf(Color(0xFF231E4A), Color(0xFF13253A), Color(0xFF33202C)),
        start = Offset(0f, Float.POSITIVE_INFINITY),
        end = Offset(Float.POSITIVE_INFINITY, 0f)
    ),
    // 「読む」画面はさらに静かに。触る画面との性質の差はダークでも保つ。
    readingGradient = Brush.linearGradient(
        colors = listOf(Color(0xFF1C1B33), Color(0xFF151E2C), Color(0xFF261B24)),
        start = Offset(0f, Float.POSITIVE_INFINITY),
        end = Offset(Float.POSITIVE_INFINITY, 0f)
    )
)

// ---------------------------------------------------------------------------
// 3. 役割トークン（画面はこれだけを見る）
//    現在のテーマから引くだけの窓口。@Composable にすることで、
//    既存の呼び出し側を1行も変えずにテーマ追従へ切り替えられる。
// ---------------------------------------------------------------------------

private inline val current: AppColorScheme
    @Composable @ReadOnlyComposable get() = LocalAppColors.current

// -- 面 --
internal val Panel: Color @Composable @ReadOnlyComposable get() = current.panel
internal val CodePanel: Color @Composable @ReadOnlyComposable get() = current.codePanel
internal val PanelTinted: Color @Composable @ReadOnlyComposable get() = current.panelTinted
internal val PanelBlue: Color @Composable @ReadOnlyComposable get() = current.panelBlue
internal val PanelChip: Color @Composable @ReadOnlyComposable get() = current.panelChip
internal val PanelRow: Color @Composable @ReadOnlyComposable get() = current.panelRow
internal val PanelBubble: Color @Composable @ReadOnlyComposable get() = current.panelBubble
internal val SkeletonBase: Color @Composable @ReadOnlyComposable get() = current.skeletonBase
internal val SkeletonHighlight: Color @Composable @ReadOnlyComposable get() = current.skeletonHighlight

// -- 罫線 --
internal val PanelDivider: Color @Composable @ReadOnlyComposable get() = current.panelDivider
internal val PanelDividerStrong: Color @Composable @ReadOnlyComposable get() = current.panelDividerStrong
internal val ChatDivider: Color @Composable @ReadOnlyComposable get() = current.chatDivider
internal val ContentDivider: Color @Composable @ReadOnlyComposable get() = current.contentDivider
internal val CheckboxOutline: Color @Composable @ReadOnlyComposable get() = current.checkboxOutline

// -- 文字 --
internal val OnSurface: Color @Composable @ReadOnlyComposable get() = current.onSurface
internal val OnSurfaceMuted: Color @Composable @ReadOnlyComposable get() = current.onSurfaceMuted
internal val OnSurfaceSubtle: Color @Composable @ReadOnlyComposable get() = current.onSurfaceSubtle
internal val OnSurfaceFaint: Color @Composable @ReadOnlyComposable get() = current.onSurfaceFaint
internal val OnSurfaceHint: Color @Composable @ReadOnlyComposable get() = current.onSurfaceHint
internal val OnSurfaceDisabled: Color @Composable @ReadOnlyComposable get() = current.onSurfaceDisabled
internal val OnSurfaceMetaBlue: Color @Composable @ReadOnlyComposable get() = current.onSurfaceMetaBlue
internal val OnVibrant: Color @Composable @ReadOnlyComposable get() = current.onVibrant
internal val OnVibrantMuted: Color @Composable @ReadOnlyComposable get() = current.onVibrantMuted
internal val LinkText: Color @Composable @ReadOnlyComposable get() = current.linkText

// -- 意味色 --
internal val ErrorText: Color @Composable @ReadOnlyComposable get() = current.errorText
internal val ErrorSurface: Color @Composable @ReadOnlyComposable get() = current.errorSurface
internal val OnErrorSurface: Color @Composable @ReadOnlyComposable get() = current.onErrorSurface
internal val DangerAction: Color @Composable @ReadOnlyComposable get() = current.dangerAction
internal val OnDangerAction: Color @Composable @ReadOnlyComposable get() = current.onDangerAction
internal val SuccessMark: Color @Composable @ReadOnlyComposable get() = current.successMark
internal val FailureMark: Color @Composable @ReadOnlyComposable get() = current.failureMark
internal val RelatedHeading: Color @Composable @ReadOnlyComposable get() = current.relatedHeading
internal val AiHeading: Color @Composable @ReadOnlyComposable get() = current.aiHeading
internal val AccentText: Color @Composable @ReadOnlyComposable get() = current.accentText
internal val AccentSurface: Color @Composable @ReadOnlyComposable get() = current.accentSurface
internal val OnAccentSurface: Color @Composable @ReadOnlyComposable get() = current.onAccentSurface
internal val AccentGlass: Color @Composable @ReadOnlyComposable get() = current.accentGlass

// -- ナビゲーション --
internal val NavBar: Color @Composable @ReadOnlyComposable get() = current.navBar
internal val NavIndicator: Color @Composable @ReadOnlyComposable get() = current.navIndicator
internal val VigilithHalo: Color @Composable @ReadOnlyComposable get() = current.vigilithHalo

// -- ボタン --
// ボタン配色の3役ルール（これ以外の色をボタンに使わない）:
//   ButtonPrimary（ピンク）＝画面の主アクション
//   ButtonSecondary（緑）＝補助・代替アクション
//   ButtonAi（Indigo系）＝AI生成系アクション
//   ※ナビ（戻る等）はOutlinedButtonで控えめに
//   ※同一画面にAI系ボタンが複数並ぶ場合は、主たる方をButtonPrimaryにして識別性を優先する
//   ※ラベル色（OnButton*）は必ず明示して渡すこと（理由は LightAppColors のコメント）
internal val ButtonPrimary: Color @Composable @ReadOnlyComposable get() = current.buttonPrimary
internal val ButtonSecondary: Color @Composable @ReadOnlyComposable get() = current.buttonSecondary
internal val ButtonAi: Color @Composable @ReadOnlyComposable get() = current.buttonAi
internal val OnButtonPrimary: Color @Composable @ReadOnlyComposable get() = current.onButtonPrimary
internal val OnButtonSecondary: Color @Composable @ReadOnlyComposable get() = current.onButtonSecondary
internal val OnButtonAi: Color @Composable @ReadOnlyComposable get() = current.onButtonAi

// -- 背景 --
internal val AppGradient: Brush @Composable @ReadOnlyComposable get() = current.appGradient
internal val ReadingGradient: Brush @Composable @ReadOnlyComposable get() = current.readingGradient

// ---------------------------------------------------------------------------
// 4. クイズ画面の暗色面
//    QuizScreen は単独でダーク配色として作られており、テーマに追従しない
//    （ダーク時は周囲と馴染み、ライト時は従来どおり暗いまま）。
//    集中画面としての意図的な例外なので、明暗で置き換えず固定値のまま残す。
// ---------------------------------------------------------------------------

internal val QuizSurface = Color(0xFF1A1C2E)      // 画面背景
internal val QuizPanel = Color(0xFF2A2D45)        // 問題カード・未選択の選択肢
internal val QuizPanelDim = Color(0xFF222436)     // 選択されなかった選択肢
internal val QuizOutline = Color(0xFF3D4070)      // 選択肢の枠
internal val QuizCorrectPanel = Color(0xFF1B3A2A) // 正解の下地
internal val QuizWrongPanel = Color(0xFF3A1B1B)   // 誤答の下地
internal val OnQuizSurface = Color(0xFFEEEEFF)    // 本文
internal val OnQuizAccent = Color(0xFFB0B8FF)     // 見出し・戻る導線
internal val OnQuizMuted = Color(0xFFCCCCDD)      // 解説
internal val OnQuizFaint = Color(0xFF777799)      // 補助
internal val OnQuizDisabled = Color(0xFF555577)   // 選ばれなかった選択肢の文字
internal val OnQuizLoading = Color(0xFFAAAAAA)    // 生成中メッセージ
internal val OnQuizError = Color(0xFFFF6B6B)      // エラー文言（旧 #CC0000 は 2.86 で読めなかった）
internal val OnQuizEmpty = Color(0xFFC3C9DA)      // 生成0件の案内（旧 #555555 は 2.25 で読めなかった）
