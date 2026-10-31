package com.example.newproject.domain

import com.example.newproject.model.BesideNote
import com.example.newproject.model.ReadingTraceLimits
import com.example.newproject.model.RelatedNote
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

/**
 * 控えた相手を、今の Vault のノートとして引く（→ features/desk_memory.md §5「名前から並べて開く」）。押したときだけ呼ぶ。
 *
 * **相対パスの完全一致だけで引く。** 本文のリンクの解決（[resolveNoteLink]）は相対の起点の補完や同名への寄せを持つので使わない —
 * 保存した相手と違うノートを開きうる。大文字小文字を畳む本文のリンクのパス引きも使わない — 大文字小文字だけ違う2ノートが共存すると、
 * 片方が上書きされて、走査済みで完全一致するノートを見つけられない。改名・移動した相手は見つからない（辿り直さない）。索引がまだ無ければ待ってもらう。
 */
internal fun resolveBesideNote(beside: BesideNote, index: NoteLinkIndex): NoteLinkResolution {
    if (index.isEmpty) return NoteLinkResolution.NotReady
    val found = index.atExactPath(beside.vaultRelativePath) ?: return NoteLinkResolution.Missing(beside.title)
    return NoteLinkResolution.Open(
        RelatedNote(title = found.name, ref = found.ref, isWikilinked = false, lastModified = found.lastModified),
        heading = null
    )
}
