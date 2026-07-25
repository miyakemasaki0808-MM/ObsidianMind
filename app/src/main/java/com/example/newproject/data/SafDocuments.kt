package com.example.newproject.data

import com.example.newproject.model.ReadingTrace
import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract

// ---------------------------------------------------------------------------
// SAF（Storage Access Framework）の低レベル操作。NoteRepository と
// ReadingTrace のサイドカー保存で共有する（同じ形を2度書かないため）。
// ---------------------------------------------------------------------------

/** 指定ドキュメント直下の子を列挙する（散在していたカーソルループの共通化）。 */
internal fun querySafChildren(
    contentResolver: ContentResolver,
    vaultUri: Uri,
    documentId: String
): List<ChildDoc> {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(vaultUri, documentId)
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED
    )
    val result = mutableListOf<ChildDoc>()
    contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
        while (cursor.moveToNext()) {
            result.add(
                ChildDoc(
                    documentId = cursor.getString(0),
                    name = cursor.getString(1),
                    isDirectory = cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                    lastModified = if (cursor.isNull(3)) null else cursor.getLong(3)
                )
            )
        }
    }
    return result
}

/**
 * Vault ルート直下にある同名フォルダを返す。無ければ null。
 * 機能フォルダ（`_AI補記` / `_ReadingTraces`）はいずれもルート直下に作る仕様なので
 * Vault全体をBFSせずルート直下だけを見る（以前は保存・一覧のたびに全走査していた）。
 */
internal fun findRootChildFolder(
    contentResolver: ContentResolver,
    vaultUri: Uri,
    folderName: String
): Uri? =
    querySafChildren(contentResolver, vaultUri, DocumentsContract.getTreeDocumentId(vaultUri))
        .firstOrNull { it.isDirectory && it.name == folderName }
        ?.let { DocumentsContract.buildDocumentUriUsingTree(vaultUri, it.documentId) }

/** Vault ルート直下にフォルダを作る。作成できなければ null。 */
internal fun createRootChildFolder(
    contentResolver: ContentResolver,
    vaultUri: Uri,
    folderName: String
): Uri? {
    val rootDocumentId = DocumentsContract.getTreeDocumentId(vaultUri)
    val rootDocumentUri = DocumentsContract.buildDocumentUriUsingTree(vaultUri, rootDocumentId)
    return DocumentsContract.createDocument(
        contentResolver,
        rootDocumentUri,
        DocumentsContract.Document.MIME_TYPE_DIR,
        folderName
    )
}
