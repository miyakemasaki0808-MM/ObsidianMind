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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.model.state.ReadingTraceBackupState
import com.example.newproject.ui.backupProgressText
import com.example.newproject.ui.backupStepLabel
import com.example.newproject.ui.component.GradientHeader
import com.example.newproject.ui.component.IconPill
import com.example.newproject.ui.component.OptionRow
import com.example.newproject.ui.exportSummary
import com.example.newproject.ui.importPlanSummary
import com.example.newproject.ui.importResultSummary
import com.example.newproject.ui.revisedPlanNotice
import com.example.newproject.ui.theme.AccentText
import com.example.newproject.ui.theme.AppGradient
import com.example.newproject.ui.theme.ButtonOutlineOnGradient
import com.example.newproject.ui.theme.ButtonPrimary
import com.example.newproject.ui.theme.ButtonSecondary
import com.example.newproject.ui.theme.DangerAction
import com.example.newproject.ui.theme.OnButtonPrimary
import com.example.newproject.ui.theme.OnButtonSecondary
import com.example.newproject.ui.theme.OnSurface
import com.example.newproject.ui.theme.OnSurfaceFaint
import com.example.newproject.ui.theme.Panel
import com.example.newproject.ui.unreadableTraceLocation
import com.example.newproject.ui.withheldImportText

/**
 * データ管理。**アプリが管理している非表示データを人間が扱えるようにする**画面。
 *
 * 読書痕跡の退避（書き出し／読み戻し）を本体に持ち、痕跡の整理と旧補記の片付けを
 * ここから開く。3つを1画面へまとめているのは、どれも「Vault内にあるがノートではない
 * データ」を相手にしていて、**単独の設定項目としては寿命が違いすぎる**ため
 * （旧補記の片付けは移行が済めば価値を失うが、退避と整理は残る）。
 *
 * **退避を上に置く。** 失われうるものを守る操作のほうが、掃除より先に必要になる。
 */
@Composable
fun DataManagementScreen(
    state: ReadingTraceBackupState,
    vaultSelected: Boolean,
    onExport: () -> Unit,
    onImport: () -> Unit,
    onApplyImport: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onManageReadingTraces: () -> Unit,
    onManageAnnotations: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppGradient)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp)
    ) {
        GradientHeader(
            title = "データ管理",
            titleSize = 24.sp,
            leading = {
                IconPill(symbol = "‹", contentDescription = "戻る", symbolSize = 22.sp, onClick = onBack)
            }
        )

        Spacer(modifier = Modifier.height(14.dp))

        SectionTitle("読書痕跡の退避")
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            // **何が守られるのかを名前で言う。**「痕跡」だけでは、自分の言葉が
            // そこに入っていることが伝わらない。
            text = "訪問の記録・AIの要約・ノートへのひとことと、あなたが書いた返事を" +
                "1つのファイルへ書き出します。Vault のフォルダが消えたり端末を移したりしても、" +
                "そこから読み戻せます。",
            color = OnSurfaceFaint,
            fontSize = 13.sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            // 平文であることと同期経路のリスクは、押す前に伝える（→ 判断2）。
            text = "書き出したファイルの中身はそのままの文字で読めます。" +
                "Vault の中へ保存すると Obsidian の同期でクラウドへ渡るため、" +
                "既定では Vault の外を選んでください。",
            color = OnSurfaceFaint,
            fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(12.dp))

        // **Vault未選択のまま保存先を選ばせない。** SAF は保存先を確定した時点で
        // ファイルを作るので、その後で「Vaultがありません」と失敗すると
        // **0バイトの退避ファイルが残る** — 退避を守る機能が、退避に見える空ファイルを
        // 置いていくことになる。押せる条件を先に閉じる。
        val busy = state is ReadingTraceBackupState.Working
        val enabled = vaultSelected && !busy
        if (!vaultSelected) {
            Text(
                text = "Vault が選択されていないため、書き出しも読み戻しもできません。",
                color = OnSurfaceFaint,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onExport,
                enabled = enabled,
                modifier = Modifier.weight(1f).height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ButtonPrimary,
                    contentColor = OnButtonPrimary
                ),
                border = BorderStroke(1.dp, ButtonOutlineOnGradient),
                shape = RoundedCornerShape(24.dp)
            ) { Text("書き出す", color = OnButtonPrimary) }

            Button(
                onClick = onImport,
                enabled = enabled,
                modifier = Modifier.weight(1f).height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ButtonSecondary,
                    contentColor = OnButtonSecondary
                ),
                border = BorderStroke(1.dp, ButtonOutlineOnGradient),
                shape = RoundedCornerShape(24.dp)
            ) { Text("読み戻す", color = OnButtonSecondary) }
        }

        Spacer(modifier = Modifier.height(12.dp))
        BackupStatus(
            state = state,
            onApplyImport = onApplyImport,
            onCancel = onCancel,
            onDismiss = onDismiss
        )

        Spacer(modifier = Modifier.height(24.dp))
        SectionTitle("片付け")
        Spacer(modifier = Modifier.height(8.dp))

        OptionRow(
            emoji = "🧹",
            title = "読書痕跡を整理",
            subtitle = "無くなったノートの読書痕跡を確認",
            onClick = onManageReadingTraces
        )
        Spacer(modifier = Modifier.height(10.dp))

        OptionRow(
            emoji = "🗂",
            title = "AI補記メモを削除",
            subtitle = "Vault に残っている旧「AI補記メモ」を削除",
            onClick = onManageAnnotations
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun BackupStatus(
    state: ReadingTraceBackupState,
    onApplyImport: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    when (state) {
        is ReadingTraceBackupState.Idle -> Unit

        is ReadingTraceBackupState.Working -> Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = AccentText, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.size(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(backupStepLabel(state.step), color = OnSurface, fontSize = 14.sp)
                    val progress = backupProgressText(state)
                    if (progress.isNotEmpty()) {
                        Text(progress, color = OnSurfaceFaint, fontSize = 12.sp)
                    }
                }
                TextButton(onClick = onCancel) { Text("中止", color = OnSurface) }
            }
        }

        is ReadingTraceBackupState.Exported -> Card {
            CardTitle("書き出しました")
            Body(exportSummary(state))
            // 読めなかった痕跡はファイル名でしか指せない（キーは相対パスのハッシュ）。
            state.unreadableKeys.forEach { key ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(unreadableTraceLocation(key), color = OnSurfaceFaint, fontSize = 11.sp)
            }
            DismissRow(onDismiss)
        }

        is ReadingTraceBackupState.Planned -> Card {
            CardTitle(
                if (state.revised) "端末側が変わったので確認し直してください"
                else "読み戻す前に確認してください",
                emphasis = state.revised
            )
            // **「まだ書いていない」を先に言う。** 二度目の確認を求められた利用者が
            // 最初に知りたいのは、押した操作がどこまで進んだのかである。
            if (state.revised) Body(revisedPlanNotice())
            Body(importPlanSummary(state.plan))
            state.plan.withheld.forEach { item ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(withheldImportText(item), color = OnSurfaceFaint, fontSize = 11.sp)
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // **取り消せない側を目立たせない。** 危険色は「押すと戻せない」の合図で、
                // 勧めているという意味ではない（削除ダイアログと同じ扱い）。
                TextButton(onClick = onApplyImport) { Text("読み戻す", color = DangerAction) }
                TextButton(onClick = onDismiss) { Text("やめる", color = OnSurface) }
            }
        }

        is ReadingTraceBackupState.Imported -> Card {
            CardTitle(if (state.interrupted) "途中で中止しました" else "読み戻しました")
            Body(importResultSummary(state))
            state.withheld.forEach { item ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(withheldImportText(item), color = OnSurfaceFaint, fontSize = 11.sp)
            }
            DismissRow(onDismiss)
        }

        is ReadingTraceBackupState.Error -> Card {
            CardTitle("できませんでした", emphasis = true)
            Body(state.message)
            DismissRow(onDismiss)
        }
    }
}

@Composable
private fun Card(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), color = Panel, shape = RoundedCornerShape(8.dp)) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun CardTitle(text: String, emphasis: Boolean = false) {
    Text(
        text = text,
        color = if (emphasis) DangerAction else OnSurface,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun Body(text: String) {
    Spacer(modifier = Modifier.height(6.dp))
    Text(text = text, color = OnSurfaceFaint, fontSize = 13.sp)
}

@Composable
private fun DismissRow(onDismiss: () -> Unit) {
    Spacer(modifier = Modifier.height(6.dp))
    TextButton(onClick = onDismiss) { Text("閉じる", color = OnSurface) }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, color = OnSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold)
}
