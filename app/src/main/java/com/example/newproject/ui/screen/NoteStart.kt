package com.example.newproject.ui.screen

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.example.newproject.domain.headingBlockIndex
import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.domain.markdown.NoteSectionModel
import com.example.newproject.ui.markdown.NoteImageMeasurements
import java.io.Serializable

/**
 * 開いたノートの始まりの位置。「このノートへ移る」は右で読んでいたブロック、リンクは見出しを持つ。
 * [heading] があれば解析が届いた後にその見出しへ送り、見つからなければ [block] から始める。
 * **画面の保存値に置く**（Fold の開閉で画面が作り直されても失わない）。
 */
internal data class PendingNoteStart(val noteUri: String, val block: Int, val heading: String? = null) : Serializable

/**
 * ノートを開くときに始まりの位置を置く口を返す。呼ぶのはノートを開いた直後。
 *
 * **開くのと同時に位置を置き、解析が届いたら置き直す。** 新しいノートの一覧は最初に組まれたときからこの位置で始まり
 * （冊子から開くときと同じ）、前のノートの一覧に縮められていたら置き直す。見出しは解析が届くまで探せない。
 */
@Composable
internal fun rememberNoteStart(
    listState: LazyListState,
    currentNoteUri: String?,
    sectionModel: NoteSectionModel?,
    imageMeasurements: NoteImageMeasurements?,
    onHeadingMissing: (heading: String) -> Unit
): (PendingNoteStart) -> Unit {
    var pending by rememberSaveable { mutableStateOf<PendingNoteStart?>(null) }
    val currentOnHeadingMissing by rememberUpdatedState(onHeadingMissing)
    LaunchedEffect(currentNoteUri, sectionModel) {
        val start = pending ?: return@LaunchedEffect
        val opened = currentNoteUri ?: return@LaunchedEffect
        if (opened == start.noteUri && sectionModel != null) {
            val (block, headingMissing) = noteStartBlock(sectionModel.blocks, start.heading, fallback = start.block)
            if (headingMissing && start.heading != null) currentOnHeadingMissing(start.heading)
            // 読書の記録は最も深い位置しか残さないので、手前で1度報告されても、置き直した位置からの報告で上書きされる。
            if (listState.firstVisibleItemIndex != block) listState.scrollToItem(block)
            // 飛び越した画像は描画されないので測られない。測らないと、その先の読書の報告が止まり続ける。
            imageMeasurements?.requestSkippedMeasurement(block)
        }
        if (opened != start.noteUri || sectionModel != null) pending = null
    }
    return { start ->
        listState.requestScrollToItem(start.block)
        pending = start
    }
}

/** 開いたノートを始めるブロックと、リンクの見出しが見つからなかったか。 */
internal data class NoteStartBlock(val block: Int, val headingMissing: Boolean)

/**
 * 開いたノートを始めるブロック。見出しがあればその見出し、**見出しが無いか見つからなければ [fallback]**
 * （リンクなら先頭、「このノートへ移る」なら右で読んでいたブロック）。
 */
internal fun noteStartBlock(blocks: List<MarkdownBlock>, heading: String?, fallback: Int): NoteStartBlock {
    val found = heading?.let { headingBlockIndex(blocks, it) }
    return NoteStartBlock(block = found ?: fallback, headingMissing = heading != null && found == null)
}

/**
 * 同じノートの中のリンクで、本文を送る先のブロック。見出しの無いリンクは先頭、見出しが見つからなければ null（動かさない）。
 * 解析が届く前は送り先を決められないので null。
 */
internal fun currentNoteScrollTarget(blocks: List<MarkdownBlock>?, heading: String?): Int? = when {
    blocks == null -> null
    heading == null -> 0
    else -> headingBlockIndex(blocks, heading)
}

/**
 * 本文のリンクから補助の面で開いたノートと、その見出し（→ features/note_links.md §4）。
 * [request] は押すたびに増える番号で、同じリンクを押し直したときにも見出しへ送り直すために使う。
 * 「← 余白へ戻る」は、これが今右にあるノートを指していれば、関連の一覧まで面を送らない。
 */
internal data class SideLinkStart(val noteUri: String, val heading: String?, val request: Long) : Serializable
