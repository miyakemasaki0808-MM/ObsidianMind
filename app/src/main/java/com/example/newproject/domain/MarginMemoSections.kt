package com.example.newproject.domain

import com.example.newproject.model.MarginMemo
import com.example.newproject.model.SectionRef

// ---------------------------------------------------------------------------
// 余白メモを今の本文の節へ置く（→ features/margin_pane.md §5.6）。
//
// メモが控えているのは書いたときの見出し名だけで、紐づけではない（→ reflect_margin_memo.md 判断2）。
// だから表示のたびに今の見出しと照合し、**一致・候補が複数・見つからない**を分ける。保存値は書き換えない。
// ---------------------------------------------------------------------------

/** メモが今の本文のどの節に当たるか。 */
internal sealed interface MemoSectionMatch {
    /** 見出しを持たないメモ。見出しより前（見出しの無いノートでは全体）に出す。 */
    data object Opening : MemoSectionMatch

    data class Unique(val section: SectionRef) : MemoSectionMatch

    /** 整えると同じになる見出しが複数ある。**どの候補にも出し、黙って先頭へ決めない。** */
    data class Shared(val sections: List<SectionRef>) : MemoSectionMatch

    /** 今の本文に見つからない（改名・削除）。本文へは飛べない。 */
    data object Missing : MemoSectionMatch
}

/**
 * メモの見出し名を今の見出し（本文の順）と照合する。
 *
 * **照合の前に、今の見出しにも保存時と同じ整形をかける。** 保存側は長い見出しを切り、制御文字を落としているので、
 * 生の見出しと比べると長い見出しのメモが全部「見つからない」になる。
 */
internal fun matchMemoSection(sectionTitle: String?, headings: List<String>): MemoSectionMatch {
    if (sectionTitle == null) return MemoSectionMatch.Opening
    val refs = sectionRefsOf(headings)
    val hits = headings.indices.filter { composeMemoSectionTitle(headings[it]) == sectionTitle }.map { refs[it] }
    return when (hits.size) {
        0 -> MemoSectionMatch.Missing
        1 -> MemoSectionMatch.Unique(hits.single())
        else -> MemoSectionMatch.Shared(hits)
    }
}

/** 照合したメモ。 */
internal data class PlacedMemo(val memo: MarginMemo, val match: MemoSectionMatch)

/** 節ごとのメモ。[section] が null の組は「見出しが見つからないメモ」。 */
internal data class MemoGroup(val section: SectionRef?, val memos: List<PlacedMemo>)

/**
 * 面に並べる形（→ features/margin_pane.md §5.2 の7・8）。
 *
 * - [current] この節のメモ（新しい順）
 * - [others] ほかの節のメモを**本文の順**に節ごと。見出しが見つからないメモは最後の組
 * - [otherCount] 畳んだときに見せる件数。同名の見出しに共通のメモを2度数えない
 * - [countsBySection] 本文の見出しの脇の印。印・件数・飛ぶ先はこの照合から作る
 */
internal data class ArrangedMemos(
    val current: List<PlacedMemo>,
    val others: List<MemoGroup>,
    val otherCount: Int,
    val countsBySection: Map<SectionRef, Int>
)

/**
 * [memos] を今の節 [current] とほかの節に分ける。[memos] の並び（新しい順）は各組の中で保つ。
 * [headings] は今の本文の見出しを本文の順に並べたもの。
 */
internal fun arrangeMemos(memos: List<MarginMemo>, headings: List<String>, current: SectionRef): ArrangedMemos {
    val placed = memos.map { PlacedMemo(it, matchMemoSection(it.sectionTitle, headings)) }
    val opening = SectionRef(title = null)
    val sections = listOf(opening) + sectionRefsOf(headings)
    val inCurrent = placed.filter { current in it.match.sections(opening) }
    val groups = sections
        .filter { it != current }
        .map { section -> MemoGroup(section, placed.filter { section in it.match.sections(opening) }) }
        .filter { it.memos.isNotEmpty() }
    val missing = placed.filter { it.match == MemoSectionMatch.Missing }
    return ArrangedMemos(
        current = inCurrent,
        others = if (missing.isEmpty()) groups else groups + MemoGroup(section = null, memos = missing),
        otherCount = placed.count { current !in it.match.sections(opening) },
        countsBySection = sections
            .filter { it != opening }
            .associateWith { section -> placed.count { section in it.match.sections(opening) } }
            .filterValues { it > 0 }
    )
}

/** 見出しの並びを節の指し方へ。同名の見出しには、その名前の中での順番を付ける。 */
private fun sectionRefsOf(headings: List<String>): List<SectionRef> =
    headings.mapIndexed { k, title -> SectionRef(title, ordinal = (0 until k).count { headings[it] == title }) }

private fun MemoSectionMatch.sections(opening: SectionRef): List<SectionRef> = when (this) {
    MemoSectionMatch.Opening -> listOf(opening)
    is MemoSectionMatch.Unique -> listOf(section)
    is MemoSectionMatch.Shared -> sections
    MemoSectionMatch.Missing -> emptyList()
}
