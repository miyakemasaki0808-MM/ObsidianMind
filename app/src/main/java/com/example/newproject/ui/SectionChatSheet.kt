package com.example.newproject.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.ChatMessage
import com.example.newproject.ChatRole
import com.example.newproject.SectionChatState
import com.example.newproject.ui.theme.ErrorRed
import com.example.newproject.ui.theme.Indigo
import com.example.newproject.ui.theme.OnSurface
import com.example.newproject.ui.theme.OnVibrant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SectionChatSheet(
    state: SectionChatState,
    onSuggestionTap: (String) -> Unit,
    onDismiss: () -> Unit,
    onEndSession: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
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
                state.isSummaryLoading -> LoadingRow("要約を生成中…")
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

            Spacer(modifier = Modifier.height(20.dp))
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
