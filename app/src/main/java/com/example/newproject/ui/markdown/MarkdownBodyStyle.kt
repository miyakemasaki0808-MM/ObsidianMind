package com.example.newproject.ui.markdown

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.domain.markdown.MarkdownBlock

/**
 * 本文のブロックの書式と寸法。**縦の流しの描画と、見開きの計測・描画が同じ値を読む**（→ features/note_spread.md §8 判断8）。
 * 別々に書くと、見開きのページの末尾がはみ出すか空く。値の正本は features/note_typesetting.md §5.2。
 *
 * 文字の書式は `LocalTextStyle`（MaterialTheme の bodyLarge）に重ねて使う — 測るときも重ねた最終の形で測る。
 * **字間はどの書式も 0 を明示する。** 明示しないと bodyLarge の欧文向けの字間（0.5sp）が和文に残る。
 * **改行の規則は行の頭から決まるもの（`LineBreak.Simple`）を明示する。** 見開きは割った段落の残りを組み直して描くので、
 * 段落全体を見て改行を決める規則だと行の切れ目がずれる（→ 同 判断5）。
 * **アプリの書式に斜体を使わない。** 日本語の斜体は傾けただけの字になる。書き手が `*…*` で付けた斜体は [inlineMarkdown] が残す。
 */
internal object MarkdownBodyStyle {
    /** ブロックの間の余白。余白はこれと [headingSpaceAbovePx] の2つだけで作る（見開きの割り方がこの2つで高さを測る）。 */
    val BlockSpacing: Dp = 14.dp

    /** 約物を詰める。見出しだけに掛け、本文には掛けない（行ごとに字の並びが不揃いになる）。 */
    private const val PALT = "palt"

    /** 本文の末尾のタグの札の文字。 */
    val Tag = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.sp, lineBreak = LineBreak.Simple)

    val TagGap: Dp = 6.dp
    val TagPaddingHorizontal: Dp = 9.dp
    val TagPaddingVertical: Dp = 3.dp

    /** 札の行の上の罫の太さと、罫と札の間。 */
    val TagRowRule: Dp = 1.dp
    val TagRowGap: Dp = 18.dp

    val Paragraph = TextStyle(fontSize = 16.sp, lineHeight = 30.sp, letterSpacing = 0.sp, lineBreak = LineBreak.Simple)

    /** リード文（本文の最初のブロックが段落のとき → [isLeadParagraph]）。行の高さは本文と同じ。 */
    val Lead = TextStyle(
        fontSize = 16.5.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
        lineBreak = LineBreak.Simple
    )

    fun heading(level: Int): TextStyle {
        val size = when (level) {
            1 -> 21
            2 -> 19
            3 -> 18
            4 -> 17
            else -> 16
        }
        return TextStyle(
            fontSize = size.sp,
            lineHeight = (size + 8).sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.sp,
            fontFeatureSettings = PALT,
            lineBreak = LineBreak.Simple
        )
    }

    fun headingTopPadding(level: Int): Dp = if (hasHeadingMark(level)) 18.dp else 10.dp

    /** H1・H2 の上に、藍の短い線を置く。 */
    fun hasHeadingMark(level: Int): Boolean = level <= 2
    val HeadingMarkWidth: Dp = 22.dp
    val HeadingMarkHeight: Dp = 2.dp
    val HeadingMarkGap: Dp = 10.dp

    /**
     * 見出しの文字より上にある高さ（上の余白と、線とその下の間）の画素。**見開きの計測はこの値を見出しの高さに足す。**
     * 描画（[MarkdownHeadingText]）は同じ部品を同じ順に積むので、ここを変えずに描画だけを変えると切れ端の高さが計測とずれる。
     * **描画と同じく部品を1つずつ画素へ丸めてから足す**（`padding`・`size`・`Spacer` はそれぞれ丸める。足してから丸めると、端数の密度で1画素ずれる）。
     */
    fun headingSpaceAbovePx(level: Int, density: Density): Int = with(density) {
        headingTopPadding(level).roundToPx() +
            if (hasHeadingMark(level)) HeadingMarkHeight.roundToPx() + HeadingMarkGap.roundToPx() else 0
    }

    /** コードの枠の中の字の幅。描画（左右の `padding`）と同じく片側ずつ画素へ丸めて引く。 */
    fun codeInnerWidthPx(widthPx: Int, density: Density): Int =
        widthPx - with(density) { CodePaddingHorizontal.roundToPx() * 2 }

    /** コードの枠の切れ端ごとに足す高さ（上下の余白と、札があれば札・間・罫・間）。各部を画素へ丸めてから足す。 */
    fun codeFragmentPaddingPx(labelHeightPx: Int?, density: Density): Int = with(density) {
        CodePaddingVertical.roundToPx() * 2 +
            (labelHeightPx?.let { it + CodeHeaderGap.roundToPx() * 2 + CodeHeaderRule.roundToPx() } ?: 0)
    }

    /** タグの札の幅。字の幅に、描画と同じく片側ずつ画素へ丸めた左右の余白を足す。 */
    fun tagChipWidthPx(textWidthPx: Int, density: Density): Int =
        textWidthPx + with(density) { TagPaddingHorizontal.roundToPx() * 2 }

    /** リストの1段あたりの字下げ。深さは字下げだけで示し、記号は段によらず変えない。 */
    val ListIndentPerDepth: Dp = 16.dp
    val ListMarkerGap: Dp = 8.dp
    val ListItemSpacing: Dp = 4.dp
    val CheckboxSize: Dp = 20.dp

    /**
     * 番号付きの箇条の番号の札（→ features/note_typesetting.md §5.4）。**字は原文の番号のまま**（「1.」）— 選択のコピーと余白メモの照らし直しが
     * 番号の字を読むので、見た目だけを札にする。札の幅は max([OrderedBadgeMinSize], 字の幅＋左右の余白)。見開きの計測も同じ式で測る。
     */
    val OrderedBadgeText = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp,
        lineBreak = LineBreak.Simple
    )
    val OrderedBadgeMinSize: Dp = 22.dp
    val OrderedBadgePaddingHorizontal: Dp = 5.dp
    val OrderedBadgeCorner: Dp = 6.dp

    /** コールアウトの中身と、引用の中の箇条の字。 */
    val Quote = TextStyle(fontSize = 16.sp, lineHeight = 30.sp, letterSpacing = 0.sp, lineBreak = LineBreak.Simple)

    /** 面を敷かない引用の行。大きな引用符に続けて、本文より少し大きく組む（→ features/note_typesetting.md §5.4）。 */
    val PullQuote = TextStyle(
        fontSize = 17.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
        lineBreak = LineBreak.Simple
    )

    /** 引用の頭の大きな引用符。飾りなので並べ方の寸法に入らない（字下げ [QuoteIndent] の中に描く）。 */
    val QuoteMarkText = TextStyle(fontSize = 34.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp)

    /** 引用の中身の字下げ。引用符はこの幅の中に描く。 */
    val QuoteIndent: Dp = 28.dp
    val QuoteVerticalPadding: Dp = 4.dp

    /** コールアウトの頭。種類の記号と言葉（[CalloutLabel]）の後に題を続ける。 */
    val CalloutLabelText = TextStyle(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp,
        lineBreak = LineBreak.Simple
    )
    val CalloutTitleText = TextStyle(
        fontSize = 16.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.sp,
        lineBreak = LineBreak.Simple
    )
    val CalloutLabelGap: Dp = 8.dp

    /** コールアウトの面（→ features/note_typesetting.md §5.4）。線画は文字と同じ倍率で大きくなるよう sp で持つ。 */
    val CalloutIconSize = 16.sp
    val CalloutPaddingHorizontal: Dp = 14.dp
    val CalloutPaddingVertical: Dp = 12.dp
    val CalloutCorner: Dp = 10.dp

    val TableCell = TextStyle(fontSize = 13.sp, letterSpacing = 0.sp, lineBreak = LineBreak.Simple)
    val TableHeader = TableCell.copy(fontWeight = FontWeight.Bold)
    val TableCellPaddingHorizontal: Dp = 12.dp
    val TableCellPaddingVertical: Dp = 9.dp
    val TableCorner: Dp = 10.dp

    /** コードの枠の中の字（端末の等幅）。枠はテーマに関わらず暗い窓（→ features/note_typesetting.md 判断14）。 */
    val Code = TextStyle(
        fontSize = 13.sp,
        lineHeight = 22.sp,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 0.sp,
        lineBreak = LineBreak.Simple
    )
    val CodePaddingHorizontal: Dp = 14.dp
    val CodePaddingVertical: Dp = 12.dp
    val CodeWindowCorner: Dp = 10.dp

    /** コードの枠の頭の行の言語の札。1行に収め、はみ出す分は切る。 */
    val CodeLabel = TextStyle(
        fontSize = 11.sp,
        lineHeight = 16.sp,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 0.sp,
        lineBreak = LineBreak.Simple
    )

    /** 札の下の間・罫・罫の下の間。見開きの計測は札の高さにこれを足す（→ [codeFragmentPaddingPx]）。 */
    val CodeHeaderGap: Dp = 10.dp
    val CodeHeaderRule: Dp = 1.dp

    val RuleThickness: Dp = 1.dp
    val RuleVerticalPadding: Dp = 8.dp

    /**
     * 本文の列の最大幅（16sp で約32字）。読みやすい1行は和文で30字台（→ features/note_typesetting.md 判断6）。
     * 字数ではなく dp で決めるので、文字の倍率を上げた人の1行は広がらない。
     */
    val ColumnMaxWidth: Dp = 520.dp

    /**
     * 幅 [available] の中で、本文の列を中央に置くための左右それぞれの余白。広くなければ 0。
     * **一覧は幅いっぱいのまま、この余白を内側に足す** — 一覧ごと絞ると、余白の側で払っても本文が送れない。
     */
    fun columnInset(available: Dp): Dp = ((available - ColumnMaxWidth) / 2).coerceAtLeast(0.dp)
}

/**
 * 本文の [index] 番目のブロックをリード文として組むか。**本文の最初のブロックが段落のときだけ**（見出しより前の段落）。
 * 縦の流しの描画と見開きの計測が同じ関数を呼ぶ。片方だけがリード文の書式で組むと、切れ端の高さが計測とずれる。
 */
internal fun isLeadParagraph(index: Int, block: MarkdownBlock): Boolean = index == 0 && block is MarkdownBlock.Paragraph
