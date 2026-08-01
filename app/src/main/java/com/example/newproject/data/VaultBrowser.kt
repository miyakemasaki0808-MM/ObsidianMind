package com.example.newproject.data

import android.content.ContentResolver
import android.net.Uri
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteFile
import com.example.newproject.model.NoteFolder
import com.example.newproject.model.VaultScan

/**
 * さがす／補記が使う Vault スコープの操作。**`ContentResolver` と Vault ルートは実装が束ねる。**
 *
 * ## なぜ要るか
 *
 * `SearchController` と `AnnotationController` は、以前は
 * `repository`・`vaultUri: () -> Uri?`・各メソッド引数の `contentResolver` の
 * 3つを受け取っていた。この3つが揃うと**素のJVMテストで happy path を1本も書けない**:
 *
 * - `vaultUri()` は非nullの `Uri` を返す必要があるが、ユニットテストの `Uri` はスタブで例外を投げる
 * - [NoteRepository] は具象クラスで差し替え口が無く、実物がSAFを叩きにいく
 * - `ContentResolver` は素のJVMで作れない
 *
 * 実際、既存のテストは全て `vaultUri = { null }` を渡して即 return させており、
 * 「Vault未選択」の枝しか通っていなかった。世代照合や走査キャッシュは実機確認だけが担保で、
 * `NoteSessionCoordinatorTest` に至っては private フィールドへリフレクションで番兵を積んでいた。
 *
 * **3つは同時に外すしかない。** どれか1つを残すと、そこで詰まって何も検証できない。
 *
 * ## 先例
 *
 * 蒸留（[DistillPersistence] ＋ `SafDistillDocumentGateway`）と
 * 読書痕跡（[ReadingTracePersistence] ＋ [SafReadingTraceDocumentGateway]）は
 * 既にこの形で、どちらも `ContentResolver` を構築時に束ねているから Fake を書ける。
 * ここはその2つと同じ形をさがす／補記へ広げたもので、新しい発明ではない。
 *
 * ## 「Vault未選択」の扱いはここでは決めない
 *
 * [current] が null を返すところまでが本インターフェースの責任で、そのとき何を出すかは
 * 呼び出し側が決める。実際に挙動が違う — さがすは黙って何もせず、補記は
 * 「Vault が選択されていません。」を状態に出す。ここへ押し込むとその差が消える。
 */
interface VaultBrowser {
    /** 選択中のVaultへの操作口。**未選択なら null。** */
    fun current(): VaultHandle?
}

/**
 * 選択中Vaultに束縛された操作。
 *
 * **ハンドルは処理の開始時に1回だけ取り、その1つを最後まで使う。**
 * 途中で [VaultBrowser.current] を引き直すと、Vault切替をまたいだときに
 * 「照合は旧Vault・書き込みは新Vault」という食い違いが起こり得る。
 * 走行中に切り替わった場合は、呼び出し側が持つ Vault世代（`vaultGeneration`）で弾く。
 * これは [ReadingTraceStore] が既に採っている規約と同じ。
 */
interface VaultHandle {
    /** Vault 第一階層のフォルダ（名前昇順・`_ReadingTraces` を除く）。 */
    suspend fun listTopLevelFolders(): List<NoteFolder>

    /** 検索スコープ配下の `.md`。[folder] が null ならルート直下のみ（非再帰）。 */
    suspend fun collectNotesInScope(folder: NoteFolder?): List<NoteFile>

    /**
     * Vault全体の `.md` を**完全性つきで**集める。
     *
     * 痕跡の孤児判定はノートの**不在**を根拠にするため、「見つからなかった」と
     * 「そのフォルダを読めなかった」を必ず区別する必要がある
     * （→ [VaultScan.unreadableFolderPaths]）。表示系が使う
     * [collectNotesInScope] と違い、ここでは完全性を捨ててはいけない。
     */
    suspend fun collectAllNotes(): VaultScan

    /** `_AI補記` 配下の一覧。フォルダが無ければ空。 */
    suspend fun listAnnotationFiles(): List<NoteFile>

    /** `_AI補記` へ1件書き出す。失敗時は例外。 */
    suspend fun createAnnotationFile(
        sanitizedTitle: String,
        timestamp: String,
        content: String
    ): SavedAnnotation

    /** 1件削除する。**SAFプロバイダの都合で失敗し得る**ので、結果を捨てないこと。 */
    suspend fun deleteDocument(ref: DocumentRef): Boolean
}

/** 本番実装。`ContentResolver` と [NoteRepository] を束ね、Vaultは呼ばれるたびに引き直す。 */
internal class SafVaultBrowser(
    private val contentResolver: ContentResolver,
    private val repository: NoteRepository,
    private val vaultUri: () -> Uri?
) : VaultBrowser {
    override fun current(): VaultHandle? =
        vaultUri()?.let { SafVaultHandle(contentResolver, repository, it) }
}

private class SafVaultHandle(
    private val contentResolver: ContentResolver,
    private val repository: NoteRepository,
    private val vaultUri: Uri
) : VaultHandle {

    override suspend fun listTopLevelFolders(): List<NoteFolder> =
        repository.listTopLevelFolders(contentResolver, vaultUri)

    override suspend fun collectNotesInScope(folder: NoteFolder?): List<NoteFile> =
        repository.collectNotesInScope(contentResolver, vaultUri, folder)

    override suspend fun collectAllNotes(): VaultScan =
        repository.collectNotes(contentResolver, vaultUri)

    override suspend fun listAnnotationFiles(): List<NoteFile> =
        repository.listAnnotationFiles(contentResolver, vaultUri)

    override suspend fun createAnnotationFile(
        sanitizedTitle: String,
        timestamp: String,
        content: String
    ): SavedAnnotation = repository.createAnnotationFile(
        contentResolver = contentResolver,
        vaultUri = vaultUri,
        sanitizedTitle = sanitizedTitle,
        timestamp = timestamp,
        content = content
    )

    // 削除自体はVaultルートを要さないが、`ContentResolver` を呼び出し側から
    // 消すのが目的なので同じハンドルに置く。
    override suspend fun deleteDocument(ref: DocumentRef): Boolean =
        repository.deleteDocument(contentResolver, ref.toUri())
}
