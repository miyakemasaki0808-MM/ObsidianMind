package com.example.newproject.domain

import com.example.newproject.model.BesideNote
import com.example.newproject.model.ReadingTraceLimits
import com.example.newproject.model.truncateToUtf8Bytes

/**
 * 右で開いたノートを、並べた相手の候補にする（→ features/desk_memory.md §5・判断9）。保存できない相手は null。
 *
 * **相対パスは変えない。** 引けない・空・上限超え・制御文字を含むなら候補にしない — 変えた値は別のノートを指しうる。
 * タイトルは表示の記録なので、制御文字を落としてから上限で整える。空になればファイル名を使い、それも空なら候補にしない。
 *
 * v9 の検証（`validateReadingTrace`）は同じ条件の値を弾くので、**ここを通った値だけを訪問に載せる**。
 * 検証だけに任せると、相手の値のせいで同じ保存に載る訪問とメモまで書けなくなる。
 */
internal fun composeBesideNote(vaultRelativePath: String?, title: String?): BesideNote? {
    val path = vaultRelativePath?.takeIf { it.isStorableBesidePath() } ?: return null
    val name = composeBesideTitle(title) ?: composeBesideTitle(fileNameOf(path)) ?: return null
    return BesideNote(vaultRelativePath = path, title = name)
}

private fun String.isStorableBesidePath(): Boolean =
    isNotBlank() &&
        none(Char::isISOControl) &&
        toByteArray(Charsets.UTF_8).size <= ReadingTraceLimits.MAX_RELATIVE_PATH_BYTES

private fun composeBesideTitle(raw: String?): String? {
    val normalized = raw?.filterNot(Char::isISOControl)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return truncateToUtf8Bytes(normalized, ReadingTraceLimits.MAX_NOTE_TITLE_BYTES).takeIf { it.isNotBlank() }
}

private fun fileNameOf(path: String): String = path.substringAfterLast('/').removeSuffix(".md")
