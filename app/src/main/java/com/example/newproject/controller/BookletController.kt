package com.example.newproject.controller

import com.example.newproject.data.VaultBrowser
import com.example.newproject.data.VaultHandle
import com.example.newproject.domain.selectCoverLine
import com.example.newproject.model.BookletCover
import com.example.newproject.model.BookletEntry
import com.example.newproject.model.BookletSeed
import com.example.newproject.model.BookletStateWriter
import com.example.newproject.model.NoteFile
import com.example.newproject.model.buildWeaveState
import com.example.newproject.model.state.BookletBundle
import com.example.newproject.model.state.BookletMode
import com.example.newproject.model.state.BookletState
import com.example.newproject.model.state.RelatedNotesState
import com.example.newproject.model.state.canWeave
import com.example.newproject.model.state.updateBundle
import com.example.newproject.model.state.updateVisibleBundle
import com.example.newproject.model.state.visibleBundle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 1回で引く枚数。**編む束の上限も同じ**（→ features/booklet_mode.md 判断12）。 */
internal const val BOOKLET_SIZE = 10

/** 扉を先読みする範囲（現在ページの前後）。 */
internal const val BOOKLET_COVER_PREFETCH = 1

/**
 * 冊子（10枚の束）。
 *
 * ## Vault単位である
 *
 * ノート切替では止めない。冊子から「これを読む」でノートへ渡り、**戻れば同じ10枚が残る**
 * のが冊子の目的そのものなので、ノート単位の契約
 * （`cancelNoteScopedJobs` / `withNoteScopedReset`）へは登録しない。
 * 無効化の契機はVault切替だけで、補記一覧・痕跡の整理と同じ扱いになる。
 *
 * ## 束は2つある
 *
 * 引く束（純粋ランダム）と編む束（済んだAI推薦から）。**行き来しても両方残る**。
 * 編む束は [open] の一度きりで作り、**種は束と同じ寿命で固定する** —
 * 「もう10枚引く」でも作り直さない（→ [drawAgain]）。
 *
 * ## 冊子では記録もAIも始めない
 *
 * ここが読むのは扉のための8KBだけで、**訪問記録・要約・関連ノートには一切触れない**。
 * 契約は「**冊子候補について新しいAI・痕跡・履歴を開始しない**」であって、
 * **済んだ結果を読むことは禁じていない** — 編む束はその範囲で成立する
 * （→ features/booklet_mode.md 判断8・判断12）。
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

    /**
     * 走行中の扉読み込み。**キーに束の世代を含める** — 束が2つあるので、
     * 同じ index でも別の束の扉が同時に走る。
     */
    private val coverJobs = mutableMapOf<CoverKey, Job>()

    /**
     * 束の通し番号。**2つの束で共有する。**
     *
     * 分けると「表示中の束が変わった」を画面が1つの値で見分けられなくなる。
     * 通しにしておけば、引き直しでもモード切替でも同じ形で新しい値が届く。
     */
    private var nextBundleId = 0L

    /**
     * いま作りにいっている引く束の世代。**「もう10枚引く」で作り直したとき、
     * 前の束へ向かっていた組み立てを捨てる。** キャンセルだけでは足りない経路
     * （走査が戻ってくる途中）があるので、`update` の直前に照合する。
     */
    private var activeDrawBundleId = 0L

    private data class CoverKey(val bundleId: Long, val index: Int)

    /**
     * 冊子をひらく。**引く束と編む束を同時に作る。**
     *
     * [seed] は冊子へ入る直前に開いていたノート、[related] はそのノートの**済んだ**AI推薦で、
     * どちらも**この一瞬の値をコピーする**。観測し続ける形にすると、`relatedNotesState` は
     * ノート単位で消える（`withNoteScopedReset()`）のに束はVault単位なので、
     * **冊子から「これを読む」で渡った瞬間に編む束が消える**。
     *
     * [loadNotes] を受け取るのは、ノート一覧のTTLキャッシュがViewModel側にあるため。
     * ここで `collectAllNotes()` を直に叩くと、開くたびにVault全走査になる。
     *
     * **枚数は `min(10, 利用可能数)`。** 0件なら空の束になり、
     * 画面が「引けるノートが無い」を出す（別のvariantを作らない → §10）。
     */
    fun open(
        seed: BookletSeed?,
        related: RelatedNotesState,
        loadNotes: suspend () -> List<NoteFile>
    ) {
        val drawBundleId = ++nextBundleId
        val weaveBundleId = ++nextBundleId
        val generation = vaultGeneration()
        activeDrawBundleId = drawBundleId
        cancelCoverJobs()
        drawJob?.cancel()
        state.update { BookletState.Loading }
        drawJob = scope.launch {
            val next = try {
                BookletState.Open(
                    drawn = BookletBundle(entries = drawEntries(loadNotes), bundleId = drawBundleId),
                    weave = buildWeaveState(seed, related, size, weaveBundleId),
                    // **開いた直後は必ず引く側。** 編む束があっても、冊子の入口は
                    // 「偶然の再会」のままにする（→ features/booklet_mode.md 判断12）。
                    mode = BookletMode.Draw
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BookletState.Failed(e.message ?: "冊子を作れませんでした。")
            }
            if (isCurrent(drawBundleId, generation)) state.update { next }
        }
    }

    /**
     * 「もう10枚引く」。**引く束だけを作り直す。**
     *
     * **編む束と種には触れない** — 種は束と同じ寿命で固定すると決めてあり、
     * ここで作り直すと「冊子の中で読んだ別のノート」が種にすり替わる。
     *
     * **`Loading` を挟まない。** 挟むと編む束が状態から一度消えるので、
     * 走査に時間がかかる間だけトグルが引っ込む。
     *
     * **失敗しても `Failed` へ落とさない。** 落とすと**引き直しの失敗が編む束と種まで
     * 巻き添えにする**（2026-09-07 のレビュー `P2-1`）。`Failed` は「冊子そのものが作れない」
     * 状態のために取っておき、ここは [BookletState.Open.redrawError] で伝える。
     */
    fun drawAgain(loadNotes: suspend () -> List<NoteFile>) {
        val open = state.current as? BookletState.Open ?: return
        val drawBundleId = ++nextBundleId
        val generation = vaultGeneration()
        val previousDrawId = open.drawn.bundleId
        activeDrawBundleId = drawBundleId
        // **引く束の扉だけを止める。** 編む束の読み込みは道連れにしない。
        cancelCoverJobs { it.bundleId == previousDrawId }
        drawJob?.cancel()
        // 前回の失敗表示を先に消す。押したのに古い理由が残っていると、また失敗したように見える。
        state.update { current ->
            if (current is BookletState.Open) current.copy(redrawError = null) else current
        }
        drawJob = scope.launch {
            val entries = try {
                drawEntries(loadNotes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isCurrent(drawBundleId, generation)) {
                    state.update { current ->
                        if (current !is BookletState.Open) return@update current
                        // **束は1つも捨てない。** 前の引く束のまま、終端で理由と再試行を出す。
                        current.copy(redrawError = e.message ?: "引き直せませんでした。")
                    }
                }
                return@launch
            }
            if (!isCurrent(drawBundleId, generation)) return@launch
            state.update { current ->
                if (current !is BookletState.Open) return@update current
                current.copy(
                    drawn = BookletBundle(entries = entries, bundleId = drawBundleId),
                    redrawError = null
                )
            }
            // **新しい束の扉をここから起こす**（2026-09-07 のレビュー `P2-2`）。
            // `Loading` を挟まなくなったので、**ページ番号と枚数が引き直しの前後で同じ**だと
            // 画面側の読込Effectはどちらの鍵も変わらず再実行されない。
            // 位置合わせも同じ位置なら動かないので、誰も扉を要求しないまま先頭が読み込み中で止まる。
            ensureCovers((state.current as? BookletState.Open)?.visibleBundle?.page ?: 0)
        }
    }

    /**
     * 引く⇄編むを切り替える。
     *
     * **編む束が用意できていなければ何も起きない。** 画面はその状態のトグルを押せなくするが、
     * 「押せない」を画面だけの約束にしない。
     */
    fun setMode(mode: BookletMode) {
        val open = state.current as? BookletState.Open ?: return
        if (open.mode == mode) return
        if (mode == BookletMode.Weave && !open.canWeave) return
        state.update { current ->
            if (current is BookletState.Open) current.copy(mode = mode) else current
        }
        // **切り替えた先の扉を用意する。** ページ位置と枚数がたまたま同じだと
        // 画面側の `onPageSettled` が呼ばれないので、ここから起こす。
        ensureCovers((state.current as? BookletState.Open)?.visibleBundle?.page ?: 0)
    }

    /**
     * ページが決まったときに呼ぶ。**現在ページを覚え、前後1ページの扉を用意する。**
     *
     * **覚える先を束と同じ場所にする。** 画面ローカルに置くと、通常表示へ渡って戻る往復で
     * ページ位置だけが失われる（2026-08-31 の実機検証で再現）。
     * 「戻れば同じ10枚が同じページ位置」は束とページ位置の2つで1つの条件なので、寿命を揃える。
     * **束が2つになっても同じ** — 更新するのは表示中の束だけで、もう一方の位置は動かさない。
     */
    fun onPageSettled(page: Int) {
        val open = state.current as? BookletState.Open ?: return
        if (open.visibleBundle.page != page) {
            state.update { current ->
                if (current is BookletState.Open) current.updateVisibleBundle { it.copy(page = page) }
                else current
            }
        }
        ensureCovers(page)
    }

    /** Vault切替。**状態は落とさない** — `withVaultScopedReset()` が唯一の登録点として落とす。 */
    fun onVaultChanged() {
        drawJob?.cancel()
        drawJob = null
        cancelCoverJobs()
    }

    private suspend fun drawEntries(loadNotes: suspend () -> List<NoteFile>): List<BookletEntry> =
        // **重複させない。** `random()` の10回呼びではなく、並べ替えてから先頭を取る。
        shuffle(loadNotes()).take(size).map { BookletEntry(it.ref, it.name) }

    /**
     * [page] とその前後1ページの扉を用意する。**表示中の束だけ**が対象。
     *
     * 既に読めているページ・失敗したページ・読み込み中のページは二度読まない。
     * 失敗を読み直さないのは、消えたノートに対して**めくるたびにSAFを叩き続ける**のを防ぐため。
     */
    private fun ensureCovers(page: Int) {
        val bundle = (state.current as? BookletState.Open)?.visibleBundle ?: return
        val handle = vault.current() ?: return
        val generation = vaultGeneration()
        for (index in (page - BOOKLET_COVER_PREFETCH)..(page + BOOKLET_COVER_PREFETCH)) {
            val entry = bundle.entries.getOrNull(index) ?: continue
            val key = CoverKey(bundle.bundleId, index)
            if (entry.cover != BookletCover.Loading || coverJobs.containsKey(key)) continue
            val job = scope.launch { loadCover(key, entry, handle, generation) }
            coverJobs[key] = job
            // **同じキーの新しいJobを消さない。** 引き直しで積み直った後に
            // 古いJobの完了が届くことがある。
            job.invokeOnCompletion { coverJobs.remove(key, job) }
        }
    }

    private suspend fun loadCover(
        key: CoverKey,
        entry: BookletEntry,
        handle: VaultHandle,
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
        if (generation != vaultGeneration()) return
        state.update { current ->
            if (current !is BookletState.Open) return@update current
            // **どの束の結果かは [CoverKey.bundleId] が決める。** 束が2つあるので、
            // 引く束向けの扉を編む束の同じ位置へ書かないためにここが要る。
            // 引き直しで消えた束宛ての結果は、該当が無いのでそのまま落ちる。
            current.updateBundle(key.bundleId) { bundle ->
                val existing = bundle.entries.getOrNull(key.index) ?: return@updateBundle bundle
                bundle.copy(
                    entries = bundle.entries.toMutableList()
                        .also { it[key.index] = existing.copy(cover = cover) }
                )
            }
        }
    }

    private fun cancelCoverJobs(predicate: (CoverKey) -> Boolean = { true }) {
        val keys = coverJobs.keys.filter(predicate)
        keys.forEach { key -> coverJobs.remove(key)?.cancel() }
    }

    /** **照合は `update` の直前の1箇所だけ**（→ system/architecture.md 判断4）。 */
    private fun isCurrent(drawBundleId: Long, generation: Long): Boolean =
        drawBundleId == activeDrawBundleId && generation == vaultGeneration()
}
