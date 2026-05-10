package com.example.newproject

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class NoteFile(val name: String, val uri: Uri)

internal fun isMarkdownFile(name: String?): Boolean =
    name?.lowercase()?.endsWith(".md") == true

class NoteRepository {

    suspend fun collectNotes(contentResolver: ContentResolver, vaultUri: Uri): List<NoteFile> =
        withContext(Dispatchers.IO) {
            buildList {
                collectRecursive(contentResolver, vaultUri, DocumentsContract.getTreeDocumentId(vaultUri), this)
            }
        }

    suspend fun readNoteContent(contentResolver: ContentResolver, uri: Uri): String =
        withContext(Dispatchers.IO) {
            contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readText()
            } ?: ""
        }

    private fun collectRecursive(
        contentResolver: ContentResolver,
        treeUri: Uri,
        documentId: String,
        result: MutableList<NoteFile>
    ) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )

        contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val childId = cursor.getString(0)
                val name = cursor.getString(1)
                val mimeType = cursor.getString(2)
                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)

                when {
                    mimeType == DocumentsContract.Document.MIME_TYPE_DIR ->
                        collectRecursive(contentResolver, treeUri, childId, result)
                    isMarkdownFile(name) ->
                        result.add(NoteFile(name, childUri))
                }
            }
        }
    }
}
