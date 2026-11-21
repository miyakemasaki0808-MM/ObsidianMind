package com.example.newproject.domain

import com.example.newproject.model.DocumentRef
import com.example.newproject.model.state.BacklinkRow

// ---------------------------------------------------------------------------
// 索引から引いたリンクを解き、今のノートへのリンクを1ノート1行にまとめる（→ docs/dev/features/backlinks.md §5・§8）。
// ---------------------------------------------------------------------------

/** 索引から名前の鍵で絞った、今のノートを指しうるリンク1つ。まだ解いていない。 */
internal data class BacklinkCandidate(
    val sourcePath: String,
    val ordinal: Int,
    val kind: IndexedLinkKind,
    val target: String,
    val heading: String?,
    val sectionHeading: String?,
    val sectionHeadingOccurrence: Int,
    val sentence: String,
    val sentenceLink: IntRange?
) {
    fun toNoteLink(): NoteLink = when (kind) {
        IndexedLinkKind.NAME -> NoteLink.ByName(target, heading)
        IndexedLinkKind.PATH -> NoteLink.ByPath(target, heading)
    }
}

/**
 * [candidates] を解き、[current] へ着くリンクだけを出どころごとに1行にまとめる。
 *
 * - **出どころは走査の索引（[index]）で引く。** 引けなければ解かずに除く — 出どころの相対パスが分からないまま解くと、
 *   根からの相対で解いて行き先を誤る（一部のフォルダを読めない走査 → knowledge_index.md §5）
 * - **出どころから本文のリンクと同じ規則（[resolveNoteLink]）で解き、今のノートへ着くものだけを残す。** 同じ名前の別のノートへ着くリンクは除く
 * - 今のノート自身の中のリンクは除く
 * - 行は本文で最初に出てくるリンクの1文と、ほかの箇所の数。並びは出どころの更新日時が新しい順、同じなら題名の順
 */
internal fun backlinkRows(candidates: List<BacklinkCandidate>, current: DocumentRef, index: NoteLinkIndex): List<BacklinkRow> =
    candidates
        .mapNotNull { candidate ->
            val source = index.atExactPath(candidate.sourcePath) ?: return@mapNotNull null
            if (source.ref == current) return@mapNotNull null
            val resolved = resolveNoteLink(candidate.toNoteLink(), source.ref, index)
            if (resolved is NoteLinkResolution.Open && resolved.note.ref == current) source to candidate else null
        }
        .groupBy({ it.first }, { it.second })
        .map { (source, links) ->
            val first = links.minBy { it.ordinal }
            BacklinkRow(
                note = source.toRelatedNote(),
                sentence = first.sentence,
                otherCount = links.size - 1,
                sectionHeading = first.sectionHeading,
                sectionHeadingOccurrence = first.sectionHeadingOccurrence
            )
        }
        .sortedWith(compareByDescending<BacklinkRow> { it.note.lastModified ?: Long.MIN_VALUE }.thenBy { it.note.title })
