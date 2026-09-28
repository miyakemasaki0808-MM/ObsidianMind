package com.example.newproject.data

import com.example.newproject.model.CrystalLimits
import com.example.newproject.model.CrystalMaterial
import com.example.newproject.model.CrystalMaterialLog
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * 結晶の材料の控え（→ `docs/dev/features/reflect_crystal.md` 判断7）。
 *
 * **Vault には書かない。** 書くとノートを開くたびに Vault への書き込みが1回増える。
 * 控えは失っても読めば溜まり直すので、端末内で足りる。
 */
internal interface CrystalMaterialPersistence {
    /** 読めなければ空の控え。**壊れた控えを直そうとしない。** */
    fun load(vaultKey: String): CrystalMaterialLog
    fun save(vaultKey: String, log: CrystalMaterialLog)
}

/**
 * Vault ごとに1ファイル。名前は Vault の名前空間の SHA-256。
 *
 * **一時ファイルへ書いてから置き換える。** 端末内のファイルなので、SAF と違って置き換えが原子的にできる。
 */
internal class FileCrystalMaterialStore(private val directory: File) : CrystalMaterialPersistence {

    @Synchronized
    override fun load(vaultKey: String): CrystalMaterialLog = try {
        val file = fileFor(vaultKey)
        if (!file.isFile) CrystalMaterialLog.EMPTY else decode(file.readText(Charsets.UTF_8))
    } catch (_: Exception) {
        CrystalMaterialLog.EMPTY
    }

    @Synchronized
    override fun save(vaultKey: String, log: CrystalMaterialLog) {
        directory.mkdirs()
        val target = fileFor(vaultKey)
        val temporary = File(directory, target.name + ".tmp")
        temporary.writeText(encode(log), Charsets.UTF_8)
        if (!temporary.renameTo(target)) {
            target.delete()
            if (!temporary.renameTo(target)) {
                temporary.delete()
                throw java.io.IOException("結晶の控えを保存できませんでした。")
            }
        }
    }

    private fun fileFor(vaultKey: String) =
        File(directory, sha256Hex(vaultKey.toByteArray(Charsets.UTF_8)) + ".json")

    private fun encode(log: CrystalMaterialLog): String {
        val entries = JSONArray()
        log.entries.forEach { entry ->
            entries.put(
                JSONObject()
                    .put(KEY_PATH, entry.vaultRelativePath)
                    .put(KEY_TITLE, entry.noteTitle)
                    .put(KEY_FRAGMENT, entry.fragment)
                    .put(KEY_LAST_SEEN_AT, entry.lastSeenAt)
            )
        }
        val presented = JSONArray()
        log.presented.forEach { presented.put(it) }
        return JSONObject()
            .put(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
            .put(KEY_LAST_ATTEMPT_AT, log.lastAttemptAt ?: JSONObject.NULL)
            .put(KEY_ENTRIES, entries)
            .put(KEY_PRESENTED, presented)
            .toString()
    }

    private fun decode(text: String): CrystalMaterialLog {
        val root = JSONObject(text)
        if (root.getInt(KEY_SCHEMA_VERSION) != SCHEMA_VERSION) return CrystalMaterialLog.EMPTY
        val entriesJson = root.getJSONArray(KEY_ENTRIES)
        val entries = (0 until entriesJson.length()).map { index ->
            val entry = entriesJson.getJSONObject(index)
            CrystalMaterial(
                vaultRelativePath = entry.getString(KEY_PATH),
                noteTitle = entry.getString(KEY_TITLE),
                fragment = entry.getString(KEY_FRAGMENT),
                lastSeenAt = entry.getLong(KEY_LAST_SEEN_AT)
            )
        }
        val presentedJson = root.getJSONArray(KEY_PRESENTED)
        val presented = (0 until presentedJson.length()).map { presentedJson.getString(it) }
        return CrystalMaterialLog(
            lastAttemptAt = if (root.isNull(KEY_LAST_ATTEMPT_AT)) null else root.getLong(KEY_LAST_ATTEMPT_AT),
            entries = entries.take(CrystalLimits.MATERIAL_ENTRIES),
            presented = presented.takeLast(CrystalLimits.PRESENTED_HISTORY)
        )
    }

    companion object {
        /** `noBackupFilesDir` の下の置き場。自動バックアップにも退避にも載らない。 */
        const val DIRECTORY_NAME = "crystal_material"

        private const val SCHEMA_VERSION = 1
        private const val KEY_SCHEMA_VERSION = "schemaVersion"
        private const val KEY_LAST_ATTEMPT_AT = "lastAttemptAt"
        private const val KEY_ENTRIES = "entries"
        private const val KEY_PRESENTED = "presented"
        private const val KEY_PATH = "vaultRelativePath"
        private const val KEY_TITLE = "noteTitle"
        private const val KEY_FRAGMENT = "fragment"
        private const val KEY_LAST_SEEN_AT = "lastSeenAt"
    }
}
