package com.example.newproject

import com.example.newproject.data.SavedAnnotation
import com.example.newproject.data.VaultBrowser
import com.example.newproject.data.VaultHandle
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteFile
import com.example.newproject.model.NoteFolder
import com.example.newproject.model.VaultScan

/**
 * [VaultBrowser] の差し替え。**Vault未選択は `handle = null` で表す。**
 *
 * これが書けるようになったのが `VaultBrowser` 導入の目的で、以前は
 * `vaultUri = { null }` しか渡せず「Vault未選択」の枝しか通せなかった。
 */
class FakeVaultBrowser(var handle: FakeVaultHandle? = FakeVaultHandle()) : VaultBrowser {
    /** [current] が呼ばれた回数。**「開始時に1回だけ取る」規約の検証に使う。** */
    var currentCount = 0
        private set

    override fun current(): VaultHandle? {
        currentCount++
        return handle
    }
}

/**
 * [VaultHandle] の差し替え。返す値・失敗・呼び出し回数を外から決める。
 *
 * [beforeEachCall] は**suspend 関数の中で戻り値を作る直前**に呼ぶ。
 * 「SAF から戻ってくる直前に Vault が切り替わった」状況をテストから作るための穴で、
 * 世代照合はここでしか検証できない（`cancel()` が効かない経路を見たいため）。
 */
class FakeVaultHandle(
    var folders: List<NoteFolder> = emptyList(),
    var notesByFolder: Map<String?, List<NoteFile>> = emptyMap(),
    var annotationFiles: List<NoteFile> = emptyList(),
    /** [collectAllNotes] が返すVault全体の走査結果。**完全性ごと差し替えられる。** */
    var vaultScan: VaultScan = VaultScan(emptyList()),
    /** null 以外にすると、その操作が例外を投げる。 */
    var failure: Exception? = null,
    /** [deleteDocument] の戻り値。false は「SAFプロバイダが消せなかった」を表す。 */
    var deleteSucceeds: Boolean = true,
    var beforeEachCall: () -> Unit = {}
) : VaultHandle {

    var listFoldersCount = 0
        private set
    var collectCount = 0
        private set
    var listAnnotationsCount = 0
        private set
    var collectAllCount = 0
        private set
    val deletedRefs = mutableListOf<DocumentRef>()

    override suspend fun listTopLevelFolders(): List<NoteFolder> {
        listFoldersCount++
        beforeEachCall()
        failure?.let { throw it }
        return folders
    }

    override suspend fun collectAllNotes(): VaultScan {
        collectAllCount++
        beforeEachCall()
        failure?.let { throw it }
        return vaultScan
    }

    override suspend fun collectNotesInScope(folder: NoteFolder?): List<NoteFile> {
        collectCount++
        beforeEachCall()
        failure?.let { throw it }
        return notesByFolder[folder?.documentId].orEmpty()
    }

    override suspend fun listAnnotationFiles(): List<NoteFile> {
        listAnnotationsCount++
        beforeEachCall()
        failure?.let { throw it }
        return annotationFiles
    }

    override suspend fun createAnnotationFile(
        sanitizedTitle: String,
        timestamp: String,
        content: String
    ): SavedAnnotation {
        beforeEachCall()
        failure?.let { throw it }
        return SavedAnnotation(
            ref = DocumentRef("content://fake/$sanitizedTitle$timestamp"),
            displayName = "$sanitizedTitle$timestamp.md"
        )
    }

    override suspend fun deleteDocument(ref: DocumentRef): Boolean {
        deletedRefs += ref
        beforeEachCall()
        failure?.let { throw it }
        return deleteSucceeds
    }
}

/** テスト用のノート。`DocumentRef` になったので `Uri` 無しで作れる。 */
fun noteFile(name: String, ref: String = "content://fake/$name"): NoteFile =
    NoteFile(name = name, ref = DocumentRef(ref))
