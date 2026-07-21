package com.example.newproject.ui

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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.ChatMessage
import com.example.newproject.ChatRole
import com.example.newproject.QuizState
import com.example.newproject.SectionChatState
import com.example.newproject.ui.theme.ButtonAi
import com.example.newproject.ui.theme.ErrorRed
import com.example.newproject.ui.theme.Indigo
import com.example.newproject.ui.theme.OnSurface
import com.example.newproject.ui.theme.OnVibrant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SectionChatSheet(
    state: SectionChatState,
    quizState: QuizState,
    onSuggestionTap: (String) -> Unit,
    onQuizTap: () -> Unit,
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
            Surface(color = Color(0xFFEEF0FF), shape = RoundedCornerShape(999.dp)) {
                Text(
                    text = "📌 ${state.sectionTitle}",
                    color = Indigo,
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
                state.error != null -> Text(state.error, fontSize = 13.sp, color = ErrorRed)
                else -> Text("—", fontSize = 14.sp, color = Color(0xFF999999))
            }

            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider(color = Color(0xFFE6E9F5))
            Spacer(modifier = Modifier.height(20.dp))

            // ── 質問（下）─────────────────────────────
            SectionHeader("💬", "質問")
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "気になる質問をタップすると回答します。",
                fontSize = 12.sp,
                color = Color(0xFF888888)
            )
            Spacer(modifier = Modifier.height(10.dp))

            if (state.suggestions.isEmpty() && !state.isSummaryLoading) {
                Text("質問候補を準備中…", fontSize = 13.sp, color = Color(0xFF888888))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.suggestions.forEach { q ->
                        SuggestionRow(text = q, enabled = !state.isGenerating) { onSuggestionTap(q) }
                    }
                }
            }

            // Q&A ログ
            if (state.messages.isNotEmpty() || state.isGenerating) {
                Spacer(modifier = Modifier.height(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.messages.forEach { message -> ChatBubble(message) }
                    if (state.isGenerating) LoadingRow("回答を生成中…")
                }
            }

            // ── この部分でクイズ ─────────────────────────
            // クイズはノート単位で1状態（別セクションの結果があればそれを開く）。
            // 色はボタン3役ルールのAI生成系（ButtonAi）。シート内の塗りボタンはこれのみ。
            Spacer(modifier = Modifier.height(20.dp))
            val isQuizBusy = quizState is QuizState.Loading
            val quizLabel = when (quizState) {
                is QuizState.Idle -> "📝 この部分でクイズ"
                is QuizState.Loading -> "クイズを作成中…"
                is QuizState.Success -> "✓ クイズを開く"
                is QuizState.Error -> if (quizState.isViewed) "↻ クイズを再試行" else "! エラーを確認"
            }
            Button(
                onClick = onQuizTap,
                enabled = !isQuizBusy,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ButtonAi),
                shape = RoundedCornerShape(12.dp)
            ) { Text(quizLabel, color = OnVibrant) }

            Spacer(modifier = Modifier.height(10.dp))
            OutlinedButton(
                onClick = onEndSession,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = if (state.isSummaryLoading || state.isGenerating) "生成を中止" else "確認を終了",
                    color = if (state.isSummaryLoading || state.isGenerating) ErrorRed else Indigo
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(emoji: String, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(emoji, fontSize = 16.sp)
        Spacer(modifier = Modifier.width(6.dp))
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Indigo)
    }
}

@Composable
private fun SuggestionRow(text: String, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        color = Color(0xFFF3F4FB),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                color = Indigo,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Text("＋", color = Indigo, fontSize = 16.sp, fontWeight = FontWeight.Bold)
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
        colors = listOf(Color(0xFFECEEF6), Color(0xFFF6F7FB), Color(0xFFECEEF6)),
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
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Indigo)
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, fontSize = 13.sp, color = Color(0xFF666666))
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
            color = if (isUser) Indigo else Color(0xFFF0F2FB),
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
                color = if (isUser) OnVibrant else OnSurface,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
            )
        }
    }
}
