package com.example.newproject

import com.example.newproject.domain.NoteLinkIndex
import com.example.newproject.model.NoteFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Vault全体のノート一覧のTTL付きキャッシュ。ランダム表示・冊子・関連ノート・痕跡のパス解決・結晶の一覧が共有する。
 *
 * **走査の結果を公開する直前に、Vaultの世代を照合する。** 走査を始めた後にVaultが切り替わっていたら、
 * 結果を捨てて [CancellationException] で呼び出し側を止める。旧Vaultの一覧がキャッシュ・開けるパス・
 * 分野の索引へ戻ると、切替後のVaultで旧Vaultのノートを開く。**呼び出し側の Job が取り消されることに頼らない** —
 * 取り消されない場所から走査を呼んでも、旧い結果は公開しない。`vaultUri` の比較で代用しないのは、
 * A→B→A と選び直したときに同じ値になって旧い走査が素通りするため（→ architecture.md 判断4）。
 *
 * Android の型に触れないので、素のJVMで交錯を組み立てて確かめられる（`NoteScanCacheTest`）。
 */
internal class NoteScanCache(
    private val ttlMillis: Long,
    private val vaultGeneration: () -> Long,
    private val clock: () -> Long = System::currentTimeMillis
) {
    /** いまのキャッシュ。空ならまだ走査していない。 */
    var notes: List<NoteFile> = emptyList()
        private set

    /**
     * 本文のリンクを引くための索引（→ features/note_links.md §6）。[notes] と同じ時点で替わる。
     * **捨てるのは Vault 切替（[clear]）だけ** — 本文の書き換え（[expireAfterBodyWrite]）では残す。
     */
    var linkIndex: NoteLinkIndex = NoteLinkIndex.EMPTY
        private set

    private var loadedAt = 0L

    private val mutableKnownPaths = MutableStateFlow<Set<String>>(emptySet())

    /** いまのキャッシュにあるノートの相対パス。結晶の一覧で、根拠のノート名を押せるかを決める。 */
    val knownPaths: StateFlow<Set<String>> = mutableKnownPaths.asStateFlow()

    /**
     * TTL内ならキャッシュを返し、切れていれば [scan] で走査して公開する。
     * [onPublished] は公開した一覧を受け取る（分野の索引へ載せるため）。**旧Vaultの結果では呼ばない。**
     */
    suspend fun get(
        scan: suspend () -> List<NoteFile>,
        onPublished: (List<NoteFile>) -> Unit
    ): List<NoteFile> {
        val now = clock()
        if (notes.isNotEmpty() && now - loadedAt < ttlMillis) return notes
        val generation = vaultGeneration()
        val scanned = scan()
        if (generation != vaultGeneration()) {
            throw CancellationException("走査の間に Vault が切り替わったので、結果を捨てました。")
        }
        notes = scanned
        linkIndex = NoteLinkIndex(scanned)
        loadedAt = now
        mutableKnownPaths.value = scanned.mapNotNullTo(HashSet()) { note ->
            note.vaultRelativePath.takeIf { it.isNotEmpty() }
        }
        onPublished(scanned)
        return scanned
    }

    /** 捨てる。Vault切替で呼ぶ。リンクの索引も捨てる — 前の Vault の参照を返さない。 */
    fun clear() {
        expireAfterBodyWrite()
        linkIndex = NoteLinkIndex.EMPTY
    }

    /**
     * 本文を書き換えた後に呼ぶ。一覧は更新日時を持つので捨て、次の [get] で走査し直す。
     * **リンクの索引は残す。** 書き換えではノートの名前・パス・参照が変わらず、ここで捨てると、
     * 本文を出したまま次の走査が起きるまで別のノートへのリンクを開けない。
     */
    fun expireAfterBodyWrite() {
        notes = emptyList()
        loadedAt = 0L
        mutableKnownPaths.value = emptySet()
    }
}
