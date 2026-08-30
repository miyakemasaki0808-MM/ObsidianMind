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
/**
 * 1件のドキュメントの**中身の世代**を引き直した結果。
 *
 * **境目は「参照先の存在を確かめられたか」に置く。** 引き直す目的は
 * 索引が古いかどうかを知ることなので、確かめられた場合だけ索引を信じ続けてよい。
 *
 * **「消えている」と「照会が失敗した」は分けない。** [RootFolderLookup] が
 * 両者を分けているのは、取り違えると**フォルダを二重に作る**という不可逆な副作用が
 * あるからで、こちらの副作用は「索引を1回作り直す」だけである。しかも SAF は
 * 消えたドキュメントの照会に**空のカーソルではなく例外**で答えるのが普通なので
 * （`ExternalStorageProvider` も [com.example.newproject.testing.FakeVaultDocumentsProvider] も
 * `FileNotFoundException` を投げる）、**分けても「消えている」側が実際には来ない。**
 * 行き先が同じ枝を2つ置くと、テストで区別できない分岐が増えるだけになる。
 */
sealed interface DocumentVersionLookup {
    /**
     * 参照先はある。[lastModified] が null なら**プロバイダが更新日時の列を返さない**。
     * **存在は確かめられている**ので、索引を作り直す理由にはならない
     * （ここで作り直すと、世代を返さないプロバイダでは走査が永久に繰り返される）。
     */
    data class Found(val lastModified: Long?) : DocumentVersionLookup

    /** 存在を確かめられなかった。索引が古い可能性があるので、**作り直してよい。** */
    object Unconfirmed : DocumentVersionLookup
}

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

    /**
     * Vault全体の画像を**完全性つきで**集める。
     *
     * ノートの走査（[collectAllNotes]）と同じ歩き方で受理条件だけが違う。
     * **別のパスとして持つのは寿命が違うから** — ノート一覧は Rediscover が
     * 常時使うが、画像索引は画像を含むノートを開いたときにだけ要る。
     */
    suspend fun collectImages(): VaultImageScan

    /**
     * `_AI補記` 配下の一覧。フォルダが無ければ空。
     *
     * **書き出す側はもう無い。** 「AI補記メモ」は「ノートへのひとこと」へ作り直され、
     * 保存先は読書痕跡サイドカーへ移った。この一覧は、作り直す前に生成された
     * `.md` をユーザーが片付けるためだけに残している。
     */
    suspend fun listAnnotationFiles(): List<NoteFile>

    /**
     * 1件の**先頭だけ**読む。冊子の扉（代表文1行）が使う。
     *
     * **全文を読まない。** 表示は最大1MBまで許容しているが、扉に要るのは1文で、
     * それは本文の先頭側にある。関連ノートが最大40件を並列で読むのに使っている
     * 8KBの境界読み出し（`NoteReadLimits.SNIPPET_MAX_BYTES`）をそのまま使う。
     *
     * **読めなかったら null。** 束を作った後に削除・改名されると、プロバイダによって
     * 例外が飛ぶ場合と `openInputStream()` が null を返す場合があり、**後者を空の本文へ畳むと
     * タイトルへフォールバックして「読めた」ように振る舞う**。呼び出し側はどちらも
     * 「そのページだけ失敗」として扱うこと（→ features/booklet_mode.md §10）。
     */
    suspend fun readNoteSnippet(ref: DocumentRef): String?

    /** 1件削除する。**SAFプロバイダの都合で失敗し得る**ので、結果を捨てないこと。 */
    suspend fun deleteDocument(ref: DocumentRef): Boolean

    /**
     * 1件の更新日時を引き直す。**走査の代わりに使う。**
     *
     * 走査結果をキャッシュする側は「当たった参照の中身が差し替わっていないか」を
     * 知りたくなるが、そのためにVault全走査をやり直すのは高すぎる
     * （画像索引の走査はVault全体を歩く）。当たった1件だけを照会すれば、
     * **同じ問いに桁違いに安く答えられる。**
     */
    suspend fun documentVersion(ref: DocumentRef): DocumentVersionLookup
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

    override suspend fun collectImages(): VaultImageScan =
        repository.collectImages(contentResolver, vaultUri)

    override suspend fun listAnnotationFiles(): List<NoteFile> =
        repository.listAnnotationFiles(contentResolver, vaultUri)

    override suspend fun readNoteSnippet(ref: DocumentRef): String? =
        repository.readNoteSnippetOrNull(contentResolver, ref.toUri())

    // 削除自体はVaultルートを要さないが、`ContentResolver` を呼び出し側から
    // 消すのが目的なので同じハンドルに置く。
    override suspend fun deleteDocument(ref: DocumentRef): Boolean =
        repository.deleteDocument(contentResolver, ref.toUri())

    override suspend fun documentVersion(ref: DocumentRef): DocumentVersionLookup =
        repository.queryDocumentVersion(contentResolver, ref.toUri())
}
