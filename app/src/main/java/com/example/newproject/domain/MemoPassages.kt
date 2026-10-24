package com.example.newproject.domain

import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.domain.markdown.NoteSectionModel
import com.example.newproject.domain.markdown.inlineVisibleText
import com.example.newproject.model.MarginMemo
import com.example.newproject.model.MemoPassage
import com.example.newproject.model.QuoteContext
import com.example.newproject.model.ReadingTraceLimits
import com.example.newproject.model.SectionRef

// ---------------------------------------------------------------------------
// 余白メモの引用と、書き始めた頃に読んでいた位置（→ features/margin_quote.md §5.2・§5.4・§5.5）。
//
// **本文の位置は保存しない。** 表示のたびに文字で照らし直し、確定・特定できない・見つからないを分ける。
// 不確かなものを確定として扱わないことが、この段の要点である（同じ文が2度ある・元の段落が消えた・前後を控えていない）。
// ---------------------------------------------------------------------------

/** 引用を整えた結果。[cut] は上限で入らなかった部分で、**置く前に見せる**（黙って切らない → features/margin_quote.md §5.2）。 */
internal data class ComposedQuote(val text: String, val cut: String) {
    val truncated: Boolean get() = cut.isNotEmpty()
}

/**
 * 選んだ文字を引用として整える。空白だけなら null。
 *
 * 整え方はメモ本文（[composeMarginMemo]）と同じで、改行とタブ以外の制御文字を落として前後の空白を削る。
 * ここを変えると、容量の見積もり（エスケープで最悪2倍）が崩れる。
 */
internal fun composeQuote(raw: String): ComposedQuote? {
    val normalized = raw
        .filter { it == '\n' || it == '\t' || !it.isISOControl() }
        .trim()
    if (normalized.isEmpty()) return null
    val kept = takeFirstUtf8Bytes(normalized, ReadingTraceLimits.MAX_QUOTE_BYTES)
    return ComposedQuote(text = kept, cut = normalized.substring(kept.length))
}

/**
 * 照らし直しに使う本文の文字。ブロックごとに表示と同じ解釈器で文字にし（→ lessons L51）、空白を畳んである。
 *
 * **本文の大きさに比例するので、解析ごとに Main の外で1回作る**（→ features/margin_quote.md §5.5）。
 */
internal class PassageIndex internal constructor(
    /** ブロックごとの、照らし直しに使う文字。 */
    internal val texts: List<String>,
    /** ブロックごとの節。 */
    internal val sections: List<SectionRef>,
    /** 書き始めた位置として控えてよいブロックか（段落・リストの項目・引用の行・コード）。 */
    private val readable: List<Boolean>
) {
    internal fun isReadable(block: Int): Boolean = readable[block]
}

internal fun buildPassageIndex(model: NoteSectionModel): PassageIndex = PassageIndex(
    texts = model.blocks.map { normalizeForMatch(it.visibleText()) },
    sections = model.blocks.indices.map(model::sectionRefAt),
    readable = model.blocks.map { it.isReadableText() }
)

/**
 * 照らし直しの鍵。引用の最初の行（ブロックの境目まで）を、本文の側と同じに整えたもの。
 * 複数のブロックをまたいで選んでも、引用元は最初のブロックで決める。
 */
internal fun quoteKey(quote: String): String =
    quote.lineSequence().map(::normalizeForMatch).firstOrNull { it.isNotEmpty() }.orEmpty()

/** 引用を付けるときに決まった引用元（→ features/margin_quote.md §5.2）。 */
internal sealed interface QuoteSource {
    /** 引用元のブロックと、そこで控えた前後の文字。 */
    data class Resolved(val block: Int, val context: QuoteContext) : QuoteSource

    /** 本文に同じ一節が2か所以上あり、位置からも決まらない。**付ける場で選んでもらう**（→ 判断13）。 */
    data class Ambiguous(val candidates: List<Int>) : QuoteSource

    /** 引用元を決められない。前後を控えずに付ける。 */
    data object Unresolved : QuoteSource
}

/**
 * 引用元を決める。[selectedBlock] は選択の範囲の矩形から求めたブロックで、決まらなければ null。
 *
 * **位置が取れないとき、見えている範囲の一意を根拠にしない** — 画面の外に本当の引用元がありうる。
 * 本文の全体で候補が1つのときだけ補う（→ features/margin_quote.md 判断11）。
 */
internal fun quoteSourceFor(quote: String, selectedBlock: Int?, index: PassageIndex): QuoteSource {
    val key = quoteKey(quote)
    if (key.isEmpty()) return QuoteSource.Unresolved
    if (selectedBlock != null && selectedBlock in index.texts.indices) {
        contextIn(index.texts[selectedBlock], key)?.let { return QuoteSource.Resolved(selectedBlock, it) }
    }
    val candidates = candidatesOf(key, index)
    return when (candidates.size) {
        0 -> QuoteSource.Unresolved
        1 -> resolveQuoteAt(quote, candidates.single(), index)
        else -> QuoteSource.Ambiguous(candidates)
    }
}

/** 付ける場で利用者が選んだ候補を引用元にする。鍵がそのブロックに無ければ決めない。 */
internal fun resolveQuoteAt(quote: String, block: Int, index: PassageIndex): QuoteSource {
    val context = index.texts.getOrNull(block)?.let { contextIn(it, quoteKey(quote)) } ?: return QuoteSource.Unresolved
    return QuoteSource.Resolved(block, context)
}

/**
 * 書き始めた頃に読んでいた位置の手掛かり（→ features/margin_quote.md §5.4）。[topBlock] は本文の最上端に見えているブロック。
 *
 * 見出し・画像・区切り線・表なら、同じ節の中で次の本文のブロックを使う。無ければ null。
 */
internal fun seenPassageAt(topBlock: Int, index: PassageIndex): MemoPassage.Seen? {
    if (topBlock !in index.texts.indices) return null
    val section = index.sections[topBlock]
    for (block in topBlock until index.texts.size) {
        if (index.sections[block] != section) return null
        val text = index.texts[block]
        if (index.isReadable(block) && text.isNotEmpty()) {
            return MemoPassage.Seen(takeFirstUtf8Bytes(text, ReadingTraceLimits.MAX_SEEN_BYTES).trimEnd())
                .takeIf { it.text.isNotEmpty() }
        }
    }
    return null
}

/** 照らし直しの結果（→ features/margin_quote.md §5.5）。 */
internal sealed interface PassageLocation {
    data class Located(val block: Int, val section: SectionRef) : PassageLocation

    /** 候補はあるが確定できない。**候補が1件でもここに入りうる**（片側だけ合う・前後を控えていない）。 */
    data class Uncertain(val candidates: List<Int>) : PassageLocation

    data object NotFound : PassageLocation
}

/**
 * 引用か控えた位置を、今の本文で照らし直す。
 *
 * - **引用は前後の文字が両方とも合う候補がちょうど1つのときだけ確定する。** 片側だけ合う候補で確定すると、
 *   元の段落が消えたとき、同じ前文か後文を持つ別の段落を元の場所と言ってしまう
 * - **前後を控えていない引用は、候補が何件でも確定しない**
 * - 控えた位置は前後を持たないので、候補が1つのときだけ使う。まず控えた見出しの節（同名ならそのすべて）の中を探す
 */
internal fun locatePassage(
    passage: MemoPassage,
    sectionTitle: String?,
    index: PassageIndex,
    headings: HeadingIndex
): PassageLocation = locatePassage(passage, sectionTitle, index, headings) { candidatesOf(it, index) }

/** [candidatesFor] は鍵から候補のブロックを引く口。メモの一覧をまとめて照らすときは、同じ鍵を1度だけ探す（[locateMemos]）。 */
private fun locatePassage(
    passage: MemoPassage,
    sectionTitle: String?,
    index: PassageIndex,
    headings: HeadingIndex,
    candidatesFor: (String) -> List<Int>
): PassageLocation = when (passage) {
    is MemoPassage.Quoted -> locateQuote(passage, index, candidatesFor)
    is MemoPassage.Seen -> locateSeen(passage, sectionTitle, index, headings, candidatesFor)
}

/**
 * 面に並べるときに引く、メモの置き場所。**候補のブロックの列を持たない** — 並べる処理は節を移るたびに呼ばれるので、
 * 本文の大きさに比例する候補の列を、走査でもハッシュでも読ませない（→ features/margin_quote.md §5.5）。
 */
internal sealed interface MemoPlacement {
    /** 引用か控えた位置が確定した段落の節。 */
    data class Located(val section: SectionRef) : MemoPlacement

    /** 特定できない引用。候補の節（同じ節にある候補は1つに数える）のどれにも出す。 */
    data class Uncertain(val sections: List<SectionRef>) : MemoPlacement
}

/**
 * メモごとの照らし直しの結果。**本文の解析かメモが替わるたびに、Main の外で作り直す**（→ features/margin_quote.md §5.5）。
 *
 * **候補から節への集約もここで済ませる。** 面に並べるとき（[arrangeMemos]）は [placementOf] を引くだけで、
 * 候補の列とブロックごとの節の列には触れない。候補の列は、付ける場や押したときに候補を並べる [of] のためだけに残す。
 */
internal class MemoLocations internal constructor(
    private val byMemo: Map<MarginMemo, PassageLocation>,
    passages: PassageIndex
) {
    private val placements: Map<MarginMemo, MemoPlacement> = byMemo.entries.mapNotNull { (memo, location) ->
        placementOf(memo.passage, location, passages)?.let { memo to it }
    }.toMap()

    internal fun of(memo: MarginMemo): PassageLocation? = byMemo[memo]

    /** 並べるときの置き場所。null は見出しの照合で並べる（見つからない・特定できない控えた位置）。 */
    internal fun placementOf(memo: MarginMemo): MemoPlacement? = placements[memo]
}

private fun placementOf(passage: MemoPassage?, location: PassageLocation, passages: PassageIndex): MemoPlacement? =
    when (location) {
        is PassageLocation.Located -> MemoPlacement.Located(location.section)
        // 控えた位置は手掛かりなので、特定できなければ見出しの照合へ戻す。
        is PassageLocation.Uncertain ->
            if (passage is MemoPassage.Quoted) MemoPlacement.Uncertain(location.candidates.map { passages.sections[it] }.distinct()) else null
        PassageLocation.NotFound -> null
    }

/**
 * メモの一覧をまとめて照らし直す。**本文の全体を鍵で探すのは、同じ鍵につき1度だけ**にし、候補の列もメモの間で共有する —
 * 同じ一節を引いたメモが多いと、探す回数と候補の列が鍵の数ではなくメモの数だけ増えるため。
 */
internal fun locateMemos(memos: List<MarginMemo>, passages: PassageIndex, headings: HeadingIndex): MemoLocations {
    val candidatesByKey = HashMap<String, List<Int>>()
    val candidatesFor: (String) -> List<Int> = { key -> candidatesByKey.getOrPut(key) { candidatesOf(key, passages) } }
    return MemoLocations(
        byMemo = memos.mapNotNull { memo ->
            memo.passage?.let { memo to locatePassage(it, memo.sectionTitle, passages, headings, candidatesFor) }
        }.toMap(),
        passages = passages
    )
}

private fun locateQuote(quote: MemoPassage.Quoted, index: PassageIndex, candidatesFor: (String) -> List<Int>): PassageLocation {
    val key = quoteKey(quote.text)
    val candidates = candidatesFor(key)
    if (candidates.isEmpty()) return PassageLocation.NotFound
    val context = quote.context ?: return PassageLocation.Uncertain(candidates)
    val matched = candidates.filter { contextIn(index.texts[it], key) == context }
    val located = matched.singleOrNull() ?: return PassageLocation.Uncertain(candidates)
    return PassageLocation.Located(located, index.sections[located])
}

private fun locateSeen(
    seen: MemoPassage.Seen,
    sectionTitle: String?,
    index: PassageIndex,
    headings: HeadingIndex,
    candidatesFor: (String) -> List<Int>
): PassageLocation {
    val key = normalizeForMatch(seen.text)
    val candidates = candidatesFor(key)
    if (candidates.isEmpty()) return PassageLocation.NotFound
    val scope = when (val match = headings.match(sectionTitle)) {
        MemoSectionMatch.Opening -> setOf(SectionRef(title = null))
        is MemoSectionMatch.Unique -> setOf(match.section)
        is MemoSectionMatch.Shared -> match.sections.toSet()
        MemoSectionMatch.Missing -> emptySet()
    }
    val inScope = candidates.filter { index.sections[it] in scope }
    val located = (if (inScope.isNotEmpty()) inScope else candidates).singleOrNull()
        ?: return PassageLocation.Uncertain(candidates)
    return PassageLocation.Located(located, index.sections[located])
}

private fun candidatesOf(key: String, index: PassageIndex): List<Int> {
    if (key.isEmpty()) return emptyList()
    val texts = index.texts
    return buildList { for (block in texts.indices) if (texts[block].contains(key)) add(block) }
}

/**
 * [blockText] の中の [key] の最初の出現の、直前と直後の文字（各32バイトまで）。鍵が無ければ null。
 * **控えるときと照らし直すときに同じ取り方をする**ので、空はブロックの端を意味し、候補の側も空のときだけ合う。
 */
private fun contextIn(blockText: String, key: String): QuoteContext? {
    val at = blockText.indexOf(key)
    if (at < 0) return null
    return QuoteContext(
        before = takeLastUtf8Bytes(blockText.substring(0, at), ReadingTraceLimits.MAX_QUOTE_CONTEXT_BYTES),
        after = takeFirstUtf8Bytes(blockText.substring(at + key.length), ReadingTraceLimits.MAX_QUOTE_CONTEXT_BYTES)
    )
}

/**
 * 先頭から UTF-8 のバイト上限まで。**文字（コードポイント）の途中では切らない** — 絵文字のような
 * 2つの char でできた文字を割ると、片割れが1バイトに数えられて上限と保存値がずれる。
 */
internal fun takeFirstUtf8Bytes(value: String, maximumBytes: Int): String {
    var end = 0
    var bytes = 0
    while (end < value.length) {
        val codePoint = value.codePointAt(end)
        val size = utf8Size(codePoint)
        if (bytes + size > maximumBytes) break
        bytes += size
        end += Character.charCount(codePoint)
    }
    return value.substring(0, end)
}

/** 末尾から UTF-8 のバイト上限まで。文字の途中では切らない。 */
internal fun takeLastUtf8Bytes(value: String, maximumBytes: Int): String {
    var start = value.length
    var bytes = 0
    while (start > 0) {
        val codePoint = value.codePointBefore(start)
        val size = utf8Size(codePoint)
        if (bytes + size > maximumBytes) break
        bytes += size
        start -= Character.charCount(codePoint)
    }
    return value.substring(start)
}

private fun utf8Size(codePoint: Int): Int = when {
    codePoint < 0x80 -> 1
    codePoint < 0x800 -> 2
    codePoint < 0x10000 -> 3
    else -> 4
}

/**
 * 照らし直しのための整え方。空白と改行の並びを1つの空白に畳み、制御文字を落とす。
 * 控える前後の文字と位置もこれを通るので、制御文字を含まない（容量の見積もりが保てる）。
 */
internal fun normalizeForMatch(text: String): String =
    text.filter { it.isWhitespace() || !it.isISOControl() }
        .split(WHITESPACE)
        .filter { it.isNotEmpty() }
        .joinToString(" ")

/** 本文に見える文字。**表示と同じ解釈器を通す**（→ lessons L51）。リストと引用の行は1行ずつ、表はセルを空白でつなぐ。 */
private fun MarkdownBlock.visibleText(): String = when (this) {
    is MarkdownBlock.Heading -> inlineVisibleText(text)
    is MarkdownBlock.Paragraph -> inlineVisibleText(text)
    is MarkdownBlock.ListBlock -> items.joinToString("\n") { inlineVisibleText(it.text) }
    is MarkdownBlock.Blockquote -> lines.joinToString("\n") { inlineVisibleText(it) }
    is MarkdownBlock.CodeBlock -> code
    is MarkdownBlock.Table -> (listOf(headers) + rows).joinToString("\n") { row -> row.joinToString(" ") { inlineVisibleText(it) } }
    is MarkdownBlock.Image -> ""
    MarkdownBlock.HorizontalRule -> ""
}

private fun MarkdownBlock.isReadableText(): Boolean = when (this) {
    is MarkdownBlock.Paragraph, is MarkdownBlock.ListBlock, is MarkdownBlock.Blockquote, is MarkdownBlock.CodeBlock -> true
    is MarkdownBlock.Heading, is MarkdownBlock.Image, is MarkdownBlock.Table, MarkdownBlock.HorizontalRule -> false
}

private val WHITESPACE = Regex("\\s+")
