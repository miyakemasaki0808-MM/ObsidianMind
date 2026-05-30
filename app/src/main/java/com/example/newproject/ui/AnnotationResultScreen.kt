package com.example.newproject.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.AnnotationState
import com.example.newproject.ui.markdown.MarkdownNoteContent
import com.example.newproject.ui.theme.Aqua
import com.example.newproject.ui.theme.Coral
import com.example.newproject.ui.theme.Indigo
import com.example.newproject.ui.theme.OnSurface
import com.example.newproject.ui.theme.OnVibrant
import com.example.newproject.ui.theme.OnVibrantMuted
import com.example.newproject.ui.theme.Panel
import com.example.newproject.ui.theme.PanelTinted

@Composable
fun AnnotationResultScreen(
    annotationState: AnnotationState,
    onBack: () -> Unit
) {
    val gradient = Brush.linearGradient(colors = listOf(Indigo, Aqua, Coral))

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(gradient)
            .statusBarsPadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "補記メモ",
            color = OnVibrant,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold
        )

        when (annotationState) {
            is AnnotationState.Idle,
            is AnnotationState.Loading -> AnnotationLoadingContent(
                modifier = Modifier.weight(1f)
            )
            is AnnotationState.Error -> AnnotationErrorContent(
                message = annotationState.message,
                modifier = Modifier.weight(1f)
            )
            is AnnotationState.Success -> AnnotationSuccessContent(
                state = annotationState,
                modifier = Modifier.weight(1f)
            )
        }

        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Indigo),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("戻る", color = OnVibrant)
        }
    }
}

@Composable
private fun AnnotationLoadingContent(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Panel,
        shape = RoundedCornerShape(8.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Indigo)
                Spacer(modifier = Modifier.height(12.dp))
                Text("補記メモを生成中…", color = OnSurface, fontSize = 14.sp)
            }
        }
    }
}

@Composable
private fun AnnotationErrorContent(message: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Panel,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "補記メモの作成に失敗しました",
                color = Color(0xFFCC0000),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(message, color = OnSurface, fontSize = 14.sp)
        }
    }
}

@Composable
private fun AnnotationSuccessContent(
    state: AnnotationState.Success,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = PanelTinted,
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "補記するべきかの評価",
                    color = Indigo,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                MarkdownNoteContent(
                    content = extractMarkdownSection(state.content, "粒度評価")
                        .ifBlank { "粒度評価を取得できませんでした。" },
                    modifier = Modifier.height(120.dp)
                )
            }
        }

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            color = Panel,
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "補記内容のMarkdown",
                    color = Indigo,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = state.fileName,
                    color = Color(0xFF666666),
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                MarkdownNoteContent(
                    content = state.content,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

private fun extractMarkdownSection(markdown: String, title: String): String {
    val marker = "## $title"
    val section = markdown.substringAfter(marker, missingDelimiterValue = "")
    if (section.isBlank()) return ""
    return section.substringBefore("\n## ").trim()
}
