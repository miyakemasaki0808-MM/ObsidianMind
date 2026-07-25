package com.example.newproject.data

import com.example.newproject.model.ReadingTrace
import com.example.newproject.model.ReadingVisit
import com.example.newproject.model.validateReadingTrace
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.json.JSONArray
import org.json.JSONObject

// ---------------------------------------------------------------------------
// ReadingTrace のサイドカー形式（JSON）。
//
// JSONを選ぶのは、閲覧履歴（NoteHistoryStore）で既にAndroid標準の org.json を
// 使っており追加ライブラリが要らないうえ、「データはポータブルで読めるべき」という
// Obsidianの思想に合う（ユーザー自身がテキストエディタで確認・修復できる）ため。
// ---------------------------------------------------------------------------

internal sealed interface ReadingTraceReadResult {
    data object None : ReadingTraceReadResult
    data class Valid(val trace: ReadingTrace) : ReadingTraceReadResult
    /** 破損。カードを出さず孤立扱いにする。ユーザーのノートには一切触れない。 */
    data class Corrupt(val reason: String) : ReadingTraceReadResult
}

internal object ReadingTraceJson {

    fun encode(trace: ReadingTrace): ByteArray {
        validateReadingTrace(trace)
        val visits = JSONArray()
        trace.visits.forEach { visit ->
            visits.put(
                JSONObject()
                    .put(KEY_VISIT_AT, visit.atEpochMillis)
                    .put(KEY_VISIT_SECTION, visit.deepestSectionTitle ?: JSONObject.NULL)
                    .put(KEY_VISIT_PROGRESS, visit.progressPercent)
            )
        }
        val root = JSONObject()
            .put(KEY_SCHEMA_VERSION, trace.schemaVersion)
            .put(KEY_RELATIVE_PATH, trace.vaultRelativePath)
            .put(KEY_NOTE_TITLE, trace.noteTitle)
            .put(KEY_DOCUMENT_ID, trace.documentId ?: JSONObject.NULL)
            .put(KEY_VISITS, visits)
            .put(KEY_AI_SUMMARY, trace.aiSummary ?: JSONObject.NULL)
            .put(KEY_AI_SUMMARY_VISIT_COUNT, trace.aiSummaryVisitCount ?: JSONObject.NULL)
            .put(KEY_CHECKSUM, checksumOf(trace))
        // 人が読める体裁で書く（ユーザーが中身を確認・修復できることを優先）。
        return root.toString(2).toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): ReadingTraceReadResult {
        if (bytes.isEmpty()) return ReadingTraceReadResult.Corrupt("痕跡ファイルが空です。")
        return try {
            val root = JSONObject(decodeUtf8Strict(bytes))
            val visitsJson = root.getJSONArray(KEY_VISITS)
            val visits = (0 until visitsJson.length()).map { index ->
                val visit = visitsJson.getJSONObject(index)
                ReadingVisit(
                    atEpochMillis = visit.getLong(KEY_VISIT_AT),
                    deepestSectionTitle = visit.stringOrNull(KEY_VISIT_SECTION),
                    progressPercent = visit.getInt(KEY_VISIT_PROGRESS)
                )
            }
            val trace = ReadingTrace(
                vaultRelativePath = root.getString(KEY_RELATIVE_PATH),
                noteTitle = root.getString(KEY_NOTE_TITLE),
                documentId = root.stringOrNull(KEY_DOCUMENT_ID),
                visits = visits,
                aiSummary = root.stringOrNull(KEY_AI_SUMMARY),
                aiSummaryVisitCount = root.intOrNull(KEY_AI_SUMMARY_VISIT_COUNT),
                schemaVersion = root.getInt(KEY_SCHEMA_VERSION)
            )
            validateReadingTrace(trace)
            require(root.getString(KEY_CHECKSUM) == checksumOf(trace)) {
                "痕跡ファイルの整合性を確認できません。"
            }
            ReadingTraceReadResult.Valid(trace)
        } catch (error: Exception) {
            ReadingTraceReadResult.Corrupt(error.message ?: error::class.java.simpleName)
        }
    }

    private fun checksumOf(trace: ReadingTrace): String = sha256Hex(canonicalPayload(trace))

    /**
     * checksum 計算用の正規形。
     *
     * org.json はキー順を保証しないため、JSON文字列そのものは checksum の入力に使えない
     * （同じ内容から別のハッシュが出る）。キー順を固定し、各文字列にUTF-8バイト長を
     * 前置して連結する。長さ前置なので、区切り文字をノートタイトルやセクション名に
     * 混ぜて別の内容へ偽装することができない。
     *
     * documentId は含めない。端末内の引き当てキャッシュにすぎず、別端末で再バインド
     * するたびに書き換わる値を整合性の対象にすると、キャッシュ更新のたびに
     * ユーザー内容の checksum を触ることになる。
     */
    private fun canonicalPayload(trace: ReadingTrace): ByteArray {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use { out ->
            out.writeInt(trace.schemaVersion)
            out.writeSized(trace.vaultRelativePath)
            out.writeSized(trace.noteTitle)
            out.writeInt(trace.visits.size)
            trace.visits.forEach { visit ->
                out.writeLong(visit.atEpochMillis)
                out.writeInt(visit.progressPercent)
                out.writeSizedNullable(visit.deepestSectionTitle)
            }
            out.writeSizedNullable(trace.aiSummary)
            out.writeInt(trace.aiSummaryVisitCount ?: ABSENT_VISIT_COUNT)
        }
        return buffer.toByteArray()
    }

    private fun DataOutputStream.writeSized(value: String) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        writeInt(encoded.size)
        write(encoded)
    }

    /** null と空文字を別物として扱うため、存在フラグを1バイト前置する。 */
    private fun DataOutputStream.writeSizedNullable(value: String?) {
        if (value == null) {
            writeByte(0)
        } else {
            writeByte(1)
            writeSized(value)
        }
    }

    // キーが無い場合も JSONObject.isNull は true を返すので、
    // 任意項目の欠落と明示的な null を同じ扱いにできる。
    private fun JSONObject.stringOrNull(key: String): String? =
        if (isNull(key)) null else getString(key)

    private fun JSONObject.intOrNull(key: String): Int? =
        if (isNull(key)) null else getInt(key)

    private const val ABSENT_VISIT_COUNT = -1

    private const val KEY_SCHEMA_VERSION = "schemaVersion"
    private const val KEY_RELATIVE_PATH = "vaultRelativePath"
    private const val KEY_NOTE_TITLE = "noteTitle"
    private const val KEY_DOCUMENT_ID = "documentId"
    private const val KEY_VISITS = "visits"
    private const val KEY_AI_SUMMARY = "aiSummary"
    private const val KEY_AI_SUMMARY_VISIT_COUNT = "aiSummaryVisitCount"
    private const val KEY_CHECKSUM = "checksum"
    private const val KEY_VISIT_AT = "at"
    private const val KEY_VISIT_SECTION = "deepestSection"
    private const val KEY_VISIT_PROGRESS = "progressPercent"
}
