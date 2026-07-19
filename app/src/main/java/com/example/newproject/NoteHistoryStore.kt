package com.example.newproject

import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HistoryEntry(val title: String, val uri: Uri)

/**
 * 当日分のみの閲覧履歴。「さっき読んだノートを見返したい」瞬間に応えるための
 * 短期記憶なので、日付が変わったら破棄し翌日へは持ち越さない。
 */
class NoteHistoryStore(private val prefs: SharedPreferences) {

    fun load(): List<HistoryEntry> {
        if (prefs.getString(KEY_DATE, null) != today()) return emptyList()
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                HistoryEntry(
                    title = obj.getString("title"),
                    uri = Uri.parse(obj.getString("uri"))
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 先頭に追加して保存する。同じノートは重複させず先頭へ移動する。 */
    fun record(title: String, uri: Uri): List<HistoryEntry> {
        val updated = (listOf(HistoryEntry(title, uri)) + load().filter { it.uri != uri })
            .take(MAX_ENTRIES)
        val array = JSONArray()
        updated.forEach { entry ->
            array.put(
                JSONObject()
                    .put("title", entry.title)
                    .put("uri", entry.uri.toString())
            )
        }
        prefs.edit()
            .putString(KEY_DATE, today())
            .putString(KEY_ENTRIES, array.toString())
            .apply()
        return updated
    }

    /** Vault切替時に呼ぶ。旧VaultのURIは新Vaultでは開けないため全破棄する。 */
    fun clear() {
        prefs.edit().remove(KEY_DATE).remove(KEY_ENTRIES).apply()
    }

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    companion object {
        private const val MAX_ENTRIES = 10
        private const val KEY_DATE = "history_date"
        private const val KEY_ENTRIES = "history_entries"
    }
}
