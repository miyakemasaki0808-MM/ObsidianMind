package com.example.newproject.data

import kotlinx.coroutines.CancellationException

/**
 * `_AI補記` へ1ファイル書き出す手順（作成 → 書込 → 検証 → 失敗時の後始末）だけを持つ。
 *
 * ## なぜ切り出したか
 *
 * 以前は [NoteRepository.createAnnotationFile] が `createDocument()` の直後に
 * 対象URIへ書き込んでおり、**ストリームを開けない・途中で例外になった場合の後始末が無かった**。
 * 画面には「生成に失敗しました」と出るのに、`_AI補記` には空または途中までのファイルが残る。
 * 蒸留のような重い復旧機構は要らないが、失敗したなら痕跡を残さないのが筋である。
 *
 * SAF（`ContentResolver` / `DocumentsContract`）は素のJVMテストで動かないため、
 * 必要な4操作だけを [AnnotationDocumentGateway] へ寄せ、**手順の側をAndroid非依存にした**。
 * 失敗注入テストが書けるのはこの分離による（読書痕跡の
 * [ReadingTraceDocumentGateway] と同じ形）。
 *
 * 参照（[AnnotationDocumentGateway] が返す文字列）を `Uri` にしないのも同じ理由で、
 * ここでは**中身を解釈しない不透明な参照**として扱う。
 */
internal class AnnotationFileWriter(private val gateway: AnnotationDocumentGateway) {

    /**
     * [fileName] で1件作って [content] を書き、読み直して一致を確かめる。
     *
     * どの段階で失敗しても、作成済みのファイルを削除してから失敗を返す。
     * **削除は成功するとは限らない**（SAFプロバイダ側の都合で失敗し得る）ので、
     * 消せなかったときは失敗メッセージに「残った可能性」を添える。
     *
     * 削除の失敗で例外を投げないのは、元の失敗理由が後始末の失敗にすり替わって
     * 原因が分からなくなるため。**握りつぶすのではなく、元の理由に足して伝える。**
     */
    fun create(fileName: String, content: String): AnnotationWriteResult {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val reference = gateway.createFile(fileName)
            // ここだけはまだ何も作れていないので、後始末も残骸も無い。
            ?: return AnnotationWriteResult.Failure("補記メモファイルを作成できませんでした。")

        try {
            gateway.write(reference, bytes)
        } catch (error: CancellationException) {
            // 中断は失敗ではないので画面には出ない。作りかけの削除だけ試みる
            // （消せなかった場合に伝える先が無いのはこの経路の限界で、
            // 残骸は補記管理画面から手動で消せる）。
            gateway.delete(reference)
            throw error
        } catch (error: Exception) {
            return failure(
                reason = error.message ?: "補記メモファイルを書き込めませんでした。",
                cleanedUp = gateway.delete(reference)
            )
        }

        // 書けたつもりで空・部分ファイルになっていないかを1度だけ読み直して確かめる。
        // 補記はAI出力（256トークン上限）なので、全文比較しても現実的なコストに収まる。
        val written = gateway.readBack(reference)
        if (written == null || !written.contentEquals(bytes)) {
            return failure(
                reason = "補記メモを保存できましたが、内容を確認できませんでした。",
                cleanedUp = gateway.delete(reference)
            )
        }

        // 表示名は保存後のメタデータから取る。同じ分に再生成すると
        // プロバイダが改名することがあり、予測した名前と実名がずれるため。
        return AnnotationWriteResult.Success(
            reference = reference,
            displayName = gateway.displayName(reference) ?: fileName
        )
    }

    private fun failure(reason: String, cleanedUp: Boolean) = AnnotationWriteResult.Failure(
        message = if (cleanedUp) reason else reason + RESIDUE_NOTICE,
        residueLeft = !cleanedUp
    )

    private companion object {
        /**
         * 後始末に失敗したときだけ足す。**黙って残すと、利用者は
         * `_AI補記` の空ファイルに気づけない**（一覧には並ぶので削除はできる）。
         */
        const val RESIDUE_NOTICE = "（不完全なファイルが _AI補記 に残った可能性があります）"
    }
}

internal sealed class AnnotationWriteResult {
    data class Success(val reference: String, val displayName: String) : AnnotationWriteResult()

    /** [residueLeft] が真なら、`_AI補記` に消せなかったファイルが残っている。 */
    data class Failure(val message: String, val residueLeft: Boolean = false) : AnnotationWriteResult()
}

/**
 * 補記1件の書き出しに要るSAF操作だけを切り出した境界。
 * [AnnotationFileWriter] から見た参照は不透明な文字列で、SAF実装が `Uri` へ解決する。
 */
internal interface AnnotationDocumentGateway {
    /** 置き場を確保して空ファイルを作る。作れなければ null。 */
    fun createFile(fileName: String): String?

    /** 本文を書き込む。開けない・途中で失敗したら例外を投げる。 */
    fun write(reference: String, bytes: ByteArray)

    /** 書き込み後の内容を読み直す。読めなければ null。 */
    fun readBack(reference: String): ByteArray?

    /**
     * 後始末の削除。**例外は投げず、消せたかどうかを返す。**
     * 例外にすると元の失敗理由がすり替わり、戻り値を捨てると残骸に誰も気づけない。
     */
    fun delete(reference: String): Boolean

    /** 保存後の実際の表示名。取れなければ null。 */
    fun displayName(reference: String): String?
}
