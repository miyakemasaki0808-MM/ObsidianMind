package com.example.newproject.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.model.state.AiNoticeAction
import com.example.newproject.model.state.AiStatusNotice
import com.example.newproject.ui.theme.OnSurface

/**
 * AI状態の説明を1箇所で描く。**文言も導線も [notice] が決める。**
 *
 * **色で状態を区別しない（一律 [OnSurface]）。** 手がかりは理由の文そのものと、
 * 再試行ボタンが出るかどうかである。ui_design_principles §1 の 1.4.1 が要求する
 * 「色以外にもう1つ」を、色を使わずに満たしている（灰色にしても伝わる）。
 * トーンの出し分けは必要になってから足す。
 *
 * 導線に対応するラムダを渡さないとボタンは出ない。**押しても何も起きないボタンを
 * 描くより、出さないほうがよい**（[AiNoticeAction.None] は元から何も出さない）。
 */
@Composable
fun AiStatusNoticeRow(
    notice: AiStatusNotice,
    modifier: Modifier = Modifier,
    onDownload: (() -> Unit)? = null,
    onRetry: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(notice.message, fontSize = 13.sp, lineHeight = 19.sp, color = OnSurface)
        val primary: Pair<String, () -> Unit>? = when (notice.action) {
            AiNoticeAction.Download -> onDownload?.let { "確認してダウンロード" to it }
            AiNoticeAction.Retry -> onRetry?.let { "再試行" to it }
            AiNoticeAction.None -> null
        }
        if (primary != null || onDismiss != null) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                primary?.let { (label, onClick) ->
                    Button(onClick = onClick, modifier = Modifier.weight(1f)) { Text(label) }
                }
                onDismiss?.let {
                    TextButton(onClick = it, modifier = Modifier.weight(1f)) { Text("閉じる") }
                }
            }
        }
    }
}
