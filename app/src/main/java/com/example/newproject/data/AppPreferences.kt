package com.example.newproject.data

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * 端末に残す設定値。**Vaultにも痕跡にも書かない小さな2つだけ**を扱う。
 *
 * インターフェースを切っているのは差し替えのためで、実装を増やす予定は無い
 * （[SharedAppPreferences] が唯一の本番実装）。ここを分けておくと
 * [com.example.newproject.NoteViewModel] の依存生成を外へ出せる。
 */
interface AppPreferences {
    /** 表示テーマ。OS設定には追従しないので、この値が唯一の真実。 */
    var darkTheme: Boolean

    /** 選択中Vaultの永続化URI（文字列）。未選択なら null。 */
    var vaultUri: String?
}

class SharedAppPreferences(private val prefs: SharedPreferences) : AppPreferences {

    override var darkTheme: Boolean
        get() = prefs.getBoolean(KEY_DARK_THEME, false)
        set(value) {
            prefs.edit { putBoolean(KEY_DARK_THEME, value) }
        }

    override var vaultUri: String?
        get() = prefs.getString(KEY_VAULT_URI, null)
        set(value) {
            prefs.edit { putString(KEY_VAULT_URI, value) }
        }

    companion object {
        const val PREFS_NAME = "random_note_prefs"
        private const val KEY_VAULT_URI = "vault_uri"
        private const val KEY_DARK_THEME = "dark_theme"
    }
}
