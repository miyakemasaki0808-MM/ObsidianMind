package com.example.newproject.domain

import com.example.newproject.model.MemoPassage
import com.example.newproject.model.QuoteContext
import com.example.newproject.model.ReadingTraceLimits
import com.example.newproject.model.SectionRef
import com.example.newproject.model.state.DraftQuote

// ---------------------------------------------------------------------------
// 選択メニューの「引用して書く」で受け取った文字から、書きかけに付ける引用を作る（→ features/margin_quote.md §5.2）。
// 引用元が位置で決まらなければ本文の全体を探すので、ここの関数は Main の外で呼ぶ（`ThreadingScanTest`）。
// ---------------------------------------------------------------------------

/** 付ける引用と、書き込み先にする引用元の節。引用元が決まらなければ節は null（そのときの本文の節にする）。 */
internal data class QuoteAttachment(val quote: DraftQuote, val section: SectionRef?)

/**
 * 選んだ文字 [selected] から付ける引用を作る。空白だけなら null。
 *
 * [sourceBlock] は選んだ範囲の始まりを含むブロックで、位置から決まらなければ null（→ 判断11）。
 * [hintBlock] は境目で切るためだけの手掛かりで、**引用元の根拠にはしない** — 範囲の始まりが画面の外にありうるとき、見えている最初のブロック。
 */
internal fun quoteAttachmentFor(selected: String, sourceBlock: Int?, hintBlock: Int?, index: PassageIndex): QuoteAttachment? {
    val composed = composeQuote(splitAtBlocks(selected, sourceBlock ?: hintBlock, index)) ?: return null
    fun quote(context: QuoteContext?, choosing: Boolean = false) =
        DraftQuote(MemoPassage.Quoted(composed.text, context, composed.truncated), composed.cut, choosing)
    return when (val source = quoteSourceFor(composed.text, sourceBlock, index)) {
        is QuoteSource.Resolved -> QuoteAttachment(quote(source.context), index.sections[source.block])
        is QuoteSource.Ambiguous -> QuoteAttachment(quote(context = null, choosing = true), section = null)
        QuoteSource.Unresolved -> QuoteAttachment(quote(context = null), section = null)
    }
}

/**
 * 選んだ文字を、[from] のブロックから先の**ブロックの境目で改行に切る**（→ features/margin_quote.md §11）。
 * Compose の選択はブロックの間にも何も挟まないので、そのままだと最初の行（照らし直しの鍵）が次のブロックへはみ出して、
 * 引用元が見つからなくなる。1つのブロックの中で選んだとき・境目が見つからないときは、選んだ文字のまま返す。
 *
 * 切ったときの各行は照らし直しと同じ整え方（空白を畳む）をかけた文字になる。境目を探すのは引用の上限（1,024バイト）までで止め、
 * 見つからなくなったところから先は1行のまま残す。
 */
internal fun splitAtBlocks(selected: String, from: Int?, index: PassageIndex): String {
    if (from == null || from !in index.texts.indices) return selected
    val wanted = normalizeForMatch(selected)
    val head = wanted.take(ReadingTraceLimits.MAX_QUOTE_BYTES)
    val first = index.texts[from]
    if (head.isEmpty() || first.contains(head)) return selected
    val inFirst = overlap(first, head)
    if (inFirst == 0) return selected
    val lines = mutableListOf(wanted.substring(0, inFirst))
    var rest = wanted.substring(inFirst).trimStart()
    var length = inFirst
    var block = from + 1
    while (rest.isNotEmpty() && length <= ReadingTraceLimits.MAX_QUOTE_BYTES && block < index.texts.size) {
        val text = index.texts[block++]
        // 画像・区切り線は文字を持たないので、選んでいても境目にならない。
        if (text.isEmpty()) continue
        val line = when {
            rest.startsWith(text) -> text
            text.startsWith(rest) -> rest
            else -> break
        }
        lines += line
        length += line.length
        rest = rest.substring(line.length).trimStart()
    }
    if (rest.isNotEmpty()) lines += rest
    return lines.joinToString("\n")
}

/**
 * [tail] の末尾と [head] の先頭が重なる最長の長さ。境界の表（KMP の失敗関数）で、[head] の長さに比例する仕事で求める。
 * 区切りの NUL は整えた文字に現れない（[normalizeForMatch] が制御文字を落とす）。
 */
private fun overlap(tail: String, head: String): Int {
    val joined = head + '\u0000' + tail.takeLast(head.length)
    val border = IntArray(joined.length)
    for (i in 1 until joined.length) {
        var k = border[i - 1]
        while (k > 0 && joined[i] != joined[k]) k = border[k - 1]
        if (joined[i] == joined[k]) k++
        border[i] = k
    }
    return border.last()
}

/** 付ける場や、特定できない引用を押したときに並べる候補（→ 判断13）。[context] は候補のブロックでの前後の文字で、候補を見分けるために見せる。 */
internal data class QuoteCandidate(val block: Int, val section: SectionRef, val context: QuoteContext)

/** [quote] の鍵（最初の行）を含むブロックを、本文の順にすべて。**本文に比例するので Main の外で呼ぶ。** 候補は削らない。 */
internal fun quoteCandidates(quote: String, index: PassageIndex): List<QuoteCandidate> {
    val key = quoteKey(quote)
    return candidatesOf(key, index).mapNotNull { block ->
        contextIn(index.texts[block], key)?.let { QuoteCandidate(block, index.sections[block], it) }
    }
}
