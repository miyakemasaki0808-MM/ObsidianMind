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
 * 今の見出しの索引（→ features/margin_pane.md §5.6）。**本文の解析ごとに1回、Main の外で作る**（→ `buildNoteSectionModel`）。
 *
 * 見出しの数に比例する仕事はここで済ませ、メモの照合と節への振り分けを**メモの数だけ**で済ませる。
 * 照合のたびに見出しを数え直すと、見出しの多いノートでは表示のたび・節を移るたびに Main が止まる。
 */
class HeadingIndex internal constructor(headings: List<String>) {
    /** 見出しの節を本文の順に。同名の見出しには、その名前の中での順番が付く。 */
    internal val sections: List<SectionRef>

    private val positions: Map<SectionRef, Int>

    /**
     * 保存時と同じ整形をかけた見出し名から、その名前の節へ。**照合の前に今の見出しにも保存時の整形をかける** —
     * 保存側は長い見出しを切り、制御文字を落としているので、生の見出しと比べると長い見出しのメモが全部見つからない。
     */
    private val byStoredTitle: Map<String, List<SectionRef>>

    init {
        val seen = HashMap<String, Int>()
        sections = headings.map { title ->
            val ordinal = seen[title] ?: 0
            seen[title] = ordinal + 1
            SectionRef(title, ordinal)
        }
        positions = HashMap<SectionRef, Int>(sections.size * 2).apply {
            sections.forEachIndexed { k, ref -> put(ref, k) }
        }
        val stored = LinkedHashMap<String, MutableList<SectionRef>>()
        sections.forEach { ref ->
            val key = ref.title?.let(::composeMemoSectionTitle) ?: return@forEach
            stored.getOrPut(key) { mutableListOf() }.add(ref)
        }
        byStoredTitle = stored
    }

    /** [ref] の見出しが本文の何番目か。同じ名前と順番の見出しが無ければ null。 */
    internal fun positionOf(ref: SectionRef): Int? = positions[ref]

    /**
     * 痕跡に控えた見出し名（メモ・訪問）を照合する。保存値は書き換えない。
     *
     * **保存値に整形をかけ直さない。** 整形は上限で切った後の末尾の空白を残すので、もう一度かけると値が変わり、
     * 索引とずれる（元の節が見つからないか、切り口と同じ別の短い見出しへ当たる）。
     * かけ直すのは、整える前に生のまま記録した訪問の見出し名（制御文字を含む）だけ。整えた値は制御文字を含まない。
     */
    internal fun match(sectionTitle: String?): MemoSectionMatch {
        if (sectionTitle == null) return MemoSectionMatch.Opening
        val key = if (sectionTitle.any(Char::isISOControl)) {
            composeMemoSectionTitle(sectionTitle) ?: return MemoSectionMatch.Missing
        } else {
            sectionTitle
        }
        val hits = byStoredTitle[key].orEmpty()
        return when (hits.size) {
            0 -> MemoSectionMatch.Missing
            1 -> MemoSectionMatch.Unique(hits.single())
            else -> MemoSectionMatch.Shared(hits)
        }
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
 * - [otherCount] 畳んだときに見せる件数。[others] に並ぶメモを、同名の見出しに共通でも2度数えない
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
 *
 * **見出しをたどらない。** 組はメモが当たる節からだけ作り、本文の順は [index] で引く。
 * 節を移るたびに呼ばれるので、ここの仕事はメモの数だけに比例させる。
 */
internal fun arrangeMemos(memos: List<MarginMemo>, index: HeadingIndex, current: SectionRef): ArrangedMemos {
    val opening = SectionRef(title = null)
    val placed = memos.map { PlacedMemo(it, index.match(it.sectionTitle)) }
    val inCurrent = placed.filter { current in it.match.sections(opening) }
    val bySection = LinkedHashMap<SectionRef, MutableList<PlacedMemo>>()
    placed.forEach { memo ->
        memo.match.sections(opening)
            .filter { it != current }
            .forEach { section -> bySection.getOrPut(section) { mutableListOf() }.add(memo) }
    }
    val groups = bySection.entries
        .sortedBy { (section, _) -> if (section == opening) -1 else index.positionOf(section) ?: Int.MAX_VALUE }
        .map { (section, inSection) -> MemoGroup(section, inSection) }
    val missing = placed.filter { it.match == MemoSectionMatch.Missing }
    val others = if (missing.isEmpty()) groups else groups + MemoGroup(section = null, memos = missing)
    return ArrangedMemos(
        current = inCurrent,
        others = others,
        // **ほかの組に並ぶメモを、重ねずに数える。** 「今の節に無いメモ」で数えると、今の節と同名のほかの候補に
        // 共通のメモが0件になり、候補の組があるのに開く口が消える。
        otherCount = others.flatMap { group -> group.memos.map { it.memo } }.distinct().size,
        countsBySection = placed
            .flatMap { it.match.sections(opening) }
            .filter { it != opening }
            .groupingBy { it }
            .eachCount()
    )
}

private fun MemoSectionMatch.sections(opening: SectionRef): List<SectionRef> = when (this) {
    MemoSectionMatch.Opening -> listOf(opening)
    is MemoSectionMatch.Unique -> listOf(section)
    is MemoSectionMatch.Shared -> sections
    MemoSectionMatch.Missing -> emptyList()
}
