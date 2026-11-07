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
import java.io.IOException
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

private const val ANNOTATION_FOLDER_NAME = "_AI補記"

/**
 * Vault全体の走査が潜らないフォルダ。読むノートも、ノートが参照する画像も置かれない場所である。
 * `_AI補記` は旧版が書き出した補記で、書き出す経路はもう無いがフォルダが残った Vault がある。
 */
internal val VAULT_SCAN_EXCLUDED_FOLDERS = setOf(
    ANNOTATION_FOLDER_NAME,
    READING_TRACE_FOLDER_NAME,
    OBSIDIAN_CONFIG_FOLDER_NAME
)

/**
 * 不在を判定する走査が潜らないフォルダ。**`.obsidian` は読むノートから外しているが、ここでは歩く** —
 * 配下の `.md` を読んだ旧版の痕跡があり、歩かないと元ノートが実在するのに不在に見えて、消せる孤児になる。
 */
internal val ABSENCE_SCAN_EXCLUDED_FOLDERS = setOf(
    ANNOTATION_FOLDER_NAME,
    READING_TRACE_FOLDER_NAME
)

/**
 * 第一階層の子一覧から、Explore の範囲に出すフォルダを名前の順に取り出す。
 * **読めなかった一覧（[SafChildren.isComplete] が false）は例外にする。** 空一覧として返すと、
 * 選んでいた範囲が外で消えたのと見分けられず、範囲と結果を捨ててしまう（→ features/ai_picker.md §5）。
 */
internal fun topLevelFoldersOf(children: SafChildren): List<NoteFolder> {
    if (!children.isComplete) throw IOException("フォルダの一覧を読めませんでした。")
    return children.items
        .filter { it.isDirectory && it.name !in VAULT_SCAN_EXCLUDED_FOLDERS }
        .map { NoteFolder(it.name, it.documentId) }
        .sortedBy { it.name }
}

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

    // 読むノートとして Vault 全体を収集する（ランダム表示・Explore・関連ノート候補用）。
    // VAULT_SCAN_EXCLUDED_FOLDERS には潜らない。旧補記や設定フォルダの .md が候補に混ざらないようにする。
    // **ここに無いことを、ノートが無いことの根拠にしない**（→ collectNotesForAbsenceCheck）。
    suspend fun collectNotes(contentResolver: ContentResolver, vaultUri: Uri): VaultScan =
        collectNotesExcluding(contentResolver, vaultUri, VAULT_SCAN_EXCLUDED_FOLDERS)

    // 不在を根拠に何かを消す処理（読書痕跡の孤児判定）のための収集。
    // 完全性まで返すのは「読めなかっただけのフォルダ」を「削除された」と誤読しないため。
    suspend fun collectNotesForAbsenceCheck(contentResolver: ContentResolver, vaultUri: Uri): VaultScan =
        collectNotesExcluding(contentResolver, vaultUri, ABSENCE_SCAN_EXCLUDED_FOLDERS)

    private suspend fun collectNotesExcluding(
        contentResolver: ContentResolver,
        vaultUri: Uri,
        excludeFolderNames: Set<String>
    ): VaultScan = withContext(Dispatchers.IO) {
        collectNotesRecursive(
            contentResolver = contentResolver,
            vaultUri = vaultUri,
            startId = DocumentsContract.getTreeDocumentId(vaultUri),
            excludeFolderNames = excludeFolderNames
        )
    }

    /**
     * Vault全体の画像を収集する（画像索引用）。
     *
     * 除外は [VAULT_SCAN_EXCLUDED_FOLDERS]。`.obsidian` の画像はテーマやプラグインのものでノートの内容ではない。
     *
     * **完全性まで返すのは、ノート走査と同じ理由ではない。** ここでは
     * 「索引に無い」を「Vaultに無い」と言い切ってよいかの判定に使う
     * （→ `ImageResolution.Unverifiable`）。
     */
    suspend fun collectImages(contentResolver: ContentResolver, vaultUri: Uri): VaultImageScan =
        withContext(Dispatchers.IO) {
            val scan = traverseVaultFiles(
                startId = DocumentsContract.getTreeDocumentId(vaultUri),
                excludeFolderNames = VAULT_SCAN_EXCLUDED_FOLDERS,
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
    // Explore の範囲は全体の一覧を場所で絞るので、全体の走査が潜らないフォルダは出しても空になる。
    // 列挙に失敗したら例外にする（→ [topLevelFoldersOf]）。
    suspend fun listTopLevelFolders(contentResolver: ContentResolver, vaultUri: Uri): List<NoteFolder> =
        withContext(Dispatchers.IO) {
            topLevelFoldersOf(queryChildren(contentResolver, vaultUri, DocumentsContract.getTreeDocumentId(vaultUri)))
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
     * 復号（[decodeNoteText]）は末尾の不完全なシーケンスを落としてから行う。
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
            text = decodeNoteText(bounded.bytes),
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
            content = decodeNoteTextStrict(bytes),
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

    // YAML の構文（コメント・引用符）を値に混ぜない。混ぜると、関連ノートの根拠に実在しないタグが
    // 「共通」と出る（→ features/related_notes_ai.md §5）。
    private fun parseFrontmatterList(lines: List<String>, key: String): List<String> {
        val keyLine = lines.indexOfFirst { it.trimStart().startsWith("$key:") }
        if (keyLine < 0) return emptyList()

        val inline = lines[keyLine].substringAfter("$key:").trim()
        // インライン形式: tags: [a, b, c]
        if (inline.startsWith("[")) return parseFlowList(inline)
        // ブロック形式:
        // tags:
        //   - a
        //   - b
        // インデントしたコメントの行は、`#` で始まる値として落ちる。
        return lines.drop(keyLine + 1)
            .takeWhile { it.startsWith(" ") || it.startsWith("\t") }
            .mapNotNull { frontmatterListValue(it.trim().removePrefix("-")) }
    }

    // `[a, "b, c", 'd'] # コメント` の項目。一覧を閉じる `]` と区切りの `,` は引用符の外でだけ認める —
    // 引用符の中の `,` `]` `#` は値の一部で、`]` の後ろはコメントなので見ない。
    private fun parseFlowList(text: String): List<String> {
        val items = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        for (c in text.drop(1)) {
            if (quote != null) {
                current.append(c)
                if (c == quote) quote = null
                continue
            }
            when (c) {
                '"', '\'' -> {
                    quote = c
                    current.append(c)
                }
                ',' -> {
                    items += current.toString()
                    current.clear()
                }
                ']' -> {
                    items += current.toString()
                    return items.mapNotNull(::frontmatterListValue)
                }
                else -> current.append(c)
            }
        }
        items += current.toString()
        return items.mapNotNull(::frontmatterListValue)
    }

    // 項目1つの値。引用符で囲んだ値は、閉じる引用符までが値で後ろはコメント（中の `#` は値の一部）。
    // 囲まない値は、`#` で始まれば YAML ではコメント（Obsidian もタグとして読まない）で、
    // 空白に続く ` #` の後ろが行末のコメント。`C#` のように空白の無い `#` は値の一部。
    private fun frontmatterListValue(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        val first = value.first()
        if (first == '"' || first == '\'') {
            val end = value.indexOf(first, startIndex = 1)
            return (if (end > 0) value.substring(1, end) else value.substring(1)).trim().ifBlank { null }
        }
        if (first == '#') return null
        return value.substringBefore(" #").trim().ifBlank { null }
    }

    companion object {
        private val WIKILINK_REGEX = Regex("\\[\\[([^\\]]+)]]")
    }
}
