package com.example.newproject

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class NoteFile(val name: String, val uri: Uri)

// Vault 直下のフォルダ。documentId は配下をたどる起点に使う。
data class NoteFolder(val name: String, val documentId: String)

data class NoteMeta(
    val tags: List<String> = emptyList(),
    val aliases: List<String> = emptyList(),
    val wikilinkTitles: Set<String> = emptySet()
)

internal fun isMarkdownFile(name: String?): Boolean =
    name?.lowercase()?.endsWith(".md") == true

internal fun sanitizeAnnotationFileTitle(title: String): String {
    val sanitized = title
        .replace(Regex("[/\\\\:*?\"<>|\\n\\r\\t]+"), "_")
        .replace(Regex("_+"), "_")
        .trim('_', ' ')
    return sanitized.ifBlank { "untitled" }
}

class NoteRepository {

    suspend fun collectNotes(contentResolver: ContentResolver, vaultUri: Uri): List<NoteFile> =
        withContext(Dispatchers.IO) {
            val result = mutableListOf<NoteFile>()
            val queue = ArrayDeque<String>()
            queue.add(DocumentsContract.getTreeDocumentId(vaultUri))

            while (queue.isNotEmpty()) {
                val documentId = queue.removeFirst()
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(vaultUri, documentId)
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
                        val childUri = DocumentsContract.buildDocumentUriUsingTree(vaultUri, childId)

                        when {
                            mimeType == DocumentsContract.Document.MIME_TYPE_DIR -> queue.add(childId)
                            isMarkdownFile(name) -> result.add(NoteFile(name, childUri))
                        }
                    }
                }
            }

            result
        }

    // Vault 第一階層のフォルダのみ列挙する（ドリルダウンなし・名前昇順）。
    suspend fun listTopLevelFolders(contentResolver: ContentResolver, vaultUri: Uri): List<NoteFolder> =
        withContext(Dispatchers.IO) {
            val rootId = DocumentsContract.getTreeDocumentId(vaultUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(vaultUri, rootId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )

            val result = mutableListOf<NoteFolder>()
            contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val childId = cursor.getString(0)
                    val name = cursor.getString(1)
                    val mimeType = cursor.getString(2)
                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        result.add(NoteFolder(name, childId))
                    }
                }
            }
            result.sortedBy { it.name }
        }

    // 検索スコープ配下のノートを収集する。
    //   scope=null      → Vault ルート直下の .md のみ（非再帰）
    //   scope=NoteFolder → そのフォルダ配下を再帰的に収集（サブフォルダのノートも含む）
    suspend fun collectNotesInScope(
        contentResolver: ContentResolver,
        vaultUri: Uri,
        scope: NoteFolder?
    ): List<NoteFile> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val result = mutableListOf<NoteFile>()

        if (scope == null) {
            // ルート直下のみ（サブフォルダには潜らない）
            val rootId = DocumentsContract.getTreeDocumentId(vaultUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(vaultUri, rootId)
            contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val childId = cursor.getString(0)
                    val name = cursor.getString(1)
                    val mimeType = cursor.getString(2)
                    if (mimeType != DocumentsContract.Document.MIME_TYPE_DIR && isMarkdownFile(name)) {
                        result.add(NoteFile(name, DocumentsContract.buildDocumentUriUsingTree(vaultUri, childId)))
                    }
                }
            }
        } else {
            // フォルダ配下を再帰BFS（collectNotes と同じ走査）
            val queue = ArrayDeque<String>()
            queue.add(scope.documentId)
            while (queue.isNotEmpty()) {
                val documentId = queue.removeFirst()
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(vaultUri, documentId)
                contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val childId = cursor.getString(0)
                        val name = cursor.getString(1)
                        val mimeType = cursor.getString(2)
                        when {
                            mimeType == DocumentsContract.Document.MIME_TYPE_DIR -> queue.add(childId)
                            isMarkdownFile(name) ->
                                result.add(NoteFile(name, DocumentsContract.buildDocumentUriUsingTree(vaultUri, childId)))
                        }
                    }
                }
            }
        }

        result
    }

    suspend fun readNoteContent(contentResolver: ContentResolver, uri: Uri): String =
        withContext(Dispatchers.IO) {
            contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readText()
            } ?: ""
        }

    suspend fun createAnnotationFile(
        contentResolver: ContentResolver,
        vaultUri: Uri,
        sanitizedTitle: String,
        timestamp: String,
        content: String
    ): Uri = withContext(Dispatchers.IO) {
        val folderUri = findAnnotationFolder(contentResolver, vaultUri)
            ?: createAnnotationFolder(contentResolver, vaultUri)
        val fileName = "${sanitizedTitle}__補記_$timestamp.md"
        val fileUri = DocumentsContract.createDocument(
            contentResolver,
            folderUri,
            "text/markdown",
            fileName
        ) ?: error("補記メモファイルを作成できませんでした。")

        contentResolver.openOutputStream(fileUri)?.use { stream ->
            stream.write(content.toByteArray(Charsets.UTF_8))
        } ?: error("補記メモファイルを書き込めませんでした。")

        fileUri
    }

    // _AI補記/ フォルダ内の補記メモファイルを列挙する（1階層のみ）
    suspend fun listAnnotationFiles(contentResolver: ContentResolver, vaultUri: Uri): List<NoteFile> =
        withContext(Dispatchers.IO) {
            val folderUri = findAnnotationFolder(contentResolver, vaultUri) ?: return@withContext emptyList()
            val folderId = DocumentsContract.getDocumentId(folderUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(vaultUri, folderId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            )

            val result = mutableListOf<NoteFile>()
            contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val childId = cursor.getString(0)
                    val name = cursor.getString(1)
                    if (isMarkdownFile(name)) {
                        val childUri = DocumentsContract.buildDocumentUriUsingTree(vaultUri, childId)
                        result.add(NoteFile(name, childUri))
                    }
                }
            }
            result.sortedByDescending { it.name }
        }

    // 単一ドキュメントを削除する。成功時 true。
    suspend fun deleteDocument(contentResolver: ContentResolver, uri: Uri): Boolean =
        withContext(Dispatchers.IO) {
            try {
                DocumentsContract.deleteDocument(contentResolver, uri)
            } catch (e: Exception) {
                false
            }
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
            .map { it.groupValues[1].toObsidianNoteTitle() }
            .filter { it.isNotBlank() }
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

    private fun findAnnotationFolder(contentResolver: ContentResolver, vaultUri: Uri): Uri? {
        val queue = ArrayDeque<String>()
        queue.add(DocumentsContract.getTreeDocumentId(vaultUri))

        while (queue.isNotEmpty()) {
            val documentId = queue.removeFirst()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(vaultUri, documentId)
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
                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (name == ANNOTATION_FOLDER_NAME) {
                            return DocumentsContract.buildDocumentUriUsingTree(vaultUri, childId)
                        }
                        queue.add(childId)
                    }
                }
            }
        }
        return null
    }

    private fun createAnnotationFolder(contentResolver: ContentResolver, vaultUri: Uri): Uri {
        val rootDocumentId = DocumentsContract.getTreeDocumentId(vaultUri)
        val rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(vaultUri, rootDocumentId)
        return DocumentsContract.createDocument(
            contentResolver,
            rootDocumentUri,
            DocumentsContract.Document.MIME_TYPE_DIR,
            ANNOTATION_FOLDER_NAME
        ) ?: error("補記メモフォルダを作成できませんでした。")
    }

    companion object {
        private const val ANNOTATION_FOLDER_NAME = "_AI補記"
        private val WIKILINK_REGEX = Regex("\\[\\[([^\\]]+)]]")
    }
}
