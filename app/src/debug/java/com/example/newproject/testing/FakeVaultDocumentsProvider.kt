package com.example.newproject.testing

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/**
 * instrumentation 用の Vault。**実物のSAF経路をそのまま通すために置く。**
 *
 * ## なぜ `androidTest` ではなく `src/debug/` なのか
 *
 * `androidTest` 側に置くと**別APK・別UID**になり、`DocumentsContract` の tree URI を
 * 使うたびに URI 権限の付与が要る。debug ソースセットならアプリ本体と同じUIDなので、
 * [treeUri] をそのまま `NoteRepository` へ渡せる。**release には入らない。**
 *
 * ## 何のために要るか
 *
 * `NoteRepository` の走査は「ノートが無い」と「そのフォルダを読めなかった」を
 * 区別する契約になっている（→ `VaultScan.unreadableFolderPaths`）。痕跡の孤児判定が
 * 不在を根拠に**削除する**ため、この区別が崩れると生きた痕跡を消す。
 * 実物のSAFでは読取失敗を意図的に起こせないので、ここで注入できるようにする。
 *
 * ## 使い方
 *
 * テストから [reset] で木を組み、[treeUri] を `SafVaultBrowser` へ渡す。
 * 状態は `companion` に置く — `DocumentsProvider` の生成はシステムが行い、
 * テストからインスタンスを掴めないため。
 */
class FakeVaultDocumentsProvider : DocumentsProvider() {

    override fun onCreate(): Boolean {
        context?.let { ensureCacheRoot(it.cacheDir) }
        return true
    }

    override fun queryRoots(projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: arrayOf(DocumentsContract.Root.COLUMN_ROOT_ID))

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val node = nodes[documentId] ?: throw FileNotFoundException(documentId)
        return cursorOf(projection).also { it.addNode(node) }
    }

    /**
     * **読めないフォルダは null を返す。** `querySafChildren` は
     * `contentResolver.query(...) ?: SafChildren.UNREADABLE` なので、
     * これが「列挙に失敗した」の実物の形になる（空リストとは別物）。
     */
    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        if (parentDocumentId in unreadableFolderIds) return null
        val node = nodes[parentDocumentId] ?: throw FileNotFoundException(parentDocumentId)
        val cursor = cursorOf(projection)
        node.childIds.mapNotNull { nodes[it] }.forEach { cursor.addNode(it) }
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val node = nodes[documentId] ?: throw FileNotFoundException(documentId)
        val file = fileOf(node.id)
        val flags = if (mode.contains("w")) {
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE
        } else {
            ParcelFileDescriptor.MODE_READ_ONLY
        }
        return ParcelFileDescriptor.open(file, flags)
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = nodes[parentDocumentId] ?: throw FileNotFoundException(parentDocumentId)
        // 同名があれば連番を足す。**実プロバイダが名前を変える**ことの再現でもある
        // （SavedAnnotation が「保存後の実名」を返す契約はこれが理由）。
        val name = uniqueNameUnder(parent, displayName)
        val id = "$parentDocumentId/$name"
        val node = Node(
            id = id,
            name = name,
            mimeType = mimeType,
            lastModified = clock,
            childIds = mutableListOf()
        )
        nodes[id] = node
        parent.childIds.add(id)
        if (mimeType != DocumentsContract.Document.MIME_TYPE_DIR) fileOf(node.id).writeBytes(ByteArray(0))
        return id
    }

    override fun deleteDocument(documentId: String) {
        val node = nodes.remove(documentId) ?: throw FileNotFoundException(documentId)
        nodes.values.forEach { it.childIds.remove(documentId) }
        node.childIds.toList().forEach { runCatching { deleteDocument(it) } }
        runCatching { fileOf(node.id).delete() }
    }

    /** tree URI 配下の入れ子を触るには必須。無いと `enforceTree` が弾く。 */
    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId == parentDocumentId || documentId.startsWith("$parentDocumentId/")

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: android.graphics.Point?,
        signal: CancellationSignal?
    ): AssetFileDescriptor? = null

    // --- 内部 -----------------------------------------------------------------

    private fun cursorOf(projection: Array<out String>?) =
        MatrixCursor(projection ?: DEFAULT_PROJECTION)

    /** 要求された projection の**順序どおり**に詰める（読み出し側は index で取る）。 */
    private fun MatrixCursor.addNode(node: Node) {
        val row = newRow()
        columnNames.forEach { column ->
            row.add(
                when (column) {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID -> node.id
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME -> node.name
                    DocumentsContract.Document.COLUMN_MIME_TYPE -> node.mimeType
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED -> node.lastModified
                    DocumentsContract.Document.COLUMN_SIZE -> when {
                        node.isDirectory -> null
                        // **サイズを返さないプロバイダの再現。** 実在するので、
                        // 上限の強制がメタデータ照会だけに頼っていないかを試せる。
                        node.id in sizelessIds -> null
                        else -> fileOf(node.id).length()
                    }
                    DocumentsContract.Document.COLUMN_FLAGS -> node.flags
                    else -> null
                }
            )
        }
    }

    private fun uniqueNameUnder(parent: Node, displayName: String): String {
        val taken = parent.childIds.mapNotNull { nodes[it]?.name }.toSet()
        if (displayName !in taken) return displayName
        val stem = displayName.substringBeforeLast('.', displayName)
        val extension = displayName.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var index = 1
        while ("$stem ($index)$extension" in taken) index++
        return "$stem ($index)$extension"
    }

    private class Node(
        val id: String,
        val name: String,
        val mimeType: String,
        val lastModified: Long,
        val childIds: MutableList<String>
    ) {
        val isDirectory get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
        val flags: Int
            get() = if (isDirectory) {
                DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE or
                    DocumentsContract.Document.FLAG_SUPPORTS_DELETE
            } else {
                DocumentsContract.Document.FLAG_SUPPORTS_WRITE or
                    DocumentsContract.Document.FLAG_SUPPORTS_DELETE
            }
    }

    companion object {
        const val AUTHORITY = "com.example.newproject.debug.fakevault"
        const val ROOT_ID = "root"

        private val DEFAULT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_FLAGS
        )

        private val nodes = linkedMapOf<String, Node>()
        private val unreadableFolderIds = mutableSetOf<String>()
        private val sizelessIds = mutableSetOf<String>()
        private var clock = 1_000L

        /** Vault ルートを指す tree URI。`SafVaultBrowser` へそのまま渡せる。 */
        val treeUri: Uri get() = DocumentsContract.buildTreeDocumentUri(AUTHORITY, ROOT_ID)

        /** ルートだけの空Vaultへ戻す。**各テストの冒頭で呼ぶ。** */
        fun reset() {
            nodes.clear()
            unreadableFolderIds.clear()
            sizelessIds.clear()
            clock = 1_000L
            nodes[ROOT_ID] = Node(
                id = ROOT_ID,
                name = "Vault",
                mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                lastModified = clock,
                childIds = mutableListOf()
            )
        }

        /** `ideas/2026/habit.md` のように、途中のフォルダごと作る。 */
        fun putFile(vaultRelativePath: String, content: String = "", lastModified: Long? = null) {
            putBinaryFile(vaultRelativePath, content.toByteArray(), lastModified)
        }

        /** 画像など、文字列で表せない中身を置く。 */
        fun putBinaryFile(
            vaultRelativePath: String,
            bytes: ByteArray,
            lastModified: Long? = null,
            reportSize: Boolean = true
        ) {
            val segments = vaultRelativePath.split('/')
            var parentId = ROOT_ID
            segments.dropLast(1).forEach { parentId = ensureFolder(parentId, it) }
            val name = segments.last()
            val id = "$parentId/$name"
            val node = Node(
                id = id,
                name = name,
                mimeType = mimeTypeOf(name),
                lastModified = lastModified ?: clock++,
                childIds = mutableListOf()
            )
            nodes[id] = node
            nodes[parentId]?.childIds?.add(id)
            fileOf(id).writeBytes(bytes)
            // **サイズを申告しないプロバイダは実在する。** 上限の強制が
            // メタデータ照会だけに頼っていないかを試すために切り替えられるようにする。
            if (reportSize) sizelessIds.remove(id) else sizelessIds.add(id)
        }

        fun putFolder(vaultRelativePath: String) {
            var parentId = ROOT_ID
            vaultRelativePath.split('/').forEach { parentId = ensureFolder(parentId, it) }
        }

        /**
         * そのフォルダの子の列挙を失敗させる。
         *
         * ルートを失敗させたいときは [ROOT_ID] を渡す（Vault全体が読めない状態）。
         */
        fun makeUnreadable(vaultRelativePath: String) {
            unreadableFolderIds += if (vaultRelativePath.isEmpty()) ROOT_ID else "$ROOT_ID/$vaultRelativePath"
        }

        private fun ensureFolder(parentId: String, name: String): String {
            val id = "$parentId/$name"
            if (nodes[id] == null) {
                nodes[id] = Node(
                    id = id,
                    name = name,
                    mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
                    lastModified = clock++,
                    childIds = mutableListOf()
                )
                nodes[parentId]?.childIds?.add(id)
            }
            return id
        }

        private fun mimeTypeOf(name: String) = when {
            name.endsWith(".md", ignoreCase = true) -> "text/markdown"
            name.endsWith(".png", ignoreCase = true) -> "image/png"
            name.endsWith(".jpg", ignoreCase = true) -> "image/jpeg"
            name.endsWith(".bmp", ignoreCase = true) -> "image/bmp"
            else -> "application/octet-stream"
        }

        private fun fileOf(id: String): File {
            val root = requireNotNull(cacheRootHolder) {
                "cacheRootHolder が未設定。テストの @Before で cacheDir を渡すこと。"
            }
            val dir = File(root, "fake-vault")
            dir.mkdirs()
            return File(dir, id.replace('/', '_'))
        }

        /**
         * 中身の置き場。**provider の生成前にも [putFile] が呼ばれ得る**ので
         * `context` に頼らず外から渡す（テストの `@Before` で設定する）。
         */
        var cacheRootHolder: File? = null

        /** provider 側からの保険。テストが先に設定していればそちらを尊重する。 */
        private fun ensureCacheRoot(dir: File) {
            if (cacheRootHolder == null) cacheRootHolder = dir
        }
    }
}
