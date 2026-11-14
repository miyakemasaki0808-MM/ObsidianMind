package com.example.newproject.data

import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.RoomDatabase

/** 知識索引の DB。Vault から作り直せる派生物だけを置き、正にしない。 */
@Database(entities = [IndexedNoteEntity::class], version = 1, exportSchema = false)
internal abstract class KnowledgeIndexDatabase : RoomDatabase()

@Entity(tableName = "indexed_note")
internal data class IndexedNoteEntity(@PrimaryKey val documentId: String)
