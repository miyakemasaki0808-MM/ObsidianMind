package com.example.newproject.ui.screen

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 本文の一覧の位置を書く口（→ features/note_spread.md §5「位置の書き込み」・§6 の監査表）。
 * **本文の位置を書く経路はすべてここを通す。** Activity で本文の一覧の状態と一緒に1つだけ作る。
 *
 * - 書き込みは `requestScrollToItem` — 一覧が一度も組まれていなくても効く。`scrollToItem` は一覧の初回の配置を待つので、見開きの間に呼ぶと効かない
 * - 書くたびに要求の番号を進める（[lastRequest]）。同じ値の送りも見開きが見分けられるように
 * - アニメーションで送る口（[animateTo]）の Job は1本だけ持ち、次の書き込みで取り消す。遅れて終わった送りが新しい位置を上書きしない
 */
@Stable
internal class BodyPosition(val listState: LazyListState) {
    private val requests = BodyPositionRequests(listState.firstVisibleItemIndex)
    private var animation: Job? = null

    /** 最後の書き込み。見開きはこの番号が進んだのを見て、外からの送りなら読む位置を替える。 */
    val lastRequest: BodyPositionRequest get() = requests.last

    fun moveTo(block: Int, offset: Int = 0, source: BodyPositionSource = BodyPositionSource.External) {
        cancelAnimation()
        listState.requestScrollToItem(block, offset)
        requests.record(block, source)
    }

    /** 縦の流しで、アニメーションで送る。**一覧が組まれていないとき（見開きの間）は [moveTo] を使う** — 初回の配置を待って終わらない。 */
    fun animateTo(scope: CoroutineScope, block: Int) {
        cancelAnimation()
        requests.record(block, BodyPositionSource.External)
        animation = scope.launch { listState.animateScrollToItem(block) }
    }

    fun cancelAnimation() {
        animation?.cancel()
        animation = null
    }
}

/** 本文の一覧の位置の書き込みの出どころ。 */
internal enum class BodyPositionSource {
    /** リンク・続きから読む・節へ飛ぶ・ノートの始まり・全画面からの戻りなど、見開きの外からの送り。 */
    External,

    /** 見開きのめくりが決まって、読む位置を書き戻した。 */
    SpreadTurn
}

/** 本文の一覧の位置への1回の書き込み。[request] は書くたびに進む番号で、同じ値の送りも見分ける。 */
internal data class BodyPositionRequest(val request: Long, val blockIndex: Int, val source: BodyPositionSource)

/** 書き込みの番号を数える。番号は位置の値と別に持つ — 同じ値を書いても位置の値は変わらない。 */
internal class BodyPositionRequests(initialBlock: Int) {
    var last by mutableStateOf(BodyPositionRequest(0, initialBlock, BodyPositionSource.External))
        private set

    fun record(block: Int, source: BodyPositionSource): BodyPositionRequest {
        last = BodyPositionRequest(last.request + 1, block, source)
        return last
    }
}
