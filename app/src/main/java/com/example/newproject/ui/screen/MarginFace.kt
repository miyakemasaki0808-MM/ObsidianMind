package com.example.newproject.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.example.newproject.domain.ArrangedMemos
import com.example.newproject.domain.MemoLocations
import com.example.newproject.domain.arrangeMemos
import com.example.newproject.domain.locateMemos
import com.example.newproject.domain.markdown.NoteSectionModel
import com.example.newproject.domain.seenPassageAt
import com.example.newproject.domain.showsPreviousReading
import com.example.newproject.model.MarginMemo
import com.example.newproject.model.MemoPassage
import com.example.newproject.model.SectionRef
import com.example.newproject.model.state.MarginMemoState
import com.example.newproject.ui.markdown.NoteImageMeasurements
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「この部分」の面が本文から受け取るもの。**通常画面と全画面が同じ規則で作る**（→ features/margin_pane.md §5.3）。
 */
internal class MarginFaceInputs(
    /** 本文の節（＝面の節）。スクロールが止まってから決まる。解析の前は null。 */
    val bodySection: SectionRef?,
    /** メモを今の見出しと照合して並べたもの。印と件数と飛ぶ先も同じ照合から作る（→ features/margin_pane.md §5.6）。 */
    val arranged: ArrangedMemos?,
    /** この節に前回の読書の跡を出すなら、その訪問の日時。 */
    val previousReadingAt: Long?,
    /** 本文をその節の始まりへ送る。 */
    val jumpToSection: (SectionRef) -> Unit,
    /** 本文をそのブロックへ送る。引用した段落と書いた頃の位置へ飛ぶのに使う（→ features/margin_quote.md §5.5）。 */
    val jumpToBlock: (Int) -> Unit,
    /** 今書き始めたら控える位置（→ features/margin_quote.md §5.4）。本文の節と同じく、スクロールが止まった最上端のブロックから決める。 */
    val seenAtStart: MemoPassage.Seen?
)

/** 照らし直しの結果と、それを作った材料。**材料が今のものと違えば使わない**（→ [currentLocations]）。 */
internal class LocatedMemos(val memos: List<MarginMemo>, val model: NoteSectionModel, val locations: MemoLocations)

/**
 * 今のメモと解析結果に対する照らし直しの結果。**古い結果を混ぜない** — 結果は Main の外で遅れて届くので、
 * 届く前にメモか解析結果が替わっていれば（ノートの切替・解析し直し・メモの保存や削除）、その結果は使わず見出しの照合で並べる。
 * 解析結果は同じものか（同一性）で、メモは中身で比べる（件数は20件まで）。
 */
internal fun currentLocations(located: LocatedMemos?, memos: List<MarginMemo>?, model: NoteSectionModel?): MemoLocations? =
    located?.takeIf { memos != null && it.model === model && it.memos == memos }?.locations

/**
 * [position] は面を並べる画面の本文の位置の口。全画面は自分の一覧の口を渡す。
 *
 * **引用と書いた頃の位置の照らし直しは [locateDispatcher] の上で行う。** 本文の大きさに比例する（1MB で数十ms）ので Main で走らせず、
 * メモか解析結果が替わったときだけ作り直す。節を移るたびに並べ直すのは、その結果を引くだけの [arrangeMemos]（→ features/margin_quote.md §5.5）。
 */
@Composable
internal fun rememberMarginFaceInputs(
    sectionModel: NoteSectionModel?,
    position: BodyPosition,
    memoState: MarginMemoState,
    imageMeasurements: NoteImageMeasurements?,
    locateDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** 節へ飛ぶときアニメーションで送るか。**見開きの間は送らない** — 一覧が組まれておらず、初回の配置を待って終わらない（→ [BodyPosition]）。 */
    animateJumps: () -> Boolean = { true }
): MarginFaceInputs {
    val coroutineScope = rememberCoroutineScope()
    val listState = position.listState
    // **スクロールが止まってから決める。** 流している途中で決めると、面の中身が通り過ぎる節ごとに入れ替わる。
    var settledBlock by remember(sectionModel) { mutableIntStateOf(listState.firstVisibleItemIndex) }
    LaunchedEffect(listState, sectionModel) {
        snapshotFlow { listState.isScrollInProgress to listState.firstVisibleItemIndex }
            .collect { (scrolling, index) -> if (!scrolling) settledBlock = index }
    }
    val bodySection = sectionModel?.sectionRefAt(settledBlock)
    val readyMemos = memoState as? MarginMemoState.Ready
    val memos = readyMemos?.memos
    // 引用か書いた頃の位置を持つメモがあるときだけ照らす。キーが替わると前の計算は取り消され、その結果は書かれない。
    val located by produceState<LocatedMemos?>(null, memos, sectionModel) {
        val forMemos = memos ?: return@produceState
        val model = sectionModel ?: return@produceState
        if (forMemos.none { it.passage != null }) return@produceState
        value = withContext(locateDispatcher) {
            LocatedMemos(forMemos, model, locateMemos(forMemos, model.passages, model.headingIndex))
        }
    }
    val locations = currentLocations(located, memos, sectionModel)
    val arranged = remember(memos, sectionModel, bodySection, locations) {
        val forMemos = memos ?: return@remember null
        val model = sectionModel ?: return@remember null
        // **見出しの索引と照らし直しは Main の外で作ってある。** ここでは見出しも候補もたどらない。
        arrangeMemos(forMemos, model.headingIndex, bodySection ?: SectionRef(title = null), locations)
    }
    // 書き始めたら控える位置。スクロールが止まった最上端のブロックから、同じ節の最初の本文のブロックを選ぶ（ほとんどは最上端そのもの）。
    val seenAtStart = remember(sectionModel, settledBlock) { sectionModel?.let { seenPassageAt(settledBlock, it.passages) } }
    // **メモと同じ照合で、見出しが一意に一致した節にだけ出す**（→ features/margin_pane.md §5.7）。
    val previousReadingAt = remember(readyMemos?.previousVisit, sectionModel, bodySection) {
        val previous = readyMemos?.previousVisit ?: return@remember null
        val model = sectionModel ?: return@remember null
        previous.atEpochMillis.takeIf { showsPreviousReading(previous, model.headingIndex, bodySection) }
    }
    // **飛び越した画像は測られない**ので、続きから読むと同じく測定を頼む。
    val jumpToBlock: (Int) -> Unit = { block ->
        if (animateJumps()) position.animateTo(coroutineScope, block) else position.moveTo(block)
        imageMeasurements?.requestSkippedMeasurement(block)
    }
    val jumpToSection: (SectionRef) -> Unit = { ref -> sectionModel?.startBlockOf(ref)?.let(jumpToBlock) }
    return MarginFaceInputs(bodySection, arranged, previousReadingAt, jumpToSection, jumpToBlock, seenAtStart)
}
