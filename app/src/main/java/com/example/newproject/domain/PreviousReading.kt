package com.example.newproject.domain

import com.example.newproject.model.ReadingVisit
import com.example.newproject.model.SectionRef
import com.example.newproject.model.isReadToEnd
import com.example.newproject.model.state.PreviousVisit

// ---------------------------------------------------------------------------
// 前回の読書の跡（→ features/margin_pane.md §5.7）。
//
// 見るのは今の読書を始める前の最新の訪問1件だけで、その節に来たときに「前回はここまで読んだ」を出す。
// ---------------------------------------------------------------------------

/**
 * 今の読書を始める前の、最新の訪問。**遡らない** — 最新が最後まで読んだ回でも、その前の読みかけは選ばない。
 *
 * [readingStartedAtMillis] 以降の訪問は今の読書のもの（背面へ回すたびに書かれる）なので数えない。
 */
internal fun previousVisitOf(visits: List<ReadingVisit>, readingStartedAtMillis: Long): PreviousVisit? =
    visits.filter { it.atEpochMillis < readingStartedAtMillis }
        .maxByOrNull { it.atEpochMillis }
        ?.let { PreviousVisit(it.deepestSectionTitle, it.atEpochMillis, isReadToEnd(it.progressPercent)) }

/**
 * 前回の読書の跡を、面の節 [current] に出すか。**見出しが一意に一致したときだけ出す**（§5.6 と同じ照合）。
 *
 * 最後まで読んだ回・見出しより前・見出しの無いノート・同名の見出しが複数・見出しが見つからない、では出さない。
 * 記録は前回いちばん深く読んだ節なので、どの節か言い切れないときに出すと、読んでいない節を指しうる。
 */
internal fun showsPreviousReading(previous: PreviousVisit?, index: HeadingIndex, current: SectionRef?): Boolean {
    if (previous == null || previous.readToEnd || current == null) return false
    val match = index.match(composeMemoSectionTitle(previous.sectionTitle))
    return match is MemoSectionMatch.Unique && match.section == current
}
