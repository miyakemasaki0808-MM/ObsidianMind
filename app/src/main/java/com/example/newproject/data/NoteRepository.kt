package com.example.newproject.data

import com.example.newproject.model.ReadingTrace
import com.example.newproject.model.READING_TRACE_FOLDER_NAME
import com.example.newproject.domain.toObsidianNoteTitle
import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.example.newproject.domain.DistillLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// lastModified はエポックミリ秒。SAFプロバイダが値を返さない場合は null。
//
// vaultRelativePath は Vault ルートからの相対パス（例 "ideas/habit.md"）。
// SAF の documentId は端末／権限グラントごとに異なり、同期した別端末では同じファイルでも
// 別IDになる＝可搬キーにならない。そのため ReadingTrace のサイドカー引き当てには
// 同期をまたいで安定するこの相対パスを使う。
// 再帰走査でのみ組み立てるので、非再帰の列挙（_AI補記 一覧）では既定の空文字が入る。
data class NoteFile(
    val name: String,
    val uri: Uri,
    val lastModified: Long? = null,
    val vaultRelativePath: String = ""
)

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

    // カーソルループと ChildDoc は SafDocuments.kt / VaultPathTraversal.kt へ移した
    // （ReadingTrace のサイドカー保存と共有するため）。
    private fun queryChildren(
        contentResolver: ContentResolver,
        vaultUri: Uri,
        documentId: String
    ): List<ChildDoc> = querySafChildren(contentResolver, vaultUri, documentId)

    // startId 配下を再帰BFSで走査して .md を集める。
    // 走査と相対パス連結の本体は traverseMarkdownPaths（純関数）に置き、
    // ここではカーソル読み出しと Uri 構築だけを担う。
    private fun collectNotesRecursive(
        contentResolver: ContentResolver,
        vaultUri: Uri,
        startId: String,
        startPath: String = "",
        excludeFolderNames: Set<String> = emptySet()
    ): List<NoteFile> =
        traverseMarkdownPaths(
            startId = startId,
            startPath = startPath,
            excludeFolderNames = excludeFolderNames
        ) { documentId -> queryChildren(contentResolver, vaultUri, documentId) }
            .map { entry ->
                NoteFile(
                    name = entry.name,
                    uri = DocumentsContract.buildDocumentUriUsingTree(vaultUri, entry.documentId),
                    lastModified = entry.lastModified,
                    vaultRelativePath = entry.vaultRelativePath
                )
            }

    private fun ChildDoc.toNoteFile(vaultUri: Uri): NoteFile =
        NoteFile(name, DocumentsContract.buildDocumentUriUsingTree(vaultUri, documentId), lastModified)

    // Vault全体のノートを収集する（ランダム表示・関連ノート候補用）。
    // AI生成の補記メモは復習対象にしない方針のため _AI補記 フォルダを除外する。
    // ※さがすタブ（collectNotesInScope）は _AI補記 だけは仕様どおり除外しない。
    suspend fun collectNotes(contentResolver: ContentResolver, vaultUri: Uri): List<NoteFile> =
        withContext(Dispatchers.IO) {
            collectNotesRecursive(
                contentResolver = contentResolver,
                vaultUri = vaultUri,
                startId = DocumentsContract.getTreeDocumentId(vaultUri),
                excludeFolderNames = setOf(ANNOTATION_FOLDER_NAME, READING_TRACE_FOLDER_NAME)
            )
        }

    // Vault 第一階層のフォルダのみ列挙する（ドリルダウンなし・名前昇順）。
    // _ReadingTraces はユーザーのノートを含まない機能の内部データなので候補に出さない。
    suspend fun listTopLevelFolders(contentResolver: ContentResolver, vaultUri: Uri): List<NoteFolder> =
        withContext(Dispatchers.IO) {
            queryChildren(contentResolver, vaultUri, DocumentsContract.getTreeDocumentId(vaultUri))
                .filter { it.isDirectory && it.name != READING_TRACE_FOLDER_NAME }
                .map { NoteFolder(it.name, it.documentId) }
                .sortedBy { it.name }
        }

    // 検索スコープ配下のノートを収集する。
    //   scope=null      → Vault ルート直下の .md のみ（非再帰）
    //   scope=NoteFolder → そのフォルダ配下を再帰的に収集（サブフォルダのノートも含む）
    // さがすタブは _AI補記 も選択対象に含める仕様のため、そちらは除外しない。
    // 一方 _ReadingTraces は機能の内部データ（.json）でユーザーが読むノートではないため除外する。
    suspend fun collectNotesInScope(
        contentResolver: ContentResolver,
        vaultUri: Uri,
        scope: NoteFolder?
    ): List<NoteFile> = withContext(Dispatchers.IO) {
        if (scope == null) {
            queryChildren(contentResolver, vaultUri, DocumentsContract.getTreeDocumentId(vaultUri))
                .filter { !it.isDirectory && isMarkdownFile(it.name) }
                // ルート直下なので相対パスはファイル名そのもの。
                .map { it.toNoteFile(vaultUri).copy(vaultRelativePath = it.name) }
        } else {
            collectNotesRecursive(
                contentResolver = contentResolver,
                vaultUri = vaultUri,
                startId = scope.documentId,
                startPath = scope.name,
                excludeFolderNames = setOf(READING_TRACE_FOLDER_NAME)
            )
        }
    }

    suspend fun readNoteContent(contentResolver: ContentResolver, uri: Uri): String =
        withContext(Dispatchers.IO) {
            contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readText()
            } ?: ""
        }

    /** 蒸留用。競合判定に使う生バイト列を保持し、UTF-8を置換なしで検証する。 */
    internal suspend fun readNoteSnapshot(
        contentResolver: ContentResolver,
        uri: Uri,
        maximumBytes: Int = DistillLimits.MAX_FILE_BYTES
    ): NoteSnapshot = withContext(Dispatchers.IO) {
        val bytes = contentResolver.openInputStream(uri)?.use { stream ->
            readBoundedBytes(stream, maximumBytes)
        } ?: error("ノートを開けませんでした。")
        NoteSnapshot(
            uri = uri,
            bytes = bytes,
            content = decodeUtf8Strict(bytes),
            hash = sha256Hex(bytes)
        )
    }

    internal suspend fun writeDocumentBytes(
        contentResolver: ContentResolver,
        uri: Uri,
        bytes: ByteArray
    ) = withContext(Dispatchers.IO) {
        contentResolver.openOutputStream(uri, "wt")?.use { output ->
            output.write(bytes)
            output.flush()
        } ?: error("書き出し先を開けませんでした。")
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

    private fun findAnnotationFolder(contentResolver: ContentResolver, vaultUri: Uri): Uri? =
        findRootChildFolder(contentResolver, vaultUri, ANNOTATION_FOLDER_NAME)

    private fun createAnnotationFolder(contentResolver: ContentResolver, vaultUri: Uri): Uri =
        createRootChildFolder(contentResolver, vaultUri, ANNOTATION_FOLDER_NAME)
            ?: error("補記メモフォルダを作成できませんでした。")

    companion object {
        private const val ANNOTATION_FOLDER_NAME = "_AI補記"
        // 補記メモのファイル名区切り: "{タイトル}__補記_{yyyyMMdd_HHmm}.md"
        private const val ANNOTATION_FILE_MARKER = "__補記_"
        private val WIKILINK_REGEX = Regex("\\[\\[([^\\]]+)]]")
    }
}
