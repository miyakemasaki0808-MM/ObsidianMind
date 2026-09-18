package com.example.newproject.controller

import com.example.newproject.data.ReadingTraceKeyListing
import com.example.newproject.data.ReadingTracePersistence
import com.example.newproject.data.ReadingTraceReadResult
import com.example.newproject.data.ReadingTraceStore
import com.example.newproject.data.VaultBrowser
import com.example.newproject.domain.assessReadingTraceOrphans
import com.example.newproject.model.OrphanAssessment
import com.example.newproject.domain.NotePresence
import com.example.newproject.domain.notePresenceAfterRescan
import com.example.newproject.domain.parentVaultPath
import com.example.newproject.model.OrphanCandidate
import com.example.newproject.model.OrphanLimits
import com.example.newproject.model.OrphanTraceInfo
import com.example.newproject.model.state.ReadingTraceCleanupState
import com.example.newproject.model.ReadingTraceCleanupStateWriter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 読書痕跡の整理（孤児の洗い出し）。
 *
 * **Vault単位の機能**なので、ノート切替では止めない。寿命は補記一覧
 * （[AnnotationController] の `listJob`）と同じで、無効化の契機は Vault切替だけ。
 * したがってノート単位の契約（`cancelNoteScopedJobs` / `withNoteScopedReset`）へは登録しない。
 *
 * 候補と、遮断器が保留した一群を洗い出す。削除は**1件ずつのみ**で、一括削除は持たない
 * （遮断器は上位フォルダが静かに欠けた場合を完全には塞げないため、実績が集まるまで外す
 * → reflect_reading_trace §14）。
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
    private val limits: OrphanLimits = OrphanLimits(),
    /**
     * SAF I/O を逃がす先。**痕跡の列挙・読み出し・削除は同期I/O**で、
     * `scope` は本番では `viewModelScope`（Main）なので、ここを通さないと
     * Google Drive 等の遠いプロバイダで画面が止まる。
     * 既存の [ReadingTraceController] と同じ規律。
     */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private var assessJob: Job? = null

    /**
     * 走行中の削除。**洗い出しからキャンセルしない** — 削除は外部I/Oで、
     * キャンセルしても物理削除は巻き戻らず、**状態更新だけが落ちる**。
     * 止めてよいのはVault切替のときだけで、そこでは状態ごと捨てる。
     */
    private val deleteJobs = mutableSetOf<Job>()

    /**
     * 洗い出しと削除を1列に並べる錠。
     *
     * **削除どうしを「後から来たら前を捨てる」で捌けない。** SAFの削除は同期I/Oなので
     * キャンセルが届いてもファイルは消え、画面だけが古いまま残る。
     * 洗い出しも同じ列に入れるのは、両方が**同じ状態の read-modify-write** だからである。
     */
    private val operations = Mutex()

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
     * 孤児候補を洗い直す。画面を開くたびに走る。
     *
     * **ノート一覧のTTLキャッシュには相乗りしない** — `collectAllNotes()` は
     * Repository へ直行するので毎回フル走査になる。掃除は「不在」を根拠にする以上
     * 鮮度を優先すべきで、キャッシュ済みの古い一覧で判断すると
     * 消えたはずのノートが残って見える（＝候補を取りこぼす）。
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
        assessJob?.cancel()
        assessJob = scope.launch {
            state.set(ReadingTraceCleanupState.Loading)
            operations.withLock {
                val next = runAssessment(handle, vaultKey)
                assessedVaultKey = vaultKey
                // 走行中にVaultが切り替わっていたら、旧Vaultの候補を新Vaultの画面へ出さない。
                // ここを落とすと、切替直後に「別Vaultのノートを消しませんか」と尋ねることになる。
                if (generation == vaultGeneration()) state.set(next)
            }
        }
    }

    private suspend fun runAssessment(
        handle: com.example.newproject.data.VaultHandle,
        vaultKey: String
    ): ReadingTraceCleanupState = try {
        val listing = withContext(ioDispatcher) { persistence.listKeys(vaultKey) }
        when (listing) {
            is ReadingTraceKeyListing.Unavailable ->
                ReadingTraceCleanupState.Error(listing.reason)
            is ReadingTraceKeyListing.Available -> {
                val scan = handle.collectAllNotes()
                // 現存ノートのパスをキー化して突き合わせる。**ここまでファイルは1つも開かない。**
                val noteKeys = scan.notes.mapTo(mutableSetOf()) {
                    ReadingTraceStore.keyFor(it.vaultRelativePath)
                }
                // 候補の中身読みも同期I/O。判定ごと IO へ載せる。
                val assessment = withContext(ioDispatcher) {
                    assessReadingTraceOrphans(
                        traceKeys = listing.keys,
                        noteKeys = noteKeys,
                        unreadableFolderPaths = scan.unreadableFolderPaths,
                        limits = limits
                    ) { key -> resolve(key, vaultKey) }
                }
                assessment.toState()
            }
        }
    } catch (error: CancellationException) {
        // キャンセルを一般エラーへ畳むと、Vault切替のたびに偽エラーが出るうえ、
        // `state.set` は suspend しないのでキャンセル後も書き込まれてしまう。
        throw error
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
     * 候補を1件削除する。**削除の直前にVaultを走査し直して、まだ不在であることを確かめる。**
     *
     * 洗い出しと削除のあいだに同期が完了したり、同名のノートが作り直されたりすると、
     * **その痕跡は生きている**。判定結果は時間が経つほど古くなるので、
     * 消す瞬間に一度だけ確かめ直す（対象は1件なので走査1回で足りる）。
     *
     * 対象は「いま画面に出ている候補」に固定する。走行中にVaultが切り替わっても
     * 拾い直した新Vaultのファイルを消さないため（[AnnotationController.deleteAll] と同じ規律）。
     *
     * **失敗した候補は一覧に残す** — 消えると再試行できなくなる。
     *
     * **結果は「押した時点の一覧」ではなく最新の状態へ当てる。** 押した時点を捕まえて
     * 上書きすると、**先に終わった削除の結果を後から終わった削除が取り消す**。
     */
    fun delete(key: String) {
        val current = state.current as? ReadingTraceCleanupState.Success ?: return
        val target = current.orphans.firstOrNull { it.key == key } ?: return
        // 同じ候補を二重に走らせない。外部I/Oは始まると取り消せないので、
        // 「走っている最中は押せない」を状態として持つ（画面はこれを見てボタンを止める）。
        if (key in current.deletingKeys) return
        // 洗い出した時点のVaultへ削除する。現在のVaultと違っていれば何もしない
        // （キーが衝突して別Vaultの生きた痕跡を消すのを防ぐ）。
        val vaultKey = assessedVaultKey ?: return
        if (vaultKey != currentVaultKey()) return
        val handle = vault.current() ?: return
        val generation = vaultGeneration()
        state.set(current.copy(deletingKeys = current.deletingKeys + key))
        lateinit var job: Job
        job = scope.launch {
            try {
                operations.withLock {
                    val outcome = runDelete(handle, target, vaultKey)
                    if (generation != vaultGeneration()) return@withLock
                    val latest = state.current as? ReadingTraceCleanupState.Success
                        ?: return@withLock
                    state.set(latest.afterDelete(key, outcome))
                }
            } finally {
                // キャンセルされても走行の印は残さない。残すとボタンが二度と押せなくなる。
                // `set` は suspend しないので、キャンセル後のここでも書ける。
                clearDeleting(key)
            }
        }
        deleteJobs += job
        job.invokeOnCompletion { deleteJobs -= job }
    }

    private fun clearDeleting(key: String) {
        val latest = state.current as? ReadingTraceCleanupState.Success ?: return
        if (key !in latest.deletingKeys) return
        state.set(latest.copy(deletingKeys = latest.deletingKeys - key))
    }

    /**
     * 削除の結果。**[UNVERIFIED] を [FAILED] や [NOT_ORPHAN_ANYMORE] へ畳まない** —
     * 「消せなかった」「生き返っていた」「確かめられなかった」は次の行動が全部違う。
     */
    internal enum class DeleteOutcome { DELETED, NOT_ORPHAN_ANYMORE, UNVERIFIED, FAILED }

    private suspend fun runDelete(
        handle: com.example.newproject.data.VaultHandle,
        target: OrphanCandidate,
        vaultKey: String
    ): DeleteOutcome = try {
        val scan = handle.collectAllNotes()
        // 安全判定は純関数へ一元化してある（洗い出しと同じ規則を使う）。
        val presence = notePresenceAfterRescan(
            targetKey = target.key,
            targetVaultRelativePath = target.vaultRelativePath,
            notes = scan.notes.map { it.vaultRelativePath },
            unreadableFolderPaths = scan.unreadableFolderPaths,
            keyOf = ReadingTraceStore::keyFor
        )
        when (presence) {
            NotePresence.PRESENT -> DeleteOutcome.NOT_ORPHAN_ANYMORE
            NotePresence.INDETERMINATE -> DeleteOutcome.UNVERIFIED
            NotePresence.MISSING ->
                if (withContext(ioDispatcher) { persistence.deleteByKey(target.key, vaultKey) }) {
                    DeleteOutcome.DELETED
                } else {
                    DeleteOutcome.FAILED
                }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        DeleteOutcome.FAILED
    }

    /**
     * Vault切替。走行中の洗い出しと削除を止める（状態のリセットは状態変換側が行う）。
     *
     * **削除を止めてよいのはここだけ。** 物理削除は巻き戻らないが、
     * 旧Vaultの一覧ごと捨てるので、更新が落ちても画面に矛盾は残らない。
     */
    fun onVaultChanged() {
        assessJob?.cancel()
        assessJob = null
        deleteJobs.toList().forEach { it.cancel() }
        deleteJobs.clear()
        assessedVaultKey = null
    }
}

/**
 * 削除1件の結果を最新の状態へ当てる。
 *
 * **失敗と未確認はキーで持つ**（→ [ReadingTraceCleanupState.Success]）。件数だと、
 * 別の候補を消せたときに前の失敗の報せまで消える。
 */
private fun ReadingTraceCleanupState.Success.afterDelete(
    key: String,
    outcome: ReadingTraceCleanupController.DeleteOutcome
): ReadingTraceCleanupState.Success = when (outcome) {
    // 消えた／生き返っていた。どちらも候補ではなくなるので一覧から外す。
    ReadingTraceCleanupController.DeleteOutcome.DELETED,
    ReadingTraceCleanupController.DeleteOutcome.NOT_ORPHAN_ANYMORE -> copy(
        orphans = orphans.filterNot { it.key == key },
        deleteFailedKeys = deleteFailedKeys - key,
        unverifiedKeys = unverifiedKeys - key
    )
    // 確かめられなかった。**候補は残す** — 消すと再試行できない。
    ReadingTraceCleanupController.DeleteOutcome.UNVERIFIED -> copy(
        deleteFailedKeys = deleteFailedKeys - key,
        unverifiedKeys = unverifiedKeys + key
    )
    ReadingTraceCleanupController.DeleteOutcome.FAILED -> copy(
        deleteFailedKeys = deleteFailedKeys + key,
        unverifiedKeys = unverifiedKeys - key
    )
}

/** 洗い出しの結果を画面の状態へ移す。**判定できなかったことを空リストへ畳まない。** */
private fun OrphanAssessment.toState(): ReadingTraceCleanupState = when (this) {
    is OrphanAssessment.Blocked -> ReadingTraceCleanupState.Blocked(reason, candidateCount)
    is OrphanAssessment.Assessed -> ReadingTraceCleanupState.Success(orphans, withheld)
}
