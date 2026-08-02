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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.ui.component.GradientHeader
import com.example.newproject.ui.theme.OnSurfaceFaint
import com.example.newproject.ui.theme.AppGradient
import com.example.newproject.ui.theme.ButtonAi
import com.example.newproject.ui.theme.OnButtonAi
import com.example.newproject.ui.theme.OnSurface
import com.example.newproject.ui.theme.OnVibrant
import com.example.newproject.ui.theme.Panel

@Composable
fun OptionsScreen(
    vaultSelected: Boolean,
    darkTheme: Boolean,
    notePaperAging: Boolean,
    onSelectVault: () -> Unit,
    onManageAnnotations: () -> Unit,
    onManageReadingTraces: () -> Unit,
    onToggleDarkTheme: (Boolean) -> Unit,
    onToggleNotePaperAging: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppGradient)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp)
    ) {
        GradientHeader(title = "オプション")

        OptionRow(
            emoji = "📁",
            title = "Vaultを変更",
            subtitle = if (vaultSelected) "Vaultフォルダ選択済み" else "Vaultフォルダが未選択です",
            onClick = onSelectVault
        )
        Spacer(modifier = Modifier.height(10.dp))

        OptionRow(
            emoji = "🗂",
            title = "AI補記メモを削除",
            subtitle = "保存済みの補記メモを管理・削除",
            onClick = onManageAnnotations
        )
        Spacer(modifier = Modifier.height(10.dp))

        OptionRow(
            emoji = "🧹",
            title = "読書痕跡を整理",
            subtitle = "無くなったノートの読書痕跡を確認",
            onClick = onManageReadingTraces
        )
        Spacer(modifier = Modifier.height(10.dp))

        // OS設定には追従しない。暗い場所で読むかどうかは本人が決める。
        OptionSwitchRow(
            emoji = if (darkTheme) "🌙" else "☀️",
            title = "ダークモード",
            subtitle = if (darkTheme) "暗い配色で表示しています" else "明るい配色で表示しています",
            checked = darkTheme,
            onCheckedChange = onToggleDarkTheme
        )
        Spacer(modifier = Modifier.height(10.dp))

        // ダークでは効かないので、その旨を説明文で言い切る（トグル自体は隠さない。
        // 隠すと「設定が消えた」に見えるうえ、ライトへ戻したときに気づけない）。
        //
        // 文言は「更新」と言い切る。判定材料は SAF の最終更新日時であって閲覧履歴ではないので、
        // 「しばらく開いていない」と書くと、読書痕跡を見ている機能に読める（実際は見ていない）。
        OptionSwitchRow(
            emoji = "📜",
            title = "ノートの紙色",
            subtitle = when {
                !notePaperAging -> "どのノートも同じ紙色で表示しています"
                darkTheme -> "ダークモードの間は紙色を変えません"
                else -> "更新から時間が経ったノートほど生成りに寄せています"
            },
            checked = notePaperAging,
            onCheckedChange = onToggleNotePaperAging
        )
    }
}

@Composable
private fun OptionSwitchRow(
    emoji: String,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            // 行全体をスイッチとして扱う。Switch本体だけでなく行のどこを押しても切り替わり、
            // TalkBackにも「スイッチ」として1つだけ読み上げさせる。
            .toggleable(
                value = checked,
                onValueChange = onCheckedChange,
                role = Role.Switch
            ),
        color = Panel,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(emoji, fontSize = 22.sp)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = OnSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                // 任意のαで薄めない。12sp は AA の大文字例外に入らないので、
                // 弱い文字は名前付きトークンの範囲から選ぶ（旧 α=0.6 は 4.31 で未達）。
                Text(subtitle, color = OnSurfaceFaint, fontSize = 12.sp)
            }
            Switch(
                checked = checked,
                // 行側で toggleable を持つので、Switch自体はSemanticsから外して二重読み上げを防ぐ。
                onCheckedChange = null,
                modifier = Modifier.clearAndSetSemantics {},
                colors = SwitchDefaults.colors(
                    checkedThumbColor = OnButtonAi,
                    checkedTrackColor = ButtonAi,
                    uncheckedThumbColor = Panel,
                    uncheckedTrackColor = OnSurface.copy(alpha = 0.35f)
                )
            )
        }
    }
}

@Composable
private fun OptionRow(
    emoji: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = Panel,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(emoji, fontSize = 22.sp)
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(title, color = OnSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(subtitle, color = OnSurfaceFaint, fontSize = 12.sp)
            }
        }
    }
}
