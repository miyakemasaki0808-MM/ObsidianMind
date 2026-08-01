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

    /** Vault切替。走行中の洗い出しを止める（状態のリセットは状態変換側が行う）。 */
    fun onVaultChanged() {
        job?.cancel()
        job = null
    }
}

/** 洗い出しの結果を画面の状態へ移す。**判定できなかったことを空リストへ畳まない。** */
private fun OrphanAssessment.toState(): ReadingTraceCleanupState = when (this) {
    is OrphanAssessment.Blocked -> ReadingTraceCleanupState.Blocked(reason, candidateCount)
    is OrphanAssessment.Assessed -> ReadingTraceCleanupState.Success(orphans, withheld)
}
