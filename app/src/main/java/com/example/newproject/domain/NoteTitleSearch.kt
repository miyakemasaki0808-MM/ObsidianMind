package com.example.newproject.domain

import com.example.newproject.model.NoteFile
import com.example.newproject.model.NoteFolder
import com.example.newproject.model.RelatedNote
import com.example.newproject.model.state.TitleMatch

/**
 * Explore のタイトルのモードの照合（→ features/ai_picker.md 判断7）。ファイル名だけを見る。
 *
 * 完全一致 → 前方一致 → 部分一致の順で、同じ段の中はタイトルの順に並べる。件数は絞らない。
 * 大文字小文字と空白の違いは無視する。拡張子は照らさない — `md` と打って全ノートが出ないように。
 */
internal fun matchNotesByTitle(query: String, notes: List<NoteFile>): List<TitleMatch> {
    val key = titleKey(query)
    if (key.isEmpty()) return emptyList()
    val matched = notes
        .mapNotNull { note ->
            val title = titleKey(note.name)
            val rank = when {
                title == key -> 0
                title.startsWith(key) -> 1
                title.contains(key) -> 2
                else -> return@mapNotNull null
            }
            RankedNote(rank, title, note)
        }
        .sortedWith(compareBy({ it.rank }, { it.title }, { it.note.vaultRelativePath }))
    val sameName = matched.groupingBy { it.note.name }.eachCount()
    return matched.map { (_, _, note) ->
        TitleMatch(
            note = note.toRelatedNote(),
            folderPath = note.vaultRelativePath.substringBeforeLast('/', "")
                .takeIf { sameName.getValue(note.name) > 1 }
        )
    }
}

/** [folder] の中のノート（奥まで）。名前の後ろの `/` まで見るので、`ideas2` を `ideas` に含めない。 */
internal fun notesInFolder(notes: List<NoteFile>, folder: NoteFolder): List<NoteFile> =
    notes.filter { it.vaultRelativePath.startsWith("${folder.name}/") }

internal fun NoteFile.toRelatedNote(): RelatedNote =
    RelatedNote(title = name, ref = ref, isWikilinked = false, lastModified = lastModified)

private data class RankedNote(val rank: Int, val title: String, val note: NoteFile)

private fun titleKey(name: String): String =
    (if (name.endsWith(".md", ignoreCase = true)) name.dropLast(3) else name).normalizedForMatching()
