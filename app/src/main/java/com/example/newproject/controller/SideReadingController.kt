package com.example.newproject.controller

import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.domain.markdown.parseMarkdownBlocks
import com.example.newproject.model.RelatedNote
import com.example.newproject.model.SideReadingStateWriter
import com.example.newproject.model.state.SideFace
import com.example.newproject.model.state.SideReadingState
import com.example.newproject.model.state.face
import com.example.newproject.model.state.withFace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 並べ読み（→ features/margin_pane.md §5.9）。関連ノートをペインの右で**眺めるだけ**のために読む。
 *
 * **今のノートにしない。** ほかの Controller はどれも今のノートを前提にしているので、眺めるだけのノートはここに閉じる。
 * 訪問の記録・要約・分野判定・余白メモのどれも呼ばない。
 *
 * 寿命はノート単位（→ architecture.md 判断4の1行目）。ノート切替と Vault 切替では [cancelAndClear] がジョブと本文を、
 * `withNoteScopedReset()` が状態を落とす。余白へ戻る・ペインを閉じる・余白のシートを出したときは [close]。
 *
 * **失効はジョブの取り消しで守る。** 書き込みはすべてこのジョブの中にあり、[open]・[close]・[cancelAndClear] は
 * どれも先にジョブを取り消す。読み出しと解析が正常に戻ったときは `withContext` が取り消しを確かめるので、遅れた本文は書かれない。
 * **例外で戻ったときは確かめない** — 読み出しは同期の I/O で取り消しでは止まらず、取り消した後に失敗すると、その例外が
 * 取り消しより優先して届く。だから失敗を書く前に、このジョブがまだ生きているかを確かめる。
 * 要求の番号を足すなら、先に「番号を消すと落ちるテスト」を書けることを確かめる。
 */
internal class SideReadingController(
    private val scope: CoroutineScope,
    private val state: SideReadingStateWriter,
    private val parseDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val mutableBlocks = MutableStateFlow<List<MarkdownBlock>?>(null)

    /**
     * 読めたノートの本文の解析結果。読める前と、並べ読みをしていない間は null。
     * **状態を [SideReadingState.Ready] にする直前に書き、状態を落とすときに一緒に消す。**
     */
    val blocks: StateFlow<List<MarkdownBlock>?> = mutableBlocks.asStateFlow()

    private var job: Job? = null

    /**
     * [note] を右で開く。前の要求は取り消す。[read] は本文を読む口で、Android の I/O を持つ窓口が渡す。
     * 失敗したら同じノートでもう一度呼べば再試行になる。
     */
    fun open(note: RelatedNote, read: suspend () -> String) {
        job?.cancel()
        mutableBlocks.value = null
        state.set(SideReadingState.Loading(note))
        job = scope.launch {
            try {
                val content = read()
                // 最大1MBの本文を解析するので Main の外で（→ architecture.md 判断3）。
                val parsed = withContext(parseDispatcher) { parseMarkdownBlocks(content) }
                mutableBlocks.value = parsed
                // 読み込む間に右の面を書きかけへ替えていたら、そのまま（→ features/margin_quote.md §5.6）。
                state.set(SideReadingState.Ready(note, state.current.face))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 取り消した後の失敗は、閉じた面や選び直した候補を上書きする。取り消されていれば投げ直して何も書かない。
                ensureActive()
                state.set(SideReadingState.Failed(note, e.message ?: "読み込めませんでした", state.current.face))
            }
        }
    }

    /**
     * 右の面の出し分けを替える（→ features/margin_quote.md §5.6）。左で引用したら書きかけ、切替や戻る操作で選んだ方。
     * 並べ読みをしていなければ何もしない。**新しい候補を開くと資料から始まる**（[open]）。
     */
    fun showFace(face: SideFace) {
        state.set(state.current.withFace(face))
    }

    /**
     * 送ったメモが受け取れて、**その受理で今の書きかけを空にできた**。右を資料へ戻す（→ features/margin_quote.md §5.6）。
     * 資料のままなら変わらない — 保存を待つ間に手で資料へ切り替えていたら、結果の到着でそれを覆さない。
     */
    fun draftConsumed() = showFace(SideFace.Reference)

    /** 余白へ戻る・ペインを閉じる・余白のシートを出した。 */
    fun close() {
        cancelAndClear()
        state.set(SideReadingState.Idle)
    }

    /** ノート・Vault切替。状態は `withNoteScopedReset()` が落とす。 */
    fun cancelAndClear() {
        job?.cancel()
        job = null
        mutableBlocks.value = null
    }
}
