package com.example.newproject.data

import com.example.newproject.model.ReadingTrace
import com.example.newproject.model.READING_TRACE_FOLDER_NAME
import com.example.newproject.model.DistillLimits
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteFile
import com.example.newproject.model.NoteFolder
import com.example.newproject.model.NoteMeta
import com.example.newproject.model.isImageFileName
import com.example.newproject.domain.image.NoteImageEntry
import com.example.newproject.model.VaultScan
import com.example.newproject.domain.toObsidianNoteTitle
import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 上限付きで読んだ本文。[isTruncated] が真なら、ノートにはまだ続きがある。 */
data class BoundedText(val text: String, val isTruncated: Boolean)

/** 読めなかったときに表示系が使う空の本文。**「読めるところまで見せる」経路だけで使う。** */
private val EMPTY_TEXT = BoundedText("", isTruncated = false)

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

class NoteRepository {

    // カーソルループと ChildDoc は SafDocuments.kt / VaultPathTraversal.kt へ移した
    // （ReadingTrace のサイドカー保存と共有するため）。
    private fun queryChildren(
        contentResolver: ContentResolver,
        vaultUri: Uri,
        documentId: String
    ): SafChildren = querySafChildren(contentResolver, vaultUri, documentId)

    // startId 配下を再帰BFSで走査して .md を集める。
    // 走査と相対パス連結の本体は traverseMarkdownPaths（純関数）に置き、
    // ここではカーソル読み出しと Uri 構築だけを担う。
    private fun collectNotesRecursive(
        contentResolver: ContentResolver,
        vaultUri: Uri,
        startId: String,
        startPath: String = "",
        excludeFolderNames: Set<String> = emptySet()
    ): VaultScan {
        val scan = traverseMarkdownPaths(
            startId = startId,
            startPath = startPath,
            excludeFolderNames = excludeFolderNames
        ) { documentId -> queryChildren(contentResolver, vaultUri, documentId) }
        return VaultScan(
            notes = scan.entries.map { entry ->
                NoteFile(
                    name = entry.name,
                    ref = DocumentsContract.buildDocumentUriUsingTree(vaultUri, entry.documentId).toDocumentRef(),
                    lastModified = entry.lastModified,
                    vaultRelativePath = entry.vaultRelativePath
                )
            },
            unreadableFolderPaths = scan.unreadableFolderPaths
        )
    }

    private fun ChildDoc.toNoteFile(vaultUri: Uri): NoteFile =
        NoteFile(name, DocumentsContract.buildDocumentUriUsingTree(vaultUri, documentId).toDocumentRef(), lastModified)

    // Vault全体のノートを収集する（ランダム表示・関連ノート候補用）。
    // AI生成の補記メモは復習対象にしない方針のため _AI補記 フォルダを除外する。
    // ※さがすタブ（collectNotesInScope）は _AI補記 だけは仕様どおり除外しない。
    // 完全性まで返すのは、不在を根拠に何かを消す処理（読書痕跡の孤児判定）が
    // 「読めなかっただけのフォルダ」を「削除された」と誤読しないため。
    // 表示系は VaultScan.notes だけ取ればよい。
    suspend fun collectNotes(contentResolver: ContentResolver, vaultUri: Uri): VaultScan =
        withContext(Dispatchers.IO) {
            collectNotesRecursive(
                contentResolver = contentResolver,
                vaultUri = vaultUri,
                startId = DocumentsContract.getTreeDocumentId(vaultUri),
                excludeFolderNames = setOf(ANNOTATION_FOLDER_NAME, READING_TRACE_FOLDER_NAME)
            )
        }

    /**
     * Vault全体の画像を収集する（画像索引用）。
     *
     * 除外するのは機能フォルダ（`_AI補記` / `_ReadingTraces`）と `.obsidian`。
     * 前2つはアプリが作るものなのでユーザーのノートが参照する画像は入らず、
     * `.obsidian` はテーマやプラグインの画像でノートの内容ではない。
     *
     * **完全性まで返すのは、ノート走査と同じ理由ではない。** ここでは
     * 「索引に無い」を「Vaultに無い」と言い切ってよいかの判定に使う
     * （→ `ImageResolution.Unverifiable`）。
     */
    suspend fun collectImages(contentResolver: ContentResolver, vaultUri: Uri): VaultImageScan =
        withContext(Dispatchers.IO) {
            val scan = traverseVaultFiles(
                startId = DocumentsContract.getTreeDocumentId(vaultUri),
                excludeFolderNames = setOf(
                    ANNOTATION_FOLDER_NAME,
                    READING_TRACE_FOLDER_NAME,
                    OBSIDIAN_CONFIG_FOLDER_NAME
                ),
                accept = ::isImageFileName
            ) { documentId -> queryChildren(contentResolver, vaultUri, documentId) }
            VaultImageScan(
                entries = scan.entries.map { entry ->
                    NoteImageEntry(
                        vaultRelativePath = entry.vaultRelativePath,
                        ref = DocumentsContract
                            .buildDocumentUriUsingTree(vaultUri, entry.documentId)
                            .toDocumentRef(),
                        lastModified = entry.lastModified
                    )
                },
                isComplete = scan.unreadableFolderPaths.isEmpty()
            )
        }

    /**
     * 1件の更新日時を引き直す（画像索引の鮮度確認用）。
     *
     * **「存在を確かめられたか」だけを返す。** 行が無い・カーソルが null・例外の3つは
     * どれも確かめられなかったことを意味し、行き先も同じなので畳む
     * （→ [DocumentVersionLookup]）。列を返さないプロバイダでは
     * [DocumentVersionLookup.Found] の値が null になり、**存在は確かめられたが
     * 世代では見分けられない**ことがそのまま呼び出し側へ伝わる。
     */
    suspend fun queryDocumentVersion(
        contentResolver: ContentResolver,
        uri: Uri
    ): DocumentVersionLookup = withContext(Dispatchers.IO) {
        try {
            contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) {
                    DocumentVersionLookup.Unconfirmed
                } else {
                    DocumentVersionLookup.Found(
                        if (cursor.isNull(0)) null else cursor.getLong(0)
                    )
                }
            } ?: DocumentVersionLookup.Unconfirmed
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            DocumentVersionLookup.Unconfirmed
        }
    }

    // Vault 第一階層のフォルダのみ列挙する（ドリルダウンなし・名前昇順）。
    // _ReadingTraces はユーザーのノートを含まない機能の内部データなので候補に出さない。
    //
    // 列挙に失敗したら空一覧になる。ここは「フォルダが1つも出ない」という形で
    // ユーザーの目に見える（黙って一部が欠けるのではない）ので、読めた分をそのまま返す。
    suspend fun listTopLevelFolders(contentResolver: ContentResolver, vaultUri: Uri): List<NoteFolder> =
        withContext(Dispatchers.IO) {
            queryChildren(contentResolver, vaultUri, DocumentsContract.getTreeDocumentId(vaultUri))
                .items
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
    // さがすは読めた分で結果を出す（完全性は使わない）。不在から何かを消すわけではないので、
    // 部分的に読めなかった場合も「候補が少ない検索結果」に留まる。
    ): List<NoteFile> = withContext(Dispatchers.IO) {
        if (scope == null) {
            queryChildren(contentResolver, vaultUri, DocumentsContract.getTreeDocumentId(vaultUri))
                .items
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
            ).notes
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
        readBoundedText(contentResolver, uri, NoteReadLimits.DISPLAY_MAX_BYTES) ?: EMPTY_TEXT

    /**
     * 関連ノート候補のスニペットとfront matterを取るために、**先頭だけ**読む。
     *
     * 候補は最大40件を並列で読むため、ここが無制限だと巨大ファイル1つで詰まる。
     * 呼び出し側（`RelatedNotesUseCase`）が使うのは冒頭150文字のスニペットと
     * front matter の tags / aliases だけなので、先頭数KBあれば足りる。
     */
    suspend fun readNoteSnippet(contentResolver: ContentResolver, uri: Uri): String =
        readNoteSnippetOrNull(contentResolver, uri) ?: ""

    /**
     * 冊子の扉用。**「開けなかった」と「中身が空」を分ける。**
     *
     * [readNoteSnippet] は開けなかった場合も空文字を返す（読めるところまで見せる経路なので
     * それでよい）。冊子は**そのページだけを失敗として見せる**必要があり、空文字へ畳むと
     * タイトルへフォールバックして「読めた」ように振る舞ってしまう
     * （→ features/booklet_mode.md §10 の境界条件）。
     */
    suspend fun readNoteSnippetOrNull(contentResolver: ContentResolver, uri: Uri): String? =
        readBoundedText(contentResolver, uri, NoteReadLimits.SNIPPET_MAX_BYTES)?.text

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
    ): BoundedText? = withContext(Dispatchers.IO) {
        // **null は「ストリームを開けなかった」。** 空の本文と同じ値へ畳まない
        // （呼び出し側が畳むかどうかを決める → [readNoteSnippetOrNull]）。
        val bounded = contentResolver.openInputStream(uri)?.use { stream ->
            readAtMostBytes(stream, maximumBytes)
        } ?: return@withContext null
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

    /**
     * 任意のドキュメントを上限付きで読む。**上限を超えたら例外**（切り詰めない）。
     *
     * 痕跡の退避ファイルを読み戻す経路で使う。**アプリの管理外のファイル**を
     * ユーザーが選ぶので、切り詰めて読むと「途中まで正しいJSON」が渡ることはなく、
     * ただ理由の分からない解析失敗になる。大きすぎることを理由として伝えられる形にする。
     */
    internal suspend fun readDocumentBytes(
        contentResolver: ContentResolver,
        uri: Uri,
        maximumBytes: Int
    ): ByteArray = withContext(Dispatchers.IO) {
        val stream = contentResolver.openInputStream(uri) ?: error("ファイルを開けませんでした。")
        stream.use {
            try {
                readBoundedBytes(it, maximumBytes)
            } catch (error: NoteFileTooLargeException) {
                // 既定の文言は蒸留のノート向けなので、ここで言い換える。
                // そのまま流すと「ノートが蒸留の上限を超えています」が退避ファイルの
                // 読み込みエラーとして出て、原因を取り違える。
                throw IllegalArgumentException(
                    "ファイルが大きすぎます（上限 ${maximumBytes / (1024 * 1024)}MB）。"
                )
            }
        }
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

    // _AI補記/ フォルダ内の補記メモファイルを列挙する（1階層のみ）
    suspend fun listAnnotationFiles(contentResolver: ContentResolver, vaultUri: Uri): List<NoteFile> =
        withContext(Dispatchers.IO) {
            val folderUri = findAnnotationFolder(contentResolver, vaultUri) ?: return@withContext emptyList()
            val folderId = DocumentsContract.getDocumentId(folderUri)
            // 作成日時の新しい順に並べる。ファイル名は "{タイトル}__補記_{yyyyMMdd_HHmm}.md"
            // 形式のため、名前全体でなくタイムスタンプ部をソートキーにする
            // （名前降順だとタイトルの辞書順が支配して日付順にならない）
            // 補記の一覧は読めた分を出す（削除判断には使わないため完全性を要求しない）。
            queryChildren(contentResolver, vaultUri, folderId)
                .items
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

    // Found 以外は null。Absent（本当に無い）と Unreadable（読めなかった）を
    // ここで潰してよいのは、この戻り値で作成判断をしないため
    // （フォルダを作る経路はもう無い。ひとことは痕跡サイドカーへ保存する）。
    private fun findAnnotationFolder(contentResolver: ContentResolver, vaultUri: Uri): Uri? =
        (findRootChildFolder(contentResolver, vaultUri, ANNOTATION_FOLDER_NAME)
            as? RootFolderLookup.Found)?.uri

    companion object {
        private const val ANNOTATION_FOLDER_NAME = "_AI補記"
        // 補記メモのファイル名区切り: "{タイトル}__補記_{yyyyMMdd_HHmm}.md"
        private const val ANNOTATION_FILE_MARKER = "__補記_"
        private val WIKILINK_REGEX = Regex("\\[\\[([^\\]]+)]]")
    }
}
