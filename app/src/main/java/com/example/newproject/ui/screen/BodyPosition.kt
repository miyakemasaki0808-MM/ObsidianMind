package com.example.newproject.ui.screen

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.newproject.ui.markdown.EndSpace
import com.example.newproject.ui.markdown.EndSpaceSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 本文の一覧の位置を書く口（→ features/note_spread.md §5「位置の書き込み」・§6 の監査表）。
 * **本文の位置を書く経路はすべてここを通す。** Activity で本文の一覧の状態と一緒に1つだけ作る。
 *
 * - 書き込みは `requestScrollToItem` — 一覧が一度も組まれていなくても効く。`scrollToItem` は一覧の初回の配置を待つので、見開きの間に呼ぶと効かない
 * - 書くたびに要求の番号を進める（[lastRequest]）。同じ値の送りも見開きが見分けられるように
 * - アニメーションで送る口（[animateTo]）の Job は1本だけ持ち、次の書き込みで取り消す。遅れて終わった送りが新しい位置を上書きしない
 * - [port] はこの口を見分ける値。Activity を作り直すと口も作り直され、番号は [FIRST_BODY_REQUEST] から数え直す
 * - 見出しへ送るときは [placeAtTop] を使う。末尾に近い見出しでも上端に置けるよう、[endSpace] に末尾の余白を足す。
 *   余白は次の書き込み（[moveTo]）で消すので、ほかのノートや先頭からの読み直しには残らない。
 *   **[endSpace] は画面の保存値に置いたものを渡す**（→ rememberEndSpace）。別の一覧（全画面）から戻るときは [takeOver] で余白ごと引き継ぐ
 */
@Stable
internal class BodyPosition(
    val listState: LazyListState,
    /** 見出しを上端に置くための本文の末尾の余白。本文の一覧へ渡す（→ [EndSpace]）。 */
    val endSpace: EndSpace = EndSpace(),
    val port: Long = Random.nextLong()
) {
    private val requests = BodyPositionRequests(listState.firstVisibleItemIndex)
    private var animation: Job? = null

    /** 最後の書き込み。見開きはこの番号が進んだのを見て、外からの送りなら読む位置を替える。 */
    val lastRequest: BodyPositionRequest get() = requests.last

    fun moveTo(block: Int, offset: Int = 0, source: BodyPositionSource = BodyPositionSource.External) {
        cancelAnimation()
        endSpace.clear()
        listState.requestScrollToItem(block, offset)
        requests.record(block, source)
    }

    /** [block] を上端に置く。末尾に近くて一覧が届かなければ、本文の一覧が足りない分の余白を足して送り直す。 */
    fun placeAtTop(block: Int) {
        moveTo(block)
        endSpace.want(block)
    }

    /** 別の一覧（全画面）で読んでいた位置を、その位置を成り立たせていた余白ごと引き継ぐ（→ [EndSpace.restore]）。 */
    fun takeOver(block: Int, offset: Int, space: EndSpaceSnapshot) {
        cancelAnimation()
        endSpace.restore(space)
        listState.requestScrollToItem(block, offset)
        requests.record(block, BodyPositionSource.External)
    }

    /** [from] の一覧（全画面）の今の位置と、その一覧の余白 [space] を引き継ぐ。 */
    fun takeOver(from: LazyListState, space: EndSpace) =
        takeOver(from.firstVisibleItemIndex, from.firstVisibleItemScrollOffset, space.snapshot(from))

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

/** 口を作った時点の番号。この番号はまだ何も書いていないことを表す。 */
internal const val FIRST_BODY_REQUEST = 0L

/** 書き込みの番号を数える。番号は位置の値と別に持つ — 同じ値を書いても位置の値は変わらない。 */
internal class BodyPositionRequests(initialBlock: Int) {
    var last by mutableStateOf(BodyPositionRequest(FIRST_BODY_REQUEST, initialBlock, BodyPositionSource.External))
        private set

    fun record(block: Int, source: BodyPositionSource): BodyPositionRequest {
        last = BodyPositionRequest(last.request + 1, block, source)
        return last
    }
}
