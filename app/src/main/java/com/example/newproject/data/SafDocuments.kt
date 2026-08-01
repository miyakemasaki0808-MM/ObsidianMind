package com.example.newproject.data

import com.example.newproject.model.DocumentRef
import com.example.newproject.model.ReadingTrace
import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.net.toUri

// ---------------------------------------------------------------------------
// SAF（Storage Access Framework）の低レベル操作。NoteRepository と
// ReadingTrace のサイドカー保存で共有する（同じ形を2度書かないため）。
// ---------------------------------------------------------------------------

// --- DocumentRef ⇄ Uri -----------------------------------------------------
//
// **この2つが `Uri` と不透明参照のあいだの唯一の通り道。** 変換を `data` に閉じる
// ことで、`model` / `domain` / `ui` は `Uri` を知らずに済む（`ui` と `domain` は
// パッケージ依存の規約上そもそも `data` を import できないので、ここへ置くこと自体が
// 「上位層で勝手に変換されない」保証になっている）。
//
// 変換関数を `model` 側へ置かないのも同じ理由で、置いた瞬間に `model` が
// `android.net.Uri` を知ることになり、葉である前提が壊れる。

/** SAFの実体参照を不透明参照へ包む。**この向きの生成は `data` の中だけで行う。** */
internal fun Uri.toDocumentRef(): DocumentRef = DocumentRef(toString())

/** 不透明参照をSAFの実体へ戻す。`data` がI/Oを行う直前だけで使う。 */
internal fun DocumentRef.toUri(): Uri = value.toUri()

/**
 * 指定ドキュメント直下の子を列挙する（散在していたカーソルループの共通化）。
 *
 * **`query()` が null を返したら [SafChildren.UNREADABLE] を返す。**
 * 以前はここで `?.use { }` と書いて null を素通りさせ、空リストを返していた。
 * その形だと「読めなかった」と「本当に空」が呼び出し側で区別できず、
 * 走査は成功したまま配下のノートが静かに0件になっていた
 * （＝Rediscoverの母集団と検索結果が理由なく減り、痕跡フォルダでは重複ファイルを作る）。
 *
 * 例外はここでは畳まない。投げられれば走査ごと失敗するので、
 * 「成功したように見えて欠けている」状態にはならない。
 */
internal fun querySafChildren(
    contentResolver: ContentResolver,
    vaultUri: Uri,
    documentId: String
): SafChildren {
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(vaultUri, documentId)
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED
    )
    val cursor = contentResolver.query(childrenUri, projection, null, null, null)
        ?: return SafChildren.UNREADABLE
    val result = mutableListOf<ChildDoc>()
    cursor.use {
        while (it.moveToNext()) {
            result.add(
                ChildDoc(
                    documentId = it.getString(0),
                    name = it.getString(1),
                    isDirectory = it.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
                    lastModified = if (it.isNull(3)) null else it.getLong(3)
                )
            )
        }
    }
    return SafChildren.complete(result)
}

/**
 * ルート直下フォルダの探索結果。
 *
 * **[Absent] と [Unreadable] を分けるのが要点。** 以前は両方 null で返していたため、
 * 呼び出し側の `findRootChildFolder(...) ?: createRootChildFolder(...)` が
 * **ルート列挙に失敗しただけで2つ目の `_ReadingTraces` を作りうる**状態だった。
 */
internal sealed interface RootFolderLookup {
    data class Found(val uri: Uri) : RootFolderLookup

    /** ルートは読めたが同名フォルダは無い。**作成してよい。** */
    object Absent : RootFolderLookup

    /** ルートの列挙に失敗した。**作成してはいけない**（重複フォルダを作る）。 */
    object Unreadable : RootFolderLookup
}

/**
 * Vault ルート直下にある同名フォルダを探す。
 * 機能フォルダ（`_AI補記` / `_ReadingTraces`）はいずれもルート直下に作る仕様なので
 * Vault全体をBFSせずルート直下だけを見る（以前は保存・一覧のたびに全走査していた）。
 */
internal fun findRootChildFolder(
    contentResolver: ContentResolver,
    vaultUri: Uri,
    folderName: String
): RootFolderLookup {
    val children =
        querySafChildren(contentResolver, vaultUri, DocumentsContract.getTreeDocumentId(vaultUri))
    if (!children.isComplete) return RootFolderLookup.Unreadable
    val match = children.items.firstOrNull { it.isDirectory && it.name == folderName }
        ?: return RootFolderLookup.Absent
    return RootFolderLookup.Found(
        DocumentsContract.buildDocumentUriUsingTree(vaultUri, match.documentId)
    )
}

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
