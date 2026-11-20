package com.example.newproject.ui.markdown

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.takeWhile
import kotlin.math.abs

/**
 * 見出しへ送ったときに、見出しを本文の最初の行に置くための**本文の末尾の余白**（→ features/note_links.md §5）。
 *
 * 本文の一覧は末尾より先へ送れないので、見出しから後ろが画面より短いと、見出しの上に前の節の終わりが見える。
 * 送った見出しが上端に来なかったときだけ、足りない分を末尾に足して送り直す。**ほかのノートでは余白を出さない** —
 * 余白は持ち主（[com.example.newproject.ui.screen.BodyPosition] か右の面のノートごと）が位置と同じ寿命で持ち、ほかの送りで消す。
 */
@Stable
internal class EndSpace {
    /** 本文の一覧の末尾に足す余白（px）。 */
    var bottomPx by mutableIntStateOf(0)
        private set

    /** 上端に置いておきたいブロック。利用者が自分で送るか、ほかの送りで消えるまで持つ（→ [settleAtTop]）。 */
    var wanted by mutableStateOf<EndSpaceRequest?>(null)
        private set

    private var count = 0L

    /** [block] を上端に置きたい。**送りの依頼の後に呼ぶ** — 置けたかは、依頼が届いた後の配置で決める。 */
    fun want(block: Int) {
        wanted = EndSpaceRequest(block, ++count)
    }

    /** ほかの位置へ送った。余白も置きたいブロックも捨てる。 */
    fun clear() {
        wanted = null
        bottomPx = 0
    }

    /** [request] がまだ最新なら、余白を [px] 足す。 */
    fun grow(request: EndSpaceRequest, px: Int) {
        if (wanted == request) bottomPx += px
    }

    /** [request] を置き終えた（利用者が自分で送った）。 */
    fun finish(request: EndSpaceRequest) {
        if (wanted == request) wanted = null
    }
}

/** 上端に置きたいブロックの依頼。同じブロックを続けて頼んでも見分けられるよう番号を持つ。 */
internal data class EndSpaceRequest(val block: Int, val id: Long)

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
