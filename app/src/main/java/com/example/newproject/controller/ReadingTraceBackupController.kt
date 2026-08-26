package com.example.newproject.controller

import com.example.newproject.data.ReadingTraceBackupEntry
import com.example.newproject.data.ReadingTraceBackupJson
import com.example.newproject.data.ReadingTraceBackupReadResult
import com.example.newproject.data.ReadingTraceKeyListing
import com.example.newproject.data.ReadingTracePersistence
import com.example.newproject.data.ReadingTraceReadResult
import com.example.newproject.data.ReadingTraceSaveResult
import com.example.newproject.data.ReadingTraceStore
import com.example.newproject.domain.adoptImportedTrace
import com.example.newproject.domain.mergeReadingTraces
import com.example.newproject.domain.DroppedReplySide
import com.example.newproject.domain.droppedReplySide
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * 「何件が増え、何件を合わせ、どちらの返事が失われるか」を見せてから確定させる。
 *
 * **確定は、見せた内容と端末側が一致していることを先に確かめてから書く。** 適用は
 * ①端末側を全件読み直して下見時と突き合わせ（1件も書かない）②一致していたら
 * 錠の中で1件ずつ読み直して書く、の2段構えになっている。①で差があれば
 * **1件も書かずに計画を作り直して出し直す** — 画面に出していない損失を確定させないため。
 *
 * ## 「読めなかった」を「無い」へ畳まない
 *
 * 端末側の点読込は、SAF の一時的な失敗でも `ReadingTraceReadResult.None` を返す。
 * これを「痕跡が無い」と読むと、**退避側を新規として丸ごと書き、端末側の返事が
 * 警告も保留もなく消える**。そこで置き場の一覧を先に取り、
 * **キーが一覧にあるのに読めない場合は「確かめられない」として書かない**。
 * 一覧そのものが取れなければ不在を根拠にできないので、読み戻し自体を始めない。
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
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * 退避ファイルの組み立てと解析を載せる先。**I/O用とは分ける。**
     *
     * 退避ファイルは最大8MB・5,000件で、JSONの組み立て・整形文字列化・各痕跡のdecode・
     * 重複の畳み込みはいずれも**入力サイズに比例する**。`scope` は本番では
     * `viewModelScope`（Main）なので、ここを通さないと上限近傍で
     * **進捗表示も中止ボタンも動かなくなる**（→ architecture 判断3・lessons L13）。
     * 「純粋」は「軽い」を意味しない。
     */
    private val cpuDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /**
     * サイドカーの read-modify-write を直列化する錠。**[ReadingTraceController] と同じものを受け取る。**
     *
     * 読み戻しは「端末側を読む → 突き合わせる → 書く」で、訪問の追記とまったく同じ形をしている。
     * 別々の錠を持つと、**適用の最中に背面化で訪問が書き出されたとき**（保存先を選ぶあいだに
     * アプリが背面へ回るので、実際に起こりうる順序）に読み取りが古いまま上書きし、
     * そのノートの読み戻しが黙って効かなくなる。
     *
     * **握るのは1件ぶんだけ。** まとまり全体で握ると、適用中は訪問の書き出しが止まる。
     */
    private val writeMutex: Mutex = Mutex()
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
        val unreadable: List<WithheldImport>,
        /**
         * **下見した時点の端末側**。相対パス → その時の状態。
         *
         * 確定時にこれと突き合わせる。**計画（件数）ではなく中身で比べる** —
         * 件数が同じでも別の痕跡が変わっていれば、承認された内容ではない。
         */
        val snapshots: Map<String, LocalTrace>
    )

    /**
     * 端末側1件の状態。**「無い」「壊れている」「読めない」を畳まない。**
     *
     * 3つは次の行動が全部違う — 無いなら新規として受け入れてよく、壊れているなら
     * 救えるものが無いので退避側で置き換えてよく、**読めないなら何もしてはいけない**。
     * [ReadingTrace] は `data class` なので、[Present] どうしの比較は中身の比較になる。
     */
    private sealed interface LocalTrace {
        /** 置き場の一覧にキーが無い＝実在しない。 */
        data object Absent : LocalTrace

        /** 読めた。突き合わせの相手。 */
        data class Present(val trace: ReadingTrace) : LocalTrace

        /** 一覧にあるが中身が壊れている。**救えるものが無いので退避側で置き換える。** */
        data object Corrupt : LocalTrace

        /** 一覧にあるのに読み出せなかった。**確かめられないので書かない。** */
        data object Unreadable : LocalTrace
    }

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
        // **束ねるのは Main の外で。** 全痕跡をJSONArrayへ積んで整形文字列にする作業は
        // 件数に比例する（→ architecture 判断3）。
        val bytes = withContext(cpuDispatcher) { ReadingTraceBackupJson.encode(traces, clock()) }
        withContext(ioDispatcher) { write(bytes) }
        return ReadingTraceBackupState.Exported(traces.size, unreadable)
    }

    // ── 端末側の読み取り ──────────────────────────────────────────────────

    /**
     * 端末側1件を読む。**「読めなかった」を「無い」へ畳まない。**
     *
     * 不在の根拠は**置き場の一覧**に置く。点読込が返す `None` は
     * 「ファイルが無い」とも「開けなかった」とも読めるので、単独では不在の証明にならない。
     * キーが一覧にあるのに読めなければ、それは読取失敗である。
     */
    private fun readLocal(
        vaultRelativePath: String,
        vaultKey: String,
        existingKeys: Set<String>
    ): LocalTrace {
        if (ReadingTraceStore.keyFor(vaultRelativePath) !in existingKeys) return LocalTrace.Absent
        return when (val result = persistence.load(vaultRelativePath, vaultKey)) {
            is ReadingTraceReadResult.Valid -> LocalTrace.Present(result.trace)
            is ReadingTraceReadResult.Corrupt -> LocalTrace.Corrupt
            ReadingTraceReadResult.None -> LocalTrace.Unreadable
        }
    }

    /** 端末側をまとめて読む。まとまりごとに世代を照合し、切り替わっていたら null。 */
    private suspend fun readLocals(
        traces: List<ReadingTrace>,
        vaultKey: String,
        existingKeys: Set<String>,
        generation: Long,
        step: ReadingTraceBackupStep
    ): Map<String, LocalTrace>? {
        val snapshots = LinkedHashMap<String, LocalTrace>(traces.size)
        var done = 0
        state.set(ReadingTraceBackupState.Working(step, 0, traces.size))
        for (chunk in traces.chunked(IO_CHUNK_SIZE)) {
            val read = withContext(ioDispatcher) {
                chunk.map { it.vaultRelativePath to readLocal(it.vaultRelativePath, vaultKey, existingKeys) }
            }
            if (generation != vaultGeneration()) return null
            snapshots.putAll(read)
            done += chunk.size
            state.set(ReadingTraceBackupState.Working(step, done, traces.size))
        }
        return snapshots
    }

    /** 下見の結果を数える。**書かないものは [ReadingTraceImportPlan.withheld] へ落とす。** */
    private fun planFor(
        traces: List<ReadingTrace>,
        snapshots: Map<String, LocalTrace>,
        carriedWithheld: List<WithheldImport>
    ): ReadingTraceImportPlan {
        var added = 0
        var merged = 0
        var localReplyReplaced = 0
        var importedReplyDropped = 0
        val withheld = carriedWithheld.toMutableList()
        traces.forEach { imported ->
            when (val local = snapshots[imported.vaultRelativePath]) {
                // 壊れていた分は退避側で置き換える。読める痕跡になるので「増える」側で数える。
                LocalTrace.Absent, LocalTrace.Corrupt, null -> added++
                LocalTrace.Unreadable -> withheld += WithheldImport(
                    imported.vaultRelativePath,
                    ReadingTraceImportWithholdReason.LOCAL_UNREADABLE
                )
                is LocalTrace.Present -> {
                    merged++
                    when (droppedReplySide(local.trace, imported)) {
                        DroppedReplySide.LOCAL -> localReplyReplaced++
                        DroppedReplySide.IMPORTED -> importedReplyDropped++
                        null -> Unit
                    }
                }
            }
        }
        return ReadingTraceImportPlan(added, merged, localReplyReplaced, importedReplyDropped, withheld)
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
        // **解析は Main の外で行う。** 退避ファイルは最大8MBで、JSONの解析と
        // 各痕跡のdecodeは入力サイズに比例する（→ architecture 判断3）。
        val parsed = withContext(cpuDispatcher) { ReadingTraceBackupJson.decode(bytes) }
        when (parsed) {
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
        // 畳む作業も件数に比例するので Main の外へ置く。
        val traces = withContext(cpuDispatcher) {
            entries.filterIsInstance<ReadingTraceBackupEntry.Valid>()
                .map { it.trace }
                .groupBy { it.vaultRelativePath }
                .map { (_, duplicates) -> duplicates.reduce(::mergeReadingTraces) }
                .sortedBy { it.vaultRelativePath }
        }
        if (traces.isEmpty()) {
            return ReadingTraceBackupState.Error(
                if (unreadable.isEmpty()) "退避ファイルに読書痕跡が入っていません。"
                else "退避ファイルの${unreadable.size}件をどれも読み取れませんでした。"
            )
        }

        val existingKeys = existingKeys(vaultKey)
            ?: return ReadingTraceBackupState.Error(
                "端末側の読書痕跡を確認できませんでした。同期の完了を待ってからお試しください。"
            )
        val snapshots =
            readLocals(traces, vaultKey, existingKeys, generation, ReadingTraceBackupStep.IMPORT_SCAN)
                ?: return null

        pending = PendingImport(vaultKey, traces, unreadable, snapshots)
        return ReadingTraceBackupState.Planned(planFor(traces, snapshots, unreadable))
    }

    /**
     * 置き場のキー一覧。**不在の唯一の根拠**なので、列挙できなければ null を返して読み戻さない。
     */
    private suspend fun existingKeys(vaultKey: String): Set<String>? =
        when (val listing = withContext(ioDispatcher) { persistence.listKeys(vaultKey) }) {
            is ReadingTraceKeyListing.Available -> listing.keys
            is ReadingTraceKeyListing.Unavailable -> null
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
            if (next != null && generation == vaultGeneration()) state.set(next)
        }
    }

    private suspend fun runApply(
        target: PendingImport,
        tally: ImportTally,
        generation: Long
    ): ReadingTraceBackupState? = try {
        // ── 段階1: 見せた計画と端末側が一致しているか。**1件も書かない。** ──
        val existingKeys = existingKeys(target.vaultKey)
        if (existingKeys == null) {
            ReadingTraceBackupState.Error(
                "端末側の読書痕跡を確認できませんでした。同期の完了を待ってからお試しください。"
            )
        } else {
            val fresh = readLocals(
                target.traces,
                target.vaultKey,
                existingKeys,
                generation,
                ReadingTraceBackupStep.IMPORT_APPLY
            )
            when {
                fresh == null -> null
                // **下見のあとに端末側が変わった。書かずに計画を作り直す。**
                // 画面に出していない損失を確定させないための1点。
                fresh != target.snapshots -> {
                    pending = PendingImport(
                        target.vaultKey,
                        target.traces,
                        target.unreadable,
                        fresh
                    )
                    applied = null
                    ReadingTraceBackupState.Planned(
                        plan = planFor(target.traces, fresh, target.unreadable),
                        revised = true
                    )
                }
                else -> {
                    val result = writeAll(target, fresh, tally, generation)
                    if (result != null) pending = null
                    result
                }
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        ReadingTraceBackupState.Error(error.message ?: "読書痕跡を読み戻せませんでした。")
    }

    /** ── 段階2: 書く。1件ずつ錠の中で読み直し、承認された状態のままのものだけ書く。 ── */
    private suspend fun writeAll(
        target: PendingImport,
        approved: Map<String, LocalTrace>,
        tally: ImportTally,
        generation: Long
    ): ReadingTraceBackupState? {
        var done = 0
        state.set(applyProgress(0, target.traces.size))
        for (chunk in target.traces.chunked(IO_CHUNK_SIZE)) {
            withContext(ioDispatcher) {
                chunk.forEach {
                    // **1件ごとに中止を見る。** `applyOne` の錠は競合していなければ
                    // 中断しないので、これが無いとまとまり（25件）を走り切ってしまう。
                    ensureActive()
                    applyOne(it, target.vaultKey, approved, tally)
                }
            }
            if (generation != vaultGeneration()) return null
            done += chunk.size
            state.set(applyProgress(done, target.traces.size))
        }
        return ReadingTraceBackupState.Imported(tally.added, tally.merged, tally.withheld)
    }

    private fun applyProgress(done: Int, total: Int): ReadingTraceBackupState.Working =
        ReadingTraceBackupState.Working(ReadingTraceBackupStep.IMPORT_APPLY, done, total)

    /**
     * 1件を突き合わせて書き、**その場で数える。**
     *
     * **錠の中で読み直してから書く。** 読みと書きが錠の外で割れていると、
     * 錠を持っている意味が無い（訪問の追記が挟まると読み取りが古いまま上書きする）。
     *
     * **読み直した結果が承認された状態と違えば書かない。** 段階1で全件を確かめてから
     * ここへ来るので通常は一致するが、その隙間に訪問が書き出されることはあり得る。
     */
    private suspend fun applyOne(
        imported: ReadingTrace,
        vaultKey: String,
        approved: Map<String, LocalTrace>,
        tally: ImportTally
    ) = writeMutex.withLock {
        val path = imported.vaultRelativePath
        val approvedLocal = approved[path]
        // **錠の中では置き場を数え直さない。** 1件ごとにフォルダを全列挙すると、
        // 遠いプロバイダで件数ぶんの列挙が走って現実的な時間で終わらない。
        // 不在の根拠は段階1の列挙が持っているので、`None` の読み替えだけをそこへ委ねる。
        val current = when (val result = persistence.load(path, vaultKey)) {
            is ReadingTraceReadResult.Valid -> LocalTrace.Present(result.trace)
            is ReadingTraceReadResult.Corrupt -> LocalTrace.Corrupt
            // 段階1で「無い」と確かめた相手が今も読めないなら、無いままとみなす。
            // **そうでなければ読取失敗**（消えた・開けなかった）なので、書かない。
            ReadingTraceReadResult.None ->
                if (approvedLocal == LocalTrace.Absent) LocalTrace.Absent else LocalTrace.Unreadable
        }
        when {
            current is LocalTrace.Unreadable ->
                tally.withhold(WithheldImport(path, ReadingTraceImportWithholdReason.LOCAL_UNREADABLE))

            // 段階1のあとに端末側が変わった（訪問が着いた・痕跡が作られた）。
            // 承認された内容ではないので書かない。
            current != approvedLocal ->
                tally.withhold(WithheldImport(path, ReadingTraceImportWithholdReason.LOCAL_CHANGED))

            else -> {
                val next = when (current) {
                    is LocalTrace.Present -> mergeReadingTraces(current.trace, imported)
                    else -> adoptImportedTrace(imported)
                }
                when {
                    persistence.save(next, vaultKey) !is ReadingTraceSaveResult.Success ->
                        // 書けなかった分は「適用できなかった」として残す。
                        // 追加・マージのどちらに数えても、実際には反映されていない。
                        tally.withhold(
                            WithheldImport(path, ReadingTraceImportWithholdReason.SAVE_FAILED)
                        )
                    current is LocalTrace.Present -> tally.countMerged()
                    else -> tally.countAdded()
                }
            }
        }
    }

    // ── 中断と後始末 ──────────────────────────────────────────────────────

    /**
     * 走行中の処理を止める。
     *
     * **適用の途中だけは「やめました」では足りない。** そこまでに書いた分は
     * 既に端末側へ反映されているので、件数を添えて結果として見せる。
     * 書き出しと下見は1件も書いていないので、待機へ戻す。
     *
     * **中止は要求であって完了ではない。** `cancel()` はJobへ印を付けるだけで、
     * 書き手が実際に止まるのはその後の中断点なので、**ここで途中経過を確定すると
     * 走り切った分だけ少なく報告する**（実測でチャンク途中の中止が16件ずれた）。
     * 件数は書き手を `join()` してから1度だけ数える。
     */
    fun cancel() {
        val running = state.current as? ReadingTraceBackupState.Working ?: return
        val stopping = job
        job = null
        pending = null
        // **停止要求だけは同期で出す。** 数える側のコルーチンへ委ねると、
        // そちらが先に捨てられたときに書き手が止まらないまま残る。
        stopping?.cancel()
        if (running.step != ReadingTraceBackupStep.IMPORT_APPLY) {
            state.set(ReadingTraceBackupState.Idle)
            return
        }
        val tally = applied
        scope.launch {
            stopping?.join()
            // 待っているあいだに次の操作が始まっていたら、その結果を上書きしない。
            // `applied` は書き出し・下見・確定・Vault切替のいずれでも差し替わるので、
            // 同じ適用の続きであることは同一性で確かめられる。
            if (applied !== tally) return@launch
            if (state.current !is ReadingTraceBackupState.Working) return@launch
            val counted = tally ?: ImportTally()
            state.set(
                ReadingTraceBackupState.Imported(
                    added = counted.added,
                    merged = counted.merged,
                    withheld = counted.withheld,
                    interrupted = true
                )
            )
        }
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
