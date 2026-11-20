package com.example.newproject.ui.markdown

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.takeWhile
import kotlin.math.abs

/**
 * 見出しへ送ったときに、見出しを本文の最初の行に置くための**本文の末尾の余白**（→ features/note_links.md 判断6）。
 *
 * 本文の一覧は末尾より先へ送れないので、見出しから後ろが画面より短いと、見出しの上に前の節の終わりが見える。
 * 送った見出しが上端に来なかったときだけ、足りない分を末尾に足して送り直す。**ほかのノートでは余白を出さない** —
 * 余白は持ち主（[com.example.newproject.ui.screen.BodyPosition] か右の面のノートごと）が位置と同じ寿命で持ち、ほかの送りで消す。
 * **位置と一緒に保存し、別の一覧へ運ぶ**（[snapshot]・[restore]）。位置だけが戻ると、末尾の上限で前の節が戻る。
 */
@Stable
internal class EndSpace {
    /** 本文の一覧の末尾に足す余白（px）。 */
    var bottomPx by mutableIntStateOf(0)
        private set

    /** 上端に置いておきたいブロック。利用者が自分で送るか、ほかの送りで消えるまで持つ（→ [settleAtTop]）。 */
    var wanted by mutableStateOf<EndSpaceRequest?>(null)
        private set

    /** 余白を足す元になったブロック。保存値から戻すときに、置き直す先になる。 */
    private var placedBlock: Int? = null

    private var count = 0L

    /** [block] を上端に置きたい。**送りの依頼の後に呼ぶ** — 置けたかは、依頼が届いた後の配置で決める。 */
    fun want(block: Int) {
        placedBlock = block
        wanted = EndSpaceRequest(block, ++count)
    }

    /** ほかの位置へ送った。余白も置きたいブロックも捨てる。 */
    fun clear() {
        wanted = null
        bottomPx = 0
        placedBlock = null
    }

    /** [request] がまだ最新なら、余白を [px] 足す。 */
    fun grow(request: EndSpaceRequest, px: Int) {
        if (wanted == request) bottomPx += px
    }

    /** [request] を置き終えた（利用者が自分で送った）。 */
    fun finish(request: EndSpaceRequest) {
        if (wanted == request) wanted = null
    }

    /** 保存値や別の一覧へ運ぶ形。[listState] はこの余白を使っている一覧。 */
    fun snapshot(listState: LazyListState): EndSpaceSnapshot = endSpaceSnapshot(
        bottomPx = bottomPx,
        placedBlock = placedBlock,
        placing = wanted != null,
        firstIndex = listState.firstVisibleItemIndex,
        firstOffset = listState.firstVisibleItemScrollOffset
    )

    /** [snapshot] から戻す。置き直す印があれば、窓の寸法が違っても足りない分を足せるよう、同じブロックをもう一度頼む。 */
    fun restore(snapshot: EndSpaceSnapshot) {
        bottomPx = snapshot.bottomPx
        placedBlock = snapshot.block
        wanted = snapshot.block?.takeIf { snapshot.replace }?.let { EndSpaceRequest(it, ++count) }
    }
}

/** 上端に置きたいブロックの依頼。同じブロックを続けて頼んでも見分けられるよう番号を持つ。 */
internal data class EndSpaceRequest(val block: Int, val id: Long)

/** [EndSpace] を運ぶ形。[replace] なら、受け取った側で [block] を上端に置き直す。 */
internal data class EndSpaceSnapshot(val bottomPx: Int, val block: Int?, val replace: Boolean)

/**
 * 運ぶ形を決める。**置き直すのは、まだ置いている途中か、一覧がそのブロックの上端にいるときだけ** —
 * 利用者が自分で送った後なら、その位置（余白の中を含む）を余白ごと戻すだけにし、見出しへ引き戻さない。
 */
internal fun endSpaceSnapshot(bottomPx: Int, placedBlock: Int?, placing: Boolean, firstIndex: Int, firstOffset: Int): EndSpaceSnapshot {
    val atBlock = placedBlock != null && firstIndex == placedBlock && firstOffset == 0
    return EndSpaceSnapshot(bottomPx, placedBlock, replace = placing || atBlock)
}

/** [listState] と並べて余白を持つ。**画面の保存値に置く**（作り直しで位置だけが戻らないように）。 */
@Composable
internal fun rememberEndSpace(listState: LazyListState, key: Any? = null, init: () -> EndSpace = { EndSpace() }): EndSpace {
    val saver = remember(listState) { endSpaceSaver(listState) }
    return rememberSaveable(key, saver = saver, init = init)
}

private fun endSpaceSaver(listState: LazyListState): Saver<EndSpace, IntArray> = Saver(
    save = { space ->
        val snapshot = space.snapshot(listState)
        intArrayOf(snapshot.bottomPx, snapshot.block ?: -1, if (snapshot.replace) 1 else 0)
    },
    restore = { saved ->
        EndSpace().apply { restore(EndSpaceSnapshot(saved[0], saved[1].takeIf { it >= 0 }, replace = saved[2] == 1)) }
    }
)

/**
 * 送ったブロックを上端に置くのに、末尾の余白がいくら足りないか（px）。**まだ決められなければ null。**
 *
 * - [targetOffset] は送ったブロックの上端の位置（一覧の上端から。描かれていなければ null）
 * - [canScrollForward] は一覧がまだ先へ送れるか。先へ送れるのに上端に無いのは、送りがまだ届いていない配置である
 * - [paddingApplied] は今の余白で組まれた配置か。余白を替えた直後の古い配置からは決めない
 */
internal fun endSpaceShortfall(targetOffset: Int?, canScrollForward: Boolean, paddingApplied: Boolean): Int? = when {
    !paddingApplied || targetOffset == null -> null
    targetOffset <= 0 -> 0
    canScrollForward -> null
    else -> targetOffset
}

/**
 * [request] のブロックを上端に置き、**利用者が自分で送るまで置いたままにする。**
 * 末尾で止まっていたら、足りない分を [space] に足して送り直す。置いた後に後ろの画像の寸法が決まって本文が縮んだときも同じ
 * （測る前の画像は画面の高さを仮に取るので、測った後に末尾が短くなる）。px と dp の換算で1px残ることがあるので、続けて足すのは数回まで。
 */
internal suspend fun settleAtTop(listState: LazyListState, space: EndSpace, request: EndSpaceRequest) {
    var grows = 0
    snapshotFlow {
        val layout = listState.layoutInfo
        SettleView(
            scrolling = listState.isScrollInProgress,
            shortfall = endSpaceShortfall(
                targetOffset = layout.visibleItemsInfo.firstOrNull { it.index == request.block }?.offset,
                canScrollForward = listState.canScrollForward,
                paddingApplied = abs(layout.afterContentPadding - space.bottomPx) <= 1
            )
        )
    }.takeWhile { !it.scrolling }.collect { view ->
        when (val shortfall = view.shortfall) {
            null -> Unit
            0 -> grows = 0
            else -> if (grows < SETTLE_GROWS) {
                grows++
                space.grow(request, shortfall)
                listState.requestScrollToItem(request.block)
            }
        }
    }
    space.finish(request)
}

private data class SettleView(val scrolling: Boolean, val shortfall: Int?)

/**
 * 位置の口を持たない一覧（右の面）で、[block] へ送って上端に置く。**前の余白は送る前に消す** —
 * 残すと、先頭や見出しの無い送りの後も、前の見出しのための空白が末尾に残る。
 */
internal suspend fun placeBlockAtTop(listState: LazyListState, space: EndSpace, block: Int) {
    space.clear()
    listState.scrollToItem(block)
    space.want(block)
}

private const val SETTLE_GROWS = 3
