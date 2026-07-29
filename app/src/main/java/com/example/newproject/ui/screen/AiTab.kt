package com.example.newproject.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.ui.component.GradientHeader
import com.example.newproject.model.state.AnnotationState
import com.example.newproject.model.state.DistillCandidateItem
import com.example.newproject.model.state.DistillState
import com.example.newproject.model.state.NoteState
import com.example.newproject.model.NoteUiState
import com.example.newproject.model.state.SummaryState
import com.example.newproject.ui.theme.OnButtonAi
import com.example.newproject.ui.theme.OnSurfaceFaint
import com.example.newproject.ui.theme.OnSurfaceMuted
import com.example.newproject.ui.theme.OnSurfaceSubtle
import com.example.newproject.ui.theme.AppGradient
import com.example.newproject.ui.theme.ButtonOutlineOnGradient
import com.example.newproject.ui.theme.ButtonAi
import com.example.newproject.ui.theme.ErrorText
import com.example.newproject.ui.theme.AccentText
import com.example.newproject.ui.theme.OnSurface
import com.example.newproject.ui.theme.OnVibrant
import com.example.newproject.ui.theme.OnVibrantMuted
import com.example.newproject.ui.theme.Panel
import com.example.newproject.ui.theme.PanelBlue

// ---------------------------------------------------------------------------
// タブ3: AI（要約・補記メモ）
// Q&Aは読書画面の吹き出し（フォーカスセクション周辺クイズ）へ移動した。
// ---------------------------------------------------------------------------

@Composable
fun AiTab(
    uiState: NoteUiState,
    onCreateAnnotation: () -> Unit,
    onOpenAnnotation: () -> Unit,
    onStartDistill: () -> Unit,
    onDownloadDistillModel: () -> Unit,
    onToggleDistillCandidate: (String) -> Unit,
    onSaveDistill: () -> Unit,
    onRetryDistill: () -> Unit,
    onDismissDistill: () -> Unit,
    onKeepCurrentRecovery: () -> Unit,
    onRestoreOriginal: () -> Unit,
    onExportOriginal: () -> Unit
) {
    val hasNote = uiState.noteState is NoteState.Success
    val annotationState = uiState.annotationState
    val isAnnotationLoading = annotationState is AnnotationState.Loading
    val annotationLabel = when (annotationState) {
        is AnnotationState.Idle -> "✨ AI補記メモをつくる"
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
        GradientHeader(
            title = "Reflect",
            subtitle = "AIと一緒に、ノートを深く読み直す。"
        )

        val recoveryVisible = uiState.distillState is DistillState.RecoveryRequired
        if (recoveryVisible) {
            DistillPanel(
                state = uiState.distillState,
                noteUnavailableReason = null,
                onStart = onStartDistill,
                onDownloadModel = onDownloadDistillModel,
                onToggleCandidate = onToggleDistillCandidate,
                onSave = onSaveDistill,
                onRetry = onRetryDistill,
                onDismiss = onDismissDistill,
                onKeepCurrentRecovery = onKeepCurrentRecovery,
                onRestoreOriginal = onRestoreOriginal,
                onExportOriginal = onExportOriginal
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (!hasNote) {
            // 半透明にするとグラデーションが透けて、実効的な背景が停止色ごとに変わる。
            // 白文字ではAqua上で1.82まで落ちていた（透過22%では下地をほとんど隠せない）。
            // 面を不透明にして、背景が何色でも文字の条件が動かないようにする。
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Panel,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "先に「ノート」タブでノートを表示してください。",
                    color = OnSurface,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(16.dp)
                )
            }
            return@Column
        }

        SummaryPanel(summaryState = uiState.summaryState)

        Spacer(modifier = Modifier.height(16.dp))
        if (!recoveryVisible) {
            DistillPanel(
                state = uiState.distillState,
                noteUnavailableReason = (uiState.noteState as? NoteState.Success)?.distillUnavailableReason,
                onStart = onStartDistill,
                onDownloadModel = onDownloadDistillModel,
                onToggleCandidate = onToggleDistillCandidate,
                onSave = onSaveDistill,
                onRetry = onRetryDistill,
                onDismiss = onDismissDistill,
                onKeepCurrentRecovery = onKeepCurrentRecovery,
                onRestoreOriginal = onRestoreOriginal,
                onExportOriginal = onExportOriginal
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
        Button(
            onClick = annotationAction,
            enabled = !isAnnotationLoading,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ButtonAi, contentColor = OnButtonAi),
            border = BorderStroke(1.5.dp, ButtonOutlineOnGradient),
            shape = RoundedCornerShape(24.dp)
        ) { Text(annotationLabel, color = OnButtonAi) }
    }
}

@Composable
private fun DistillPanel(
    state: DistillState,
    noteUnavailableReason: String?,
    onStart: () -> Unit,
    onDownloadModel: () -> Unit,
    onToggleCandidate: (String) -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onKeepCurrentRecovery: () -> Unit,
    onRestoreOriginal: () -> Unit,
    onExportOriginal: () -> Unit
) {
    var showConfirmation by remember { mutableStateOf(false) }
    var showRestoreConfirmation by remember { mutableStateOf(false) }
    var showKeepConfirmation by remember { mutableStateOf(false) }
    val candidates = state as? DistillState.Candidates

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = PanelBlue,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("✦ ノートを蒸留", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = AccentText)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "AIが重要文を選び、確認した文だけをノート内で太字にします。",
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = OnSurface
            )
            Spacer(modifier = Modifier.height(12.dp))

            when (state) {
                is DistillState.Idle -> {
                    if (noteUnavailableReason != null) {
                        Text(noteUnavailableReason, fontSize = 13.sp, color = ErrorText)
                    } else {
                        Button(
                            onClick = onStart,
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ButtonAi, contentColor = OnButtonAi)
                        ) { Text("重要文を見つける", color = OnButtonAi) }
                    }
                }
                is DistillState.Analyzing -> ProgressRow("AIを待っています／分析中…")
                is DistillState.NeedsDownload -> {
                    Text(
                        "蒸留にはGemini Nanoのダウンロードが必要です。通信量を確認してから開始してください。",
                        fontSize = 13.sp,
                        color = OnSurface
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(onClick = onDownloadModel, modifier = Modifier.fillMaxWidth()) {
                        Text("確認してダウンロード")
                    }
                }
                is DistillState.Downloading -> {
                    val progress = if (state.total > 0) state.downloaded.toFloat() / state.total else -1f
                    Text("Gemini Nanoをダウンロード中…", fontSize = 13.sp, color = OnSurface)
                    Spacer(modifier = Modifier.height(8.dp))
                    if (progress >= 0f) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = AccentText)
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = AccentText)
                    }
                }
                is DistillState.Unavailable -> {
                    Text(state.message, fontSize = 13.sp, color = ErrorText)
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = onDismiss) { Text("閉じる") }
                }
                is DistillState.Candidates -> {
                    state.items.forEach { item ->
                        DistillCandidateRow(item, onToggleCandidate)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    val ratioPercent = state.projectedBoldRatio * 100.0
                    Text(
                        "選択 ${state.selectedCount}文・変更後の太字率 %.1f%%".format(ratioPercent),
                        fontSize = 12.sp,
                        color = when {
                            state.isSingleSentenceException -> AccentText
                            state.isWithinBoldLimit -> OnSurface
                            else -> ErrorText
                        }
                    )
                    when {
                        state.isSingleSentenceException -> Text(
                            "短いノートのため、最重要の1文だけを上限の例外として選択しています。保存前に太字率を確認してください。",
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = AccentText
                        )
                        !state.isWithinBoldLimit && state.selectedCount == 0 -> Text(
                            "既存の太字率が累積上限30%に達しているため、これ以上追加できません。",
                            fontSize = 12.sp,
                            color = ErrorText
                        )
                        !state.isWithinBoldLimit -> Text(
                            "累積上限30%を超えています。選択を減らしてください。",
                            fontSize = 12.sp,
                            color = ErrorText
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("キャンセル") }
                        Button(
                            onClick = { showConfirmation = true },
                            enabled = state.canSaveSelection,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = ButtonAi, contentColor = OnButtonAi)
                        ) { Text("プレビュー", color = OnButtonAi) }
                    }
                }
                is DistillState.Saving -> ProgressRow("保存して内容を検証中…")
                is DistillState.Saved -> {
                    Text("${state.sentenceCount}文を太字にしました。", fontSize = 13.sp, color = AccentText)
                    TextButton(onClick = onDismiss) { Text("完了") }
                }
                is DistillState.Conflict -> {
                    Text(state.message, fontSize = 13.sp, color = ErrorText)
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("最新の本文で再解析") }
                }
                is DistillState.RecoveryRequired -> {
                    Text("復旧が必要です", fontWeight = FontWeight.Bold, color = ErrorText)
                    Text(state.message, fontSize = 13.sp, lineHeight = 19.sp, color = OnSurface)
                    Spacer(modifier = Modifier.height(10.dp))
                    if (state.canRestore) {
                        Button(onClick = { showRestoreConfirmation = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("保存前の本文へ復元")
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    if (state.canExport) {
                        OutlinedButton(onClick = onExportOriginal, modifier = Modifier.fillMaxWidth()) {
                            Text("保存前の本文を別ファイルへ書き出す")
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    if (state.canKeepCurrent) {
                        TextButton(onClick = { showKeepConfirmation = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (state.canRestore) "現在のファイルを維持" else "復旧情報を破棄")
                        }
                    }
                }
                is DistillState.RecoveryResolved -> {
                    Text(state.message, fontSize = 13.sp, color = AccentText)
                    TextButton(onClick = onDismiss) { Text("完了") }
                }
                is DistillState.Error -> {
                    Text(state.message, fontSize = 13.sp, color = ErrorText)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.canRetry) {
                            Button(onClick = onRetry, modifier = Modifier.weight(1f)) { Text("再試行") }
                        }
                        TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("閉じる") }
                    }
                }
            }
        }
    }

    if (showConfirmation && candidates != null) {
        AlertDialog(
            onDismissRequest = { showConfirmation = false },
            title = { Text("選択した${candidates.selectedCount}文を太字にします") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    candidates.items.filter { it.isSelected }.forEach { item ->
                        Text(item.text, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = OnSurface)
                    }
                    if (candidates.isSingleSentenceException) {
                        Text(
                            "短いノートのため、この1文で変更後の太字率は %.1f%% になります。通常の累積上限30%%を超えますが、この1文だけ保存できます。"
                                .format(candidates.projectedBoldRatio * 100.0),
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = AccentText
                        )
                    }
                    Text("元の文字は削除しません。この操作の取り消し機能はありません。", fontSize = 12.sp, color = ErrorText)
                }
            },
            confirmButton = {
                Button(onClick = {
                    showConfirmation = false
                    onSave()
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmation = false }) { Text("キャンセル") }
            }
        )
    }

    if (showRestoreConfirmation) {
        AlertDialog(
            onDismissRequest = { showRestoreConfirmation = false },
            title = { Text("保存前の本文へ復元しますか？") },
            text = { Text("現在のファイル内容は保存前の本文で置き換わります。必要なら先に別ファイルへ書き出してください。") },
            confirmButton = {
                Button(onClick = {
                    showRestoreConfirmation = false
                    onRestoreOriginal()
                }) { Text("復元する") }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreConfirmation = false }) { Text("キャンセル") }
            }
        )
    }

    if (showKeepConfirmation) {
        AlertDialog(
            onDismissRequest = { showKeepConfirmation = false },
            title = { Text("現在のファイルを維持しますか？") },
            text = { Text("保存前の本文を保持している復旧情報を削除します。この操作後はアプリから復元できません。") },
            confirmButton = {
                Button(onClick = {
                    showKeepConfirmation = false
                    onKeepCurrentRecovery()
                }) { Text("維持して完了") }
            },
            dismissButton = {
                TextButton(onClick = { showKeepConfirmation = false }) { Text("キャンセル") }
            }
        )
    }
}

@Composable
private fun DistillCandidateRow(item: DistillCandidateItem, onToggle: (String) -> Unit) {
    Surface(color = Panel.copy(alpha = 0.72f), shape = RoundedCornerShape(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Top) {
            Checkbox(checked = item.isSelected, onCheckedChange = { onToggle(item.id) })
            Column(modifier = Modifier.weight(1f).padding(top = 4.dp, end = 4.dp)) {
                val meta = listOfNotNull(item.heading, item.positionLabel).joinToString(" · ")
                Text(meta, fontSize = 11.sp, color = OnSurfaceSubtle)
                item.context?.takeIf { it.isNotBlank() }?.let { context ->
                    Text(context, fontSize = 11.sp, color = OnSurfaceFaint, maxLines = 2)
                    Spacer(modifier = Modifier.height(3.dp))
                }
                Text(item.text, fontSize = 14.sp, lineHeight = 20.sp, color = OnSurface)
            }
        }
    }
}

@Composable
private fun ProgressRow(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = AccentText)
        Spacer(modifier = Modifier.width(8.dp))
        Text(label, fontSize = 13.sp, color = OnSurface)
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
                color = AccentText
            )
            Spacer(modifier = Modifier.height(8.dp))
            when (summaryState) {
                is SummaryState.Loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AccentText)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("要約を生成中…", fontSize = 13.sp, color = OnSurfaceMuted)
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
                        Text(label, fontSize = 13.sp, color = OnSurfaceMuted)
                        if (progress >= 0) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth(),
                                color = AccentText
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = AccentText
                            )
                        }
                    }
                }
                is SummaryState.Success -> {
                    Text(text = summaryState.summary, fontSize = 14.sp, lineHeight = 22.sp, color = OnSurface)
                }
                is SummaryState.AiUnavailable -> {
                    Text("この端末はGemini Nanoに対応していません。", fontSize = 13.sp, color = OnSurfaceFaint)
                }
                is SummaryState.Error -> {
                    Text("要約の取得に失敗しました: ${summaryState.message}", fontSize = 13.sp, color = ErrorText)
                }
                else -> {}
            }
        }
    }
}
