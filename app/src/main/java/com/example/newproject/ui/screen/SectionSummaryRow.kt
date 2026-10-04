package com.example.newproject.ui.screen

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.model.state.SectionChatProblem
import com.example.newproject.model.state.SectionSummary
import com.example.newproject.ui.component.AiStatusNoticeRow
import com.example.newproject.ui.theme.AccentText
import com.example.newproject.ui.theme.ErrorText
import com.example.newproject.ui.theme.OnSurface
import com.example.newproject.ui.theme.OnSurfaceMuted
import com.example.newproject.ui.theme.SkeletonBase
import com.example.newproject.ui.theme.SkeletonHighlight

/** 面に要約の行を出すときに渡すもの。**渡さなければ行を出さない**（全画面のシートと、本文の節が分からない間）。 */
internal class SummaryRowInputs(
    /** 面の節の要約。持っていなければ null。 */
    val summary: SectionSummary?,
    val onRequest: () -> Unit,
    val onRetry: () -> Unit,
    val onCancel: () -> Unit
)

/**
 * 面の中の部分要約の行（→ features/margin_pane.md §5.2 の4）。**持っている節ならその要約、無ければ「この節を要約」。**
 *
 * 生成中は「生成を中止」だけを添える。取り消すとボタンに戻る。出せなかったときは理由と再試行を出す。
 * 面を閉じても要約は消えない（最近作った3節分）ので、終了のボタンは置かない。
 */
@Composable
internal fun SectionSummaryRow(
    summary: SectionSummary?,
    onRequest: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        when {
            summary == null -> TextButton(
                onClick = onRequest,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
            ) { Text("この節を要約", color = AccentText, fontSize = 13.sp) }

            summary.isSummaryLoading -> {
                SummarySkeleton(modifier = Modifier.padding(top = 8.dp))
                TextButton(
                    onClick = onCancel,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
                ) { Text("生成を中止", color = OnSurfaceMuted, fontSize = 12.sp) }
            }

            summary.summary != null -> {
                Text("この節の要約", color = AccentText, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                Text(
                    text = summary.summary,
                    color = OnSurface,
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
                )
            }

            summary.summaryProblem != null -> SectionChatProblemRow(summary.summaryProblem, onRetry)
        }
    }
}

/**
 * 出せなかった理由と、その再試行導線。
 *
 * 生成の失敗と端末AIの状態で色を分ける — 前者は実際に落ちたので `ErrorText`、
 * 後者はまだ何も失敗していないので通常色（`AiStatusNoticeRow` に任せる）。
 * 文言だけ出して導線を出さないと、タイムアウトのたびに要約の欄が空のまま残る。
 */
@Composable
private fun SectionChatProblemRow(problem: SectionChatProblem, onRetry: () -> Unit) {
    when (problem) {
        is SectionChatProblem.GenerationFailed -> Column(modifier = Modifier.padding(top = 4.dp)) {
            Text(problem.message, fontSize = 13.sp, color = ErrorText)
            TextButton(
                onClick = onRetry,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
            ) { Text("再試行") }
        }
        is SectionChatProblem.AiStatus ->
            AiStatusNoticeRow(notice = problem.notice, onRetry = onRetry)
    }
}

// 生成中は「骨組み」を見せて待たされ感を減らす。shimmerは自前（accompanistは非推奨のため不使用）。
@Composable
private fun SummarySkeleton(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "summarySkeleton")
    val shift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerShift"
    )
    val brush = Brush.linearGradient(
        colors = listOf(SkeletonBase, SkeletonHighlight, SkeletonBase),
        start = Offset(shift - 300f, 0f),
        end = Offset(shift, 0f)
    )
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SkeletonLine(brush, 1f)
        SkeletonLine(brush, 0.92f)
        SkeletonLine(brush, 0.6f)
    }
}

@Composable
private fun SkeletonLine(brush: Brush, widthFraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(14.dp)
            .background(brush, RoundedCornerShape(6.dp))
    )
}
