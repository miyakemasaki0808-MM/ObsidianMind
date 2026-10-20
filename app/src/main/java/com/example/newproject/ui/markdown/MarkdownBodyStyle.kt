package com.example.newproject.ui.markdown

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 本文のブロックの書式と寸法。**縦の流しの描画と、見開きの計測・描画が同じ値を読む**（→ features/note_spread.md §8 判断8）。
 * 別々に書くと、見開きのページの末尾がはみ出すか空く。
 *
 * 文字の書式は `LocalTextStyle`（MaterialTheme の bodyLarge）に重ねて使う — 字間などはそちらから来るので、測るときも重ねた最終の形で測る。
 * **改行の規則は行の頭から決まるもの（`LineBreak.Simple`）を明示する。** 見開きは割った段落の残りを組み直して描くので、
 * 段落全体を見て改行を決める規則だと行の切れ目がずれる（→ 同 判断5）。今の既定も同じなので、縦の流しの見え方は変わらない。
 */
internal object MarkdownBodyStyle {
    /** ブロックの間の余白。 */
    val BlockSpacing: Dp = 8.dp

    val Paragraph = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, lineBreak = LineBreak.Simple)

    fun heading(level: Int): TextStyle {
        val size = when (level) {
            1 -> 24
            2 -> 21
            3 -> 19
            4 -> 17
            5 -> 15
            else -> 14
        }
        return TextStyle(
            fontSize = size.sp,
            lineHeight = (size + 6).sp,
            fontWeight = FontWeight.Bold,
            fontStyle = if (level >= 6) FontStyle.Italic else FontStyle.Normal,
            lineBreak = LineBreak.Simple
        )
    }

    fun headingTopPadding(level: Int): Dp = if (level <= 2) 8.dp else 4.dp

    /** リストの1段あたりの字下げ。深さは字下げだけで示し、記号は段によらず変えない。 */
    val ListIndentPerDepth: Dp = 16.dp
    val ListMarkerGap: Dp = 8.dp
    val ListItemSpacing: Dp = 4.dp
    val CheckboxSize: Dp = 20.dp

    val Quote = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontStyle = FontStyle.Italic, lineBreak = LineBreak.Simple)
    val QuoteBarWidth: Dp = 4.dp
    val QuoteTextStart: Dp = 12.dp
    val QuoteVerticalPadding: Dp = 4.dp

    val TableCell = TextStyle(fontSize = 14.sp, lineBreak = LineBreak.Simple)
    val TableHeader = TableCell.copy(fontWeight = FontWeight.Bold)
    val TableCellPaddingHorizontal: Dp = 8.dp
    val TableCellPaddingVertical: Dp = 6.dp

    val Code = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontFamily = FontFamily.Monospace, lineBreak = LineBreak.Simple)
    val CodePadding: Dp = 12.dp

    val RuleThickness: Dp = 1.dp
    val RuleVerticalPadding: Dp = 8.dp
}
