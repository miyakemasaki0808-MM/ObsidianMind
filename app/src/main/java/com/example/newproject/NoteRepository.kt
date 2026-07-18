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

    // SAFの子要素1件。カーソル列の詰め替え先として共通ヘルパが返す。
    private data class ChildDoc(val documentId: String, val name: String, val isDirectory: Boolean)

    // 指定ドキュメント直下の子を列挙する（散在していたカーソルループの共通化）
    private fun queryChildren(
        contentResolver: ContentResolver,
        vaultUri: Uri,
        documentId: String
    ): List<ChildDoc> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(vaultUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        val result = mutableListOf<ChildDoc>()
        contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    ChildDoc(
                        documentId = cursor.getString(0),
                        name = cursor.getString(1),
                        isDirectory = cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                    )
                )
            }
        }
        return result
    }

    // startId 配下を再帰BFSで走査して .md を集める。
    // excludeFolderNames に一致するフォルダは潜らない。
    private fun collectNotesRecursive(
        contentResolver: ContentResolver,
        vaultUri: Uri,
        startId: String,
        excludeFolderNames: Set<String> = emptySet()
    ): List<NoteFile> {
        val result = mutableListOf<NoteFile>()
        val queue = ArrayDeque<String>()
        queue.add(startId)
        while (queue.isNotEmpty()) {
            val documentId = queue.removeFirst()
            for (child in queryChildren(contentResolver, vaultUri, documentId)) {
                when {
                    child.isDirectory ->
                        if (child.name !in excludeFolderNames) queue.add(child.documentId)
                    isMarkdownFile(child.name) ->
                        result.add(child.toNoteFile(vaultUri))
                }
            }
        }
        return result
    }

    private fun ChildDoc.toNoteFile(vaultUri: Uri): NoteFile =
        NoteFile(name, DocumentsContract.buildDocumentUriUsingTree(vaultUri, documentId))

    // Vault全体のノートを収集する（ランダム表示・関連ノート候補用）。
    // AI生成の補記メモは復習対象にしない方針のため _AI補記 フォルダを除外する。
    // ※さがすタブ（collectNotesInScope）は仕様どおり除外しない。
    suspend fun collectNotes(contentResolver: ContentResolver, vaultUri: Uri): List<NoteFile> =
        withContext(Dispatchers.IO) {
            collectNotesRecursive(
                contentResolver = contentResolver,
                vaultUri = vaultUri,
                startId = DocumentsContract.getTreeDocumentId(vaultUri),
                excludeFolderNames = setOf(ANNOTATION_FOLDER_NAME)
            )
        }

    // Vault 第一階層のフォルダのみ列挙する（ドリルダウンなし・名前昇順）。
    suspend fun listTopLevelFolders(contentResolver: ContentResolver, vaultUri: Uri): List<NoteFolder> =
        withContext(Dispatchers.IO) {
            queryChildren(contentResolver, vaultUri, DocumentsContract.getTreeDocumentId(vaultUri))
                .filter { it.isDirectory }
                .map { NoteFolder(it.name, it.documentId) }
                .sortedBy { it.name }
        }

    // 検索スコープ配下のノートを収集する。
    //   scope=null      → Vault ルート直下の .md のみ（非再帰）
    //   scope=NoteFolder → そのフォルダ配下を再帰的に収集（サブフォルダのノートも含む）
    // さがすタブは _AI補記 も選択対象に含める仕様のため、ここでは除外しない。
    suspend fun collectNotesInScope(
        contentResolver: ContentResolver,
        vaultUri: Uri,
        scope: NoteFolder?
    ): List<NoteFile> = withContext(Dispatchers.IO) {
        if (scope == null) {
            queryChildren(contentResolver, vaultUri, DocumentsContract.getTreeDocumentId(vaultUri))
                .filter { !it.isDirectory && isMarkdownFile(it.name) }
                .map { it.toNoteFile(vaultUri) }
        } else {
            collectNotesRecursive(contentResolver, vaultUri, startId = scope.documentId)
        }
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
        val fileName = "${sanitizedTitle}${ANNOTATION_FILE_MARKER}$timestamp.md"
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
            // 作成日時の新しい順に並べる。ファイル名は "{タイトル}__補記_{yyyyMMdd_HHmm}.md"
            // 形式のため、名前全体でなくタイムスタンプ部をソートキーにする
            // （名前降順だとタイトルの辞書順が支配して日付順にならない）
            queryChildren(contentResolver, vaultUri, folderId)
                .filter { !it.isDirectory && isMarkdownFile(it.name) }
                .map { it.toNoteFile(vaultUri) }
                .sortedByDescending { it.name.substringAfterLast(ANNOTATION_FILE_MARKER, "") }
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

    // _AI補記 フォルダはルート直下に作成する仕様（createAnnotationFolder 参照）のため、
    // ルート直下のみ探索する。以前はVault全体をBFSしており保存・一覧のたびに重かった。
    private fun findAnnotationFolder(contentResolver: ContentResolver, vaultUri: Uri): Uri? =
        queryChildren(contentResolver, vaultUri, DocumentsContract.getTreeDocumentId(vaultUri))
            .firstOrNull { it.isDirectory && it.name == ANNOTATION_FOLDER_NAME }
            ?.let { DocumentsContract.buildDocumentUriUsingTree(vaultUri, it.documentId) }

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
        // 補記メモのファイル名区切り: "{タイトル}__補記_{yyyyMMdd_HHmm}.md"
        private const val ANNOTATION_FILE_MARKER = "__補記_"
        private val WIKILINK_REGEX = Regex("\\[\\[([^\\]]+)]]")
    }
}
