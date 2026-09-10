package com.example.newproject.data

import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.newproject.model.NoteField
import com.example.newproject.model.NoteFieldClassification

/**
 * 分野の確定を端末に残す（→ `docs/dev/features/note_field_color.md` 判断3・判断10）。
 *
 * **保存はこの機能の前提条件である。** 保存しないと、同じノートを開き直すたびに Nano が走り、
 * 3本目の待ち行列が伸び続ける。
 *
 * **Vault には書かない。** 分野は再生成できる導出値なので、退避・復元の対象にもしない。
 */
interface NoteFieldStore {

    /** そのVaultの確定を全部読む。鍵は**相対パスのハッシュ**（`noteFieldPathKey`）。 */
    fun load(vaultKey: String): Map<String, NoteFieldClassification.Confirmed>

    /** 確定を1件書く。**暫定は渡さない** — 走査で作り直せるので永続しない（判断14）。 */
    fun save(vaultKey: String, pathKey: String, confirmed: NoteFieldClassification.Confirmed)

    /** そのVaultの確定を全部捨てる。**語彙やプロンプトを変えたとき**に使う。 */
    fun clear(vaultKey: String)
}

/**
 * `SharedPreferences` に1件1キーで持つ実装。
 *
 * **1件1キーにするのは、確定するたびに全件を書き直さないため。** ノートを開くたびに
 * 数百件のJSONを組み直すのは、この機能が全ノートで走ることと相性が悪い。
 *
 * **Vaultごとに名前空間を分ける**（→ 判断9）。分野IDはVault別語彙を足した時点で
 * Vaultローカルになるので、混ざらないようにしておく。
 */
class SharedNoteFieldStore(private val prefs: SharedPreferences) : NoteFieldStore {

    override fun load(vaultKey: String): Map<String, NoteFieldClassification.Confirmed> {
        val prefix = prefix(vaultKey)
        return prefs.all.asSequence()
            .filter { it.key.startsWith(prefix) }
            .mapNotNull { (key, value) ->
                val decoded = decode(value as? String ?: return@mapNotNull null)
                    ?: return@mapNotNull null
                key.removePrefix(prefix) to decoded
            }
            .toMap()
    }

    override fun save(vaultKey: String, pathKey: String, confirmed: NoteFieldClassification.Confirmed) {
        // **上限を超えたら新しい確定を永続しない。** その場の表示はメモリで効くので、
        // ユーザーには何も起きない — 失うのは「再起動後も色が残る」ことだけである。
        // 消して作り直す形にしないのは、境界で毎回全消しが起きるため。
        if (countFor(vaultKey) >= MAX_ENTRIES && !prefs.contains(prefix(vaultKey) + pathKey)) return
        prefs.edit { putString(prefix(vaultKey) + pathKey, encode(confirmed)) }
    }

    override fun clear(vaultKey: String) {
        val prefix = prefix(vaultKey)
        val stale = prefs.all.keys.filter { it.startsWith(prefix) }
        prefs.edit { stale.forEach(::remove) }
    }

    private fun countFor(vaultKey: String): Int =
        prefs.all.keys.count { it.startsWith(prefix(vaultKey)) }

    /** `分野の序数（無しは -1）|入力版`。**序数を使うのは短いから**で、名前を変えても壊れない。 */
    private fun encode(confirmed: NoteFieldClassification.Confirmed): String =
        "${confirmed.field?.ordinal ?: -1}|${confirmed.inputVersion}"

    private fun decode(raw: String): NoteFieldClassification.Confirmed? {
        val separator = raw.indexOf('|')
        if (separator <= 0) return null
        val ordinal = raw.substring(0, separator).toIntOrNull() ?: return null
        val inputVersion = raw.substring(separator + 1)
        if (inputVersion.isEmpty()) return null
        // **知らない序数は捨てる。** 語彙を減らした後に古い値が残っていても、
        // 存在しない分野として復元しない（次に開けば作り直せる）。
        val field = when {
            ordinal == NO_FIELD -> null
            ordinal in NoteField.entries.indices -> NoteField.entries[ordinal]
            else -> return null
        }
        return NoteFieldClassification.Confirmed(field, inputVersion)
    }

    private fun prefix(vaultKey: String) = "$KEY_PREFIX$vaultKey:"

    private companion object {
        const val KEY_PREFIX = "noteField:"
        const val NO_FIELD = -1

        /**
         * 1つのVaultで永続する上限。
         *
         * `SharedPreferences` は起動時に全体をメモリへ読むので、際限なく増やせない。
         * 1件あたり約100バイトなので、この上限で約400KB。
         * **実測して決めた値ではない** — 大きなVaultで体感が出たら測って決め直す。
         */
        const val MAX_ENTRIES = 4000
    }
}
