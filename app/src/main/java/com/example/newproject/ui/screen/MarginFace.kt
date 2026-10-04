package com.example.newproject.ui.screen

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.example.newproject.domain.ArrangedMemos
import com.example.newproject.domain.arrangeMemos
import com.example.newproject.domain.markdown.NoteSectionModel
import com.example.newproject.domain.showsPreviousReading
import com.example.newproject.model.SectionRef
import com.example.newproject.model.state.MarginMemoState
import com.example.newproject.ui.markdown.NoteImageMeasurements
import kotlinx.coroutines.launch

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
    val jumpToSection: (SectionRef) -> Unit
)

/** [listState] は面を並べる画面の本文の一覧。全画面は自分の一覧を渡す。 */
@Composable
internal fun rememberMarginFaceInputs(
    sectionModel: NoteSectionModel?,
    listState: LazyListState,
    memoState: MarginMemoState,
    imageMeasurements: NoteImageMeasurements?
): MarginFaceInputs {
    val coroutineScope = rememberCoroutineScope()
    // **スクロールが止まってから決める。** 流している途中で決めると、面の中身が通り過ぎる節ごとに入れ替わる。
    var settledBlock by remember(sectionModel) { mutableIntStateOf(listState.firstVisibleItemIndex) }
    LaunchedEffect(listState, sectionModel) {
        snapshotFlow { listState.isScrollInProgress to listState.firstVisibleItemIndex }
            .collect { (scrolling, index) -> if (!scrolling) settledBlock = index }
    }
    val bodySection = sectionModel?.sectionRefAt(settledBlock)
    val readyMemos = memoState as? MarginMemoState.Ready
    val arranged = remember(readyMemos?.memos, sectionModel, bodySection) {
        val memos = readyMemos?.memos ?: return@remember null
        val model = sectionModel ?: return@remember null
        // **見出しの索引は解析と一緒に Main の外で作ってある。** ここでは見出しをたどらない。
        arrangeMemos(memos, model.headingIndex, bodySection ?: SectionRef(title = null))
    }
    // **メモと同じ照合で、見出しが一意に一致した節にだけ出す**（→ features/margin_pane.md §5.7）。
    val previousReadingAt = remember(readyMemos?.previousVisit, sectionModel, bodySection) {
        val previous = readyMemos?.previousVisit ?: return@remember null
        val model = sectionModel ?: return@remember null
        previous.atEpochMillis.takeIf { showsPreviousReading(previous, model.headingIndex, bodySection) }
    }
    // **飛び越した画像は測られない**ので、続きから読むと同じく測定を頼む。
    val jumpToSection: (SectionRef) -> Unit = { ref ->
        sectionModel?.startBlockOf(ref)?.let { block ->
            coroutineScope.launch { listState.animateScrollToItem(block) }
            imageMeasurements?.requestSkippedMeasurement(block)
        }
    }
    return MarginFaceInputs(bodySection, arranged, previousReadingAt, jumpToSection)
}
