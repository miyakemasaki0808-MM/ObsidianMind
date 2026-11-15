package com.example.newproject.controller

import com.example.newproject.data.IndexedNoteRecord
import com.example.newproject.data.KnowledgeIndexSnapshot
import com.example.newproject.data.KnowledgeIndexStore
import com.example.newproject.data.VaultBrowser
import com.example.newproject.data.VaultHandle
import com.example.newproject.domain.contentFingerprint
import com.example.newproject.domain.extractIndexedLinks
import com.example.newproject.domain.planReconcile
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteFile
import com.example.newproject.model.NoteScanKind
import com.example.newproject.model.PublishedNoteScan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * 知識索引を作る（→ docs/dev/system/knowledge_index.md）。**Vault 単位**で、ノート単位の契約2箇所には載せない
 * （→ architecture.md 判断4 の2行目）。止める契機は Vault 切替だけで、照合は `vaultGeneration` で行う。
 *
 * **突き合わせは1本ずつ走らせる**（§3）。走っている間に届いた公開は「もう一度」として1つにまとめ（[pending]）、
 * 走り終えたら必ずもう1回走らせる。2本が重なると、遅れた古い読み込みが新しい結果を上書きしうる。
 * 読んだ結果を書く条件（書き換えの番号が最新のときだけ）は置き場が1つのトランザクションで持つ。
 *
 * 走査は自分では走らせず、走査の入れ物へ頼む（[requestScan]）。結果は入れ物の公開の知らせ（[onScanPublished]）で届く。
 * 頼む契機は Vault を開いたときと本文を書き換えたときだけで、タイマーでは頼まない。
 */
internal class KnowledgeIndexController(
    private val scope: CoroutineScope,
    private val store: KnowledgeIndexStore,
    private val vault: VaultBrowser,
    private val vaultGeneration: () -> Long,
    private val vaultKey: () -> String?,
    private val requestScan: suspend () -> Unit,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val readParallelism: Int = READ_PARALLELISM
) {
    private var pending: PublishedNoteScan? = null
    private var worker: Job? = null
    private val requests = mutableListOf<Job>()
    private var lastPublishCount = 0L

    /** 索引を直すたびに進む数。バックリンクを引く側が、引き直す契機に使う（→ knowledge_index.md §5）。 */
    var indexGeneration = 0L
        private set

    /**
     * Vault の復元・選び直しの後始末がすべて終わってから呼ぶ。**索引がどの Vault のものかは走査を待たずに照合する** —
     * 替えた先の Vault を読めないと走査は読めずに空になり、突き合わせまで進まないので、前の Vault の行が残る（→ knowledge_index.md §4）。
     * 照合してから走査を頼み、公開されたら索引を作る。
     */
    fun onVaultOpened() {
        val generation = vaultGeneration()
        val key = vaultKey()
        track(scope.launch {
            if (key != null) openQuietly(key)
            requestScanQuietly(generation)
        })
    }

    /**
     * 走査の入れ物が公開した。**読めずに空の公開は準備完了と見なさない**（[planReconcile] が何もしない）。
     * 走っている突き合わせがあれば、新しい公開だけを残して終わるのを待つ。
     */
    fun onScanPublished(scan: PublishedNoteScan) {
        if (scan.vaultGeneration != vaultGeneration()) return
        lastPublishCount = scan.publishCount
        pending = scan
        if (worker?.isActive != true) worker = scope.launch { drain() }
    }

    /**
     * アプリが本文を書き終えた（蒸留の保存・保存前の本文への復元）。**書いたスレッド（I/O）から同期で呼び、控えを永続してから戻る** —
     * 画面のスコープへ渡すと、書いている途中に ViewModel が破棄されたとき控えが作られない。控えさえ残れば、
     * 次に Vault を開いたときの突き合わせが読み直す（→ knowledge_index.md §3 の手順4）。
     * **控えは参照そのもので持つ** — 相対パスがその場で引けなくても、索引に無いノートでも控えられる。
     * 参照は Vault ごとに違うので、前の Vault の参照は今の Vault のノートに当たらない。
     */
    fun markRewrittenNow(ref: DocumentRef) {
        try {
            store.markRewrittenNow(ref.value)
        } catch (_: Exception) {
            // 置き場が使えない。索引が無いだけで、今の動きに戻る。
        }
    }

    /**
     * 控えた後に、Main から走査を頼む。**頼んだ走査の途中でまた書き換えると、その走査は公開されない**ので、
     * 公開が無ければもう一度だけ頼む。書き換えが続けば、その書き換えの呼び出しが自分で頼む。
     */
    fun onNoteBodyWritten() {
        val generation = vaultGeneration()
        track(scope.launch {
            val before = lastPublishCount
            requestScanQuietly(generation)
            if (lastPublishCount == before && generation == vaultGeneration()) requestScanQuietly(generation)
        })
    }

    /** Vault 切替。走っている突き合わせと頼みを止め、「もう一度」も捨てる。索引の中身は次に開いたときに照合する。 */
    fun onVaultChanged() {
        worker?.cancel()
        worker = null
        pending = null
        requests.forEach { it.cancel() }
        requests.clear()
    }

    private fun track(job: Job) {
        requests.removeAll { !it.isActive }
        requests += job
    }

    private suspend fun openQuietly(key: String) {
        try {
            store.openVault(key)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 置き場が使えない。索引が無いだけで、今の動きに戻る。
        }
    }

    private suspend fun requestScanQuietly(generation: Long) {
        if (generation != vaultGeneration()) return
        try {
            requestScan()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 走査の失敗は、次の契機（Vault を開く・本文の書き換え・ほかの機能の走査）で直る。繰り返し頼まない。
        }
    }

    private suspend fun drain() {
        while (true) {
            val scan = pending ?: return
            pending = null
            try {
                reconcile(scan)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 置き場が使えない。索引が無いだけで、今の動きに戻る（→ knowledge_index.md §10 の保証していないこと）。
            }
        }
    }

    private suspend fun reconcile(scan: PublishedNoteScan) {
        val generation = scan.vaultGeneration
        ensureCurrent(generation)
        val key = vaultKey() ?: return
        val handle = vault.current() ?: return
        // **開いてから読めずに空を見る。** 開くことが Vault の照合で、別の Vault の索引ならここで消える。
        // 読めずに空の走査で残してよいのは、同じ Vault の行だけ（→ knowledge_index.md §4）。
        val snapshot = store.openVault(key)
        ensureCurrent(generation)
        if (scan.kind == NoteScanKind.UNREADABLE_EMPTY) return
        val plan = planReconcile(scan.notes, scan.kind, snapshot.notes, snapshot.writeNumbers.keys)
        if (plan.toDelete.isNotEmpty()) store.deleteNotes(key, plan.toDelete)
        if (plan.forgetMarks.isNotEmpty()) store.forgetMarks(key, plan.forgetMarks)
        val permits = Semaphore(readParallelism)
        val applied = coroutineScope {
            plan.toRead.map { note ->
                async { note to permits.withPermit { indexNote(handle, key, note, snapshot, generation) } }
            }.awaitAll()
        }
        ensureCurrent(generation)
        // **控えは参照の単位で外す。** 同じ参照のパス（同じ文書を2つのフォルダに見せるプロバイダ）を全部書き終えたときだけ、
        // 番号が始めたときのままなら外す。1つでも書けなかったら残し、次の回で読み直す（→ knowledge_index.md §3 の手順4）。
        applied.groupBy({ it.first.ref.value }, { it.second })
            .forEach { (ref, results) ->
                val number = snapshot.writeNumbers[ref] ?: return@forEach
                if (results.all { it }) store.clearMark(key, ref, number)
            }
        if (!snapshot.firstBuildDone) store.markFirstBuildDone(key)
        indexGeneration++
    }

    /**
     * 1ノートを読み、リンクを取り出して書く。**書き換えの番号は突き合わせを始めたときのもの**を使う —
     * 読み始めより早いので、その間に書き換えがあれば番号が進み、書かずに控えを残して次の回で読み直す。
     * 書けた（または変わっていないと確かめた）ら true。読めなかった・番号が合わなかったら false で、控えを外さない理由になる。
     */
    private suspend fun indexNote(
        handle: VaultHandle,
        key: String,
        note: NoteFile,
        snapshot: KnowledgeIndexSnapshot,
        generation: Long
    ): Boolean {
        val path = note.vaultRelativePath
        // 控えは参照で持つ。索引に無いノートでも、初めて読んでいる途中の書き換えをここで止められる。
        val writeNumberAtRead = snapshot.writeNumbers[note.ref.value]
        val content = try {
            handle.readNoteForIndex(note.ref)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return false
        // 最大1MBの本文を解析するので Main の外で（→ architecture.md 判断3）。
        val (links, fingerprint) = withContext(parseDispatcher) {
            extractIndexedLinks(content) to contentFingerprint(content)
        }
        ensureCurrent(generation)
        val previous = snapshot.notes[path]
        val unchanged = previous != null && previous.fingerprint == fingerprint && previous.lastModified == note.lastModified
        if (writeNumberAtRead == null && unchanged) return true
        return store.applyRead(
            vaultKey = key,
            note = IndexedNoteRecord(
                relativePath = path,
                documentRef = note.ref.value,
                name = note.name,
                lastModified = note.lastModified,
                fingerprint = fingerprint
            ),
            links = links,
            writeNumberAtRead = writeNumberAtRead
        )
    }

    private fun ensureCurrent(generation: Long) {
        if (generation != vaultGeneration()) throw CancellationException("Vault が切り替わったので、索引の作業を捨てました。")
    }

    private companion object {
        /** 本文を同時に読む数。関連ノートの並列読込と同じく上限を置く（→ features/related_notes_ai.md 判断6）。 */
        const val READ_PARALLELISM = 4
    }
}
