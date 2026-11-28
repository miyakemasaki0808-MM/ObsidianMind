package com.example.newproject.ui

/*
 * オプションの行の副題（→ docs/dev/system/ui_copy.md §2 の1）。**スイッチと選択が見せている状態を言い直さない。**
 * 副題は、何が変わるかか、今は効かないことだけを言う。言うことが無ければ null（副題を出さない）。
 */

/** Vault の行。選んであれば副題を出さない。 */
internal fun vaultOptionSubtitle(vaultSelected: Boolean): String? = if (vaultSelected) null else "まだ選んでいません"

/**
 * ノートの紙色の行。オンでもオフでも、何が変わるかを同じ文で言う。ダークの間は効かないことを言い、トグルは隠さない。
 * 「更新」と言い切る — 判定は最終更新の日時で閲覧の履歴ではないので、「開いていない」と書くと読書痕跡を見る機能に読める。
 */
internal fun notePaperOptionSubtitle(darkTheme: Boolean): String =
    if (darkTheme) DARK_THEME_NOT_APPLIED else "更新が古いノートほど生成りに"

/** 読む画面の背景を鮮やかにする行。題が変わることを言っているので、ライトでは副題を出さない。 */
internal fun vividGroundOptionSubtitle(darkTheme: Boolean): String? = if (darkTheme) DARK_THEME_NOT_APPLIED else null

internal const val DARK_THEME_NOT_APPLIED = "ダークモードの間は効きません"
