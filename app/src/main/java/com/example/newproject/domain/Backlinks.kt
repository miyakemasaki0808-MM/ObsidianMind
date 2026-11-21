package com.example.newproject.domain

import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteFile
import com.example.newproject.model.ReadingTrace
import com.example.newproject.model.state.BacklinkRow

// ---------------------------------------------------------------------------
// 索引から引いたリンクを解き、今のノートへのリンクを1ノート1行にまとめて、久しく読んでいない順に並べる
// （→ docs/dev/features/backlinks.md §5・§8）。
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
    val sentenceLink: IntRange?,
    val sentenceNameLength: Int?
) {
    fun toNoteLink(): NoteLink = when (kind) {
        IndexedLinkKind.NAME -> NoteLink.ByName(target, heading)
        IndexedLinkKind.PATH -> NoteLink.ByPath(target, heading)
    }
}

/** 今のノートへリンクしている出どころ1つと、本文で最初に出てくるそのリンク（→ [backlinkSources]）。 */
internal class BacklinkSource(val note: NoteFile, val firstLink: BacklinkCandidate)

/**
 * 出どころのノートを、このアプリで最後に読んだ日時と、余白メモを書いた節（痕跡から → backlinks.md §5）。
 * 痕跡が無い・壊れている・読めないときは [UNREAD] で、読んだ記録が無いとして最も古く並べる。
 *
 * [memoSectionKeys] は見出しの照合の鍵（[headingMatchKey]）。**見出しを持たないメモは入れない** —
 * 見出しより前で書いたメモと、見出しを控えていない古いメモを区別できないため。
 */
internal data class SourceReading(val lastReadAt: Long?, val memoSectionKeys: Set<String>) {
    companion object {
        val UNREAD = SourceReading(lastReadAt = null, memoSectionKeys = emptySet())
    }
}

internal fun sourceReadingOf(trace: ReadingTrace): SourceReading = SourceReading(
    lastReadAt = trace.visits.maxOfOrNull { it.atEpochMillis },
    memoSectionKeys = trace.memos.mapNotNullTo(HashSet()) { memo -> memo.sectionTitle?.let(::headingMatchKey) }
)

/**
 * [candidates] を解き、[current] へ着くリンクだけを出どころごとにまとめる。
 * [outgoing] は今のノートから出るリンクで、**[current] から同じ規則で解いて着いた先の出どころを除く**（互いのリンク。本文を読めば分かる）。
 *
 * - **出どころは走査の索引（[index]）で引く。** 引けなければ解かずに除く — 出どころの相対パスが分からないまま解くと、
 *   根からの相対で解いて行き先を誤る（一部のフォルダを読めない走査 → knowledge_index.md §5）
 * - **出どころから本文のリンクと同じ規則（[resolveNoteLink]）で解き、今のノートへ着くものだけを残す。** 同じ名前の別のノートへ着くリンクは除く
 * - 今のノート自身の中のリンクは除く
 */
internal fun backlinkSources(
    candidates: List<BacklinkCandidate>,
    outgoing: List<NoteLink>,
    current: DocumentRef,
    index: NoteLinkIndex
): List<BacklinkSource> {
    val linkedFromCurrent = outgoing.mapNotNullTo(HashSet()) { link ->
        (resolveNoteLink(link, current, index) as? NoteLinkResolution.Open)?.note?.ref
    }
    return candidates
        .mapNotNull { candidate ->
            val source = index.atExactPath(candidate.sourcePath) ?: return@mapNotNull null
            if (source.ref == current || source.ref in linkedFromCurrent) return@mapNotNull null
            val resolved = resolveNoteLink(candidate.toNoteLink(), source.ref, index)
            if (resolved is NoteLinkResolution.Open && resolved.note.ref == current) source to candidate else null
        }
        .groupBy({ it.first }, { it.second })
        .map { (source, links) -> BacklinkSource(source, links.minBy { it.ordinal }) }
}

/** 1文の中の今のノートの題名を言い換える語（→ backlinks.md 判断6）。 */
internal const val THIS_NOTE_WORDS = "このノート"

/**
 * リンクの範囲（[link]）の先頭から [nameLength] 文字の名前を「このノート」へ替え、替えた1文と、替えた後のリンクの範囲を返す。
 * 替えてよい長さは本文から取り出すときに記法で決めてある（`KnowledgeIndexLinks` の `sentenceNameLength`）。
 * 別名（null）は替えない — 題名と違う表示名は、そのとき自分が選んだ言葉だから。見出しは名前の後ろにあるので書かれたまま残る。
 */
internal fun sentenceForCurrent(sentence: String, link: IntRange?, nameLength: Int?): Pair<String, IntRange?> {
    if (link == null || link.isEmpty() || link.last >= sentence.length || nameLength == null || nameLength <= 0) return sentence to link
    val length = nameLength.coerceAtMost(link.last - link.first + 1)
    val replaced = sentence.replaceRange(link.first, link.first + length, THIS_NOTE_WORDS)
    return replaced to (link.first until link.last + 1 - length + THIS_NOTE_WORDS.length)
}

/**
 * 出どころを1行ずつにし、**最後に読んだ日が古い順**に並べる（再会の入口 → backlinks.md 判断3）。
 * [readings] は出どころの相対パスごとの痕跡で、無ければ読んだ記録が無いとして扱う。
 * 1文の中の、別名の無いリンクの名前は「このノート」と出す（[sentenceForCurrent]）。
 *
 * - 読んだ記録が無い出どころを最も古いとして先に出す。同じなら出どころの更新日時が古い順（日時が無ければ最も古い）、それも同じなら題名の順
 * - ✎ は、最初のリンクのある節と同じ見出しにメモがあるときだけ。リンクが見出しより前なら付けない（→ [SourceReading]）
 */
internal fun backlinkRows(sources: List<BacklinkSource>, readings: Map<String, SourceReading>): List<BacklinkRow> =
    sources
        .map { source -> source to (readings[source.note.vaultRelativePath] ?: SourceReading.UNREAD) }
        .sortedWith(
            compareBy<Pair<BacklinkSource, SourceReading>> { (_, reading) -> reading.lastReadAt ?: Long.MIN_VALUE }
                .thenBy { (source, _) -> source.note.lastModified ?: Long.MIN_VALUE }
                .thenBy { (source, _) -> source.note.name }
        )
        .map { (source, reading) ->
            val link = source.firstLink
            val (sentence, sentenceLink) = sentenceForCurrent(link.sentence, link.sentenceLink, link.sentenceNameLength)
            BacklinkRow(
                note = source.note.toRelatedNote(),
                sentence = sentence,
                sentenceLink = sentenceLink,
                sectionHeading = link.sectionHeading,
                sectionHeadingOccurrence = link.sectionHeadingOccurrence,
                hasMemo = link.sectionHeading?.let { headingMatchKey(it) in reading.memoSectionKeys } == true
            )
        }
