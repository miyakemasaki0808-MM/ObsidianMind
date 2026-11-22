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
    CalloutRole.Caution -> CalloutLabel("⚠︎", "注意")
    CalloutRole.Example -> CalloutLabel("☞", "例・ヒント")
    null -> CalloutLabel(SUMMARY_MARK, callout.type)
}

private const val SUMMARY_MARK = "≡"
