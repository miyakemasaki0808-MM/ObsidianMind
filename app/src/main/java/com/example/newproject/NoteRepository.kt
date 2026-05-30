package com.example.newproject

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class NoteFile(val name: String, val uri: Uri)

data class NoteMeta(
    val tags: List<String> = emptyList(),
    val aliases: List<String> = emptyList(),
    val wikilinkTitles: Set<String> = emptySet()
)

internal fun isMarkdownFile(name: String?): Boolean =
    name?.lowercase()?.endsWith(".md") == true

class NoteRepository {

    suspend fun collectNotes(contentResolver: ContentResolver, vaultUri: Uri): List<NoteFile> =
        withContext(Dispatchers.IO) {
            buildList {
                collectRecursive(contentResolver, vaultUri, DocumentsContract.getTreeDocumentId(vaultUri), this)
            }
        }

    suspend fun readNoteContent(contentResolver: ContentResolver, uri: Uri): String =
        withContext(Dispatchers.IO) {
            contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readText()
            } ?: ""
        }

    // frontmatter（tags/aliases）と [[wikilink]] を抽出
    fun parseMeta(content: String): NoteMeta {
        val lines = content.lines()
        var tags = emptyList<String>()
        var aliases = emptyList<String>()

        // YAML frontmatter（--- で囲まれた先頭ブロック）
        if (lines.firstOrNull()?.trim() == "---") {
            val endIndex = lines.drop(1).indexOfFirst { it.trim() == "---" }
            if (endIndex >= 0) {
                val frontmatter = lines.drop(1).take(endIndex)
                tags = parseFrontmatterList(frontmatter, "tags")
                aliases = parseFrontmatterList(frontmatter, "aliases")
            }
        }

        val wikilinkTitles = WIKILINK_REGEX.findAll(content)
            .map { it.groupValues[1].split("|").first().trim() }
            .toSet()

        return NoteMeta(tags = tags, aliases = aliases, wikilinkTitles = wikilinkTitles)
    }

    private fun parseFrontmatterList(lines: List<String>, key: String): List<String> {
        val keyLine = lines.indexOfFirst { it.trimStart().startsWith("$key:") }
        if (keyLine < 0) return emptyList()

        val inline = lines[keyLine].substringAfter("$key:").trim()
        // インライン形式: tags: [a, b, c]
        if (inline.startsWith("[")) {
            return inline.removeSurrounding("[", "]").split(",").map { it.trim() }.filter { it.isNotBlank() }
        }
        // ブロック形式:
        // tags:
        //   - a
        //   - b
        return lines.drop(keyLine + 1)
            .takeWhile { it.startsWith(" ") || it.startsWith("\t") }
            .map { it.trim().removePrefix("-").trim() }
            .filter { it.isNotBlank() }
    }

    private fun collectRecursive(
        contentResolver: ContentResolver,
        treeUri: Uri,
        documentId: String,
        result: MutableList<NoteFile>
    ) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )

        contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val childId = cursor.getString(0)
                val name = cursor.getString(1)
                val mimeType = cursor.getString(2)
                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)

                when {
                    mimeType == DocumentsContract.Document.MIME_TYPE_DIR ->
                        collectRecursive(contentResolver, treeUri, childId, result)
                    isMarkdownFile(name) ->
                        result.add(NoteFile(name, childUri))
                }
            }
        }
    }

    companion object {
        private val WIKILINK_REGEX = Regex("\\[\\[([^\\]]+)]]")
    }
}
