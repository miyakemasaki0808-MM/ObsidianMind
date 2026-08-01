package com.example.newproject.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.model.OrphanCandidate
import com.example.newproject.model.WithheldOrphans
import com.example.newproject.model.state.ReadingTraceCleanupState
import com.example.newproject.ui.blockedExplanation
import com.example.newproject.ui.component.GradientHeader
import com.example.newproject.ui.component.IconPill
import com.example.newproject.ui.theme.AccentText
import com.example.newproject.ui.theme.AppGradient
import com.example.newproject.ui.theme.DangerAction
import com.example.newproject.ui.theme.OnSurface
import com.example.newproject.ui.theme.OnDangerAction
import com.example.newproject.ui.theme.OnSurfaceFaint
import com.example.newproject.ui.theme.Panel
import com.example.newproject.ui.withheldLocation
import com.example.newproject.ui.withheldReasonText

/**
 * 読書痕跡の整理（シャドーモード）。
 *
 * **段階3 では削除しない。削除ボタンも置かない。** 「自動なら消していた候補」と
 * 「遮断器が止めた一群」を並べて見せ、判定が信用できるかを実運用で観測するのが目的。
 * ここで再出現する候補が出れば、それが自動化の条件が危険だったことの反証になる
 * （→ reflect_reading_trace §14）。
 *
 * 文言の方針: **「候補ゼロ」と「判定できなかった」を必ず別の言葉で出す。**
 * 同じ「表示なし」にすると、遮断器が働いている状態を「掃除するものが無い」と読み違える。
 */
@Composable
fun ReadingTraceCleanupScreen(
    state: ReadingTraceCleanupState,
    onLoad: () -> Unit,
    onDelete: (List<String>) -> Unit,
    onBack: () -> Unit
) {
    LaunchedEffect(Unit) { onLoad() }

    var pendingDelete by remember { mutableStateOf<OrphanCandidate?>(null) }
    var showDeleteAll by remember { mutableStateOf(false) }
    val orphans = (state as? ReadingTraceCleanupState.Success)?.orphans.orEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppGradient)
            .safeDrawingPadding()
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp)
    ) {
        GradientHeader(
            title = "読書痕跡の整理",
            titleSize = 24.sp,
            leading = {
                IconPill(symbol = "‹", contentDescription = "戻る", symbolSize = 22.sp, onClick = onBack)
            },
            trailing = if (orphans.isNotEmpty()) {
                {
                    Surface(
                        modifier = Modifier.clickable { showDeleteAll = true },
                        color = DangerAction,
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Text(
                            text = "すべて削除",
                            color = OnDangerAction,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }
            } else null
        )

        Spacer(modifier = Modifier.height(14.dp))

        when (state) {
            is ReadingTraceCleanupState.Idle,
            is ReadingTraceCleanupState.Loading -> LoadingBody()

            is ReadingTraceCleanupState.Error -> NoticeCard(
                title = "調べられませんでした",
                body = state.message,
                emphasis = true
            )

            is ReadingTraceCleanupState.Blocked -> NoticeCard(
                title = "今回は判定を見送りました",
                body = blockedExplanation(state),
                emphasis = true
            )

            is ReadingTraceCleanupState.Success ->
                SuccessBody(state, onRequestDelete = { pendingDelete = it })
        }
    }

    pendingDelete?.let { candidate ->
        ConfirmDialog(
            title = "この読書痕跡を削除しますか",
            // 何が消えて何が消えないかを必ず並べて書く。痕跡とノートの区別が付かないと
            // 「ノートが消える」と受け取られる。
            body = "「${candidate.noteTitle}」の読書記録を削除します。\n" +
                "ノート本文は削除されません（すでに見つからないノートです）。",
            onConfirm = {
                onDelete(listOf(candidate.key))
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }

    if (showDeleteAll) {
        ConfirmDialog(
            title = "候補をすべて削除しますか",
            body = "${orphans.size}件の読書記録を削除します。\n" +
                "ノート本文は削除されません。安全のため保留した分は削除されません。",
            onConfirm = {
                onDelete(orphans.map { it.key })
                showDeleteAll = false
            },
            onDismiss = { showDeleteAll = false }
        )
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, color = OnSurface) },
        text = { Text(text = body, color = OnSurfaceFaint, fontSize = 14.sp) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(text = "削除", color = DangerAction) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "キャンセル", color = OnSurface) }
        },
        containerColor = Panel
    )
}

@Composable
private fun LoadingBody() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = AccentText)
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = "Vault を調べています…", color = OnSurfaceFaint, fontSize = 14.sp)
    }
}

@Composable
private fun SuccessBody(
    state: ReadingTraceCleanupState.Success,
    onRequestDelete: (OrphanCandidate) -> Unit
) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        // 削除に失敗しても候補は一覧に残す（消えると再試行できない）。件数だけ上に添える。
        if (state.deleteFailureCount > 0) {
            NoticeCard(
                title = "${state.deleteFailureCount}件を削除できませんでした",
                body = "しばらくしてからもう一度お試しください。",
                emphasis = true
            )
            Spacer(modifier = Modifier.height(14.dp))
        }

        SectionTitle("削除候補 ${state.orphans.size}件")
        if (state.orphans.isEmpty()) {
            // 「候補ゼロ」を明示する。無表示にすると保留中の状態と見分けが付かない。
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "対応するノートが見つからない痕跡はありませんでした。",
                color = OnSurfaceFaint,
                fontSize = 14.sp
            )
        } else {
            state.orphans.forEach { candidate ->
                Spacer(modifier = Modifier.height(8.dp))
                CandidateRow(candidate, onDelete = { onRequestDelete(candidate) })
            }
        }

        if (state.withheld.isNotEmpty()) {
            Spacer(modifier = Modifier.height(20.dp))
            SectionTitle("安全のため保留 ${state.withheld.sumOf { it.count }}件")
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "ノートが見つからなくても、読み取りに失敗しただけの可能性がある分です。",
                color = OnSurfaceFaint,
                fontSize = 13.sp
            )
            state.withheld.forEach { group ->
                Spacer(modifier = Modifier.height(8.dp))
                WithheldRow(group)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, color = OnSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun CandidateRow(candidate: OrphanCandidate, onDelete: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), color = Panel, shape = RoundedCornerShape(8.dp)) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = candidate.noteTitle,
                color = OnSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = candidate.vaultRelativePath, color = OnSurfaceFaint, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "これまで${candidate.totalVisitCount}回開いています",
                color = OnSurfaceFaint,
                fontSize = 12.sp
            )
        }
            IconPill(
                symbol = "×",
                contentDescription = "${candidate.noteTitle} の読書痕跡を削除",
                symbolSize = 18.sp,
                onClick = onDelete
            )
        }
    }
}

@Composable
private fun WithheldRow(group: WithheldOrphans) {
    Surface(modifier = Modifier.fillMaxWidth(), color = Panel, shape = RoundedCornerShape(8.dp)) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = withheldLocation(group),
                    color = OnSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = withheldReasonText(group), color = OnSurfaceFaint, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun NoticeCard(title: String, body: String, emphasis: Boolean = false) {
    Surface(modifier = Modifier.fillMaxWidth(), color = Panel, shape = RoundedCornerShape(8.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                color = if (emphasis) DangerAction else OnSurface,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(text = body, color = OnSurfaceFaint, fontSize = 13.sp)
        }
    }
}
