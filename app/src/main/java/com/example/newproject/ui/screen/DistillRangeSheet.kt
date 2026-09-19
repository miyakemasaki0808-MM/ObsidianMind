package com.example.newproject.ui.screen

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.model.state.DistillCandidateItem
import com.example.newproject.model.state.DistillRangeEdge
import com.example.newproject.model.state.DistillRangeEdgeMove
import com.example.newproject.model.state.DistillRangePreset
import com.example.newproject.ui.theme.AccentText
import com.example.newproject.ui.theme.ButtonAi
import com.example.newproject.ui.theme.ErrorText
import com.example.newproject.ui.theme.OnButtonAi
import com.example.newproject.ui.theme.OnSurface
import com.example.newproject.ui.theme.OnSurfaceSubtle
import com.example.newproject.ui.theme.PanelChip

/** 段の表示名。**モデル側は名前を持たない**（判断だけを純関数に残す）。 */
internal fun DistillRangePreset.label(): String = when (this) {
    DistillRangePreset.Term -> "語句"
    DistillRangePreset.Clause -> "意味節"
    DistillRangePreset.Sentence -> "文全体"
}

/** 微調整ボタンの読み上げ名。**矢印だけでは何が動くか読めない。** */
internal fun DistillRangeEdgeMove.label(): String = when (this) {
    DistillRangeEdgeMove.ExpandStart -> "始点を前へ広げる"
    DistillRangeEdgeMove.ShrinkStart -> "始点を後ろへ狭める"
    DistillRangeEdgeMove.ShrinkEnd -> "終点を前へ狭める"
    DistillRangeEdgeMove.ExpandEnd -> "終点を後ろへ広げる"
}

private fun DistillRangeEdgeMove.arrow(): String =
    if (this == DistillRangeEdgeMove.ExpandStart || this == DistillRangeEdgeMove.ShrinkEnd) "◀" else "▶"

/**
 * 太字にする範囲を調整するシート。
 *
 * **閉じることが確定。** 取り消しの口は「最初の範囲に戻す」1つに絞り、
 * キャンセルボタンを別に置かない（口が2つあるとどちらが効いたか読めなくなる）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DistillRangeSheet(
    item: DistillCandidateItem,
    projectedBoldRatio: Double,
    isWithinBoldLimit: Boolean,
    isDeselectedByOverlap: Boolean,
    otherDeselectedCount: Int,
    onSelectPreset: (DistillRangePreset) -> Unit,
    onDragEdge: (DistillRangeEdge, Int) -> Unit,
    onNudgeEdge: (DistillRangeEdgeMove) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        scrimColor = BottomSheetDefaults.ScrimColor.copy(alpha = 0.5f)
    ) {
        DistillRangeSheetContent(
            item = item,
            projectedBoldRatio = projectedBoldRatio,
            isWithinBoldLimit = isWithinBoldLimit,
            isDeselectedByOverlap = isDeselectedByOverlap,
            otherDeselectedCount = otherDeselectedCount,
            onSelectPreset = onSelectPreset,
            onDragEdge = onDragEdge,
            onNudgeEdge = onNudgeEdge,
            onReset = onReset
        )
    }
}

/**
 * シートの中身。**`ModalBottomSheet` を開かずに描画を検査するため**に切り出してある
 * （[QuizActionSection] と同じ理由）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DistillRangeSheetContent(
    item: DistillCandidateItem,
    projectedBoldRatio: Double,
    isWithinBoldLimit: Boolean,
    isDeselectedByOverlap: Boolean,
    otherDeselectedCount: Int,
    onSelectPreset: (DistillRangePreset) -> Unit,
    onDragEdge: (DistillRangeEdge, Int) -> Unit,
    onNudgeEdge: (DistillRangeEdgeMove) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 320.dp)
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
    ) {
        Surface(color = PanelChip, shape = RoundedCornerShape(999.dp)) {
            Text(
                text = "✦ 太字にする範囲",
                color = AccentText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        AdjustableParentText(item = item, onDragEdge = onDragEdge)
        // つまみは行の下辺に重ねて描くので、次の行と重ならないだけの間を空ける。
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "端のつまみを引くと、好きな範囲にできます。太字にできるのは、この文の内側だけです。",
            fontSize = 11.sp,
            color = OnSurfaceSubtle
        )
        Spacer(modifier = Modifier.height(16.dp))

        // **存在する段だけを出す。** 押せない選択肢は理由の説明を毎回要求する。
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item.availablePresets.forEach { preset ->
                val isCurrent = preset == item.currentPreset
                if (isCurrent) {
                    Button(
                        onClick = { onSelectPreset(preset) },
                        modifier = Modifier.heightIn(min = 48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ButtonAi,
                            contentColor = OnButtonAi
                        )
                    ) { Text("✓ ${preset.label()}", color = OnButtonAi) }
                } else {
                    OutlinedButton(
                        onClick = { onSelectPreset(preset) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text(preset.label()) }
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))

        EdgeNudgeRow(item = item, onNudgeEdge = onNudgeEdge)
        Spacer(modifier = Modifier.height(4.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = onReset,
                enabled = item.isRangeAdjusted,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("最初の範囲に戻す") }
        }

        Text(
            "変更後の太字率 %.1f%%".format(projectedBoldRatio * 100.0),
            fontSize = 12.sp,
            color = if (isWithinBoldLimit) OnSurfaceSubtle else ErrorText
        )

        // **告知は状態として残す。** 一時的な通知にすると、見ていない場所の変化を
        // 見ていない間に流すことになる。次に選択集合か確定範囲が変わるまで消えない。
        //
        // **主語は開いている候補で決まる。** 理由を残して再訪できるようにした結果、
        // 外された候補自身のシートも開けるようになった。そこで件数だけを言うと、
        // 目の前の候補を「ほかの1箇所」と呼んで関係を逆に読ませる。
        overlapNotice(isDeselectedByOverlap, otherDeselectedCount)?.let { notice ->
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = notice,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = ErrorText,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
    }
}

/**
 * 親文と、その上の2つのつまみ。**引いた先を寄せるのは Controller 側**で、ここは位置しか送らない。
 *
 * **[item] と [onDragEdge] は [rememberUpdatedState] を通す。** `pointerInput` は
 * キーが変わるまで同じブロックを走らせ続けるので、素で捉えると範囲を1回動かした後
 * 古い `item` を見続け、2回目以降の引きが効かなくなる（→ lessons L34）。
 */
@Composable
private fun AdjustableParentText(
    item: DistillCandidateItem,
    onDragEdge: (DistillRangeEdge, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentItem by rememberUpdatedState(item)
    val currentOnDragEdge by rememberUpdatedState(onDragEdge)
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val handleColor = AccentText
    val density = LocalDensity.current
    val handleRadius = with(density) { 7.dp.toPx() }
    val grabSlop = with(density) { 24.dp.toPx() }

    Text(
        text = highlightedParent(item),
        fontSize = 15.sp,
        lineHeight = 26.sp,
        color = OnSurface,
        onTextLayout = { layout = it },
        modifier = modifier
            .fillMaxWidth()
            .drawWithContent {
                drawContent()
                val centers = layout?.let { distillHandleCenters(it, currentItem) } ?: return@drawWithContent
                listOf(centers.first, centers.second).forEach { center ->
                    // 端では半分が描画域の外へ出るので、内側へ寄せて描く。
                    drawCircle(
                        color = handleColor,
                        radius = handleRadius,
                        center = center.copy(
                            x = center.x.coerceIn(handleRadius, size.width - handleRadius)
                        )
                    )
                }
            }
            .pointerInput(item.id) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val centers = layout?.let { distillHandleCenters(it, currentItem) }
                        ?: return@awaitEachGesture
                    val edge = grabbedDistillEdge(down.position, centers.first, centers.second)
                    val handle = if (edge == DistillRangeEdge.Start) centers.first else centers.second
                    // **つまみの近くで始まった押下だけを掴む。** 本文のどこでも掴むと、
                    // シートを縦に送ろうとした指が範囲を動かしてしまう。
                    if ((down.position - handle).getDistance() > grabSlop) return@awaitEachGesture
                    down.consume()
                    drag(down.id) { change ->
                        change.consume()
                        layout?.let { currentOnDragEdge(edge, it.getOffsetForPosition(change.position)) }
                    }
                }
            }
    )
}

/**
 * 端を1つぶんずつ動かす矢印。**指で狙った1文字には止まれないので置いている。**
 *
 * 動かせない向きは出さずに無効化する — 4つの位置が固定されているほうが、
 * 端に着くたびにボタンが消えて並びがずれるより読める。
 */
@Composable
private fun EdgeNudgeRow(
    item: DistillCandidateItem,
    onNudgeEdge: (DistillRangeEdgeMove) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text("始点", fontSize = 12.sp, color = OnSurfaceSubtle)
        NudgeButton(DistillRangeEdgeMove.ExpandStart, item, onNudgeEdge)
        NudgeButton(DistillRangeEdgeMove.ShrinkStart, item, onNudgeEdge)
        Spacer(modifier = Modifier.width(12.dp))
        Text("終点", fontSize = 12.sp, color = OnSurfaceSubtle)
        NudgeButton(DistillRangeEdgeMove.ShrinkEnd, item, onNudgeEdge)
        NudgeButton(DistillRangeEdgeMove.ExpandEnd, item, onNudgeEdge)
    }
}

@Composable
private fun NudgeButton(
    move: DistillRangeEdgeMove,
    item: DistillCandidateItem,
    onNudgeEdge: (DistillRangeEdgeMove) -> Unit
) {
    TextButton(
        onClick = { onNudgeEdge(move) },
        enabled = move in item.availableEdgeMoves,
        contentPadding = PaddingValues(horizontal = 4.dp),
        modifier = Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .semantics { contentDescription = move.label() }
    ) { Text(move.arrow(), fontSize = 16.sp) }
}

/**
 * 掴む端を**1回だけ決める。**
 *
 * 引いている途中で近いほうを選び直すと、範囲が潰れる手前で反対の端へ乗り換わり、
 * 指を動かしていないのに別の端が動き出す。同距離なら始点を採る（左から読むため）。
 */
internal fun grabbedDistillEdge(
    touch: Offset,
    startHandle: Offset,
    endHandle: Offset
): DistillRangeEdge =
    if ((touch - startHandle).getDistanceSquared() <= (touch - endHandle).getDistanceSquared()) {
        DistillRangeEdge.Start
    } else {
        DistillRangeEdge.End
    }

/**
 * つまみの中心。**始点は確定範囲の左端、終点は右端**の、行の下辺に置く。
 *
 * 範囲が空なら `null`（つまみを出さない）。確定範囲は必ず1文字以上あるので通常は起きないが、
 * 親文が空の入力で `getBoundingBox` が落ちるのを避ける。
 */
internal fun distillHandleCenters(
    layout: TextLayoutResult,
    item: DistillCandidateItem
): Pair<Offset, Offset>? {
    val parentLength = item.parentText.length
    val start = item.boldStartInParent.coerceIn(0, parentLength)
    val end = item.boldEndInParent.coerceIn(start, parentLength)
    if (end <= start) return null
    val startBox = layout.getBoundingBox(start)
    val endBox = layout.getBoundingBox(end - 1)
    return Offset(startBox.left, startBox.bottom) to Offset(endBox.right, endBox.bottom)
}

/**
 * 重なり解消の告知。**開いている候補との関係で文言が変わる。**
 *
 * 自分が外された側なら「ほか」と呼ばない。両方に当てはまるときは、
 * シートが説明すべき相手＝開いている候補自身を優先する。
 */
internal fun overlapNotice(isDeselectedByOverlap: Boolean, otherDeselectedCount: Int): String? = when {
    isDeselectedByOverlap -> "! この箇所は範囲が重なるため、選択が外れています。"
    otherDeselectedCount > 0 -> "! 重なるため、ほかの${otherDeselectedCount}箇所の選択を外しました。"
    else -> null
}

/**
 * 親文のうち、確定範囲だけを太字＋下線で示す。
 *
 * **色だけの手がかりにしない**（→ `docs/dev/system/ui_design_principles.md` §1）。
 * 太字と下線の両方を掛けるのは、灰色にしても範囲が伝わるようにするためである。
 */
internal fun highlightedParent(item: DistillCandidateItem) = buildAnnotatedString {
    val parent = item.parentText
    val start = item.boldStartInParent.coerceIn(0, parent.length)
    val end = item.boldEndInParent.coerceIn(start, parent.length)
    append(parent.substring(0, start))
    withStyle(
        SpanStyle(
            fontWeight = FontWeight.Bold,
            textDecoration = TextDecoration.Underline
        )
    ) { append(parent.substring(start, end)) }
    append(parent.substring(end))
}
