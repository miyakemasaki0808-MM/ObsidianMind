package com.example.newproject.domain

import com.example.newproject.model.DistillConfirmedRange
import com.example.newproject.model.DistillSentence
import com.example.newproject.model.DistillSourceModel
import com.example.newproject.model.DistillTextRange
import com.example.newproject.model.state.DistillRangePreset

/**
 * **新しい境界規則を作らない。** 3段はすべて `buildDistillSourceModel` が出した要素
 * （語句・句・親文）を引き直すだけなので、v1で確立した境界
 * （記法をまたがない・リンクだけを避ける・鉤括弧の内側で割らない）がそのまま効く。
 */
internal data class DistillRangeOption(
    val preset: DistillRangePreset,
    val range: DistillConfirmedRange
)

/**
 * 候補が選べる段を、狭い順に返す。**存在する段だけを返す。**
 *
 * 押せない選択肢は「なぜ押せないか」の説明を毎回要求するので、出さない。
 *
 * | 候補の種類 | [DistillRangePreset.Term] | [DistillRangePreset.Clause] | [DistillRangePreset.Sentence] |
 * |---|---|---|---|
 * | 語句候補 | 自分 | 自分を含む最小の非語句要素 | 文脈範囲 |
 * | 句・文の候補 | 内側の語句がちょうど1つのときだけ | 自分 | 文脈範囲 |
 *
 * **同じ範囲になる段は広いほうの名前へ畳む。** 割れなかった文では句と親文が一致するので、
 * `意味節` と `文全体` の両方を出すと同じ結果になるボタンが2つ並ぶ。
 *
 * **語句が複数あるときに `語句` 段を出さないのは、どれを指すか決められないから。**
 * 語句は独立候補として一覧に並びうるので、そちらで選ぶ道が残る。
 *
 * **モデルへ欄を足さない。** 句・語句はすべて [DistillSourceModel.sentences] に平らに入っているので、
 * 包含関係だけで引ける。欄を足すと、その欄を読む箇所を数え直す義務が生まれる。
 */
internal fun presetRangesFor(
    model: DistillSourceModel,
    sentence: DistillSentence
): List<DistillRangeOption> {
    val context = sentence.contextRange
    val termRange: DistillTextRange?
    val clauseRange: DistillTextRange?
    if (sentence.isTerm) {
        termRange = sentence.range
        clauseRange = smallestEnclosingClause(model, sentence.range)
    } else {
        termRange = soleTermInside(model, sentence.range)
        clauseRange = sentence.range
    }

    // 狭い順に入れ、同じ範囲は後から入る広い段の名前で上書きする。
    val presetByRange = LinkedHashMap<DistillTextRange, DistillRangePreset>()
    listOf(
        DistillRangePreset.Term to termRange,
        DistillRangePreset.Clause to clauseRange,
        DistillRangePreset.Sentence to context
    ).forEach { (preset, range) ->
        if (range != null && context.encloses(range) && range.length > 0) {
            presetByRange[range] = preset
        }
    }
    return presetByRange.entries
        .sortedBy { it.key.length }
        .map { (range, preset) -> DistillRangeOption(preset, DistillConfirmedRange(context, range)) }
}

/**
 * 語句候補を含む最小の非語句要素。
 *
 * **語句の親「句」はモデルのどこにも書かれていない**（語句の文脈範囲は句ではなく文）。
 * 語句は必ず句の内側から取り出されるので、包含関係で引く。
 */
private fun smallestEnclosingClause(
    model: DistillSourceModel,
    termRange: DistillTextRange
): DistillTextRange? =
    model.sentences
        .filterNot { it.isTerm }
        .map { it.range }
        .filter { it.encloses(termRange) }
        .minByOrNull { it.length }

/** 範囲の内側にある語句要素が**ちょうど1つ**のときだけ、その範囲を返す。 */
private fun soleTermInside(
    model: DistillSourceModel,
    range: DistillTextRange
): DistillTextRange? =
    model.sentences
        .filter { it.isTerm && range.encloses(it.range) && it.range != range }
        .map { it.range }
        .distinct()
        .singleOrNull()

internal data class DistillOverlapResolution(
    val selectedIds: List<String>,
    val deselectedIds: List<String>
)

/**
 * 選択集合から重なりを取り除き、**外した候補を返す。**
 *
 * **調整は実行時に重なりを作れる。** v1は選定の出口で候補集合を非重複にしていたので
 * [applyDistillBold] の `require` へ届く経路が無かったが、範囲を変えられるようにすると届く。
 *
 * **[priorityId]（ユーザーが最後に触った候補）を必ず残す。** 最後の明示操作を尊重し、
 * 押した本人の操作が黙って無効化される形を作らない。
 *
 * **呼び出し口は範囲変更とチェックの2つある。** 片方に置くと、外された候補を
 * チェックし直すだけで重なりが復活するので、純関数へ切り出して両方から通す。
 *
 * 同一範囲も重なりとして数える。[applyDistillBold] は `distinct()` で畳んで落ちない代わりに、
 * **画面の選択件数と保存件数が食い違う**（`Saved.changedCount` は選択数を数える）。
 */
internal fun resolveOverlaps(
    selectedIds: List<String>,
    rangesById: Map<String, DistillTextRange>,
    priorityId: String
): DistillOverlapResolution {
    val ordered = if (selectedIds.contains(priorityId)) {
        listOf(priorityId) + selectedIds.filterNot { it == priorityId }
    } else {
        selectedIds
    }
    val keptRanges = mutableListOf<DistillTextRange>()
    val dropped = mutableSetOf<String>()
    ordered.forEach { id ->
        // 範囲を引けない候補は判定材料が無いので触らない。保存直前のガードが最後に受ける。
        val range = rangesById[id] ?: return@forEach
        if (keptRanges.any { it.overlaps(range) }) dropped += id else keptRanges += range
    }
    return DistillOverlapResolution(
        selectedIds = selectedIds.filterNot(dropped::contains),
        deselectedIds = selectedIds.filter(dropped::contains)
    )
}

/**
 * 保存直前の検証。**「念のため」ではない。**
 *
 * `saveSelection` は [applyDistillBold] を Main で `try` なしに同期呼び出しするので、
 * 部分重複は例外ではなく**その場のクラッシュ**になる。
 * `require` へユーザー操作から到達できない状態にすることが、この検査の目的である。
 */
internal fun hasOverlappingDistillRanges(ranges: Collection<DistillTextRange>): Boolean =
    ranges.sortedBy { it.start }
        .zipWithNext()
        .any { (left, right) -> left.endExclusive > right.start }
