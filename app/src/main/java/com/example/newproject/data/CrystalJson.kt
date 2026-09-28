package com.example.newproject.data

import com.example.newproject.model.Crystal
import com.example.newproject.model.CrystalLimits
import com.example.newproject.model.CrystalSource
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * 結晶1件のファイル形式（→ `docs/dev/features/reflect_crystal.md` §6）。
 *
 * 読書痕跡と同じく org.json で人が読める体裁に書き、checksum で改変と破損を見分ける。
 * **ファイル名は中身に入れない** — 端末間で衝突すると SAF が別名にするので、読んだ側が付ける。
 */
internal object CrystalJson {

    const val SCHEMA_VERSION = 1

    fun encode(crystal: Crystal): ByteArray {
        require(crystal.sources.size in CrystalLimits.MIN_SOURCES..CrystalLimits.MAX_SOURCES) {
            "根拠は${CrystalLimits.MIN_SOURCES}〜${CrystalLimits.MAX_SOURCES}件です（${crystal.sources.size}件）。"
        }
        require(crystal.sentence.isNotBlank()) { "結晶の文が空です。" }
        val sources = JSONArray()
        crystal.sources.forEach { source ->
            sources.put(
                JSONObject()
                    .put(KEY_PATH, source.vaultRelativePath)
                    .put(KEY_TITLE, source.noteTitle)
                    .put(KEY_FRAGMENT, source.fragment)
            )
        }
        val root = JSONObject()
            .put(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
            .put(KEY_CREATED_AT, crystal.createdAt)
            .put(KEY_SENTENCE, crystal.sentence)
            .put(KEY_SOURCES, sources)
            .put(KEY_CHECKSUM, checksumOf(crystal))
        return root.toString(2).toByteArray(Charsets.UTF_8)
    }

    /**
     * 読めなければ null。**呼び出し側はその1件だけを飛ばす**（一覧全体は止めない）。
     *
     * 未知の版・checksum 不一致・根拠の件数の外れ・空の文を読めないものとして扱う。
     */
    fun decode(bytes: ByteArray, fileName: String): Crystal? = try {
        val root = JSONObject(decodeUtf8Strict(bytes))
        if (root.getInt(KEY_SCHEMA_VERSION) != SCHEMA_VERSION) {
            null
        } else {
            val array = root.getJSONArray(KEY_SOURCES)
            val sources = (0 until array.length()).map { index ->
                val source = array.getJSONObject(index)
                CrystalSource(
                    vaultRelativePath = source.getString(KEY_PATH),
                    noteTitle = source.getString(KEY_TITLE),
                    fragment = source.getString(KEY_FRAGMENT)
                )
            }
            val crystal = Crystal(
                createdAt = root.getLong(KEY_CREATED_AT),
                sentence = root.getString(KEY_SENTENCE),
                sources = sources,
                fileName = fileName
            )
            val valid = crystal.sources.size in CrystalLimits.MIN_SOURCES..CrystalLimits.MAX_SOURCES &&
                crystal.sentence.isNotBlank() &&
                root.getString(KEY_CHECKSUM) == checksumOf(crystal)
            if (valid) crystal else null
        }
    } catch (_: Exception) {
        null
    }

    /**
     * checksum の正規形。**キー順を固定し、各文字列に UTF-8 のバイト長を前置する**
     * （org.json はキー順を保証せず、区切り文字で連結すると別の内容へ偽装できる）。
     * ファイル名は含めない。
     */
    private fun checksumOf(crystal: Crystal): String {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(SCHEMA_VERSION)
            out.writeLong(crystal.createdAt)
            out.writeSized(crystal.sentence)
            out.writeInt(crystal.sources.size)
            crystal.sources.forEach { source ->
                out.writeSized(source.vaultRelativePath)
                out.writeSized(source.noteTitle)
                out.writeSized(source.fragment)
            }
        }
        return sha256Hex(buffer.toByteArray())
    }

    private fun DataOutputStream.writeSized(value: String) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        writeInt(encoded.size)
        write(encoded)
    }

    private const val KEY_SCHEMA_VERSION = "schemaVersion"
    private const val KEY_CREATED_AT = "createdAt"
    private const val KEY_SENTENCE = "sentence"
    private const val KEY_SOURCES = "sources"
    private const val KEY_PATH = "vaultRelativePath"
    private const val KEY_TITLE = "noteTitle"
    private const val KEY_FRAGMENT = "fragment"
    private const val KEY_CHECKSUM = "checksum"
}
