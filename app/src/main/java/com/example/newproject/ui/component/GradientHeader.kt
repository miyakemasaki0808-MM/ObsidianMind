package com.example.newproject.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.ui.theme.GradientHeaderScrim
import com.example.newproject.ui.theme.OnGradientHeaderSubtitle
import com.example.newproject.ui.theme.OnGradientHeaderTitle

/**
 * グラデーション直上に置く画面見出し。**背景を自分で持つ**のが要点。
 *
 * ライトのグラデーション上では白文字が読めない（Aqua停止色に対し 2.07、副題 1.89）。
 * 28sp Bold は大文字扱いで3:1に緩むが、それすら満たさない。
 *
 * **解き方を「暗くする」から「白で霞ませる」へ反転させた。** 暗幕は帯として重く出て、
 * 角丸を付けるとカードにしか見えない。白を薄く重ねると停止色が持ち上がり、そこへ
 * 濃い文字を置ける。白文字と違い、濃い側は最も暗い Indigo 停止色でも余裕がある
 * （α=0.35 でタイトル 6.15／副題 5.12）。
 *
 * ダークは何も敷かない。暗いグラデーションでは白文字が元から基準を満たしており、
 * 霞ませる意味が無いため。**したがって面と文字は明暗で反転する。**
 *
 * 文字領域とフェードは別の要素に分ける。1つの縦グラデーションで兼ねると、一定の
 * 濃さを保つ割合が要素全体の高さに対する比になるため、**副題が2行に折り返した瞬間に
 * フェードの開始位置が文字へ食い込む**。
 *
 * **[leading]・[trailing] は題名の行にだけ並べ、副題はその下に全幅で通す。** 題名と副題の列の横に並べると、
 * ボタンの幅だけ副題が細い列へ押し込まれて折り返す。題名とボタンが1行に収まらないときだけ、[trailing] を題名の下の行へ回す（→ [HeaderTitleRow]）。
 * 題名の行は丸ボタンの高さ（[ICON_PILL_SIZE]）を最初から持つので、ボタンの有無で見出しの高さは変わらない
 * （→ system/theme_and_ui_refactor.md 判断5）。
 *
 * [fade] を切ると、文字領域の下のフェードを描かない。机の画面の帯のように見出しを箱の中へ閉じ込める所では、
 * フェードが箱の中ほどの横縞に見える（→ `DeskBar`）。
 *
 * 面はメイン領域の**全幅**へ広げる。角丸を付けず端まで抜くことで、
 * 「上に乗ったカード」ではなく「上部が霞んだ背景」として読ませる。
 * **広げる幅は画面の余白から取るので、見出しを置く画面は [screenContentPadding] で余白を取る。**
 *
 * [bleedEnd] を切ると、右へは広げない。左右2列の左の列のように、右が画面の端ではなく列の間の溝である所で使う。
 * 右の内側の余白も同時に外すので、題名とボタンの位置は変わらない。
 */
@Composable
internal fun GradientHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    /** 副題の行に文字の代わりに置くもの。渡すと [subtitle] より優先する（読む画面の居場所の1行 → features/rediscover.md §5.1）。 */
    subtitleContent: (@Composable () -> Unit)? = null,
    titleSize: TextUnit = 28.sp,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    fade: Boolean = true,
    bleedEnd: Boolean = true
) {
    val scrim = GradientHeaderScrim
    Column(modifier = modifier.fillMaxWidth().bleedHorizontally(SCREEN_HORIZONTAL_PADDING, bleedEnd)) {
        // 文字領域。濃さは一様で、高さは中身に任せる。
        // 内側の余白を bleed と同じにすることで、外へ広げても文字の位置は動かない。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(scrim)
                .padding(
                    start = SCREEN_HORIZONTAL_PADDING,
                    end = if (bleedEnd) SCREEN_HORIZONTAL_PADDING else 0.dp,
                    top = 12.dp,
                    bottom = 12.dp
                )
        ) {
            HeaderTitleRow(
                modifier = Modifier.fillMaxWidth().heightIn(min = ICON_PILL_SIZE),
                leading = leading,
                trailing = trailing
            ) {
                Text(text = title, color = OnGradientHeaderTitle, fontSize = titleSize, fontWeight = FontWeight.Bold)
            }
            if (subtitleContent != null) {
                Box(modifier = Modifier.padding(top = 4.dp)) { subtitleContent() }
            } else if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = OnGradientHeaderSubtitle,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        // フェード。文字は載らないので高さを固定できる。
        if (fade) {
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(SCRIM_FADE_HEIGHT)
                    .background(Brush.verticalGradient(listOf(scrim, Color.Transparent)))
            )
        }
    }
}

/**
 * 題名の行。左から [leading]・題名・[trailing] を並べ、題名は残りの幅をもらう。
 * **題名を1行で置いた幅と [trailing] が収まらないときだけ、[trailing] を題名の下の行の右へ回す**（→ [headerTrailingBesideTitle]）。
 * 題名の文字は縮めない — 大きい文字を選んだ人に、題名だけ小さく見せない。
 * 1行目は [modifier] の最低の高さ（丸ボタンの高さ）を持ち、中身はその中で上下の中央に置く。
 */
@Composable
private fun HeaderTitleRow(
    modifier: Modifier,
    leading: @Composable (() -> Unit)?,
    trailing: @Composable (() -> Unit)?,
    title: @Composable () -> Unit
) {
    Layout(contents = listOf(leading ?: {}, title, trailing ?: {}), modifier = modifier) { (leadingSlot, titleSlot, trailingSlot), constraints ->
        val gap = TITLE_ROW_GAP.roundToPx()
        val loose = Constraints(maxWidth = constraints.maxWidth)
        val leadingPlaceable = leadingSlot.firstOrNull()?.measure(loose)
        val trailingPlaceable = trailingSlot.firstOrNull()?.measure(loose)
        val titleMeasurable = titleSlot.single()
        val leadingWidth = leadingPlaceable?.let { it.width + gap } ?: 0
        val titleOneLine = titleMeasurable.maxIntrinsicWidth(Constraints.Infinity)
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth
        else leadingWidth + titleOneLine + (trailingPlaceable?.let { it.width + gap } ?: 0)
        // 題名の行に並べる [trailing] と、下の行へ回す [trailing]。どちらか一方だけが残る。
        val beside = trailingPlaceable?.takeIf {
            headerTrailingBesideTitle(rowWidth = width, titleWidth = titleOneLine, leadingWidth = leadingPlaceable?.width, trailingWidth = it.width, gap = gap)
        }
        val below = trailingPlaceable?.takeIf { beside == null }
        val titlePlaceable = titleMeasurable.measure(
            Constraints.fixedWidth((width - leadingWidth - (beside?.let { it.width + gap } ?: 0)).coerceAtLeast(0))
        )
        val firstRow = maxOf(constraints.minHeight, leadingPlaceable?.height ?: 0, titlePlaceable.height, beside?.height ?: 0)
        val rowGap = STACKED_ROW_GAP.roundToPx()
        val secondRow = below?.let { rowGap + it.height } ?: 0
        layout(width, firstRow + secondRow) {
            leadingPlaceable?.placeRelative(0, (firstRow - leadingPlaceable.height) / 2)
            titlePlaceable.placeRelative(leadingWidth, (firstRow - titlePlaceable.height) / 2)
            beside?.placeRelative(width - beside.width, (firstRow - beside.height) / 2)
            below?.placeRelative(width - below.width, firstRow + rowGap)
        }
    }
}

/**
 * 見出しの [trailing] を題名の行に並べるか。題名を1行で置いた幅・左の部品・ボタンと、その間の [gap] が [rowWidth] に収まれば並べる。
 * 収まらなければボタンを題名の下の行へ回し、題名を語の途中で割らない（→ system/theme_and_ui_refactor.md）。
 */
internal fun headerTrailingBesideTitle(rowWidth: Int, titleWidth: Int, leadingWidth: Int?, trailingWidth: Int, gap: Int): Boolean {
    val leading = leadingWidth?.let { it + gap } ?: 0
    return leading + titleWidth + gap + trailingWidth <= rowWidth
}

/** 題名の行の部品の間。 */
private val TITLE_ROW_GAP = 12.dp

/** ボタンを題名の下の行へ回したときの、行の間。 */
private val STACKED_ROW_GAP = 8.dp

/**
 * 親が与えた左右の余白の外側まで広がる。[end] が偽なら左だけに広がる。
 *
 * 各画面は本文用に左右の余白を持っており、その内側で背景を描くと帯が浮いたカードに
 * 見える。見出しの面だけは端まで抜きたいので、測定時に横幅を広げて負のオフセットで
 * 戻す。**[bleed] が親の余白と食い違うと、見出しの面だけが端からずれる** —
 * だから余白と同じ定数から渡す（→ [screenContentPadding]）。
 */
private fun Modifier.bleedHorizontally(bleed: Dp, end: Boolean = true) = this.layout { measurable, constraints ->
    val extra = bleed.roundToPx() * if (end) 2 else 1
    val widened = constraints.copy(
        minWidth = constraints.minWidth + extra,
        maxWidth = if (constraints.maxWidth == Constraints.Infinity) {
            Constraints.Infinity
        } else {
            constraints.maxWidth + extra
        }
    )
    val placeable = measurable.measure(widened)
    // 親には元の幅で報告する。広げた分は描画だけに使い、後続の配置をずらさない。
    layout(placeable.width - extra, placeable.height) {
        placeable.place(-bleed.roundToPx(), 0)
    }
}

/**
 * 見出しを置く画面の本文の余白。**左右は [GradientHeader] が面を広げる幅と同じ値である**ので、
 * 余白を数値で書き写さず、必ずこれで取る（片方だけ変えると見出しだけがずれる）。
 */
internal fun Modifier.screenContentPadding(): Modifier =
    padding(start = SCREEN_HORIZONTAL_PADDING, end = SCREEN_HORIZONTAL_PADDING, top = 8.dp, bottom = 12.dp)

/**
 * [screenContentPadding] の左右の余白の外まで、見出しの面と同じ幅だけ広げる。**中身は [screenHorizontalPadding] で元の位置へ戻す。**
 *
 * 中身を自分の枠で切る器（ノート画面の余白メモのシート）を、余白の内側に置いたまま画面の端まで届かせるのに使う。
 * 切られると、見出しの霞が本文の列の幅で止まり、題名が霞の左端に付く。**片方だけ使うと中身が余白の分ずれる。**
 */
internal fun Modifier.bleedScreenPadding(): Modifier = bleedHorizontally(SCREEN_HORIZONTAL_PADDING)

/** [bleedScreenPadding] で広げた器の中身を、元の余白の位置へ戻す。 */
internal fun Modifier.screenHorizontalPadding(): Modifier = padding(horizontal = SCREEN_HORIZONTAL_PADDING)

private val SCREEN_HORIZONTAL_PADDING = 20.dp

/** 帯の下端で面を透明へ抜く区間の高さ。文字は載らない。 */
internal val SCRIM_FADE_HEIGHT = 10.dp
