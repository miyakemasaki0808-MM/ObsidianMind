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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.model.state.SectionChatProblem
import com.example.newproject.model.state.SectionChatState
import com.example.newproject.ui.component.AiStatusNoticeRow
import com.example.newproject.ui.theme.OnSurfaceFaint
import com.example.newproject.ui.theme.PanelChip
import com.example.newproject.ui.theme.SkeletonBase
import com.example.newproject.ui.theme.SkeletonHighlight
import com.example.newproject.ui.theme.ErrorText
import com.example.newproject.ui.theme.AccentText
import com.example.newproject.ui.theme.OnSurface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SectionChatSheet(
    state: SectionChatState,
    onRetrySummary: () -> Unit,
    onDismiss: () -> Unit,
    onEndSession: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 背後の読書グラデーションが透けて情報密度が上がるのを抑えるため、既定より濃いスクリムにする。
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        scrimColor = BottomSheetDefaults.ScrimColor.copy(alpha = 0.5f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 420.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
        ) {
            // スコープ
            Surface(color = PanelChip, shape = RoundedCornerShape(999.dp)) {
                Text(
                    text = "📌 ${state.sectionTitle}",
                    color = AccentText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── 要約 ─────────────────────────────
            SectionHeader("📝", "要約")
            Spacer(modifier = Modifier.height(8.dp))
            when {
                state.isSummaryLoading -> SummarySkeleton()
                state.summary != null -> Text(
                    text = state.summary,
                    fontSize = 14.sp,
                    lineHeight = 22.sp,
                    color = OnSurface
                )
                state.summaryProblem != null ->
                    SectionChatProblemRow(state.summaryProblem, onRetrySummary)
                else -> Text("—", fontSize = 14.sp, color = OnSurfaceFaint)
            }

            Spacer(modifier = Modifier.height(20.dp))
            OutlinedButton(
                onClick = onEndSession,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = if (state.isSummaryLoading) "生成を中止" else "確認を終了",
                    color = if (state.isSummaryLoading) ErrorText else AccentText
                )
            }
        }
    }
}

/**
 * 出せなかった理由と、その再試行導線。**[onRetry] が指す対象は呼び出し位置で決まる。**
 *
 * 生成の失敗と端末AIの状態で色を分ける — 前者は実際に落ちたので `ErrorText`、
 * 後者はまだ何も失敗していないので通常色（`AiStatusNoticeRow` に任せる）。
 * 文言だけ出して導線を出さないと、タイムアウトのたびに要約の欄が空のまま残る。
 */
@Composable
private fun SectionChatProblemRow(problem: SectionChatProblem, onRetry: () -> Unit) {
    when (problem) {
        is SectionChatProblem.GenerationFailed -> Column {
            Text(problem.message, fontSize = 13.sp, color = ErrorText)
            Spacer(modifier = Modifier.height(6.dp))
            TextButton(onClick = onRetry) { Text("再試行") }
        }
        is SectionChatProblem.AiStatus ->
            AiStatusNoticeRow(notice = problem.notice, onRetry = onRetry)
    }
}

@Composable
private fun SectionHeader(emoji: String, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(emoji, fontSize = 16.sp)
        Spacer(modifier = Modifier.width(6.dp))
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AccentText)
    }
}

// 要約生成中は「骨組み」を見せて待たされ感を減らす。shimmerは自前（accompanistは非推奨のため不使用）。
@Composable
private fun SummarySkeleton() {
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
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
