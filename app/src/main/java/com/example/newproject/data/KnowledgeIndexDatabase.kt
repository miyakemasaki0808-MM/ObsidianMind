package com.example.newproject.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import com.example.newproject.domain.mayApplyIndexedRead
import java.io.File

/**
 * 知識索引の DB（→ docs/dev/system/knowledge_index.md §6）。**Vault から作り直せる派生物だけを置き、正にしない。**
 *
 * 置き場は `noBackupFilesDir` で、端末の自動バックアップに載せない（§9。結晶の材料の控えと同じ）。
 * 版を上げるときは移行を書かずに作り直す — 中身は Vault から作り直せるため。
 */
@Database(
    entities = [IndexMetaEntity::class, IndexedNoteEntity::class, IndexedLinkEntity::class, RewriteMarkEntity::class],
    version = 2,
    exportSchema = false
)
internal abstract class KnowledgeIndexDatabase : RoomDatabase() {
    abstract fun dao(): KnowledgeIndexDao

    companion object {
        const val FILE_NAME = "knowledge_index.db"

        /** 本番の DB。開くのは最初の問い合わせのときで、ここでは I/O をしない。 */
        fun open(context: Context): KnowledgeIndexDatabase =
            Room.databaseBuilder(
                context,
                KnowledgeIndexDatabase::class.java,
                File(context.noBackupFilesDir, FILE_NAME).path
            )
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}

/** 索引の素性。行は1つだけ（[id] は常に 0）。[lastWriteNumber] は書き換えの番号の最後の値で、Vault を替えても戻さない。 */
@Entity(tableName = "index_meta")
internal data class IndexMetaEntity(
    @PrimaryKey val id: Int = 0,
    val vaultKey: String,
    val firstBuildDone: Boolean,
    val lastWriteNumber: Long
)

@Entity(tableName = "indexed_note", indices = [Index("documentRef")])
internal data class IndexedNoteEntity(
    @PrimaryKey val relativePath: String,
    val documentRef: String,
    val name: String,
    val lastModified: Long?,
    val fingerprint: String
)

/** 1つのリンク。行き先は書かれたまま持つ。[nameKey]・[decodedNameKey] は候補を絞る名前の鍵（§6）。 */
@Entity(
    tableName = "indexed_link",
    primaryKeys = ["sourcePath", "ordinal"],
    indices = [Index("nameKey"), Index("decodedNameKey")]
)
internal data class IndexedLinkEntity(
    val sourcePath: String,
    val ordinal: Int,
    val kind: String,
    val target: String,
    val heading: String?,
    val sectionHeading: String?,
    val sectionHeadingOccurrence: Int,
    val sentence: String,
    val nameKey: String,
    val decodedNameKey: String?
)

/** アプリが本文を書き換えたと控えたパスと、その書き換えの番号（§3 の手順4）。プロセスを越えて残す。 */
@Entity(tableName = "rewrite_mark")
internal data class RewriteMarkEntity(
    @PrimaryKey val relativePath: String,
    val writeNumber: Long
)

internal data class IndexedNoteStateRow(
    val relativePath: String,
    val lastModified: Long?,
    val fingerprint: String
)

/**
 * 書き込みはどれも、索引の素性が受け取った Vault と同じときだけ行う。**照合と書き込みを1つのトランザクションに入れる** —
 * 分けると、照合の後に Vault を替えて索引を作り直した間に、旧い Vault の結果が入る。
 */
@Dao
internal abstract class KnowledgeIndexDao {
    @Query("SELECT * FROM index_meta WHERE id = 0")
    abstract suspend fun meta(): IndexMetaEntity?

    @Upsert
    abstract suspend fun upsertMeta(meta: IndexMetaEntity)

    @Query("SELECT relativePath, lastModified, fingerprint FROM indexed_note")
    abstract suspend fun noteStates(): List<IndexedNoteStateRow>

    @Query("SELECT * FROM rewrite_mark")
    abstract suspend fun marks(): List<RewriteMarkEntity>

    @Query("SELECT writeNumber FROM rewrite_mark WHERE relativePath = :path")
    abstract suspend fun markOf(path: String): Long?

    @Query("SELECT relativePath FROM indexed_note WHERE documentRef = :documentRef")
    abstract suspend fun pathsOf(documentRef: String): List<String>

    @Query("SELECT * FROM indexed_link WHERE sourcePath = :path ORDER BY ordinal")
    abstract suspend fun linksOf(path: String): List<IndexedLinkEntity>

    @Upsert
    abstract suspend fun upsertMark(mark: RewriteMarkEntity)

    @Query("DELETE FROM rewrite_mark WHERE relativePath = :path")
    abstract suspend fun deleteMark(path: String)

    @Upsert
    abstract suspend fun upsertNote(note: IndexedNoteEntity)

    @Query("DELETE FROM indexed_link WHERE sourcePath = :path")
    abstract suspend fun deleteLinksOf(path: String)

    @Insert
    abstract suspend fun insertLinks(links: List<IndexedLinkEntity>)

    @Query("DELETE FROM indexed_note WHERE relativePath IN (:paths)")
    abstract suspend fun deleteNoteRows(paths: List<String>)

    @Query("DELETE FROM indexed_link WHERE sourcePath IN (:paths)")
    abstract suspend fun deleteLinkRows(paths: List<String>)

    @Query("DELETE FROM rewrite_mark WHERE relativePath IN (:paths)")
    abstract suspend fun deleteMarkRows(paths: List<String>)

    @Query("DELETE FROM indexed_note")
    abstract suspend fun clearNotes()

    @Query("DELETE FROM indexed_link")
    abstract suspend fun clearLinks()

    @Query("DELETE FROM rewrite_mark")
    abstract suspend fun clearMarks()

    /** [vaultKey] の索引を開く。別の Vault の索引なら全部消し、素性を作り直す。 */
    @Transaction
    open suspend fun openVault(vaultKey: String): IndexMetaEntity {
        val current = meta()
        if (current?.vaultKey == vaultKey) return current
        clearLinks()
        clearNotes()
        clearMarks()
        val fresh = IndexMetaEntity(vaultKey = vaultKey, firstBuildDone = false, lastWriteNumber = current?.lastWriteNumber ?: 0L)
        upsertMeta(fresh)
        return fresh
    }

    /** 参照で引いたノートを、読み直すと控える。索引に無い参照なら何もしない。 */
    @Transaction
    open suspend fun markRewritten(vaultKey: String, documentRef: String) {
        val current = meta()?.takeIf { it.vaultKey == vaultKey } ?: return
        val paths = pathsOf(documentRef)
        if (paths.isEmpty()) return
        var number = current.lastWriteNumber
        paths.forEach { path -> upsertMark(RewriteMarkEntity(path, ++number)) }
        upsertMeta(current.copy(lastWriteNumber = number))
    }

    @Transaction
    open suspend fun applyRead(
        vaultKey: String,
        note: IndexedNoteEntity,
        links: List<IndexedLinkEntity>,
        writeNumberAtRead: Long?
    ): Boolean {
        if (meta()?.vaultKey != vaultKey) return false
        if (!mayApplyIndexedRead(writeNumberAtRead, markOf(note.relativePath))) return false
        upsertNote(note)
        deleteLinksOf(note.relativePath)
        if (links.isNotEmpty()) insertLinks(links)
        if (writeNumberAtRead != null) deleteMark(note.relativePath)
        return true
    }

    /** SQLite の変数の上限（古い端末で 999）を超えないよう、まとまりに分けて消す。 */
    @Transaction
    open suspend fun deleteNotes(vaultKey: String, relativePaths: List<String>) {
        if (meta()?.vaultKey != vaultKey) return
        relativePaths.chunked(DELETE_CHUNK).forEach { chunk ->
            deleteLinkRows(chunk)
            deleteNoteRows(chunk)
            deleteMarkRows(chunk)
        }
    }

    @Transaction
    open suspend fun markFirstBuildDone(vaultKey: String) {
        val current = meta()?.takeIf { it.vaultKey == vaultKey } ?: return
        if (!current.firstBuildDone) upsertMeta(current.copy(firstBuildDone = true))
    }

    private companion object {
        const val DELETE_CHUNK = 500
    }
}
