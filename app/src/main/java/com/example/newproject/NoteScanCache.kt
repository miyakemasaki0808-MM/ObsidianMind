package com.example.newproject

import com.example.newproject.domain.NoteLinkIndex
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// ノート一覧の有効期間。Vault側の編集（Obsidian同期等）は最大この時間だけ反映が遅れる。
internal const val NOTES_CACHE_TTL_MS = 60_000L

/**
 * Vault全体のノート一覧のTTL付きキャッシュ。ランダム表示・Explore・冊子・関連ノート・痕跡のパス解決・結晶の一覧が共有する。
 *
 * **走査の結果を公開する直前に、Vaultの世代を照合する。** 走査を始めた後にVaultが切り替わっていたら、
 * 結果を捨てて [CancellationException] で呼び出し側を止める。旧Vaultの一覧がキャッシュ・開けるパス・
 * 分野の索引へ戻ると、切替後のVaultで旧Vaultのノートを開く。**呼び出し側の Job が取り消されることに頼らない** —
 * 取り消されない場所から走査を呼んでも、旧い結果は公開しない。`vaultUri` の比較で代用しないのは、
 * A→B→A と選び直したときに同じ値になって旧い走査が素通りするため（→ architecture.md 判断4）。
 *
 * **走査の途中に来た要求は、同じ走査の結果を待つ。** 走査は [scope] で走らせ、頼んだ人の Job では走らせない —
 * 最初に頼んだ人が取り消されても、相乗りした人へ結果を届けるため。読み書きは [scope] と同じ Main の上で起きる。
 *
 * Android の型に触れないので、素のJVMで交錯を組み立てて確かめられる（`NoteScanCacheTest`）。
 */
internal class NoteScanCache(
    scope: CoroutineScope,
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

    // 走査の失敗を scope の親へ伝えない。失敗は待っている人が await で受け取る。
    private val scanScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private var running: Deferred<List<NoteFile>>? = null
    private var bodyWrites = 0L

    private val mutableKnownNotes = MutableStateFlow<Map<String, DocumentRef>>(emptyMap())

    /**
     * いまのキャッシュにあるノートの相対パスと参照。結晶の一覧で、根拠のノート名を押せるかを決め、
     * 根拠のノートの分野を引く（分野の索引は参照で引く）。
     */
    val knownNotes: StateFlow<Map<String, DocumentRef>> = mutableKnownNotes.asStateFlow()

    /**
     * TTL内ならキャッシュを返し、切れていれば [scan] で走査して公開する。走査の途中なら、その結果を待つ。
     * [onPublished] は公開した一覧を受け取る（分野の索引へ載せるため）。**旧Vaultの結果では呼ばない。**
     */
    suspend fun get(
        scan: suspend () -> List<NoteFile>,
        onPublished: (List<NoteFile>) -> Unit
    ): List<NoteFile> {
        val now = clock()
        if (notes.isNotEmpty() && now - loadedAt < ttlMillis) return notes
        val result = running?.takeUnless { it.isCompleted } ?: startScan(now, scan, onPublished)
        return result.await()
    }

    private fun startScan(
        startedAt: Long,
        scan: suspend () -> List<NoteFile>,
        onPublished: (List<NoteFile>) -> Unit
    ): Deferred<List<NoteFile>> {
        val generation = vaultGeneration()
        val writes = bodyWrites
        val result = scanScope.async {
            val scanned = scan()
            if (generation != vaultGeneration()) {
                throw CancellationException("走査の間に Vault が切り替わったので、結果を捨てました。")
            }
            // 走査の途中で本文を書き換えたなら、この一覧の更新日時は古い。待っている人へ返すだけで残さない。
            if (writes == bodyWrites) publish(scanned, startedAt, onPublished)
            scanned
        }
        running = result
        return result
    }

    private fun publish(
        scanned: List<NoteFile>,
        startedAt: Long,
        onPublished: (List<NoteFile>) -> Unit
    ) {
        notes = scanned
        linkIndex = NoteLinkIndex(scanned)
        loadedAt = startedAt
        mutableKnownNotes.value = scanned
            .filter { it.vaultRelativePath.isNotEmpty() }
            .associate { it.vaultRelativePath to it.ref }
        onPublished(scanned)
    }

    /**
     * 参照から Vault 相対パスを引く。**I/Oも走査もしない。** 一覧は本文の書き換えで捨てるが、
     * 名前・パス・参照は変わらないので、残してあるリンクの索引から引く（並べた相手 → features/desk_memory.md §5）。
     * Vault 切替（[clear]）の後は前の Vault のパスを返さない。
     */
    fun relativePathOf(ref: DocumentRef): String? = linkIndex.pathOf(ref)

    /** 捨てる。Vault切替で呼ぶ。リンクの索引も捨てる — 前の Vault の参照を返さない。 */
    fun clear() {
        expireAfterBodyWrite()
        linkIndex = NoteLinkIndex.EMPTY
    }

    /**
     * 本文を書き換えた後に呼ぶ。一覧は更新日時を持つので捨て、次の [get] で走査し直す。走っている走査には相乗りさせない。
     * **リンクの索引は残す。** 書き換えではノートの名前・パス・参照が変わらず、ここで捨てると、
     * 本文を出したまま次の走査が起きるまで別のノートへのリンクを開けない。
     */
    fun expireAfterBodyWrite() {
        running = null
        bodyWrites++
        notes = emptyList()
        loadedAt = 0L
        mutableKnownNotes.value = emptyMap()
    }
}
