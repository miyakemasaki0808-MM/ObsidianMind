package com.example.newproject.data

import com.example.newproject.model.ReadingTrace
import com.example.newproject.model.READING_TRACE_FOLDER_NAME
import com.example.newproject.model.DistillLimits
import com.example.newproject.model.NoteFile
import com.example.newproject.model.NoteFolder
import com.example.newproject.model.NoteMeta
import com.example.newproject.domain.toObsidianNoteTitle
import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 上限付きで読んだ本文。[isTruncated] が真なら、ノートにはまだ続きがある。 */
data class BoundedText(val text: String, val isTruncated: Boolean)

/**
 * 保存できた補記メモ。[displayName] は予測値ではなく**保存後のメタデータから取った実名**。
 * 同じノートを同じ分に再生成するとプロバイダがファイル名を変えることがあるため。
 */
data class SavedAnnotation(val uri: Uri, val displayName: String)

/** [AnnotationDocumentGateway] のSAF実装。参照は `Uri` の文字列表現をそのまま使う。 */
private class SafAnnotationDocumentGateway(
    private val contentResolver: ContentResolver,
    private val folderUri: Uri
) : AnnotationDocumentGateway {

    override fun createFile(fileName: String): String? =
        DocumentsContract.createDocument(contentResolver, folderUri, "text/markdown", fileName)
            ?.toString()

    override fun write(reference: String, bytes: ByteArray) {
        contentResolver.openOutputStream(reference.toUri(), "wt")?.use { stream ->
            stream.write(bytes)
            stream.flush()
        } ?: error("補記メモファイルを書き込めませんでした。")
    }

    override fun readBack(reference: String): ByteArray? = try {
        contentResolver.openInputStream(reference.toUri())?.use { it.readBytes() }
    } catch (e: Exception) {
        null
    }

    override fun delete(reference: String): Boolean = try {
        // 例外にしない代わりに、消せたかどうかは必ず呼び出し側へ返す。
        // 握りつぶすと `_AI補記` に残った空ファイルを誰も知らせられない。
        DocumentsContract.deleteDocument(contentResolver, reference.toUri())
    } catch (e: Exception) {
        false
    }

    override fun displayName(reference: String): String? = try {
        contentResolver.query(
            reference.toUri(),
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (e: Exception) {
        null
    }
}

/**
 * 本文読込の予算。用途ごとに別の入口を持たせ、**呼び出し側にバイト数を選ばせない**。
 * 引数で受けると「とりあえず大きめ」を渡せてしまい、上限を置く意味が薄れる。
 */
internal object NoteReadLimits {
    /** 表示用。蒸留の256KBを超えるノートでも、先頭はここまで見せる。 */
    const val DISPLAY_MAX_BYTES = 1024 * 1024

    /** 関連ノート候補用。front matter と冒頭150文字を取れれば足りる。 */
    const val SNIPPET_MAX_BYTES = 8 * 1024
}

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

    /**
     * 表示用に読む。[NoteReadLimits.DISPLAY_MAX_BYTES] を超える分は切り詰める。
     *
     * 蒸留できないほど大きいノート・UTF-8として厳密に読めないノートのフォールバック経路。
     * 皮肉なことに、ここへ落ちてくるのは一番大きいノートなので、上限が無いと
     * 「蒸留は256KBで止めたのに表示は無制限」という抜け道になる。
     */
    suspend fun readNoteForDisplay(contentResolver: ContentResolver, uri: Uri): BoundedText =
        readBoundedText(contentResolver, uri, NoteReadLimits.DISPLAY_MAX_BYTES)

    /**
     * 関連ノート候補のスニペットとfront matterを取るために、**先頭だけ**読む。
     *
     * 候補は最大40件を並列で読むため、ここが無制限だと巨大ファイル1つで詰まる。
     * 呼び出し側（`RelatedNotesUseCase`）が使うのは冒頭150文字のスニペットと
     * front matter の tags / aliases だけなので、先頭数KBあれば足りる。
     */
    suspend fun readNoteSnippet(contentResolver: ContentResolver, uri: Uri): String =
        readBoundedText(contentResolver, uri, NoteReadLimits.SNIPPET_MAX_BYTES).text

    /**
     * 上限付きでテキストを読む。上限で切ると多バイト文字が割れるため、
     * 復号前に末尾の不完全なシーケンスを落とす。
     *
     * 復号は厳密検証しない（この2経路は「読めるところまで見せる」のが目的で、
     * 不正UTF-8を弾く役目は蒸留用の [readNoteSnapshot] が持つ）。
     */
    private suspend fun readBoundedText(
        contentResolver: ContentResolver,
        uri: Uri,
        maximumBytes: Int
    ): BoundedText = withContext(Dispatchers.IO) {
        val bounded = contentResolver.openInputStream(uri)?.use { stream ->
            readAtMostBytes(stream, maximumBytes)
        } ?: return@withContext BoundedText("", isTruncated = false)
        BoundedText(
            text = String(dropIncompleteUtf8Tail(bounded.bytes), Charsets.UTF_8),
            isTruncated = bounded.isTruncated
        )
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

    /**
     * 補記メモを1件書き出す。**手順（作成→書込→検証→失敗時の後始末）は
     * [AnnotationFileWriter] が持ち、ここはSAFの実装だけを渡す。**
     * 表示名は予測せず、保存後のメタデータから取った実名を返す。
     */
    suspend fun createAnnotationFile(
        contentResolver: ContentResolver,
        vaultUri: Uri,
        sanitizedTitle: String,
        timestamp: String,
        content: String
    ): SavedAnnotation = withContext(Dispatchers.IO) {
        val folderUri = findAnnotationFolder(contentResolver, vaultUri)
            ?: createAnnotationFolder(contentResolver, vaultUri)
        val fileName = "${sanitizedTitle}${ANNOTATION_FILE_MARKER}$timestamp.md"
        val writer = AnnotationFileWriter(SafAnnotationDocumentGateway(contentResolver, folderUri))

        when (val result = writer.create(fileName, content)) {
            is AnnotationWriteResult.Success ->
                SavedAnnotation(result.reference.toUri(), result.displayName)
            is AnnotationWriteResult.Failure -> error(result.message)
        }
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
