package com.example.newproject.data

import com.example.newproject.model.READING_TRACE_SCHEMA_VERSION
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
        // 読み込みは旧版も受け付けるが、書き戻しは常に現行版。decode が移行済みの
        // トレースを返すので、ここへ旧版が来るのは実装ミス。
        require(trace.schemaVersion == READING_TRACE_SCHEMA_VERSION) {
            "書き込めるのは現行フォーマット（version=$READING_TRACE_SCHEMA_VERSION）だけです。"
        }
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
            .put(KEY_TOTAL_VISIT_COUNT, trace.totalVisitCount)
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
            val version = root.getInt(KEY_SCHEMA_VERSION)
            val trace = ReadingTrace(
                vaultRelativePath = root.getString(KEY_RELATIVE_PATH),
                noteTitle = root.getString(KEY_NOTE_TITLE),
                documentId = root.stringOrNull(KEY_DOCUMENT_ID),
                visits = visits,
                aiSummary = root.stringOrNull(KEY_AI_SUMMARY),
                aiSummaryVisitCount = root.intOrNull(KEY_AI_SUMMARY_VISIT_COUNT),
                // v1 には累計という概念が無いので、保持件数を初期値にする（30件で
                // 頭打ちだった分は取り戻せないが、そこから先は正しく積み上がる）。
                // v1 のファイルにこの項目が書かれていても読まない。v1 の checksum は
                // この値を含まないため、読むと改変し放題の入口になる。
                totalVisitCount = if (version >= 2) root.getInt(KEY_TOTAL_VISIT_COUNT) else visits.size,
                schemaVersion = version
            )
            validateReadingTrace(trace)
            // checksum は**書かれた版の正規形**で照合する。現行版の正規形で計算すると、
            // 既存の v1 ファイルが軒並み不一致＝破損扱いになる。
            require(root.getString(KEY_CHECKSUM) == checksumOf(trace)) {
                "痕跡ファイルの整合性を確認できません。"
            }
            // ここから先はメモリ上では常に現行版。encode が旧版を書き戻すことはない。
            ReadingTraceReadResult.Valid(trace.copy(schemaVersion = READING_TRACE_SCHEMA_VERSION))
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
     *
     * **版ごとに形が違う。** v1 のファイルは v1 の正規形で checksum が計算されている
     * ため、現行版の形で照合すると既存の痕跡が全部「破損」になる。追加した項目は
     * 末尾に足すだけにして、旧版の並びはそのまま残す。
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
            // v2 で追加。v1 には無いので書かない。
            if (trace.schemaVersion >= 2) out.writeInt(trace.totalVisitCount)
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
    private const val KEY_TOTAL_VISIT_COUNT = "totalVisitCount"
    private const val KEY_CHECKSUM = "checksum"
    private const val KEY_VISIT_AT = "at"
    private const val KEY_VISIT_SECTION = "deepestSection"
    private const val KEY_VISIT_PROGRESS = "progressPercent"
}
