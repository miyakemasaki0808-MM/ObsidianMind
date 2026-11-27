package com.example.newproject.ui.markdown

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.unit.Density
import com.example.newproject.domain.markdown.Callout
import com.example.newproject.domain.markdown.CalloutRole

/**
 * コールアウトの頭に出す記号と言葉（→ docs/dev/system/markdown_rendering.md §8）。
 * **見分けの主役は言葉で、記号は飾り。** 読み上げは言葉だけを読み、選択とコピーには入れない（ノートに無い文字なので、
 * 入れると照らし直しで元の場所が見つからない）。記号は言葉と同じ色で塗れる文字に限り、絵文字の形では出さない。
 */
internal data class CalloutLabel(val mark: String, val text: String)

/** 役割の言葉と記号。知らない種類は、まとめの記号に書かれたままの種類名を添える。 */
internal fun calloutLabelOf(callout: Callout): CalloutLabel = when (callout.role) {
    CalloutRole.Quote -> CalloutLabel("❝", "引用")
    CalloutRole.Summary -> CalloutLabel(SUMMARY_MARK, "まとめ")
    CalloutRole.Question -> CalloutLabel("？", "問い")
    // U+FE0E で文字の形を指定する。無いと端末によっては色の付いた絵文字で出る。
    CalloutRole.Caution -> CalloutLabel("⚠\uFE0E", "注意")
    CalloutRole.Example -> CalloutLabel("☞", "例・ヒント")
    null -> CalloutLabel(SUMMARY_MARK, callout.type)
}

private const val SUMMARY_MARK = "≡"

/**
 * 題を言葉の下の行へ送るか。言葉（記号を含めて1行に並べた幅 [labelWidth]）が並びの幅 [rowWidth] の半分を超えたら送る —
 * 知らない種類名は書かれたまま出すので、長いと同じ行に題の幅が残らない。
 * **本文の並べ方（[MarkdownCalloutHeader]）と見開きの計測が同じ判定を使う**（別々に決めると、切れ端の高さが計測とずれる）。
 */
internal fun calloutTitleBelow(labelWidth: Float, rowWidth: Float): Boolean = labelWidth > rowWidth * CALLOUT_LABEL_MAX_FRACTION

/** 言葉が並びの幅のこの割合までなら、題を同じ行に続ける。 */
internal const val CALLOUT_LABEL_MAX_FRACTION = 0.5f


/**
 * コールアウトの面の種類（→ features/note_typesetting.md §5.4）。種類ごとに紙へ薄い面を敷く。
 * 引用（`quote`・`cite`）は面を敷かず、引用と同じ形で描く（[calloutFaceOf] が null を返す）。
 */
internal enum class CalloutFace { Summary, Question, Example, Caution }

/** 引用の中身から面の種類を引く。コールアウトでない引用と、引用の役割のコールアウトは null。知らない種類はまとめの面。 */
internal fun calloutFaceOf(callout: Callout?): CalloutFace? = when {
    callout == null -> null
    else -> when (callout.role) {
        CalloutRole.Quote -> null
        CalloutRole.Question -> CalloutFace.Question
        CalloutRole.Caution -> CalloutFace.Caution
        CalloutRole.Example -> CalloutFace.Example
        CalloutRole.Summary, null -> CalloutFace.Summary
    }
}

/** 頭の言葉の線画を置く場所の名前（`inlineContent` の鍵）。 */
internal const val CALLOUT_ICON_ID = "callout-icon"

/**
 * 頭の言葉の文字。**面を持つ種類は、記号の位置に線画を置く**（文字の幅の代わりに [calloutLabelPlaceholders] の大きさを取る）。
 * 面を持たない種類は今の記号の文字のまま。描画（[MarkdownCalloutHeader]）と見開きの計測がこれと同じ置き場所で組む。
 */
internal fun calloutLabelText(label: CalloutLabel, face: CalloutFace?): AnnotatedString = buildAnnotatedString {
    if (face != null) appendInlineContent(CALLOUT_ICON_ID, label.mark) else append(label.mark)
    append(" ")
    append(label.text)
}

/** 線画の置き場所。記号の文字の位置を、文字と同じ倍率で大きさの決まった四角にする。 */
internal fun calloutLabelPlaceholders(label: CalloutLabel, face: CalloutFace?): List<AnnotatedString.Range<Placeholder>> =
    if (face == null) {
        emptyList()
    } else {
        listOf(
            AnnotatedString.Range(
                Placeholder(MarkdownBodyStyle.CalloutIconSize, MarkdownBodyStyle.CalloutIconSize, PlaceholderVerticalAlign.TextCenter),
                0,
                label.mark.length
            )
        )
    }

/** 線画（24×24 の枠で描いた単色の線）。アイコン集は入れない。 */
internal fun calloutIconPath(face: CalloutFace): String = when (face) {
    CalloutFace.Summary -> "M12 3a9 9 0 1 1 0 18a9 9 0 1 1 0-18z M12 11v5 M12 7.5v.5"
    CalloutFace.Question -> "M12 3a9 9 0 1 1 0 18a9 9 0 1 1 0-18z M9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.7.3-1 .9-1 1.7v.3 M12 16.8v.4"
    CalloutFace.Example -> "M9 18h6 M10 21h4 M12 3a6 6 0 0 0-4 10.5c.7.7 1 1.5 1 2.5h6c0-1 .3-1.8 1-2.5A6 6 0 0 0 12 3z"
    CalloutFace.Caution -> "M12 4l9 16H3z M12 10v4 M12 17.5v.5"
}

/** 頭の記号の線画。言葉と同じ色で塗る。 */
@Composable
internal fun CalloutIcon(face: CalloutFace, color: Color) {
    val path = remember(face) { PathParser().parsePathString(calloutIconPath(face)).toPath() }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val scale = size.minDimension / 24f
        withTransform({ scale(scale, scale, pivot = Offset.Zero) }) {
            drawPath(path, color, style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/**
 * 頭の言葉の幅（px）。題を言葉の下の行へ送るか（[calloutTitleBelow]）を決める値で、描画の `maxIntrinsicWidth` と同じものを測る。
 */
internal fun calloutLabelWidthPx(
    measurer: TextMeasurer,
    baseStyle: TextStyle,
    density: Density,
    label: CalloutLabel,
    face: CalloutFace?
): Int = measurer.measure(
    calloutLabelText(label, face),
    baseStyle.merge(MarkdownBodyStyle.CalloutLabelText),
    placeholders = calloutLabelPlaceholders(label, face),
    density = density
).size.width
