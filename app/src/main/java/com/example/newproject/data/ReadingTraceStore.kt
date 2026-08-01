package com.example.newproject.data

import com.example.newproject.model.ReadingTrace
import com.example.newproject.model.ReadingTraceLimits
import com.example.newproject.model.READING_TRACE_FOLDER_NAME
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

/**
 * [vaultKey] は「どのVaultへの要求か」を表す不透明な識別子。
 *
 * 保存は非同期に起動されるため、書き込みが実際に走る時点では利用者が別のVaultへ
 * 切り替えているかもしれない。保存先を書込時点の現在Vaultから解決すると、旧Vaultの
 * ノートの痕跡が新Vaultへ書き込まれてしまう。要求を出した時点のVaultを要求自身に
 * 持たせ、書き込み直前に照合することでこれを防ぐ。
 *
 * Uri ではなく String にしているのは、この境界より上を Android 非依存に保つため。
 */
internal interface ReadingTracePersistence {
    fun folderStatus(): ReadingTraceFolderStatus
    fun load(vaultRelativePath: String, vaultKey: String): ReadingTraceReadResult
    fun save(trace: ReadingTrace, vaultKey: String): ReadingTraceSaveResult
}

/**
 * サイドカー1ファイルの読み書き。[key] は相対パスから導いた安全なファイル名の素。
 * Uri を扱わないので、この境界より上（[ReadingTraceStore]）は素のJVMテストで検証できる。
 */
internal interface ReadingTraceDocumentGateway {
    /** 置き場を確保する。Vault未選択・作成不可なら false。 */
    fun ensureFolder(): Boolean

    /** 該当ファイルが無ければ null。[vaultKey] が現在のVaultと違う場合も null。 */
    fun read(key: String, maximumBytes: Int, vaultKey: String): ByteArray?

    /** [vaultKey] が現在のVaultと違う場合は書き込まず例外を投げる。 */
    fun write(key: String, bytes: ByteArray, vaultKey: String)
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

    override fun load(vaultRelativePath: String, vaultKey: String): ReadingTraceReadResult = try {
        val bytes = gateway.read(keyFor(vaultRelativePath), ReadingTraceLimits.MAX_FILE_BYTES, vaultKey)
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

    override fun save(trace: ReadingTrace, vaultKey: String): ReadingTraceSaveResult = try {
        val bytes = ReadingTraceJson.encode(trace)
        gateway.write(keyFor(trace.vaultRelativePath), bytes, vaultKey)
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
        val files: MutableMap<String, Uri>,
        /**
         * 置き場の子一覧を最後まで読めたか。false なら [files] に載っていないキーが
         * **存在しないことを意味しない**ので、新規作成してはいけない（重複ファイルを作る）。
         */
        val isComplete: Boolean
    )

    private var index: FolderIndex? = null

    @Synchronized
    override fun ensureFolder(): Boolean = folderIndex() != null

    @Synchronized
    override fun read(key: String, maximumBytes: Int, vaultKey: String): ByteArray? {
        val file = folderIndex(vaultKey)?.files?.get(key) ?: return null
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
    override fun write(key: String, bytes: ByteArray, vaultKey: String) {
        // 要求を出した時点のVaultと現在のVaultが違えば、書かずに捨てる。判定と保存先の解決を
        // [folderIndex] の中へ寄せているのは、そこが vaultUri() を読む唯一の場所であることを
        // 保証するため（→ [folderIndex] のコメント）。
        val current = folderIndex(vaultKey)
            ?: throw IOException("痕跡の保存先を用意できませんでした（Vault切替またはフォルダ作成失敗）。")
        // 索引が不完全なら、キーが載っていなくても「まだ無い」とは言えない。
        // ここで作ると同じキーのファイルが2つでき、次回どちらを採るかが列挙順依存になる。
        // 書けなかった訪問は消費済みの印が巻き戻されて次の契機で書き直される（判断10）。
        val target = current.files[key]
            ?: if (current.isComplete) {
                createFile(current, key)
            } else {
                throw IOException("痕跡フォルダの一覧を読めなかったため、新規作成を見送りました。")
            }
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

    /**
     * [vaultKey] が指すVaultの置き場。現在のVaultが違えば null。
     *
     * **`vaultUri()` を読むのは1回だけ**で、その1つの値を照合にも保存先の解決にも使う。
     * 読み直してはいけない: `vaultUri` はこのクラスのロックの外（ViewModel／メインスレッド）で
     * 更新されるため、「一致を確認してから改めて読む」と、その隙にVaultが切り替わった場合に
     * **切替後のVaultへ旧ノートの痕跡を書いてしまう**（`@Synchronized` は自身の状態しか守らない）。
     * 1回だけ読めば、書き込み先は必ず「読んだ瞬間に選択されていたVault」＝[vaultKey] のVaultになる。
     *
     * 読んだ直後に切り替わった場合は旧Vaultへ書き切ることになるが、それは正しい
     * （その訪問は旧Vaultのノートで起きたもので、永続化した権限も生きている）。
     */
    private fun folderIndex(vaultKey: String): FolderIndex? {
        val vault = vaultUri() ?: return null
        if (vault.toString() != vaultKey) return null
        return folderIndexOf(vault)
    }

    /** 現在のVaultの置き場。利用可否の確認（[ensureFolder]）専用。 */
    private fun folderIndex(): FolderIndex? {
        val vault = vaultUri() ?: return null
        return folderIndexOf(vault)
    }

    private fun folderIndexOf(vault: Uri): FolderIndex? {
        index?.let { if (it.vault == vault) return it }
        // 「探して、無ければ作る」を1回の探索で行う。**ルートを読めなかった場合は作らない** —
        // 以前は find の失敗と不在が同じ null だったため、ルート列挙に失敗しただけで
        // 2つ目の _ReadingTraces を作りうる状態だった。
        val folder = when (val lookup =
            findRootChildFolder(contentResolver, vault, READING_TRACE_FOLDER_NAME)) {
            is RootFolderLookup.Found -> lookup.uri
            RootFolderLookup.Unreadable -> return null
            RootFolderLookup.Absent ->
                createRootChildFolder(contentResolver, vault, READING_TRACE_FOLDER_NAME)
                    ?: return null
        }
        val children = querySafChildren(contentResolver, vault, DocumentsContract.getDocumentId(folder))
        val files = mutableMapOf<String, Uri>()
        children.items
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
        val resolved = FolderIndex(vault, folder, files, isComplete = children.isComplete)
        // 不完全な索引はキャッシュしない。載せると、一度読み損ねただけで
        // Vault切替まで新規作成が止まり続ける。次の呼び出しで読み直させる。
        if (children.isComplete) index = resolved
        return resolved
    }

    private companion object {
        const val MIME_TYPE_JSON = "application/json"
        /** SHA-256 の16進表記の長さ。ファイル名の先頭がこの長さのキーになる。 */
        const val KEY_LENGTH = 64
    }
}
