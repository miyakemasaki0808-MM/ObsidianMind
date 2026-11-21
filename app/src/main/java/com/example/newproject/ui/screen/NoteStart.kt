package com.example.newproject.ui.screen

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
 * 別のノートを本文の先頭から開く。**ランダム・さがす・関連・結晶・冊子から開く経路はここを通す。**
 *
 * 本文の一覧の状態は Activity 生存で共有され、ノート切替ではリセットされないので、何もしないと
 * 開いたノートが前のノートで読んでいた位置から始まる。「このノートへ移る」とリンクは
 * [rememberNoteStart] が始まりの位置を置くので、ここを通さない。
 */
internal fun openNoteFromTop(position: BodyPosition, open: () -> Unit) {
    open()
    position.moveTo(0)
}

/**
 * 別のノートを、[start] の位置から開く。**[openNoteFromTop] と対の境界**で、始まりを先頭ではなく見出しに置きたい経路が通る
 * （関連タブのこのノートへのリンク）。[place] は [rememberNoteStart] が返す口で、解析が届いたら見出しへ送る。
 */
internal fun openNoteAt(start: PendingNoteStart, place: (PendingNoteStart) -> Unit, open: () -> Unit) {
    open()
    place(start)
}

/**
 * 開いたノートの始まりの位置。「このノートへ移る」は右で読んでいたブロック、リンクは見出しを持つ。
 * [heading] があれば解析が届いた後にその見出しへ送り、見つからなければ [block] から始める。
 * [headingOccurrence] は同じ見出しのうち何番目か（→ [headingBlockIndex]）で、渡すのはこのノートへのリンクだけ。
 * **画面の保存値に置く**（Fold の開閉で画面が作り直されても失わない）。
 */
internal data class PendingNoteStart(
    val noteUri: String,
    val block: Int,
    val heading: String? = null,
    val headingOccurrence: Int = 0
) : Serializable

/**
 * ノートを開くときに始まりの位置を置く口を返す。呼ぶのはノートを開いた直後。
 *
 * **開くのと同時に位置を置き、解析が届いたら置き直す。** 新しいノートの一覧は最初に組まれたときからこの位置で始まり
 * （冊子から開くときと同じ）、前のノートの一覧に縮められていたら置き直す。見出しは解析が届くまで探せない。
 * [noteLoading] は今ノートを読み込んでいるか。読み込みが終わったのに本文が無ければ依頼を捨てる（→ [noteStartStep]）。
 */
@Composable
internal fun rememberNoteStart(
    position: BodyPosition,
    currentNoteUri: String?,
    noteLoading: Boolean,
    sectionModel: NoteSectionModel?,
    imageMeasurements: NoteImageMeasurements?,
    onHeadingMissing: (heading: String) -> Unit
): (PendingNoteStart) -> Unit {
    var pending by rememberSaveable { mutableStateOf<PendingNoteStart?>(null) }
    val currentOnHeadingMissing by rememberUpdatedState(onHeadingMissing)
    LaunchedEffect(currentNoteUri, noteLoading, sectionModel) {
        val start = pending ?: return@LaunchedEffect
        when (noteStartStep(start, currentNoteUri, noteLoading, parsed = sectionModel != null)) {
            NoteStartStep.Wait -> Unit
            NoteStartStep.Drop -> pending = null
            NoteStartStep.Place -> {
                val blocks = checkNotNull(sectionModel).blocks
                val (block, headingMissing) = noteStartBlock(blocks, start.heading, fallback = start.block, start.headingOccurrence)
                if (headingMissing && start.heading != null) currentOnHeadingMissing(start.heading)
                // 読書の記録は最も深い位置しか残さないので、手前で1度報告されても、置き直した位置からの報告で上書きされる。
                // 見出しへ送るときは、末尾に近くても見出しを上端に置く（→ BodyPosition.placeAtTop）。
                if (start.heading != null && !headingMissing) {
                    position.placeAtTop(block)
                } else if (position.listState.firstVisibleItemIndex != block) {
                    position.moveTo(block)
                }
                // 飛び越した画像は描画されないので測られない。測らないと、その先の読書の報告が止まり続ける。
                imageMeasurements?.requestSkippedMeasurement(block)
                pending = null
            }
        }
    }
    return { start ->
        position.moveTo(start.block)
        pending = start
    }
}

/** 置いた始まりをどうするか（→ [noteStartStep]）。 */
internal enum class NoteStartStep { Wait, Place, Drop }

/**
 * 置いた始まりをどうするか。読み込み中と、開いたノートの解析がまだなら待つ。開いたノートの解析が届いたら置く。
 * **読み込みが終わったのに本文が無い（失敗した）か、別のノートが開いたら捨てる** — 残すと、同じノートを別の経路
 * （先頭から開く）で開いたときに古い見出しへ送る。
 */
internal fun noteStartStep(start: PendingNoteStart, openedUri: String?, noteLoading: Boolean, parsed: Boolean): NoteStartStep = when {
    openedUri == null -> if (noteLoading) NoteStartStep.Wait else NoteStartStep.Drop
    openedUri != start.noteUri -> NoteStartStep.Drop
    parsed -> NoteStartStep.Place
    else -> NoteStartStep.Wait
}

/** 開いたノートを始めるブロックと、リンクの見出しが見つからなかったか。 */
internal data class NoteStartBlock(val block: Int, val headingMissing: Boolean)

/**
 * 開いたノートを始めるブロック。見出しがあればその見出し（同じ見出しなら [occurrence] 番目、無ければ最初のもの）、
 * **見出しが無いか見つからなければ [fallback]**（リンクなら先頭、「このノートへ移る」なら右で読んでいたブロック）。
 */
internal fun noteStartBlock(blocks: List<MarkdownBlock>, heading: String?, fallback: Int, occurrence: Int = 0): NoteStartBlock {
    val found = heading?.let { headingBlockIndex(blocks, it, occurrence) }
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
 * 補助の面で開いたノートと、その見出し（→ features/note_links.md §4）。本文のリンクと、このノートへのリンクの行から開く。
 * [request] は押すたびに増える番号で、同じリンクを押し直したときにも見出しへ送り直すために使う。
 * [headingOccurrence] は同じ見出しのうち何番目かで、渡すのはこのノートへのリンクだけ（→ [PendingNoteStart]）。
 */
internal data class SideLinkStart(
    val noteUri: String,
    val heading: String?,
    val request: Long,
    val headingOccurrence: Int = 0
) : Serializable
