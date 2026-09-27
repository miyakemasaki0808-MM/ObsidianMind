package com.example.newproject.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import com.example.newproject.model.Crystal
import com.example.newproject.model.CrystalLimits
import com.example.newproject.model.READING_TRACE_FOLDER_NAME
import java.io.IOException

// ---------------------------------------------------------------------------
// 結晶の置き場（→ docs/dev/features/reflect_crystal.md 判断6・判断7）。
//
// Vault 内 `_ReadingTraces/crystals/` に1件1ファイル。**既存のファイルを書き換えない** —
// 書くのは新しいファイルを作るときだけで、壊れても失うのはその1件で済む。
// 痕跡の索引はフォルダと64文字未満の名前を載せないので、このフォルダは孤児掃除・退避に現れない。
// ---------------------------------------------------------------------------

internal sealed interface CrystalListing {
    /** 置き場を最後まで読めた。[unreadable] は飛ばした件数（壊れた・大きすぎる・未知の版）。 */
    data class Available(val crystals: List<Crystal>, val unreadable: Int) : CrystalListing

    /** 列挙できなかった。**結晶が無いことを意味しない。** */
    data class Unavailable(val reason: String) : CrystalListing
}

internal sealed interface CrystalSaveResult {
    /** [crystal] には実際に作ったファイル名が入っている。 */
    data class Saved(val crystal: Crystal) : CrystalSaveResult
    data class Failure(val message: String) : CrystalSaveResult
}

/**
 * [vaultKey] は「どのVaultへの要求か」を表す不透明な識別子。
 * **書き込み直前に現在のVaultと照合し、違えば書かない**（読書痕跡の保存と同じ契約）。
 */
internal interface CrystalPersistence {
    fun readAll(vaultKey: String): CrystalListing
    fun append(crystal: Crystal, vaultKey: String): CrystalSaveResult
}

/** 置き場の1ファイル。[bytes] が null なら読めなかった（上限超過・読み取り失敗）。 */
internal class CrystalDocument(val name: String, val bytes: ByteArray?)

/** SAF の境界。Uri を扱わないので、この上（[CrystalStore]）は素のJVMテストで検証できる。 */
internal interface CrystalDocumentGateway {
    /**
     * 置き場のファイルを列挙して読む。**置き場がまだ無ければ空のリスト**。
     * 列挙に失敗した・[vaultKey] が現在のVaultでないときは null（空と区別する）。
     */
    fun readAll(maximumBytes: Int, vaultKey: String): List<CrystalDocument>?

    /**
     * 新しいファイルを作って書き、**実際に作った名前**を返す（プロバイダが別名にしうる）。
     * [vaultKey] が現在のVaultでない・作れないときは例外。
     */
    fun create(fileName: String, bytes: ByteArray, vaultKey: String): String
}

internal class CrystalStore(private val gateway: CrystalDocumentGateway) : CrystalPersistence {

    override fun readAll(vaultKey: String): CrystalListing = try {
        val documents = gateway.readAll(CrystalLimits.MAX_FILE_BYTES, vaultKey)
        if (documents == null) {
            CrystalListing.Unavailable("結晶の置き場を列挙できませんでした。")
        } else {
            val crystals = documents.mapNotNull { document ->
                document.bytes?.let { CrystalJson.decode(it, document.name) }
            }
            CrystalListing.Available(crystals, unreadable = documents.size - crystals.size)
        }
    } catch (error: Exception) {
        CrystalListing.Unavailable(error.message ?: error::class.java.simpleName)
    }

    /**
     * **書く前に encode 後の大きさを測る。** 上限を超えたまま書くと、次の読み込みで飛ばされて
     * 「書けたのに一覧に出ない」になる。
     */
    override fun append(crystal: Crystal, vaultKey: String): CrystalSaveResult = try {
        val bytes = CrystalJson.encode(crystal)
        if (bytes.size > CrystalLimits.MAX_FILE_BYTES) {
            CrystalSaveResult.Failure("結晶が上限（${CrystalLimits.MAX_FILE_BYTES}バイト）を超えます。")
        } else {
            val created = gateway.create(fileNameFor(crystal), bytes, vaultKey)
            CrystalSaveResult.Saved(crystal.copy(fileName = created))
        }
    } catch (error: Exception) {
        CrystalSaveResult.Failure(error.message ?: error::class.java.simpleName)
    }

    companion object {
        /** 作成時刻のエポックミリ秒。**名前に意味を持たせない**（並べ替えは中身の時刻で行う）。 */
        internal fun fileNameFor(crystal: Crystal): String = "${crystal.createdAt}.json"
    }
}

/**
 * SAF実装。Android依存のためJVMユニットテストの対象外（[CrystalStore] 側を偽の境界で検証する）。
 *
 * **`vaultUri()` を読むのは1回の呼び出しにつき1回だけ**で、その値を照合にも保存先の解決にも使う
 * （読み直すと、その隙に切り替わったVaultへ書く → [SafReadingTraceDocumentGateway] と同じ理由）。
 */
internal class SafCrystalDocumentGateway(
    private val contentResolver: ContentResolver,
    private val vaultUri: () -> Uri?
) : CrystalDocumentGateway {

    @Synchronized
    override fun readAll(maximumBytes: Int, vaultKey: String): List<CrystalDocument>? {
        val vault = vaultUri() ?: return null
        if (vault.toString() != vaultKey) return null
        val traces = when (val lookup = findRootChildFolder(contentResolver, vault, READING_TRACE_FOLDER_NAME)) {
            is RootFolderLookup.Found -> lookup.uri
            RootFolderLookup.Absent -> return emptyList()
            RootFolderLookup.Unreadable -> return null
        }
        val crystals = when (val lookup = findChildFolder(vault, traces, CRYSTAL_FOLDER_NAME)) {
            is RootFolderLookup.Found -> lookup.uri
            RootFolderLookup.Absent -> return emptyList()
            RootFolderLookup.Unreadable -> return null
        }
        val children = querySafChildren(contentResolver, vault, DocumentsContract.getDocumentId(crystals))
        if (!children.isComplete) return null
        return children.items
            .filter { !it.isDirectory && it.name.endsWith(".json") }
            .map { child ->
                val uri = DocumentsContract.buildDocumentUriUsingTree(vault, child.documentId)
                val bytes = try {
                    contentResolver.openInputStream(uri)?.use { readBoundedBytes(it, maximumBytes) }
                } catch (_: Exception) {
                    null
                }
                CrystalDocument(child.name, bytes)
            }
    }

    @Synchronized
    override fun create(fileName: String, bytes: ByteArray, vaultKey: String): String {
        val vault = vaultUri() ?: throw IOException("Vault が選ばれていません。")
        if (vault.toString() != vaultKey) throw IOException("Vault が切り替わったので書きませんでした。")
        // **読めなかったことを不在とみなさない。** 列挙に失敗しただけで2つ目のフォルダを作る。
        val traces = when (val lookup = findRootChildFolder(contentResolver, vault, READING_TRACE_FOLDER_NAME)) {
            is RootFolderLookup.Found -> lookup.uri
            RootFolderLookup.Absent -> createRootChildFolder(contentResolver, vault, READING_TRACE_FOLDER_NAME)
                ?: throw IOException("痕跡のフォルダを作れませんでした。")
            RootFolderLookup.Unreadable -> throw IOException("Vault の一覧を読めませんでした。")
        }
        val folder = when (val lookup = findChildFolder(vault, traces, CRYSTAL_FOLDER_NAME)) {
            is RootFolderLookup.Found -> lookup.uri
            RootFolderLookup.Absent -> DocumentsContract.createDocument(
                contentResolver,
                traces,
                DocumentsContract.Document.MIME_TYPE_DIR,
                CRYSTAL_FOLDER_NAME
            ) ?: throw IOException("結晶のフォルダを作れませんでした。")
            RootFolderLookup.Unreadable -> throw IOException("痕跡のフォルダの一覧を読めませんでした。")
        }
        val created = DocumentsContract.createDocument(contentResolver, folder, MIME_TYPE_JSON, fileName)
            ?: throw IOException("結晶のファイルを作れませんでした。")
        try {
            contentResolver.openOutputStream(created, "w")?.use { output ->
                output.write(bytes)
                output.flush()
            } ?: throw IOException("結晶のファイルへ書き込めませんでした。")
        } catch (error: Exception) {
            // 書きかけは読み込みで checksum が合わず飛ばされるので、消せなくても一覧は壊れない。
            runCatching { DocumentsContract.deleteDocument(contentResolver, created) }
            throw error
        }
        return displayNameOf(created) ?: fileName
    }

    /** [parent] 直下の同名フォルダ。探索結果の型はルート直下と同じものを使う。 */
    private fun findChildFolder(vault: Uri, parent: Uri, name: String): RootFolderLookup {
        val children = querySafChildren(contentResolver, vault, DocumentsContract.getDocumentId(parent))
        if (!children.isComplete) return RootFolderLookup.Unreadable
        val match = children.items.firstOrNull { it.isDirectory && it.name == name }
            ?: return RootFolderLookup.Absent
        return RootFolderLookup.Found(DocumentsContract.buildDocumentUriUsingTree(vault, match.documentId))
    }

    private fun displayNameOf(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val CRYSTAL_FOLDER_NAME = "crystals"
        const val MIME_TYPE_JSON = "application/json"
    }
}
