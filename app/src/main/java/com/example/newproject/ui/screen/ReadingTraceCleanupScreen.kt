package com.example.newproject.ui.screen

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
    onBack: () -> Unit
) {
    LaunchedEffect(Unit) { onLoad() }

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
            }
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

            is ReadingTraceCleanupState.Success -> SuccessBody(state)
        }
    }
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
private fun SuccessBody(state: ReadingTraceCleanupState.Success) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        NoticeCard(
            title = "まだ削除はしません",
            body = "この画面は動作確認中です。いま削除するとしたらどれが対象になるかだけを表示しています。"
        )

        Spacer(modifier = Modifier.height(14.dp))
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
                CandidateRow(candidate)
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
private fun CandidateRow(candidate: OrphanCandidate) {
    Surface(modifier = Modifier.fillMaxWidth(), color = Panel, shape = RoundedCornerShape(8.dp)) {
        Column(modifier = Modifier.padding(14.dp)) {
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
