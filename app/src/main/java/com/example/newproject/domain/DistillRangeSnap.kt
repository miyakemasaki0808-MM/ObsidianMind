package com.example.newproject.domain

import com.example.newproject.model.DistillTextRange
import com.example.newproject.model.state.DistillRangeEdge
import com.example.newproject.model.state.DistillRangeEdgeMove

/**
 * 親文の中で `**` の端を置いてよい位置を**すべて列挙する。**
 *
 * **補正手順を重ねるのではなく、置ける位置の一覧を作る。** 「保護範囲から押し出す」
 * 「書記素の切れ目へ寄せる」「端の空白を落とす」を順に当てると、1つ目の補正が
 * 2つ目の禁止域へ落とす形が残り、何度当て直せば収まるかが読めなくなる。
 * **親文は最大160文字**（`MAX_SENTENCE_CHARACTERS`）なので、全列挙して選ぶほうが安く、正しさも読める。
 *
 * 置けない位置は次のとおりで、正本は [reflect_distill §5](
 * ../../../../../../../../docs/dev/features/reflect_distill.md) の「端を置いてよい位置」である。
 *
 * - **書記素の内側** — サロゲートペア・結合文字・異体字セレクタ・ZWJ 連結・肌色修飾・国旗の対
 * - **保護範囲の内部** — コードスパン・リンク・斜体・打ち消し線。
 *   **内部かどうかではなく対を割るかどうかで数える**ので、端の一致は置いてよい
 * - **バックスラッシュの直後** — 挿した `**` が `\*` になり、エスケープとして消える
 *
 * **既存の `**` 強調は数えない。** 親文が `subtractRanges` で既存強調を差し引いた区間から
 * 作られる限り到達しないので、落ちるテストを書けない（→ 同書 §8 判断18）。
 */
internal fun distillBoundaryOffsets(
    content: String,
    context: DistillTextRange,
    protectedSpans: List<DistillTextRange>
): List<Int> = (context.start..context.endExclusive).filter { offset ->
    isGraphemeBoundary(content, offset) &&
        !followsBackslash(content, offset) &&
        protectedSpans.none { it.contains(offset) }
}

/**
 * 直前がバックスラッシュか。**`**` を挿した瞬間に `\*` がエスケープへ変わる。**
 *
 * **元の本文がエスケープ構文でなくても起きる。** `C:\Users` の `\U` は記法ではないが、
 * `U` の前へ `**` を挿すと `\**` になり、表示側は先頭の `*` を文字として消して
 * 残る `*` を斜体の開始として読む。**既存の `InlineEscape` を保護範囲へ足しても防げない** —
 * 守るべきなのは元の本文ではなく、挿入後に生まれる並びだからである。
 *
 * エスケープ済みのバックスラッシュ（`\\`）の直後は実際には安全だが、数え分けない。
 * 置ける位置を1つ失うだけで、判定を単純に保てる。
 */
private fun followsBackslash(content: String, offset: Int): Boolean =
    offset > 0 && content[offset - 1] == '\\'

/**
 * 端を置ける位置へ寄せる。**倒す先は向きで変わる。**
 *
 * 広げているときは外側の安全境界へ、狭めているときは内側の安全境界へ倒す
 * （句分割が「下限未満の余りは直前の句へ吸収する」と向きを固定したのと同じ型の決めごと）。
 * **どちらの向きにも置ける位置が無いときだけ**反対側を探す。

 *
 * 端の空白は落とす。開始位置に空白そのものを置かない・終了位置の直前に空白を残さない、
 * という形で列挙の段で落としてあるので、寄せた結果が空白で始まったり終わったりしない。
 *
 * 反対の端を越える位置は候補から除くので、**範囲が潰れることはない。**
 * 置ける位置が1つも残らなければ `null`（呼び出し側は何もしない）。
 */
internal fun snapDistillRangeEdge(
    content: String,
    context: DistillTextRange,
    current: DistillTextRange,
    edge: DistillRangeEdge,
    desiredOffset: Int,
    protectedSpans: List<DistillTextRange>
): DistillTextRange? {
    val offsets = edgeOffsets(content, context, current, edge, protectedSpans)
    if (offsets.isEmpty()) return null
    val desired = desiredOffset.coerceIn(context.start, context.endExclusive)
    val isWidening = when (edge) {
        DistillRangeEdge.Start -> desired < current.start
        DistillRangeEdge.End -> desired > current.endExclusive
    }
    // 外側は Start では小さい側、End では大きい側。広げているなら外側を優先する。
    val prefersLower = (edge == DistillRangeEdge.Start) == isWidening
    val snapped = if (prefersLower) {
        offsets.lastOrNull { it <= desired } ?: offsets.first()
    } else {
        offsets.firstOrNull { it >= desired } ?: offsets.last()
    }
    return rangeWithEdge(current, edge, snapped)
}

/**
 * 端を、置ける位置ひとつぶんだけ動かす。
 *
 * **指で狙った1文字に止まれないから置いている。** 15sp の本文でハンドルを引いても
 * 書記素1つの精度は出ないので、最後の詰めはこちらが受ける。
 * スナップと同じ一覧を使うので、**ドラッグで到達できない位置へは微調整でも行けない。**
 */
internal fun nudgeDistillRangeEdge(
    content: String,
    context: DistillTextRange,
    current: DistillTextRange,
    move: DistillRangeEdgeMove,
    protectedSpans: List<DistillTextRange>
): DistillTextRange? = nextEdgeRange(
    content,
    distillBoundaryOffsets(content, context, protectedSpans),
    current,
    move
)

/**
 * 微調整で動かせる向き。**押せない矢印を出さないために引く。**
 *
 * **置ける位置は1度だけ引く。** 自由範囲のドラッグは指の動きに合わせて候補状態を作り直すので、
 * 向きごとに列挙し直すと同じ一覧をフレームあたり4本作ることになる。
 */
internal fun availableDistillEdgeMoves(
    content: String,
    context: DistillTextRange,
    current: DistillTextRange,
    protectedSpans: List<DistillTextRange>
): Set<DistillRangeEdgeMove> {
    val offsets = distillBoundaryOffsets(content, context, protectedSpans)
    return DistillRangeEdgeMove.entries
        .filterTo(mutableSetOf()) { nextEdgeRange(content, offsets, current, it) != null }
}

/** [move] の向きへ1つぶん動かした範囲。動かせなければ `null`。 */
private fun nextEdgeRange(
    content: String,
    offsets: List<Int>,
    current: DistillTextRange,
    move: DistillRangeEdgeMove
): DistillTextRange? {
    val placeable = offsets.filter { placeableForEdge(content, it, current, move.edge) }
    val from = currentOffset(current, move.edge)
    // 外側は Start では小さい側、End では大きい側。
    val movesLower = (move.edge == DistillRangeEdge.Start) == move.isOutward
    val next = if (movesLower) {
        placeable.lastOrNull { it < from }
    } else {
        placeable.firstOrNull { it > from }
    }
    return next?.let { rangeWithEdge(current, move.edge, it) }
}

/**
 * その端に置ける位置。**反対の端を越えるものと、空白を巻き込むものを落としてある。**
 *
 * 開始位置はその文字が空白でないこと、終了位置は直前の文字が空白でないことを要求する。
 * これが `trimmedRange` と同じ規則を列挙の段で効かせている箇所である。
 */
private fun edgeOffsets(
    content: String,
    context: DistillTextRange,
    current: DistillTextRange,
    edge: DistillRangeEdge,
    protectedSpans: List<DistillTextRange>
): List<Int> = distillBoundaryOffsets(content, context, protectedSpans)
    .filter { placeableForEdge(content, it, current, edge) }

private fun placeableForEdge(
    content: String,
    offset: Int,
    current: DistillTextRange,
    edge: DistillRangeEdge
): Boolean = when (edge) {
    DistillRangeEdge.Start -> offset < current.endExclusive && !content[offset].isWhitespace()
    DistillRangeEdge.End -> offset > current.start && !content[offset - 1].isWhitespace()
}

private fun currentOffset(current: DistillTextRange, edge: DistillRangeEdge): Int = when (edge) {
    DistillRangeEdge.Start -> current.start
    DistillRangeEdge.End -> current.endExclusive
}

private fun rangeWithEdge(
    current: DistillTextRange,
    edge: DistillRangeEdge,
    offset: Int
): DistillTextRange = when (edge) {
    DistillRangeEdge.Start -> DistillTextRange(offset, current.endExclusive)
    DistillRangeEdge.End -> DistillTextRange(current.start, offset)
}

/**
 * [offset] が書記素（表示上の1文字）の切れ目か。
 *
 * **UAX#29 を丸ごと持ち込まず、`BreakIterator` にも寄せない。** 端末とデスクトップJVMで
 * ICU の版が違うと**同じ入力に別の答えを返し、JVMテストで固定できなくなる**。
 * 割ってはいけない連結だけを自前で数えれば、両方で同じ答えになる。
 */
private fun isGraphemeBoundary(text: String, offset: Int): Boolean {
    if (offset <= 0 || offset >= text.length) return true
    if (Character.isHighSurrogate(text[offset - 1]) && Character.isLowSurrogate(text[offset])) return false
    val next = text.codePointAt(offset)
    val previous = text.codePointBefore(offset)
    if (next == ZERO_WIDTH_JOINER || previous == ZERO_WIDTH_JOINER) return false
    if (isVariationSelector(next) || isCombiningMark(next) || isEmojiModifier(next)) return false
    // 国旗は地域指示子2つで1つの絵文字になる。**奇数個目の前では割れない。**
    if (isRegionalIndicator(next) && precedingRegionalIndicators(text, offset) % 2 == 1) return false
    return true
}

private fun precedingRegionalIndicators(text: String, offset: Int): Int {
    var index = offset
    var count = 0
    while (index > 0) {
        val codePoint = text.codePointBefore(index)
        if (!isRegionalIndicator(codePoint)) break
        count++
        index -= Character.charCount(codePoint)
    }
    return count
}

private fun isCombiningMark(codePoint: Int): Boolean = Character.getType(codePoint) in COMBINING_TYPES

private fun isVariationSelector(codePoint: Int): Boolean =
    codePoint in 0xFE00..0xFE0F || codePoint in 0xE0100..0xE01EF

/** 肌色修飾子。結合文字ではなく記号なので、[isCombiningMark] では拾えない。 */
private fun isEmojiModifier(codePoint: Int): Boolean = codePoint in 0x1F3FB..0x1F3FF

private fun isRegionalIndicator(codePoint: Int): Boolean = codePoint in 0x1F1E6..0x1F1FF

private const val ZERO_WIDTH_JOINER = 0x200D

private val COMBINING_TYPES = setOf(
    Character.NON_SPACING_MARK.toInt(),
    Character.ENCLOSING_MARK.toInt(),
    Character.COMBINING_SPACING_MARK.toInt()
)
