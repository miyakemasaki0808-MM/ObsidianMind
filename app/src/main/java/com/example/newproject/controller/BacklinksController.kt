package com.example.newproject.controller

import com.example.newproject.data.KnowledgeIndexStore
import com.example.newproject.domain.NoteLinkIndex
import com.example.newproject.domain.backlinkRows
import com.example.newproject.domain.noteKeyOf
import com.example.newproject.model.BacklinksStateWriter
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.NoteScanKind
import com.example.newproject.model.PublishedNoteScan
import com.example.newproject.model.state.BacklinksState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 今のノートへのリンクを引く（→ docs/dev/features/backlinks.md）。**ノート単位**で、`cancelNoteScopedJobs()` と
 * `withNoteScopedReset()` の両方に載せる（→ architecture.md 判断4 の1行目）。AI を呼ばない。
 *
 * 結果は DB の行と走査の入れ物のリンクの索引の2つで決まるので、**どちらかが替わるたびに呼び出し側が [refresh] を呼ぶ**
 * （→ knowledge_index.md §5）。[refresh] は前の照会を取り消してから始める。
 *
 * **失効はジョブの取り消しで守る。** 置き場の読み出しは取り消しで止まらないことがあり、取り消した後に戻った結果
 * （失敗や別の Vault）は取り消しを確かめずに届くので、書く前にこのジョブがまだ生きているかを確かめる
 * （並べ読みと同じ形 → [SideReadingController]）。
 */
internal class BacklinksController(
    private val scope: CoroutineScope,
    private val store: KnowledgeIndexStore,
    private val state: BacklinksStateWriter,
    private val vaultKey: () -> String?,
    /** 今の Vault で最後に受けた公開。まだ無ければ null。 */
    private val latestScan: () -> PublishedNoteScan?,
    /** 解くのに使う走査のリンクの索引。公開と同時に替わる。 */
    private val linkIndex: () -> NoteLinkIndex,
    private val resolveDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private var job: Job? = null

    /**
     * [current] へのリンクを引き直す。引いている間も、前に引けた行は残す（索引が直るたびに節が消えて戻るのを避ける）。
     *
     * - 準備完了の公開がまだ無い間は「引いている」で待つ。リンクの索引が空だと全部「まだ引けない」になり、0件に見えてしまう
     * - 最新の公開が読めずに空なら「読めない」。0件とは言わない
     * - 今のノートが走査に無ければ、どのリンクも今のノートへは着かないので0件
     */
    fun refresh(current: DocumentRef) {
        job?.cancel()
        job = null
        val scan = latestScan()
        if (scan == null) {
            state.set(BacklinksState.Loading)
            return
        }
        if (scan.kind == NoteScanKind.UNREADABLE_EMPTY) {
            state.set(BacklinksState.Unavailable)
            return
        }
        val key = vaultKey() ?: return state.set(BacklinksState.Unavailable)
        val index = linkIndex()
        val target = index.noteAt(current) ?: return state.set(BacklinksState.Ready(emptyList()))
        if (state.current !is BacklinksState.Ready) state.set(BacklinksState.Loading)
        job = scope.launch {
            val rows = try {
                store.backlinkCandidates(key, noteKeyOf(target.name))?.let { candidates ->
                    store.linksFrom(key, target.vaultRelativePath)?.let { outgoing ->
                        withContext(resolveDispatcher) { backlinkRows(candidates, outgoing, current, index) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 置き場が使えない。読書は止めず、節で「数えられませんでした」と出す。
                null
            }
            ensureActive()
            state.set(rows?.let { BacklinksState.Ready(it) } ?: BacklinksState.Unavailable)
        }
    }

    /** ノート・Vault切替。状態は `withNoteScopedReset()` が落とす。 */
    fun cancelAndClear() {
        job?.cancel()
        job = null
    }
}
