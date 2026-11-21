package com.example.newproject.data

import com.example.newproject.domain.BacklinkCandidate
import com.example.newproject.domain.IndexedLink
import com.example.newproject.domain.IndexedNoteState
import com.example.newproject.domain.NoteLink

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
     *
     * **書いたスレッド（I/O）から同期で呼び、永続してから戻る。** 画面のスコープへ渡すと、書いている途中に ViewModel が
     * 破棄されたとき控えが作られない。Vault の照合はしない — 参照は Vault ごとに違うので、前の Vault の控えは今の Vault の
     * ノートに当たらず、読み切れた走査で外れる。索引をまだ開いていなければ何もしない（開けば全部を読む）。Main からは呼ばない。
     */
    fun markRewrittenNow(documentRef: String)

    /** もう読むことの無い参照の控えを外す（読み切れた走査に無い参照）。 */
    suspend fun forgetMarks(vaultKey: String, documentRefs: List<String>)

    /**
     * 読んだ結果で、そのノートの行とリンクを入れ替える。**[writeNumberAtRead] が今もその参照の最新のときだけ書く。**
     * 書かなければ false。**控えはここでは外さない** — 控えは参照の単位なので、同じ参照のパスを全部書き終えてから [clearMark] で外す（§3）。
     */
    suspend fun applyRead(
        vaultKey: String,
        note: IndexedNoteRecord,
        links: List<IndexedLink>,
        writeNumberAtRead: Long?
    ): Boolean

    /** ノートの行とリンクを消す。**控えは消さない**（参照を保ったまま名前を変えたノートは新しいパスで読み直す）。 */
    suspend fun deleteNotes(vaultKey: String, relativePaths: List<String>)

    /** 控えを外す。番号が [writeNumber] のままのときだけ（途中で書き換えて番号が進んでいたら、新しい控えを残す）。 */
    suspend fun clearMark(vaultKey: String, documentRef: String, writeNumber: Long)

    /** 最初の作成を終えたと控える。 */
    suspend fun markFirstBuildDone(vaultKey: String)

    /**
     * 名前の鍵が [nameKey] のリンク（今のノートを指しうる）と、[relativePath] の今のノートから出るリンクを、**1つの読み取りで一組に**引く
     * （→ docs/dev/features/backlinks.md §7・判断5）。行き先はまだ解いていない — 引く側が走査の索引で解く。
     * **別々に引かない** — 間に索引の突き合わせが入ると、どの時点の索引にも無い組み合わせになり、互いのリンクの相手が出る。
     * 索引が別の Vault のものなら null。**索引がそのノートをまだ読んでいなければ出るリンクは null** —
     * 空にすると「出るリンクが無い」と区別できず、本文がリンクしている相手を互いのリンクとして除けない。
     */
    suspend fun backlinkLinks(vaultKey: String, nameKey: String, relativePath: String): BacklinkLinks?
}

/**
 * 今のノートを指しうるリンクと、今のノートから出るリンクの一組（→ [KnowledgeIndexStore.backlinkLinks]）。
 * [outgoing] は、索引が今のノートをまだ読んでいなければ null（読んでいてリンクが無ければ空）。
 */
internal data class BacklinkLinks(val candidates: List<BacklinkCandidate>, val outgoing: List<NoteLink>?)

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
