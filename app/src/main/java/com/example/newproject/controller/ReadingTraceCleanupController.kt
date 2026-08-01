package com.example.newproject.controller

import com.example.newproject.data.ReadingTraceKeyListing
import com.example.newproject.data.ReadingTracePersistence
import com.example.newproject.data.ReadingTraceReadResult
import com.example.newproject.data.ReadingTraceStore
import com.example.newproject.data.VaultBrowser
import com.example.newproject.domain.assessReadingTraceOrphans
import com.example.newproject.model.OrphanAssessment
import com.example.newproject.model.OrphanLimits
import com.example.newproject.model.OrphanTraceInfo
import com.example.newproject.model.state.ReadingTraceCleanupState
import com.example.newproject.model.ReadingTraceCleanupStateWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 読書痕跡の整理（孤児の洗い出し）。
 *
 * **Vault単位の機能**なので、ノート切替では止めない。寿命は補記一覧
 * （[AnnotationController] の `listJob`）と同じで、無効化の契機は Vault切替だけ。
 * したがってノート単位の契約（`cancelNoteScopedJobs` / `withNoteScopedReset`）へは登録しない。
 *
 * **段階3 の時点では削除しない。** 候補と、遮断器が保留した一群を並べて見せるだけの
 * シャドーモードで、判定が信用できるかを実運用で観測する（→ reflect_reading_trace §14）。
 */
internal class ReadingTraceCleanupController(
    private val scope: CoroutineScope,
    private val vault: VaultBrowser,
    private val persistence: ReadingTracePersistence,
    private val state: ReadingTraceCleanupStateWriter,
    /** ノートを開いた時点ではなく、**この操作を始めた時点**のVault識別子。 */
    private val currentVaultKey: () -> String?,
    /** Vault単位の世代。走行中に切り替わったら結果を捨てる。 */
    private val vaultGeneration: () -> Long,
    private val limits: OrphanLimits = OrphanLimits()
) {
    private var job: Job? = null

    /**
     * 候補を洗い出した時点のVault識別子。**削除はこの値に対して行う。**
     *
     * 削除時に `currentVaultKey()` を読み直してはいけない。キーは相対パスのハッシュなので、
     * **別のVaultに同じ相対パスのノートがあればキーも一致する** — 洗い出しと削除の間に
     * Vaultが切り替わると、旧Vaultの候補キーで**新Vaultの生きた痕跡を消し得る**。
     * 要求時点のVaultを要求自身に持たせる規律は [ReadingTraceController] と同じ。
     */
    private var assessedVaultKey: String? = null

    /**
     * 孤児候補を洗い直す。画面を開くたびに走らせてよい
     * （ノート一覧はTTLキャッシュ、痕跡の列挙は掃除のためだけの1回）。
     */
    fun assess() {
        val handle = vault.current()
        if (handle == null) {
            state.set(ReadingTraceCleanupState.Error("Vault が選択されていません。"))
            return
        }
        val vaultKey = currentVaultKey()
        if (vaultKey == null) {
            state.set(ReadingTraceCleanupState.Error("Vault が選択されていません。"))
            return
        }
        val generation = vaultGeneration()
        job?.cancel()
        job = scope.launch {
            state.set(ReadingTraceCleanupState.Loading)
            val next = runAssessment(handle, vaultKey)
            assessedVaultKey = vaultKey
            // 走行中にVaultが切り替わっていたら、旧Vaultの候補を新Vaultの画面へ出さない。
            // ここを落とすと、切替直後に「別Vaultのノートを消しませんか」と尋ねることになる。
            if (generation == vaultGeneration()) state.set(next)
        }
    }

    private suspend fun runAssessment(
        handle: com.example.newproject.data.VaultHandle,
        vaultKey: String
    ): ReadingTraceCleanupState = try {
        val listing = persistence.listKeys(vaultKey)
        when (listing) {
            is ReadingTraceKeyListing.Unavailable ->
                ReadingTraceCleanupState.Error(listing.reason)
            is ReadingTraceKeyListing.Available -> {
                val scan = handle.collectAllNotes()
                // 現存ノートのパスをキー化して突き合わせる。**ここまでファイルは1つも開かない。**
                val noteKeys = scan.notes.mapTo(mutableSetOf()) {
                    ReadingTraceStore.keyFor(it.vaultRelativePath)
                }
                val assessment = assessReadingTraceOrphans(
                    traceKeys = listing.keys,
                    noteKeys = noteKeys,
                    unreadableFolderPaths = scan.unreadableFolderPaths,
                    limits = limits
                ) { key -> resolve(key, vaultKey) }
                assessment.toState()
            }
        }
    } catch (error: Exception) {
        ReadingTraceCleanupState.Error(error.message ?: error::class.java.simpleName)
    }

    /**
     * 候補1件の中身を読む。孤児はパスが分からない（キーはハッシュで不可逆）ため
     * キー指定で引く。読めない・壊れている場合は null を返し、判定側が保留にする。
     */
    private fun resolve(key: String, vaultKey: String): OrphanTraceInfo? =
        when (val result = persistence.loadByKey(key, vaultKey)) {
            is ReadingTraceReadResult.Valid -> OrphanTraceInfo(
                vaultRelativePath = result.trace.vaultRelativePath,
                noteTitle = result.trace.noteTitle,
                totalVisitCount = result.trace.totalVisitCount,
                lastVisitAtEpochMillis = result.trace.visits.lastOrNull()?.atEpochMillis
            )
            else -> null
        }

    /**
     * 候補を削除する。**対象は「いま画面に出ている候補」に固定する。**
     *
     * 洗い出し直後の一覧だけを消しにいくのは、走行中にVaultが切り替わっても
     * 拾い直した新Vaultのファイルを消さないため（[AnnotationController.deleteAll] と同じ規律）。
     *
     * **削除後に洗い直さない。** もう一度 Vault 全走査を掛けるのは重く、
     * 残った候補は同じ判定で一緒に評価済みなので、消えた分を落とすだけで一覧は正しい。
     * **失敗した候補は残す** — 消えると再試行できなくなる。
     */
    fun delete(keys: List<String>) {
        val current = state.current as? ReadingTraceCleanupState.Success ?: return
        // 洗い出した時点のVaultへ削除する。現在のVaultと違っていれば何もしない
        // （キーが衝突して別Vaultの生きた痕跡を消すのを防ぐ）。
        val vaultKey = assessedVaultKey ?: return
        if (vaultKey != currentVaultKey()) return
        val targets = current.orphans.filter { it.key in keys }
        if (targets.isEmpty()) return
        val generation = vaultGeneration()
        job?.cancel()
        job = scope.launch {
            val failed = mutableSetOf<String>()
            targets.forEach { candidate ->
                if (!persistence.deleteByKey(candidate.key, vaultKey)) failed += candidate.key
            }
            if (generation != vaultGeneration()) return@launch
            state.set(
                current.copy(
                    orphans = current.orphans.filter { it.key !in keys || it.key in failed },
                    deleteFailureCount = failed.size
                )
            )
        }
    }

    /** Vault切替。走行中の洗い出しを止める（状態のリセットは状態変換側が行う）。 */
    fun onVaultChanged() {
        job?.cancel()
        job = null
        assessedVaultKey = null
    }
}

/** 洗い出しの結果を画面の状態へ移す。**判定できなかったことを空リストへ畳まない。** */
private fun OrphanAssessment.toState(): ReadingTraceCleanupState = when (this) {
    is OrphanAssessment.Blocked -> ReadingTraceCleanupState.Blocked(reason, candidateCount)
    is OrphanAssessment.Assessed -> ReadingTraceCleanupState.Success(orphans, withheld)
}
