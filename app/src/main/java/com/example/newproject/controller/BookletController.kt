package com.example.newproject.controller

import com.example.newproject.data.VaultBrowser
import com.example.newproject.data.VaultHandle
import com.example.newproject.domain.selectCoverLine
import com.example.newproject.model.BookletCover
import com.example.newproject.model.BookletEntry
import com.example.newproject.model.BookletStateWriter
import com.example.newproject.model.NoteFile
import com.example.newproject.model.state.BookletState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 1回で引く枚数。 */
internal const val BOOKLET_SIZE = 10

/** 扉を先読みする範囲（現在ページの前後）。 */
internal const val BOOKLET_COVER_PREFETCH = 1

/**
 * 冊子（ランダムに引いた10枚の束）。
 *
 * ## Vault単位である
 *
 * ノート切替では止めない。冊子から「これを読む」でノートへ渡り、**戻れば同じ10枚が残る**
 * のが冊子の目的そのものなので、ノート単位の契約
 * （`cancelNoteScopedJobs` / `withNoteScopedReset`）へは登録しない。
 * 無効化の契機はVault切替だけで、補記一覧・痕跡の整理と同じ扱いになる。
 *
 * ## 冊子では記録もAIも始めない
 *
 * ここが読むのは扉のための8KBだけで、**訪問記録・要約・関連ノートには一切触れない**。
 * 契約は「**冊子候補について新しいAI・痕跡・履歴を開始しない**」であって、
 * 冊子へ入る前から走っている処理を止めるものではない（→ features/booklet_mode.md 判断8）。
 *
 * ## 本文を10枚ぶん抱えない
 *
 * 保持するのは参照・タイトルと抽出後の1行だけ。読み出しは現在ページと前後1ページに限り、
 * 全文ではなく8KBの境界読み出しを使う（→ 判断4・§5）。
 */
internal class BookletController(
    private val scope: CoroutineScope,
    private val vault: VaultBrowser,
    private val state: BookletStateWriter,
    /** Vault単位の世代。走行中に切り替わったら結果を捨てる。 */
    private val vaultGeneration: () -> Long,
    /**
     * 扉の抽出を逃がす先。入力は8KBだが、**純粋と軽いは別**なので原則どおり Main の外へ出す
     * （→ lessons L13）。
     */
    private val coverDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val size: Int = BOOKLET_SIZE,
    /**
     * 束の並べ方。**差し替えるのはテストのためだけ**で、本番は素の [List.shuffled] を使う。
     * 引きに条件を足す穴にしない（→ features/booklet_mode.md 判断1）。
     */
    private val shuffle: (List<NoteFile>) -> List<NoteFile> = { it.shuffled() }
) {
    private var drawJob: Job? = null
    private val coverJobs = mutableMapOf<Int, Job>()

    /**
     * 束の世代。**「もう10枚引く」で作り直したとき、前の束へ向かっていた扉の読み込みを捨てる。**
     * キャンセルだけでは足りない経路（読み出しが戻ってくる途中）があるので、
     * `update` の直前に照合する。
     */
    private var activeDrawId = 0L

    /**
     * 10枚を引く。**押すたびに新しい束**を作る（→ features/booklet_mode.md 判断6）。
     *
     * [loadNotes] を受け取るのは、ノート一覧のTTLキャッシュがViewModel側にあるため。
     * ここで `collectAllNotes()` を直に叩くと、開くたびにVault全走査になる。
     *
     * **枚数は `min(10, 利用可能数)`。** 0件なら空の [BookletState.Open] になり、
     * 画面が「引けるノートが無い」を出す（別のvariantを作らない → §10）。
     */
    fun draw(loadNotes: suspend () -> List<NoteFile>) {
        val drawId = ++activeDrawId
        val generation = vaultGeneration()
        cancelCoverJobs()
        drawJob?.cancel()
        state.update { BookletState.Loading }
        drawJob = scope.launch {
            val next = try {
                // **重複させない。** `random()` の10回呼びではなく、並べ替えてから先頭を取る。
                BookletState.Open(
                    shuffle(loadNotes()).take(size).map { BookletEntry(it.ref, it.name) }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BookletState.Failed(e.message ?: "冊子を作れませんでした。")
            }
            if (isCurrent(drawId, generation)) state.update { next }
        }
    }

    /**
     * ページが決まったときに呼ぶ。**現在ページを覚え、前後1ページの扉を用意する。**
     *
     * **覚える先を束と同じ場所にする。** 画面ローカルに置くと、通常表示へ渡って戻る往復で
     * ページ位置だけが失われる（2026-08-31 の実機検証で再現）。
     * 「戻れば同じ10枚が同じページ位置」は束とページ位置の2つで1つの条件なので、寿命を揃える。
     */
    fun onPageSettled(page: Int) {
        val open = state.current as? BookletState.Open ?: return
        if (open.page != page) {
            state.update { current ->
                if (current is BookletState.Open) current.copy(page = page) else current
            }
        }
        ensureCovers(page)
    }

    /**
     * [page] とその前後1ページの扉を用意する。
     *
     * 既に読めているページ・失敗したページ・読み込み中のページは二度読まない。
     * 失敗を読み直さないのは、消えたノートに対して**めくるたびにSAFを叩き続ける**のを防ぐため。
     */
    private fun ensureCovers(page: Int) {
        val entries = (state.current as? BookletState.Open)?.entries ?: return
        val handle = vault.current() ?: return
        val drawId = activeDrawId
        val generation = vaultGeneration()
        for (index in (page - BOOKLET_COVER_PREFETCH)..(page + BOOKLET_COVER_PREFETCH)) {
            val entry = entries.getOrNull(index) ?: continue
            if (entry.cover != BookletCover.Loading || coverJobs.containsKey(index)) continue
            val job = scope.launch { loadCover(index, entry, handle, drawId, generation) }
            coverJobs[index] = job
            // **同じ index の新しいJobを消さない。** 引き直しで積み直った後に
            // 古いJobの完了が届くことがある。
            job.invokeOnCompletion { coverJobs.remove(index, job) }
        }
    }

    /** Vault切替。**状態は落とさない** — `withVaultScopedReset()` が唯一の登録点として落とす。 */
    fun onVaultChanged() {
        drawJob?.cancel()
        drawJob = null
        cancelCoverJobs()
    }

    private suspend fun loadCover(
        index: Int,
        entry: BookletEntry,
        handle: VaultHandle,
        drawId: Long,
        generation: Long
    ) {
        val cover = try {
            // **null は「開けなかった」。** 空の本文（タイトルへフォールバックしてよい）と
            // 区別しないと、消えたノートのページが読めたように見える。
            val snippet = handle.readNoteSnippet(entry.ref)
            if (snippet == null) {
                BookletCover.Failed
            } else {
                BookletCover.Ready(withContext(coverDispatcher) { selectCoverLine(snippet, entry.title) })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 束を作った後に削除・改名されるとここへ来る。**そのページだけ**失敗にする。
            BookletCover.Failed
        }
        if (!isCurrent(drawId, generation)) return
        state.update { current ->
            if (current !is BookletState.Open) return@update current
            // **参照の一致は見ない。** 束を差し替えるのは [draw] だけで、そこでは必ず
            // `activeDrawId` が進むため、上の照合を通った時点で同じ束であることが決まっている。
            // 変異確認でも ref 照合を消して落ちるテストが書けなかったので置かない
            // （→ system/architecture.md「落ちるテストを書けないガードは削除の候補」）。
            val existing = current.entries.getOrNull(index) ?: return@update current
            current.copy(
                entries = current.entries.toMutableList().also { it[index] = existing.copy(cover = cover) }
            )
        }
    }

    private fun cancelCoverJobs() {
        coverJobs.values.forEach { it.cancel() }
        coverJobs.clear()
    }

    /** **照合は `update` の直前の1箇所だけ**（→ system/architecture.md 判断4）。 */
    private fun isCurrent(drawId: Long, generation: Long): Boolean =
        drawId == activeDrawId && generation == vaultGeneration()
}
