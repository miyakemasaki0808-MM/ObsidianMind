package com.example.newproject.controller

import com.example.newproject.domain.composeMarginMemo
import com.example.newproject.model.MarginMemo
import com.example.newproject.model.MarginMemoSlice
import com.example.newproject.model.MarginMemoStateWriter
import com.example.newproject.model.state.MarginMemoState
import com.example.newproject.model.state.MemoSaveStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 余白メモ — 読んでいる最中に置く短い断片。
 *
 * **AIを1回も呼ばない。** Reflect 系で唯一、端末AIの可否と無関係に動く。
 * `generateMutex` の待ち行列にも並ばないので、要約・関連ノート・痕跡要約の
 * 待ち時間を1ミリ秒も増やさない（→ features/reflect_margin_memo.md 判断1）。
 *
 * **保存そのものは持たない。** 3箇所（永続ファイル・セッションの預かり・退避）の
 * 合流は [ReadingTraceController] の契約で、ここは**画面の状態と入力の整形だけ**を持つ。
 */
internal class MarginMemoController(
    private val scope: CoroutineScope,
    private val state: MarginMemoStateWriter,
    /** このノートのメモを読む。**3箇所を合流した結果**が返る。 */
    private val loadMemos: suspend (vaultRelativePath: String) -> List<MarginMemo>,
    private val appendMemo: suspend (vaultRelativePath: String, memo: MarginMemo) -> MemoSaveOutcome,
    private val deleteMemo: suspend (vaultRelativePath: String, memo: MarginMemo) -> MemoDeleteOutcome,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private var loadJob: Job? = null
    private var writeJob: Job? = null
    private var activeRequestId = 0L

    /**
     * シートを開いたときに読む。**ノート表示の経路では呼ばない** —
     * 呼ぶとノートを開くたびサイドカーを1件読むことになる。
     */
    fun open(vaultRelativePath: String?) {
        val requestId = ++activeRequestId
        val path = vaultRelativePath?.takeIf { it.isNotBlank() }
        if (path == null) {
            // 相対パスが分からないノートには保存先が無い。空で開いて、書ける状態にはしない。
            state.update { it.copy(marginMemoState = MarginMemoState.Ready(memos = emptyList())) }
            return
        }
        state.update { it.copy(marginMemoState = MarginMemoState.Loading) }
        loadJob?.cancel()
        loadJob = scope.launch {
            val loaded = try {
                loadMemos(path)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isCurrent(requestId)) return@launch
                state.update { it.copy(marginMemoState = MarginMemoState.Error(e.message ?: "メモを読めませんでした。")) }
                return@launch
            }
            if (!isCurrent(requestId)) return@launch
            state.update { it.copy(marginMemoState = MarginMemoState.Ready(memos = loaded.newestFirst())) }
        }
    }

    /**
     * メモを置く。
     *
     * **入力欄を空にしてよいのは、置けたときだけ。** [MemoSaveStatus.Full] と
     * [MemoSaveStatus.Failed] では画面側が文字を残す（消すと「置けなかった」と
     * 「消された」が同じ顔になる）。
     *
     * [sectionTitle] は置いたときに見ていた見出し。**紐づけではなく歴史的記録**なので、
     * 解決できなければ null のままでよい。
     */
    fun save(vaultRelativePath: String?, raw: String, sectionTitle: String?) {
        val current = state.current.marginMemoState as? MarginMemoState.Ready ?: return
        if (current.status == MemoSaveStatus.Saving) return
        val draft = composeMarginMemo(raw)
        if (draft.isBlank) return
        val path = vaultRelativePath?.takeIf { it.isNotBlank() } ?: return

        val requestId = ++activeRequestId
        state.update { it.copy(marginMemoState = current.copy(status = MemoSaveStatus.Saving, wasTruncated = false)) }
        val memo = MarginMemo(
            text = draft.text,
            writtenAtEpochMillis = clock(),
            sectionTitle = sectionTitle
        )
        writeJob?.cancel()
        writeJob = scope.launch {
            val outcome = try {
                appendMemo(path, memo)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 例外も「どこにも残っていない」として扱う。握り潰すと
                // 画面だけ保存済みになり、メモが黙って消える。
                MemoSaveOutcome.Lost
            }
            if (!isCurrent(requestId)) return@launch
            state.update { latest ->
                val ready = latest.marginMemoState as? MarginMemoState.Ready ?: return@update latest
                // **置けた場合だけ一覧へ足す。** Full・Lost で足すと、
                // 画面には在るのにどこにも保存されていないメモができる。
                val memos = when (outcome) {
                    MemoSaveOutcome.Saved, MemoSaveOutcome.Held ->
                        (ready.memos + memo).newestFirst()
                    MemoSaveOutcome.Full, MemoSaveOutcome.Lost -> ready.memos
                }
                latest.copy(marginMemoState = ready.copy(
                    memos = memos,
                    status = when (outcome) {
                        MemoSaveOutcome.Saved -> MemoSaveStatus.Saved
                        // **保存済みとは呼ばない。** 離脱時の書き込みで確定する。
                        MemoSaveOutcome.Held -> MemoSaveStatus.Held
                        MemoSaveOutcome.Full -> MemoSaveStatus.Full
                        MemoSaveOutcome.Lost -> MemoSaveStatus.Failed
                    },
                    // 切り詰めは保存の成否と独立に示す（切ったうえで保存は成功しうる）。
                    wasTruncated = draft.wasTruncated &&
                        outcome != MemoSaveOutcome.Full &&
                        outcome != MemoSaveOutcome.Lost
                ))
            }
        }
    }

    /**
     * メモを1件消す。**不可逆なので画面側が確認を挟む。**
     *
     * 消えるのは3箇所すべてから（→ [ReadingTraceController] の契約5）。
     * 1箇所でも残ると、次の書き込み契機で合流して復活する。
     */
    fun delete(vaultRelativePath: String?, memo: MarginMemo) {
        val current = state.current.marginMemoState as? MarginMemoState.Ready ?: return
        val path = vaultRelativePath?.takeIf { it.isNotBlank() } ?: return

        val requestId = ++activeRequestId
        // 先に画面から消す。消えたように見えてから失敗したら、下で戻す。
        state.update { slice -> slice.copy(marginMemoState = current.copy(memos = current.memos.filterNot { it == memo })) }
        writeJob?.cancel()
        writeJob = scope.launch {
            val outcome = try {
                deleteMemo(path, memo)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MemoDeleteOutcome.Failed
            }
            if (!isCurrent(requestId)) return@launch
            if (outcome == MemoDeleteOutcome.Deleted) return@launch
            // **消せなかったら戻す。** 画面から消したままにすると、
            // 次に開いたとき復活して「消したはずのものが戻った」に見える。
            state.update { latest ->
                val ready = latest.marginMemoState as? MarginMemoState.Ready ?: return@update latest
                latest.copy(
                    marginMemoState = ready.copy(
                        memos = (ready.memos + memo).newestFirst(),
                        status = MemoSaveStatus.Failed
                    )
                )
            }
        }
    }

    /** シートの開閉。**開いたときにだけ**サイドカーを1件読む。 */
    fun setSheetVisible(visible: Boolean) {
        state.update { it.copy(isMarginMemoSheetVisible = visible) }
    }

    /**
     * ノート・Vault切替で読み書きを止め、旧ノートの結果が後から混入するのを防ぐ。
     * **シートも閉じる** — 開いたままだと前のノートのメモを載せた面が残る。
     */
    fun cancelAndClear() {
        activeRequestId++
        loadJob?.cancel()
        writeJob?.cancel()
        loadJob = null
        writeJob = null
        state.update { MarginMemoSlice(MarginMemoState.Idle, isMarginMemoSheetVisible = false) }
    }

    private fun isCurrent(requestId: Long): Boolean = activeRequestId == requestId

    /** 保存は追記順（古い順）。**読むのは書いた順の逆**なので、表示の直前で反転する。 */
    private fun List<MarginMemo>.newestFirst(): List<MarginMemo> =
        sortedByDescending { it.writtenAtEpochMillis }
}
