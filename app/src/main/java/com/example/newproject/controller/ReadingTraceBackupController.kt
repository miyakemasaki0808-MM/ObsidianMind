package com.example.newproject.controller

import com.example.newproject.data.ReadingTraceBackupEntry
import com.example.newproject.data.ReadingTraceBackupJson
import com.example.newproject.data.ReadingTraceBackupReadResult
import com.example.newproject.data.ReadingTraceKeyListing
import com.example.newproject.data.ReadingTracePersistence
import com.example.newproject.data.ReadingTraceReadResult
import com.example.newproject.data.ReadingTraceSaveResult
import com.example.newproject.domain.adoptImportedTrace
import com.example.newproject.domain.mergeReadingTraces
import com.example.newproject.domain.replacesReply
import com.example.newproject.model.ReadingTrace
import com.example.newproject.model.ReadingTraceBackupStateWriter
import com.example.newproject.model.ReadingTraceBackupStep
import com.example.newproject.model.ReadingTraceImportPlan
import com.example.newproject.model.ReadingTraceImportWithholdReason
import com.example.newproject.model.WithheldImport
import com.example.newproject.model.state.ReadingTraceBackupState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * 読書痕跡の退避（書き出し／読み戻し）。
 *
 * **Vault単位の機能**なので、ノート切替では止めない。無効化の契機は Vault切替だけで、
 * ノート単位の契約（`cancelNoteScopedJobs` / `withNoteScopedReset`）へは登録しない
 * （→ [architecture](../../../../../../../../docs/dev/system/architecture.md) 判断2・4）。
 * 整理（[ReadingTraceCleanupController]）と同じ寿命である。
 *
 * ## 新しく作っているものは3つだけ
 *
 * 列挙・読み出し・版管理・checksum は既存部品（`ReadingTraceStore` / `ReadingTraceJson`）が
 * そのまま担う。ここが持つのは束ね方の呼び出し・突き合わせの結線・進捗と中断だけ。
 * 突き合わせ規則そのものは `domain` の純関数（`mergeReadingTraces`）にあり、
 * JVMテストで固定されている。
 *
 * ## 読み戻しは2段階
 *
 * **下見（[prepareImport]）と適用（[applyImport]）を分ける。** 読み戻しは不可逆なので、
 * 「何件が上書きされるか」「返事が何件置き換わるか」を見せてから確定させる。
 * 適用側は下見の結果を再利用せず**もう一度端末側を読み直す** — 下見と確定のあいだに
 * 背面化での訪問書き出しが走りうるので、確定の瞬間の中身へ対して突き合わせる。
 */
internal class ReadingTraceBackupController(
    private val scope: CoroutineScope,
    private val persistence: ReadingTracePersistence,
    private val state: ReadingTraceBackupStateWriter,
    /** ノートを開いた時点ではなく、**この操作を始めた時点**のVault識別子。 */
    private val currentVaultKey: () -> String?,
    /** Vault単位の世代。走行中に切り替わったら結果を捨てる。 */
    private val vaultGeneration: () -> Long,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * SAF I/O を逃がす先。**痕跡の列挙・読み出し・書き込みは同期I/O**で、
     * `scope` は本番では `viewModelScope`（Main）なので、ここを通さないと
     * 遠いプロバイダで画面が止まる。既存の痕跡系Controllerと同じ規律。
     */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private var job: Job? = null

    /** 下見が済んだ読み戻し。**確定するまで1件も書かない。** */
    private var pending: PendingImport? = null

    /**
     * 適用の途中経過。**中断したときに「どこまで適用したか」を答えるために持つ。**
     * 中断は不可逆な操作の途中で起こるので、「やめました」だけでは足りない。
     */
    private var applied: ImportTally? = null

    private class PendingImport(
        val vaultKey: String,
        val traces: List<ReadingTrace>,
        /** 退避ファイル側で既に読めなかった分。適用の結果へそのまま持ち越す。 */
        val unreadable: List<WithheldImport>
    )

    /**
     * 適用の集計。**書いたその場で数える。**
     *
     * まとまりを書き終えてから数えると、中断したときに「0件適用した」と報告する
     * （実際には書かれている）。**中断の報告は、実際に書けた件数と揃っていなければ意味が無い。**
     * 数えるのはI/O側、読むのは中断を受けたUI側なので、並行に触れる形で持つ。
     */
    private class ImportTally {
        private val addedCount = AtomicInteger()
        private val mergedCount = AtomicInteger()
        private val withheldItems = CopyOnWriteArrayList<WithheldImport>()

        val added: Int get() = addedCount.get()
        val merged: Int get() = mergedCount.get()
        val withheld: List<WithheldImport> get() = withheldItems.toList()

        fun countAdded() {
            addedCount.incrementAndGet()
        }

        fun countMerged() {
            mergedCount.incrementAndGet()
        }

        fun withhold(item: WithheldImport) {
            withheldItems += item
        }
    }

    // ── 書き出し ──────────────────────────────────────────────────────────

    /**
     * Vault内の全痕跡を1ファイルへ束ねて [write] へ渡す。
     *
     * 保存先の解決とSAF書き込みは呼び出し側（ViewModel）が担う。この層は `Uri` を扱わない。
     * **[write] を呼ぶのは束ね終えた後の1回だけ**なので、途中で中断しても保存先は汚れない。
     */
    fun export(write: suspend (ByteArray) -> Unit) {
        val vaultKey = currentVaultKey() ?: return failWithoutVault()
        val generation = vaultGeneration()
        job?.cancel()
        pending = null
        applied = null
        job = scope.launch {
            state.set(ReadingTraceBackupState.Working(ReadingTraceBackupStep.EXPORT_READ, 0, 0))
            val next = runExport(vaultKey, generation, write)
            if (next != null && generation == vaultGeneration()) state.set(next)
        }
    }

    private suspend fun runExport(
        vaultKey: String,
        generation: Long,
        write: suspend (ByteArray) -> Unit
    ): ReadingTraceBackupState? = try {
        when (val listing = withContext(ioDispatcher) { persistence.listKeys(vaultKey) }) {
            // **列挙できなかったを「痕跡ゼロ」へ畳まない。** 畳むと空の退避ファイルを
            // 書き出し、それを信じて端末を移した時点で全部失われる。
            is ReadingTraceKeyListing.Unavailable ->
                ReadingTraceBackupState.Error(listing.reason)

            is ReadingTraceKeyListing.Available -> {
                val keys = listing.keys.sorted()
                if (keys.isEmpty()) {
                    ReadingTraceBackupState.Error("書き出せる読書痕跡がまだありません。")
                } else {
                    exportKeys(keys, vaultKey, generation, write)
                }
            }
        }
    } catch (error: CancellationException) {
        // キャンセルを一般エラーへ畳むと、Vault切替のたびに偽エラーが出る。
        // `state.set` は suspend しないのでキャンセル後も書き込まれてしまう。
        throw error
    } catch (error: Exception) {
        ReadingTraceBackupState.Error(error.message ?: "読書痕跡を書き出せませんでした。")
    }

    private suspend fun exportKeys(
        keys: List<String>,
        vaultKey: String,
        generation: Long,
        write: suspend (ByteArray) -> Unit
    ): ReadingTraceBackupState? {
        val traces = mutableListOf<ReadingTrace>()
        val unreadable = mutableListOf<String>()
        var done = 0
        state.set(ReadingTraceBackupState.Working(ReadingTraceBackupStep.EXPORT_READ, 0, keys.size))
        // **まとめてI/Oへ渡し、進捗と世代照合は呼び出し側の文脈で行う。**
        // 1件ずつ withContext すると遠いプロバイダで切替コストが件数分載り、
        // 逆にI/Oの中から状態を書くと世代照合をI/Oスレッドから行うことになる。
        for (chunk in keys.chunked(IO_CHUNK_SIZE)) {
            val read = withContext(ioDispatcher) {
                chunk.map { key -> key to persistence.loadByKey(key, vaultKey) }
            }
            if (generation != vaultGeneration()) return null
            read.forEach { (key, result) ->
                if (result is ReadingTraceReadResult.Valid) traces += result.trace else unreadable += key
            }
            done += chunk.size
            state.set(
                ReadingTraceBackupState.Working(ReadingTraceBackupStep.EXPORT_READ, done, keys.size)
            )
        }
        // 1件も読めなかったなら書き出さない。**空のファイルを「退避できた」と見せない。**
        if (traces.isEmpty()) {
            return ReadingTraceBackupState.Error(
                "${unreadable.size}件の読書痕跡をどれも読み取れなかったため、書き出しませんでした。"
            )
        }
        val bytes = ReadingTraceBackupJson.encode(traces, clock())
        withContext(ioDispatcher) { write(bytes) }
        return ReadingTraceBackupState.Exported(traces.size, unreadable)
    }

    // ── 読み戻しの下見 ────────────────────────────────────────────────────

    /**
     * 退避ファイルを読んで、適用したら何がどうなるかを数える。**まだ1件も書かない。**
     */
    fun prepareImport(read: suspend () -> ByteArray) {
        val vaultKey = currentVaultKey() ?: return failWithoutVault()
        val generation = vaultGeneration()
        job?.cancel()
        pending = null
        applied = null
        job = scope.launch {
            state.set(ReadingTraceBackupState.Working(ReadingTraceBackupStep.IMPORT_SCAN, 0, 0))
            val next = runScan(vaultKey, generation, read)
            if (next != null && generation == vaultGeneration()) state.set(next)
        }
    }

    private suspend fun runScan(
        vaultKey: String,
        generation: Long,
        read: suspend () -> ByteArray
    ): ReadingTraceBackupState? = try {
        val bytes = withContext(ioDispatcher) { read() }
        when (val parsed = ReadingTraceBackupJson.decode(bytes)) {
            // 読めない版・別形式はファイルごと中止する。部分適用は不可逆な操作を
            // 中途半端に残すので、読めた分だけ適用するという逃げ道を作らない。
            is ReadingTraceBackupReadResult.Unusable ->
                ReadingTraceBackupState.Error(parsed.reason)

            is ReadingTraceBackupReadResult.Valid ->
                scanEntries(parsed.entries, vaultKey, generation)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        ReadingTraceBackupState.Error(error.message ?: "退避ファイルを読み取れませんでした。")
    }

    private suspend fun scanEntries(
        entries: List<ReadingTraceBackupEntry>,
        vaultKey: String,
        generation: Long
    ): ReadingTraceBackupState? {
        val unreadable = entries.filterIsInstance<ReadingTraceBackupEntry.Corrupt>().map {
            WithheldImport(null, ReadingTraceImportWithholdReason.UNREADABLE_ENTRY)
        }
        // 同じノートの痕跡が退避ファイル内で重複していたら、**捨てずに畳む。**
        // 手で結合された退避ファイルがこの形になり、片方を落とすと返事を失う。
        val traces = entries.filterIsInstance<ReadingTraceBackupEntry.Valid>()
            .map { it.trace }
            .groupBy { it.vaultRelativePath }
            .map { (_, duplicates) -> duplicates.reduce(::mergeReadingTraces) }
            .sortedBy { it.vaultRelativePath }
        if (traces.isEmpty()) {
            return ReadingTraceBackupState.Error(
                if (unreadable.isEmpty()) "退避ファイルに読書痕跡が入っていません。"
                else "退避ファイルの${unreadable.size}件をどれも読み取れませんでした。"
            )
        }

        var added = 0
        var merged = 0
        var replyReplaced = 0
        var done = 0
        state.set(
            ReadingTraceBackupState.Working(ReadingTraceBackupStep.IMPORT_SCAN, 0, traces.size)
        )
        for (chunk in traces.chunked(IO_CHUNK_SIZE)) {
            val locals = withContext(ioDispatcher) {
                chunk.map { persistence.load(it.vaultRelativePath, vaultKey) }
            }
            if (generation != vaultGeneration()) return null
            chunk.forEachIndexed { index, imported ->
                val local = (locals[index] as? ReadingTraceReadResult.Valid)?.trace
                if (local == null) {
                    added++
                } else {
                    merged++
                    if (replacesReply(local, imported)) replyReplaced++
                }
            }
            done += chunk.size
            state.set(
                ReadingTraceBackupState.Working(
                    ReadingTraceBackupStep.IMPORT_SCAN,
                    done,
                    traces.size
                )
            )
        }
        pending = PendingImport(vaultKey, traces, unreadable)
        return ReadingTraceBackupState.Planned(
            ReadingTraceImportPlan(added, merged, replyReplaced, unreadable)
        )
    }

    // ── 読み戻しの適用 ────────────────────────────────────────────────────

    /**
     * 下見の結果を確定させる。**ここから先は不可逆。**
     *
     * 対象は下見した時点のVaultに固定する。切り替わっていたら何もしない —
     * 痕跡のキーは相対パスのハッシュなので、**別Vaultに同じ相対パスのノートがあれば
     * キーも一致し、無関係な痕跡を上書きし得る**（整理側と同じ規律）。
     */
    fun applyImport() {
        if (state.current !is ReadingTraceBackupState.Planned) return
        val target = pending ?: return
        if (target.vaultKey != currentVaultKey()) {
            state.set(ReadingTraceBackupState.Error("Vault が切り替わりました。選び直してください。"))
            pending = null
            return
        }
        val generation = vaultGeneration()
        job?.cancel()
        val tally = ImportTally()
        target.unreadable.forEach(tally::withhold)
        applied = tally
        job = scope.launch {
            val next = runApply(target, tally, generation)
            pending = null
            if (next != null && generation == vaultGeneration()) state.set(next)
        }
    }

    private suspend fun runApply(
        target: PendingImport,
        tally: ImportTally,
        generation: Long
    ): ReadingTraceBackupState? {
        var done = 0
        return try {
            state.set(applyProgress(0, target.traces.size))
            for (chunk in target.traces.chunked(IO_CHUNK_SIZE)) {
                withContext(ioDispatcher) {
                    chunk.forEach { applyOne(it, target.vaultKey, tally) }
                }
                if (generation != vaultGeneration()) return null
                done += chunk.size
                state.set(applyProgress(done, target.traces.size))
            }
            ReadingTraceBackupState.Imported(tally.added, tally.merged, tally.withheld)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ReadingTraceBackupState.Error(error.message ?: "読書痕跡を読み戻せませんでした。")
        }
    }

    private fun applyProgress(done: Int, total: Int): ReadingTraceBackupState.Working =
        ReadingTraceBackupState.Working(ReadingTraceBackupStep.IMPORT_APPLY, done, total)

    /**
     * 1件を突き合わせて書き、**その場で数える。**
     *
     * **確定の瞬間に端末側を読み直す。** 下見と確定のあいだに背面化での訪問書き出しが
     * 走りうるので、下見のときに読んだ中身へ対して突き合わせてはいけない。
     *
     * 端末側が壊れていた（[ReadingTraceReadResult.Corrupt]）場合は「無い」と同じ扱いで
     * 退避側をそのまま採る。壊れた痕跡から救えるものは無く、読める痕跡へ置き換わる方がよい。
     */
    private fun applyOne(imported: ReadingTrace, vaultKey: String, tally: ImportTally) {
        val local = (persistence.load(imported.vaultRelativePath, vaultKey)
            as? ReadingTraceReadResult.Valid)?.trace
        val next = if (local == null) adoptImportedTrace(imported) else mergeReadingTraces(local, imported)
        when {
            persistence.save(next, vaultKey) !is ReadingTraceSaveResult.Success ->
                // 書けなかった分は「適用できなかった」として残す。
                // 追加・マージのどちらに数えても、実際には反映されていない。
                tally.withhold(
                    WithheldImport(
                        imported.vaultRelativePath,
                        ReadingTraceImportWithholdReason.SAVE_FAILED
                    )
                )
            local == null -> tally.countAdded()
            else -> tally.countMerged()
        }
    }

    // ── 中断と後始末 ──────────────────────────────────────────────────────

    /**
     * 走行中の処理を止める。
     *
     * **適用の途中だけは「やめました」では足りない。** そこまでに書いた分は
     * 既に端末側へ反映されているので、件数を添えて結果として見せる。
     * 書き出しと下見は1件も書いていないので、待機へ戻す。
     */
    fun cancel() {
        val running = state.current as? ReadingTraceBackupState.Working ?: return
        job?.cancel()
        job = null
        if (running.step == ReadingTraceBackupStep.IMPORT_APPLY) {
            val tally = applied ?: ImportTally()
            state.set(
                ReadingTraceBackupState.Imported(
                    added = tally.added,
                    merged = tally.merged,
                    withheld = tally.withheld,
                    interrupted = true
                )
            )
        } else {
            state.set(ReadingTraceBackupState.Idle)
        }
        pending = null
    }

    /** 結果表示や下見を閉じて待機へ戻す。**走行中には使わない**（そちらは [cancel]）。 */
    fun dismiss() {
        if (state.current is ReadingTraceBackupState.Working) return
        pending = null
        applied = null
        state.set(ReadingTraceBackupState.Idle)
    }

    /** Vault切替。走行中の処理と下見を捨てる（状態のリセットは状態変換側が行う）。 */
    fun onVaultChanged() {
        job?.cancel()
        job = null
        pending = null
        applied = null
    }

    private fun failWithoutVault() {
        state.set(ReadingTraceBackupState.Error("Vault が選択されていません。"))
    }

    private companion object {
        /**
         * 一度にI/Oへ渡す件数。
         *
         * 1件ずつ `withContext` すると遠いプロバイダで切替コストが件数分載り、
         * 逆に全件を1回で渡すと進捗が出ず中断もできない。**進捗の粒度とI/Oの粒度は
         * 同じ数字で決まる**ので、体感で分かる程度に細かく、かつ切替が支配しない値にする。
         */
        const val IO_CHUNK_SIZE = 25
    }
}
