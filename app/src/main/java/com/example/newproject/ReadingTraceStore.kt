package com.example.newproject

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException

// ---------------------------------------------------------------------------
// ReadingTrace のサイドカー永続化。
//
// 置き場は Vault内の `_ReadingTraces/`（アンダースコア始まりの可視フォルダ）。
// SAFでは `.` 始まりの隠しフォルダはプロバイダ（Google Drive・OneDrive・一部の
// ローカルファイラー）によって作成が失敗したり不可視化の挙動が違うため、
// `_AI補記` と同じくアンダースコア始まりにして可搬性を優先する。
//
// 書き込みは "wt"（write-truncate）の直接上書きで、原子性はない。truncate 後に
// 書き直すため書込中にプロセスが死ぬと部分破損が残り復旧元もない。SAF の
// renameDocument() はプロバイダ非互換で古典的な atomic-rename が成立せず、
// DistillRecoveryStore の原子パターンは内部ストレージ専用でSAFへ移植できない。
// 「シンプル最優先」の設計原則どおり、ここは割り切る。失われるのは痕跡だけで
// ユーザーのノート(.md)には一切触れないため、本質（ノート閲覧）は損なわれない。
// 破損は ReadingTraceJson の checksum 検証で検知し、孤立扱いにする。
// 端末間の同時編集は last-writer-wins（マージや世代解決はしない）。
// ---------------------------------------------------------------------------

internal sealed interface ReadingTraceFolderStatus {
    data object Ready : ReadingTraceFolderStatus
    data class Unavailable(val reason: String) : ReadingTraceFolderStatus
}

internal sealed interface ReadingTraceSaveResult {
    data object Success : ReadingTraceSaveResult
    /**
     * 保存できなかった。Vault未選択・フォルダ作成不可・書込失敗を区別しないのは、
     * 呼び出し側の扱いが同じ（黙って諦める）ため。利用可否の事前判定は
     * [ReadingTracePersistence.folderStatus] が担う。
     */
    data class Failure(val message: String) : ReadingTraceSaveResult
}

internal interface ReadingTracePersistence {
    fun folderStatus(): ReadingTraceFolderStatus
    fun load(vaultRelativePath: String): ReadingTraceReadResult
    fun save(trace: ReadingTrace): ReadingTraceSaveResult
}

/**
 * サイドカー1ファイルの読み書き。[key] は相対パスから導いた安全なファイル名の素。
 * Uri を扱わないので、この境界より上（[ReadingTraceStore]）は素のJVMテストで検証できる。
 */
internal interface ReadingTraceDocumentGateway {
    /** 置き場を確保する。Vault未選択・作成不可なら false。 */
    fun ensureFolder(): Boolean

    /** 該当ファイルが無ければ null。 */
    fun read(key: String, maximumBytes: Int): ByteArray?

    fun write(key: String, bytes: ByteArray)
}

internal class ReadingTraceStore(
    private val gateway: ReadingTraceDocumentGateway
) : ReadingTracePersistence {

    override fun folderStatus(): ReadingTraceFolderStatus = try {
        if (gateway.ensureFolder()) {
            ReadingTraceFolderStatus.Ready
        } else {
            ReadingTraceFolderStatus.Unavailable("痕跡の保存先を用意できませんでした。")
        }
    } catch (error: Exception) {
        ReadingTraceFolderStatus.Unavailable(error.message ?: "痕跡の保存先を用意できませんでした。")
    }

    override fun load(vaultRelativePath: String): ReadingTraceReadResult = try {
        val bytes = gateway.read(keyFor(vaultRelativePath), ReadingTraceLimits.MAX_FILE_BYTES)
            ?: return ReadingTraceReadResult.None
        val result = ReadingTraceJson.decode(bytes)
        // ファイル名は相対パスのハッシュなので、中身のパスが食い違っていたら
        // ハッシュ衝突か手による改変。信用せず孤立扱いにする。
        if (result is ReadingTraceReadResult.Valid && result.trace.vaultRelativePath != vaultRelativePath) {
            ReadingTraceReadResult.Corrupt("痕跡ファイルの対象ノートが一致しません。")
        } else {
            result
        }
    } catch (error: Exception) {
        ReadingTraceReadResult.Corrupt(error.message ?: error::class.java.simpleName)
    }

    override fun save(trace: ReadingTrace): ReadingTraceSaveResult = try {
        val bytes = ReadingTraceJson.encode(trace)
        gateway.write(keyFor(trace.vaultRelativePath), bytes)
        ReadingTraceSaveResult.Success
    } catch (error: Exception) {
        ReadingTraceSaveResult.Failure(error.message ?: error::class.java.simpleName)
    }

    companion object {
        /**
         * 相対パスをそのままファイル名にはできない（`/` を含み、長さ・使用可能文字も
         * プロバイダ依存）ため、SHA-256 の16進表記を使う。
         */
        internal fun keyFor(vaultRelativePath: String): String =
            sha256Hex(vaultRelativePath.toByteArray(Charsets.UTF_8))
    }
}

/**
 * SAF実装。Android依存のためJVMユニットテストの対象外（テストは
 * [ReadingTraceDocumentGateway] のFakeで [ReadingTraceStore] 側を検証する）。
 *
 * 置き場の子一覧は**セッション中1回だけ**読み、key→Uri のインデックスに保持する。
 * 毎回 [querySafChildren] するとノートを開く／離れるたびにフォルダ全走査になり、
 * 痕跡ファイルが増えるほど重くなる（特にGoogle Drive等の遠いプロバイダ）。
 * 作成したファイルは即インデックスへ足すので、同一セッション内で取りこぼさない。
 *
 * 読み書きは [Synchronized] で直列化する。訪問の追記は Controller 側の Mutex で
 * 直列化されているが、再会時の照合（load）はその外側で走るため、
 * インデックスへの同時アクセスが起きうる。
 */
internal class SafReadingTraceDocumentGateway(
    private val contentResolver: ContentResolver,
    private val vaultUri: () -> Uri?
) : ReadingTraceDocumentGateway {

    private class FolderIndex(
        val vault: Uri,
        val folder: Uri,
        val files: MutableMap<String, Uri>
    )

    private var index: FolderIndex? = null

    @Synchronized
    override fun ensureFolder(): Boolean = folderIndex() != null

    @Synchronized
    override fun read(key: String, maximumBytes: Int): ByteArray? {
        val file = folderIndex()?.files?.get(key) ?: return null
        return try {
            contentResolver.openInputStream(file)?.use { readBoundedBytes(it, maximumBytes) }
        } catch (error: Exception) {
            // 外部で削除・移動されるとキャッシュ済みUriが無効になるので、索引を捨てて作り直させる。
            // 例外を投げ返さず「痕跡なし」として扱う（呼び出し側の分岐を増やさない）。
            // 読めなかった場合、次の訪問で1件だけの痕跡として書き直されるため履歴は失われる。
            // 開けないファイルに追記する術はなく、痕跡は失っても .md は無傷という
            // 設計原則の範囲内なので、自己修復する側に振っている。
            index = null
            null
        }
    }

    @Synchronized
    override fun write(key: String, bytes: ByteArray) {
        val current = folderIndex() ?: throw IOException("痕跡の保存先を用意できませんでした。")
        val target = current.files[key] ?: createFile(current, key)
        try {
            contentResolver.openOutputStream(target, "wt")?.use { output ->
                output.write(bytes)
                output.flush()
            } ?: throw IOException("痕跡ファイルへ書き込めませんでした。")
        } catch (error: Exception) {
            // キャッシュ済みUriが無効だった可能性があるので捨てて作り直させる。
            index = null
            throw error
        }
    }

    private fun createFile(current: FolderIndex, key: String): Uri {
        val created = DocumentsContract.createDocument(
            contentResolver,
            current.folder,
            MIME_TYPE_JSON,
            "$key.json"
        ) ?: throw IOException("痕跡ファイルを作成できませんでした。")
        current.files[key] = created
        return created
    }

    private fun folderIndex(): FolderIndex? {
        val vault = vaultUri() ?: return null
        index?.let { if (it.vault == vault) return it }
        val folder = findRootChildFolder(contentResolver, vault, READING_TRACE_FOLDER_NAME)
            ?: createRootChildFolder(contentResolver, vault, READING_TRACE_FOLDER_NAME)
            ?: return null
        val files = mutableMapOf<String, Uri>()
        querySafChildren(contentResolver, vault, DocumentsContract.getDocumentId(folder))
            .filter { !it.isDirectory }
            .forEach { child ->
                // ファイル名は "<64桁hex>.json"。完全一致で引かないのは、SAF の
                // createDocument が displayName をプロバイダに改名されうるため
                // （重複時の "foo (1).json"、MIMEに合わせた "foo.json.json" 等）。
                // 先頭のキーだけは残るので、そこを索引キーにする。この置き場には
                // 自分が作ったファイルしか入らないため取り違えない。
                val key = child.name.take(KEY_LENGTH)
                if (key.length == KEY_LENGTH && !files.containsKey(key)) {
                    files[key] = DocumentsContract.buildDocumentUriUsingTree(vault, child.documentId)
                }
            }
        return FolderIndex(vault, folder, files).also { index = it }
    }

    private companion object {
        const val MIME_TYPE_JSON = "application/json"
        /** SHA-256 の16進表記の長さ。ファイル名の先頭がこの長さのキーになる。 */
        const val KEY_LENGTH = 64
    }
}
