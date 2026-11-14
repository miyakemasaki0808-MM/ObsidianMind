package com.example.newproject.data

import com.example.newproject.domain.IndexedLink
import com.example.newproject.domain.IndexedNoteState

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

    override suspend fun markFirstBuildDone(vaultKey: String) =
        dao.markFirstBuildDone(vaultKey)
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
    nameKey = keys.firstOrNull().orEmpty(),
    decodedNameKey = keys.getOrNull(1)
)
