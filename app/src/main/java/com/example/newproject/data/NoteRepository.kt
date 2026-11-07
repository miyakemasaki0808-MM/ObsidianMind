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

    // **確実に読める書き方だけを読み、それ以外が1つでも混ざったらキーをまるごと使わない**（空を返す）。
    // 推測して読むと、事実に反するタグや欠けた別名を出す（→ features/related_notes_ai.md §5）。
    //
    // 読むのは、行頭から始まる `tags:` がちょうど1つあり（入れ子の行はインデントがあるので当たらない）、
    // それより前の行がすべて1行で閉じていて（→ closesOnItsLine）、値が次のどちらかのとき。
    // - 1行で閉じるインライン一覧 `tags: [a, "b"]`。後ろに空白に続く `#` のコメントを置ける
    // - 同じ深さにインデントした `- a` の並び。空行・コメントの行・値の無い `- #メモ` は飛ばし、次のルートのキーまでを見る
    // 項目の値は、引用符で始まらず YAML の別の構文にならない語か、エスケープを含まない引用符つきの語。
    // タブを含む行・1語の形（`tags: a`）・インデントの無い `- a` は読まない。
    private fun parseFrontmatterList(lines: List<String>, key: String): List<String> {
        val keyLines = lines.indices.filter { lines[it] == "$key:" || lines[it].startsWith("$key: ") }
        if (keyLines.size != 1) return emptyList()
        val keyLine = keyLines.single()
        if (!lines.take(keyLine).all(::closesOnItsLine)) return emptyList()
        val rest = lines[keyLine].substringAfter("$key:")
        val block = lines.drop(keyLine + 1).takeWhile { it.isBlank() || it.first().isWhitespace() || it.startsWith("#") }
        if ('\t' in rest || block.any { '\t' in it }) return emptyList()
        val value = rest.trim()
        val items = when {
            value.startsWith("[") -> flowListItems(value).takeIf { block.all(::isBlankOrComment) }
            value.isEmpty() || value.startsWith("#") -> blockListItems(block)
            else -> null
        }
        return items ?: emptyList()
    }

    private fun isBlankOrComment(line: String): Boolean = line.isBlank() || line.trimStart().startsWith("#")

    // 引用符や括弧を開いたまま次の行へ続く行があると、その後ろの行頭の `tags:` も文字列や一覧の中身になる。
    // 1行で閉じたと確かめられる行だけを認め、閉じたかを決められない形（エスケープ・語の途中の引用符・タグや錨の印・タブ）は
    // 閉じていないものとして扱う。ルートの行は `キー:` の形に限る（ルートが辞書でなければ、行頭の `tags:` はキーではない）。
    private fun closesOnItsLine(line: String): Boolean {
        if (isBlankOrComment(line)) return true
        if ('\t' in line) return false
        val atRoot = !line.first().isWhitespace()
        var node = line.trim()
        if (!atRoot) while (node == "-" || node.startsWith("- ")) node = node.drop(1).trimStart()
        val key = PLAIN_KEY.find(node)
        if (key == null && atRoot) return false
        if (key != null) node = node.substring(key.range.last + 1).trim()
        return when {
            node.isEmpty() -> true
            node.first() == '"' || node.first() == '\'' -> quotedEnd(node, 0)?.let { isListEnd(node.substring(it + 1)) } ?: false
            node.first() == '[' || node.first() == '{' -> flowClosesOnItsLine(node)
            node.first() in "!&?%@`" -> false
            // 囲まない値に `: ` が残るのは、読めなかったキー（`Alice's key: "…`）の後ろを見ている。
            else -> node.substringBefore(" #").let { ": " !in it && !it.endsWith(":") }
        }
    }

    // `[a, "b"]`・`{a: b}`。引用符は値の先頭（`[` `{` `,` か `: ` の後ろ）でだけ認め、
    // 語の途中の引用符と一覧の中のコメントがあれば閉じていないものとして扱う。
    private fun flowClosesOnItsLine(node: String): Boolean {
        val closers = ArrayDeque<Char>()
        var index = 0
        while (index < node.length) {
            when (val c = node[index]) {
                '[' -> closers.addLast(']')
                '{' -> closers.addLast('}')
                ']', '}' -> {
                    if (closers.removeLastOrNull() != c) return false
                    if (closers.isEmpty()) return isListEnd(node.substring(index + 1))
                }
                '"', '\'' -> {
                    val before = node.substring(0, index).trimEnd()
                    val startsValue = before.last() in "[{," || (before.endsWith(":") && before.length < index)
                    if (!startsValue) return false
                    index = quotedEnd(node, index) ?: return false
                }
                '#' -> return false
            }
            index++
        }
        return false
    }

    // `start` の引用符を閉じる位置。単一引用符の中の `''` は引用符そのもの。二重引用符の中のバックスラッシュは
    // エスケープで、閉じる位置を決められないので null。
    private fun quotedEnd(text: String, start: Int): Int? {
        val quote = text[start]
        var index = start + 1
        while (index < text.length) {
            val c = text[index]
            if (quote == '"' && c == '\\') return null
            if (c == quote) {
                if (quote == '\'' && text.getOrNull(index + 1) == '\'') {
                    index += 2
                    continue
                }
                return index
            }
            index++
        }
        return null
    }

    // `[a, "b, c"] # コメント`。引用符は項目の先頭でだけ認め、区切りの `,` と閉じる `]` は引用符の外でだけ認める
    // （引用符の中の `,` `]` `#` は値の一部）。その行で閉じない一覧は読めない。一覧の中のコメントは、項目の値として読めずに落ちる。
    private fun flowListItems(text: String): List<String>? {
        if (text.drop(1).trimStart().startsWith("]")) {
            return if (isListEnd(text.substring(text.indexOf(']') + 1))) emptyList() else null
        }
        val items = mutableListOf<String>()
        var index = 1
        while (true) {
            while (index < text.length && text[index] == ' ') index++
            if (index >= text.length) return null
            val first = text[index]
            if (first == '"' || first == '\'') {
                val end = text.indexOf(first, startIndex = index + 1)
                if (end < 0) return null
                items += quotedValue(text.substring(index, end + 1)) ?: return null
                index = end + 1
                while (index < text.length && text[index] == ' ') index++
            } else {
                val stop = text.indexOfAny(charArrayOf(',', ']'), startIndex = index)
                if (stop < 0) return null
                items += plainValue(text.substring(index, stop)) ?: return null
                index = stop
            }
            if (index >= text.length) return null
            when (text[index]) {
                ',' -> index++
                ']' -> return if (isListEnd(text.substring(index + 1))) items else null
                else -> return null
            }
        }
    }

    // 一覧を閉じた後ろに置けるのは、空白に続く `#` のコメントだけ。
    private fun isListEnd(after: String): Boolean = after.isEmpty() || TRAILING_COMMENT.matches(after)

    // 同じ深さにインデントした `- a` の並び。深さの違う行・項目でない行（入れ子のキー・続きの行）は読めない。
    private fun blockListItems(lines: List<String>): List<String>? {
        val items = mutableListOf<String>()
        var depth: Int? = null
        for (line in lines) {
            if (isBlankOrComment(line)) continue
            val indent = line.length - line.trimStart().length
            if (depth != null && indent != depth) return null
            depth = indent
            val trimmed = line.trim()
            if (trimmed != "-" && !trimmed.startsWith("- ")) return null
            val body = trimmed.removePrefix("-").trim()
            if (body.isEmpty() || body.startsWith("#")) continue
            val value = if (body.first() == '"' || body.first() == '\'') {
                // 閉じる引用符の後ろに置けるのは行末のコメントだけ。
                val end = body.indexOf(body.first(), startIndex = 1)
                if (end < 0) return null
                val after = body.substring(end + 1)
                if (!isListEnd(after)) return null
                quotedValue(body.substring(0, end + 1))
            } else {
                plainValue(body.substringBefore(" #"))
            }
            items += value ?: return null
        }
        return items
    }

    // 引用符で囲んだ値。二重引用符の中のバックスラッシュは YAML のエスケープなので読まない。
    private fun quotedValue(token: String): String? {
        val inner = token.substring(1, token.length - 1)
        if (token.first() == '"' && '\\' in inner) return null
        return inner.trim().ifBlank { null }
    }

    // 囲まない値。読めなければ null。YAML の別の構文になる先頭の文字・辞書の形（`a: b`・`a:`）・括弧・行中のコメントを含む語と、
    // YAML で値が無いことを表す語（`null`・`~`。文字列ではない）は読まない。
    // `C#` や `Alice's` のように語の途中の `#` と引用符は値の一部。
    private fun plainValue(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        if (value in YAML_NULLS) return null
        if (value.first() in UNREADABLE_VALUE_START) return null
        if (value == "-" || value.startsWith("- ") || value == "?" || value.startsWith("? ")) return null
        if (": " in value || value.endsWith(":") || " #" in value) return null
        if (value.any { it in "[]{}" }) return null
        return value
    }

    companion object {
        private val WIKILINK_REGEX = Regex("\\[\\[([^\\]]+)]]")

        /** 一覧や引用符の後ろに置ける行末のコメント（空白に続く `#`）。タブは読む前に断っている。 */
        private val TRAILING_COMMENT = Regex("\\s+#.*")

        /** 囲まない値の先頭に来ると YAML の別の構文になる文字。 */
        private const val UNREADABLE_VALUE_START = "#[]{},&*!|>%@`"

        /** 囲まないキーの `キー:`。記号で始まらず、引用符・括弧・カンマ・`#`・`:` を含まないものだけ。 */
        private val PLAIN_KEY = Regex("""^[^\s#"'\[\]{},&*!|>%@`?:-][^#"'\[\]{},:]*:(?= |$)""")

        /** YAML 1.2 の Core Schema で null になる、囲まない語。 */
        private val YAML_NULLS = setOf("null", "Null", "NULL", "~")
    }
}
