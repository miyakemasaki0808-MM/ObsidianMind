package com.example.newproject.data

import com.example.newproject.model.READING_TRACE_SCHEMA_VERSION
import com.example.newproject.model.ReadingTrace
import com.example.newproject.model.ReadingTraceBackupLimits
import org.json.JSONArray
import org.json.JSONObject

// ---------------------------------------------------------------------------
// 退避ファイル（痕跡をまとめた1ファイル）の組み立てと解釈。
//
// **中身1件ずつの形式は `ReadingTraceJson` をそのまま使う。** 版の解釈も checksum の
// 照合もあちらが持っているので、ここが持つのは「束ね方」だけになる。
// 生バイトを詰め替えず decode → 再encode を通すのは、壊れたファイルをそのまま
// 運ばないため（「読めないものを検出する」というゴールと噛み合わせる）。
//
// **束ねる版は痕跡のスキーマ版とは別に持つ。** 束ね方が変わっても中身の版は変わらない。
// ---------------------------------------------------------------------------

/** 退避ファイル内の1件。**読めなかったものを黙って捨てない。** */
internal sealed interface ReadingTraceBackupEntry {
    data class Valid(val trace: ReadingTrace) : ReadingTraceBackupEntry

    /** 版違い・checksum不一致・改変。**適用しないが、件数としては報告する。** */
    data class Corrupt(val reason: String) : ReadingTraceBackupEntry
}

internal sealed interface ReadingTraceBackupReadResult {
    data class Valid(val entries: List<ReadingTraceBackupEntry>) : ReadingTraceBackupReadResult

    /**
     * ファイルごと受け付けられない。**部分的に読まない** —
     * 読み戻しは不可逆なので、中途半端に適用した状態を残さない（→ reading_trace_backup §11）。
     */
    data class Unusable(val reason: String) : ReadingTraceBackupReadResult
}

internal class ReadingTraceBackupTooLargeException(message: String) : IllegalArgumentException(message)

internal object ReadingTraceBackupJson {

    /**
     * 痕跡を1ファイルへ束ねる。
     *
     * **`documentId` はここでキーごと落とす。** 端末／権限グラントごとに変わる引き当てキャッシュで、
     * 別端末はもちろん同じ端末の再インストール後でも無効になる値である。しかも
     * SAF の documentId には端末内のパスが入るため、**平文で外へ出す退避ファイルへ
     * 載せる理由が1つも無い**。読み戻し側の規則（端末側を保つ）にも自動的に沿う。
     *
     * 上限を超えたら[ReadingTraceBackupTooLargeException]。**書きかけを渡さない** —
     * 呼び出し側は返ってきたバイト列をそのまま1回で書き出すので、
     * ここで投げれば保存先には何も書かれない。
     */
    fun encode(traces: List<ReadingTrace>, exportedAtEpochMillis: Long): ByteArray {
        if (traces.size > ReadingTraceBackupLimits.MAX_ENTRIES) {
            throw ReadingTraceBackupTooLargeException(
                "痕跡が多すぎます（${traces.size}件／上限 ${ReadingTraceBackupLimits.MAX_ENTRIES}件）。"
            )
        }
        val entries = JSONArray()
        traces.forEach { trace ->
            val encoded = ReadingTraceJson.encode(trace.copy(documentId = null))
            // **値を null にするだけでは足りない。** 通常の痕跡形式は欄を必ず書くので
            // `"documentId": null` というキーが残り、「退避ファイルのどこにも無い」という
            // 可搬形式の契約（→ §5・実機ケース BACKUP-03）を満たさない。
            // 値と形の両方で落とす — 片方が消えても端末内のパスは外へ出ない。
            entries.put(
                JSONObject(String(encoded, Charsets.UTF_8))
                    .apply { remove(ReadingTraceJson.KEY_DOCUMENT_ID) }
            )
        }
        val root = JSONObject()
            .put(KEY_FORMAT, ReadingTraceBackupLimits.FORMAT_ID)
            .put(KEY_BACKUP_VERSION, ReadingTraceBackupLimits.FORMAT_VERSION)
            .put(KEY_EXPORTED_AT, exportedAtEpochMillis)
            // 中身の版は情報として添えるだけで、解釈には使わない（1件ずつが自分の版を持つ）。
            .put(KEY_TRACE_SCHEMA_VERSION, READING_TRACE_SCHEMA_VERSION)
            .put(KEY_TRACE_COUNT, traces.size)
            .put(KEY_TRACES, entries)
        // 人が読める体裁で書く。中身の痕跡ファイルと同じ方針（ユーザーが確認できること）。
        val bytes = root.toString(2).toByteArray(Charsets.UTF_8)
        if (bytes.size > ReadingTraceBackupLimits.MAX_FILE_BYTES) {
            throw ReadingTraceBackupTooLargeException(
                "退避ファイルが上限（${ReadingTraceBackupLimits.MAX_FILE_BYTES / (1024 * 1024)}MB）を超えます。"
            )
        }
        return bytes
    }

    /**
     * 退避ファイルを解く。
     *
     * **新しすぎる版は中身を1件も読まない。** 知らない束ね方のファイルから読めた分だけ
     * 適用すると、不可逆な操作を中途半端に残すことになる。
     */
    fun decode(bytes: ByteArray): ReadingTraceBackupReadResult {
        if (bytes.isEmpty()) return ReadingTraceBackupReadResult.Unusable("退避ファイルが空です。")
        val root = try {
            JSONObject(decodeUtf8Strict(bytes))
        } catch (error: Exception) {
            // **同じ「選んだファイルが違う」を、入力形式で二通りに言わない。**
            // JSONとして解けない（`.md` など）のと、解けたが別形式なのは、
            // 内部の失敗経路が違うだけで利用者から見れば同じ1つの事象である。
            // 壊れた退避ファイルと非退避ファイルは原理的に区別できないので、
            // 区別しているふりをした文言を出さない（→ 実機ケース BACKUP-13）。
            return ReadingTraceBackupReadResult.Unusable(NOT_A_BACKUP)
        }
        if (root.optString(KEY_FORMAT) != ReadingTraceBackupLimits.FORMAT_ID) {
            return ReadingTraceBackupReadResult.Unusable(NOT_A_BACKUP)
        }
        val version = root.optInt(KEY_BACKUP_VERSION, 0)
        if (version < 1) {
            return ReadingTraceBackupReadResult.Unusable("退避ファイルの版を読み取れません。")
        }
        if (version > ReadingTraceBackupLimits.FORMAT_VERSION) {
            return ReadingTraceBackupReadResult.Unusable(
                "この退避ファイルは新しい版（v$version）で作られています。" +
                    "アプリを更新してから読み戻してください。"
            )
        }
        val array = root.optJSONArray(KEY_TRACES)
            ?: return ReadingTraceBackupReadResult.Unusable("退避ファイルに痕跡が入っていません。")
        if (array.length() > ReadingTraceBackupLimits.MAX_ENTRIES) {
            return ReadingTraceBackupReadResult.Unusable(
                "痕跡が多すぎます（${array.length()}件／上限 ${ReadingTraceBackupLimits.MAX_ENTRIES}件）。"
            )
        }
        val entries = (0 until array.length()).map { index ->
            val item = array.optJSONObject(index)
                ?: return@map ReadingTraceBackupEntry.Corrupt("痕跡の形式が不正です。")
            when (val result = ReadingTraceJson.decode(item.toString().toByteArray(Charsets.UTF_8))) {
                is ReadingTraceReadResult.Valid -> ReadingTraceBackupEntry.Valid(result.trace)
                is ReadingTraceReadResult.Corrupt -> ReadingTraceBackupEntry.Corrupt(result.reason)
                ReadingTraceReadResult.None -> ReadingTraceBackupEntry.Corrupt("痕跡が空です。")
            }
        }
        return ReadingTraceBackupReadResult.Valid(entries)
    }

    /**
     * 非退避ファイルの拒否文言。**空・上限超過・将来版とは分けたまま、ここだけ1本にする** —
     * それらは「このファイルではあるが受け付けられない」で、次にやることが違う。
     */
    private const val NOT_A_BACKUP = "このファイルは読書痕跡の退避ファイルではありません。"

    private const val KEY_FORMAT = "format"
    private const val KEY_BACKUP_VERSION = "backupVersion"
    private const val KEY_EXPORTED_AT = "exportedAt"
    private const val KEY_TRACE_SCHEMA_VERSION = "traceSchemaVersion"
    private const val KEY_TRACE_COUNT = "traceCount"
    private const val KEY_TRACES = "traces"
}
