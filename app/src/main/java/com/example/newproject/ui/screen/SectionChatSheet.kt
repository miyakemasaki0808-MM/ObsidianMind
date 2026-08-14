package com.example.newproject.ui.screen

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import com.example.newproject.model.state.ChatMessage
import com.example.newproject.model.state.ChatRole
import com.example.newproject.model.state.AiNoticeAction
import com.example.newproject.model.state.isQuizActionEnabled
import com.example.newproject.model.state.QuizState
import com.example.newproject.model.state.SectionChatProblem
import com.example.newproject.model.state.SectionChatState
import com.example.newproject.model.state.SuggestionsDisplay
import com.example.newproject.model.state.suggestionsDisplay
import com.example.newproject.ui.component.AiStatusNoticeRow
import com.example.newproject.ui.theme.AccentSurface
import com.example.newproject.ui.theme.OnAccentSurface
import com.example.newproject.ui.theme.OnButtonAi
import com.example.newproject.ui.theme.ChatDivider
import com.example.newproject.ui.theme.OnSurfaceFaint
import com.example.newproject.ui.theme.OnSurfaceSubtle
import com.example.newproject.ui.theme.PanelBubble
import com.example.newproject.ui.theme.PanelChip
import com.example.newproject.ui.theme.PanelRow
import com.example.newproject.ui.theme.SkeletonBase
import com.example.newproject.ui.theme.SkeletonHighlight
import com.example.newproject.ui.theme.ButtonAi
import com.example.newproject.ui.theme.ErrorText
import com.example.newproject.ui.theme.AccentText
import com.example.newproject.ui.theme.OnSurface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SectionChatSheet(
    state: SectionChatState,
    quizState: QuizState,
    onSuggestionTap: (String) -> Unit,
    onQuizTap: () -> Unit,
    onRetrySummary: () -> Unit,
    onRetryAnswer: () -> Unit,
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

            // ── 要約（上）─────────────────────────────
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
                // **要約が出せなかった理由はここだけに出す。** 押された導線の位置が
                // 再試行の対象を決めるので、回答側の理由をここへ混ぜない。
                state.summaryProblem != null ->
                    SectionChatProblemRow(state.summaryProblem, onRetrySummary)
                else -> Text("—", fontSize = 14.sp, color = OnSurfaceFaint)
            }

            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider(color = ChatDivider)
            Spacer(modifier = Modifier.height(20.dp))

            // ── 質問（下）─────────────────────────────
            SectionHeader("💬", "質問")
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "気になる質問をタップすると回答します。",
                fontSize = 12.sp,
                color = OnSurfaceFaint
            )
            Spacer(modifier = Modifier.height(10.dp))

            // **空リストから進行中を推測しない**（→ suggestionsDisplay）。
            when (state.suggestionsDisplay()) {
                SuggestionsDisplay.Loading ->
                    Text("質問候補を準備中…", fontSize = 13.sp, color = OnSurfaceFaint)
                SuggestionsDisplay.Ready -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.suggestions.forEach { q ->
                        SuggestionRow(text = q, enabled = !state.isGenerating) { onSuggestionTap(q) }
                    }
                }
                // 走っていないのに空。**待たせない**ので、終わったことが分かる文にする。
                SuggestionsDisplay.None ->
                    Text("質問候補はありません。", fontSize = 13.sp, color = OnSurfaceFaint)
            }

            // Q&A ログ
            if (state.messages.isNotEmpty() || state.isGenerating) {
                Spacer(modifier = Modifier.height(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.messages.forEach { message -> ChatBubble(message) }
                    if (state.isGenerating) LoadingRow("回答を生成中…")
                }
            }

            // **回答が出せなかった理由はログの直後に出す。** 要約エリアへ出すと、
            // 要約がある場合に表示が優先されて見えず、未回答の質問だけが残る。
            state.answerProblem?.let { problem ->
                Spacer(modifier = Modifier.height(8.dp))
                SectionChatProblemRow(problem, onRetryAnswer)
            }

            // ── この部分でクイズ ─────────────────────────
            // クイズはノート単位で1状態（別セクションの結果があればそれを開く）。
            // 色はボタン3役ルールのAI生成系（ButtonAi）。シート内の塗りボタンはこれのみ。
            Spacer(modifier = Modifier.height(20.dp))
            val quizLabel = when (quizState) {
                is QuizState.Idle -> "📝 この部分でクイズ"
                is QuizState.Loading -> "クイズを作成中…"
                is QuizState.Success -> "✓ クイズを開く"
                is QuizState.Error -> if (quizState.isViewed) "↻ クイズを再試行" else "! エラーを確認"
                // 恒久非対応なら押せないので、再試行を促すラベルにしない。
                // **DL中は押せる**ので「使えません」と言い切らない（→ isQuizActionEnabled）。
                is QuizState.AiNotice ->
                    if (quizState.notice.canTryAgainLater) "↻ クイズを再試行" else "クイズを使えません"
            }
            Button(
                onClick = onQuizTap,
                enabled = quizState.isQuizActionEnabled(),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ButtonAi, contentColor = OnButtonAi),
                shape = RoundedCornerShape(12.dp)
            ) { Text(quizLabel, color = OnButtonAi) }

            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(
                onClick = onEndSession,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = if (state.isSummaryLoading || state.isGenerating) "生成を中止" else "確認を終了",
                    color = if (state.isSummaryLoading || state.isGenerating) ErrorText else AccentText
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
 * 文言だけ出して導線を出さないと、タイムアウトのたびに質問だけが残る。
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

@Composable
private fun SuggestionRow(text: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        color = PanelRow,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                color = AccentText,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text("＋", color = AccentText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
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

@Composable
private fun LoadingRow(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AccentText)
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, fontSize = 13.sp, color = OnSurfaceSubtle)
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    val isUser = message.role == ChatRole.User
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            color = if (isUser) AccentSurface else PanelBubble,
            shape = RoundedCornerShape(
                topStart = 14.dp,
                topEnd = 14.dp,
                bottomStart = if (isUser) 14.dp else 3.dp,
                bottomEnd = if (isUser) 3.dp else 14.dp
            ),
            modifier = Modifier.fillMaxWidth(0.86f)
        ) {
            Text(
                text = message.text,
                color = if (isUser) OnAccentSurface else OnSurface,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
            )
        }
    }
}
