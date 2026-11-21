package com.example.newproject.data

import com.example.newproject.domain.BacklinkCandidate
import com.example.newproject.domain.IndexedLink
import com.example.newproject.domain.IndexedLinkKind
import com.example.newproject.domain.IndexedNoteState
import com.example.newproject.domain.NoteLink

/**
 * [KnowledgeIndexStore] の本番実装。[database] は最初に使うときに作る — 作るだけなら I/O はしないが、
 * 依存の組み立てを索引が使われない経路（テスト・索引を持たない組み立て）から切り離す。
 */
internal class RoomKnowledgeIndexStore(database: () -> KnowledgeIndexDatabase) : KnowledgeIndexStore {
    private val dao by lazy { database().dao() }

    override suspend fun openVault(vaultKey: String): KnowledgeIndexSnapshot {
        val meta = dao.openVault(vaultKey)
        return KnowledgeIndexSnapshot(
            notes = dao.noteStates().associate { it.relativePath to IndexedNoteState(it.lastModified, it.fingerprint) },
            writeNumbers = dao.marks().associate { it.documentRef to it.writeNumber },
            firstBuildDone = meta.firstBuildDone
        )
    }

    override fun markRewrittenNow(documentRef: String) =
        dao.markRewrittenNow(documentRef)

    override suspend fun forgetMarks(vaultKey: String, documentRefs: List<String>) =
        dao.forgetMarks(vaultKey, documentRefs)

    override suspend fun applyRead(
        vaultKey: String,
        note: IndexedNoteRecord,
        links: List<IndexedLink>,
        writeNumberAtRead: Long?
    ): Boolean = dao.applyRead(
        vaultKey = vaultKey,
        note = IndexedNoteEntity(
            relativePath = note.relativePath,
            documentRef = note.documentRef,
            name = note.name,
            lastModified = note.lastModified,
            fingerprint = note.fingerprint
        ),
        links = links.map { it.toEntity(note.relativePath) },
        writeNumberAtRead = writeNumberAtRead
    )

    override suspend fun deleteNotes(vaultKey: String, relativePaths: List<String>) =
        dao.deleteNotes(vaultKey, relativePaths)

    override suspend fun clearMark(vaultKey: String, documentRef: String, writeNumber: Long) =
        dao.clearMark(vaultKey, documentRef, writeNumber)

    override suspend fun markFirstBuildDone(vaultKey: String) =
        dao.markFirstBuildDone(vaultKey)

    override suspend fun backlinkLinks(vaultKey: String, nameKey: String, relativePath: String): BacklinkLinks? =
        dao.backlinkLinksIn(vaultKey, nameKey, relativePath)?.let { (candidates, outgoing) ->
            BacklinkLinks(
                candidates = candidates.map { row ->
                    BacklinkCandidate(
                        sourcePath = row.sourcePath,
                        ordinal = row.ordinal,
                        kind = IndexedLinkKind.valueOf(row.kind),
                        target = row.target,
                        heading = row.heading,
                        sectionHeading = row.sectionHeading,
                        sectionHeadingOccurrence = row.sectionHeadingOccurrence,
                        sentence = row.sentence,
                        sentenceLink = sentenceLinkOf(row.sentenceLinkStart, row.sentenceLinkEnd),
                        sentenceNameLength = row.sentenceNameLength
                    )
                },
                outgoing = outgoing?.map { row ->
                    when (IndexedLinkKind.valueOf(row.kind)) {
                        IndexedLinkKind.NAME -> NoteLink.ByName(row.target, row.heading)
                        IndexedLinkKind.PATH -> NoteLink.ByPath(row.target, row.heading)
                    }
                }
            )
        }
}

private fun IndexedLink.toEntity(sourcePath: String) = IndexedLinkEntity(
    sourcePath = sourcePath,
    ordinal = ordinal,
    kind = kind.name,
    target = target,
    heading = heading,
    sectionHeading = sectionHeading,
    sectionHeadingOccurrence = sectionHeadingOccurrence,
    sentence = sentence,
    sentenceLinkStart = sentenceLink?.first,
    sentenceLinkEnd = sentenceLink?.let { it.last + 1 },
    sentenceNameLength = sentenceNameLength,
    nameKey = keys.firstOrNull().orEmpty(),
    decodedNameKey = keys.getOrNull(1)
)

/** 1文の中のリンクの範囲を戻す。片方でも欠けていれば null。 */
private fun sentenceLinkOf(start: Int?, endExclusive: Int?): IntRange? =
    if (start != null && endExclusive != null && start < endExclusive) start until endExclusive else null
