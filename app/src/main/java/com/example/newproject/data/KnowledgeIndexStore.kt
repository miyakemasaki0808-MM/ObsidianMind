package com.example.newproject.data

import com.example.newproject.domain.IndexedLink
import com.example.newproject.domain.IndexedNoteState

/**
 * 知識索引の置き場（→ docs/dev/system/knowledge_index.md）。**Room を知らない口**で、`controller` はこれだけを触る。
 *
 * **索引を正にしない。** 置くのは Vault から作り直せる派生物だけで、消えてもノートは無傷（判断1）。
 * どの書き込みも [vaultKey] を受け、索引がその Vault のものでなければ何もしない —
 * Vault を替えた後に旧い Vault の作業が書き込むのを、置き場の側でも止める。
 */
internal interface KnowledgeIndexStore {
    /** [vaultKey] の索引を開く。**別の Vault の索引なら全部消して作り直す**（判断3）。 */
    suspend fun openVault(vaultKey: String): KnowledgeIndexSnapshot

    /**
     * アプリが本文を書き換えたと控え、書き換えの番号を進める（§3 の手順4）。**控えは参照そのもので持つ** —
     * 索引に無いノートでも控えられ、初めて読んでいる途中の書き換えを番号の照合で止められる。
     */
    suspend fun markRewritten(vaultKey: String, documentRef: String)

    /** もう読むことの無い参照の控えを外す（読み切れた走査に無い参照）。 */
    suspend fun forgetMarks(vaultKey: String, documentRefs: List<String>)

    /**
     * 読んだ結果で、そのノートの行とリンクを入れ替える。**[writeNumberAtRead] が今もそのパスの最新のときだけ書き、
     * 書いたら控えも同じトランザクションで外す。** 書かなければ false で、控えは残る（§3）。
     */
    suspend fun applyRead(
        vaultKey: String,
        note: IndexedNoteRecord,
        links: List<IndexedLink>,
        writeNumberAtRead: Long?
    ): Boolean

    /** ノートの行・リンク・控えを消す。 */
    suspend fun deleteNotes(vaultKey: String, relativePaths: List<String>)

    /** 最初の作成を終えたと控える。 */
    suspend fun markFirstBuildDone(vaultKey: String)
}

/**
 * 開いた索引の、突き合わせに要る分。[writeNumbers] はアプリが書き換えたと控えてある**参照**と、その書き換えの番号。
 */
internal data class KnowledgeIndexSnapshot(
    val notes: Map<String, IndexedNoteState>,
    val writeNumbers: Map<String, Long>,
    val firstBuildDone: Boolean
)

/** 索引に入れる1ノート。[documentRef] は端末内の参照で、可搬の鍵は [relativePath]。 */
internal data class IndexedNoteRecord(
    val relativePath: String,
    val documentRef: String,
    val name: String,
    val lastModified: Long?,
    val fingerprint: String
)
