package com.example.newproject

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
        loadedAt = now
        mutableKnownPaths.value = scanned.mapNotNullTo(HashSet()) { note ->
            note.vaultRelativePath.takeIf { it.isNotEmpty() }
        }
        onPublished(scanned)
        return scanned
    }

    /** 捨てる。Vault切替と、本文を書き換えた後に呼ぶ。 */
    fun clear() {
        notes = emptyList()
        loadedAt = 0L
        mutableKnownPaths.value = emptySet()
    }
}
