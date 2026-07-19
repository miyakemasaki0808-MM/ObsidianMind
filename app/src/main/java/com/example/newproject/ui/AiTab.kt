package com.example.newproject.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.AnnotationState
import com.example.newproject.NoteState
import com.example.newproject.NoteUiState
import com.example.newproject.SummaryState
import com.example.newproject.ui.theme.AppGradient
import com.example.newproject.ui.theme.ButtonAi
import com.example.newproject.ui.theme.ButtonPrimary
import com.example.newproject.ui.theme.ErrorRed
import com.example.newproject.ui.theme.Indigo
import com.example.newproject.ui.theme.OnSurface
import com.example.newproject.ui.theme.OnVibrant
import com.example.newproject.ui.theme.Panel
import com.example.newproject.ui.theme.PanelBlue

// ---------------------------------------------------------------------------
// タブ3: AI（要約・Q&A・補記メモ）
// ---------------------------------------------------------------------------

@Composable
fun AiTab(
    uiState: NoteUiState,
    onGenerateQuiz: () -> Unit,
    onCreateAnnotation: () -> Unit,
    onOpenAnnotation: () -> Unit
) {
    val hasNote = uiState.noteState is NoteState.Success
    val annotationState = uiState.annotationState
    val isAnnotationLoading = annotationState is AnnotationState.Loading
    val annotationLabel = when (annotationState) {
        is AnnotationState.Idle -> "✨ AI補記メモ"
        is AnnotationState.Loading -> "AI補記メモを作成中…"
        is AnnotationState.Success -> "✓ AI補記メモを見る"
        is AnnotationState.Error -> if (annotationState.isViewed) {
            "↻ AI補記メモを再試行"
        } else {
            "! エラーを確認"
        }
    }
    val annotationAction = when (annotationState) {
        is AnnotationState.Success -> onOpenAnnotation
        is AnnotationState.Error -> if (annotationState.isViewed) onCreateAnnotation else onOpenAnnotation
        else -> onCreateAnnotation
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppGradient)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp)
    ) {
        Text(
            text = "AIアシスト",
            color = OnVibrant,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))

        if (!hasNote) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Panel.copy(alpha = 0.22f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "先に「ノート」タブでノートを表示してください。",
                    color = OnVibrant,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(16.dp)
                )
            }
            return@Column
        }

        SummaryPanel(summaryState = uiState.summaryState)

        Spacer(modifier = Modifier.height(16.dp))
        // 隣のAI補記メモ（ButtonAi）と同色だと区別しづらいため、画面の主アクションとしてピンクにする
        Button(
            onClick = onGenerateQuiz,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ButtonPrimary),
            shape = RoundedCornerShape(24.dp)
        ) { Text("📝 Q&Aを作る", color = OnVibrant) }

        Spacer(modifier = Modifier.height(10.dp))
        Button(
            onClick = annotationAction,
            enabled = !isAnnotationLoading,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ButtonAi),
            shape = RoundedCornerShape(24.dp)
        ) { Text(annotationLabel, color = OnVibrant) }
    }
}

@Composable
internal fun SummaryPanel(summaryState: SummaryState, modifier: Modifier = Modifier) {
    when (summaryState) {
        is SummaryState.Idle,
        is SummaryState.AiUnavailable -> return
        is SummaryState.Loading,
        is SummaryState.Downloading,
        is SummaryState.Success,
        is SummaryState.Error -> Unit
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = PanelBlue,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "📝 AI 要約",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Indigo
            )
            Spacer(modifier = Modifier.height(8.dp))
            when (summaryState) {
                is SummaryState.Loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Indigo)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("要約を生成中…", fontSize = 13.sp, color = Color(0xFF555555))
                    }
                }
                is SummaryState.Downloading -> {
                    val downloaded = summaryState.downloaded
                    val total = summaryState.total
                    val progress = if (total > 0) downloaded.toFloat() / total else -1f
                    val label = when {
                        downloaded < 0   -> "Gemini Nano をダウンロード中…"
                        total <= 0       -> "Gemini Nano をダウンロード中…"
                        else -> {
                            val dlMb = downloaded / 1_048_576f
                            val totalMb = total / 1_048_576f
                            "Gemini Nano をダウンロード中… %.0f / %.0f MB".format(dlMb, totalMb)
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(label, fontSize = 13.sp, color = Color(0xFF555555))
                        if (progress >= 0) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                                color = Indigo
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = Indigo
                            )
                        }
                    }
                }
                is SummaryState.Success -> {
                    Text(text = summaryState.summary, fontSize = 14.sp, lineHeight = 22.sp, color = OnSurface)
                }
                is SummaryState.AiUnavailable -> {
                    Text("この端末はGemini Nanoに対応していません。", fontSize = 13.sp, color = Color(0xFF888888))
                }
                is SummaryState.Error -> {
                    Text("要約の取得に失敗しました: ${summaryState.message}", fontSize = 13.sp, color = ErrorRed)
                }
                else -> {}
            }
        }
    }
}
