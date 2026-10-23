package com.example.newproject.data

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * 端末に残す設定値。**Vaultにも痕跡にも書かない小さな値だけ**を扱う。
 *
 * インターフェースを切っているのは差し替えのためで、実装を増やす予定は無い
 * （[SharedAppPreferences] が唯一の本番実装）。ここを分けておくと
 * [com.example.newproject.NoteViewModel] の依存生成を外へ出せる。
 */
interface AppPreferences {
    /** 表示テーマ。OS設定には追従しないので、この値が唯一の真実。 */
    var darkTheme: Boolean

    /**
     * ノートの放置期間に応じて本文の紙を生成り色へ寄せるか。
     * **既定オフ** — 本文が載る面の色なので、既存ユーザーの見た目を勝手に変えない
     * （[darkTheme] と同じ判断）。ダーク時はこの値によらず無効。
     */
    var notePaperAging: Boolean

    /** 選択中Vaultの永続化URI（文字列）。未選択なら null。 */
    var vaultUri: String?
}

class SharedAppPreferences(private val prefs: SharedPreferences) : AppPreferences {

    override var darkTheme: Boolean
        get() = prefs.getBoolean(KEY_DARK_THEME, false)
        set(value) {
            prefs.edit { putBoolean(KEY_DARK_THEME, value) }
        }

    override var notePaperAging: Boolean
        get() = prefs.getBoolean(KEY_NOTE_PAPER_AGING, false)
        set(value) {
            prefs.edit { putBoolean(KEY_NOTE_PAPER_AGING, value) }
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
        private const val KEY_NOTE_PAPER_AGING = "note_paper_aging"
    }
}
