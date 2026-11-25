package com.example.newproject.ui.markdown

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

