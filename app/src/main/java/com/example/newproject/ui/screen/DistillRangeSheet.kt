package com.example.newproject.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.model.state.DistillCandidateItem
import com.example.newproject.model.state.DistillRangePreset
import com.example.newproject.ui.theme.AccentText
import com.example.newproject.ui.theme.ButtonAi
import com.example.newproject.ui.theme.ErrorText
import com.example.newproject.ui.theme.OnButtonAi
import com.example.newproject.ui.theme.OnSurface
import com.example.newproject.ui.theme.OnSurfaceSubtle
import com.example.newproject.ui.theme.PanelChip

/** 段の表示名。**モデル側は名前を持たない**（判断だけを純関数に残す）。 */
internal fun DistillRangePreset.label(): String = when (this) {
    DistillRangePreset.Term -> "語句"
    DistillRangePreset.Clause -> "意味節"
    DistillRangePreset.Sentence -> "文全体"
}

/**
 * 太字にする範囲を調整するシート。
 *
 * **閉じることが確定。** 取り消しの口は「最初の範囲に戻す」1つに絞り、
 * キャンセルボタンを別に置かない（口が2つあるとどちらが効いたか読めなくなる）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DistillRangeSheet(
    item: DistillCandidateItem,
    projectedBoldRatio: Double,
    isWithinBoldLimit: Boolean,
    isDeselectedByOverlap: Boolean,
    otherDeselectedCount: Int,
    onSelectPreset: (DistillRangePreset) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        scrimColor = BottomSheetDefaults.ScrimColor.copy(alpha = 0.5f)
    ) {
        DistillRangeSheetContent(
            item = item,
            projectedBoldRatio = projectedBoldRatio,
            isWithinBoldLimit = isWithinBoldLimit,
            isDeselectedByOverlap = isDeselectedByOverlap,
            otherDeselectedCount = otherDeselectedCount,
            onSelectPreset = onSelectPreset,
            onReset = onReset
        )
    }
}

/**
 * シートの中身。**`ModalBottomSheet` を開かずに描画を検査するため**に切り出してある
 * （[QuizActionSection] と同じ理由）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DistillRangeSheetContent(
    item: DistillCandidateItem,
    projectedBoldRatio: Double,
    isWithinBoldLimit: Boolean,
    isDeselectedByOverlap: Boolean,
    otherDeselectedCount: Int,
    onSelectPreset: (DistillRangePreset) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 320.dp)
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
    ) {
        Surface(color = PanelChip, shape = RoundedCornerShape(999.dp)) {
            Text(
                text = "✦ 太字にする範囲",
                color = AccentText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        // **色だけで範囲を示さない。** 実際の太字と下線を併用する（→ ui_design_principles §1）。
        Text(
            text = highlightedParent(item),
            fontSize = 15.sp,
            lineHeight = 24.sp,
            color = OnSurface
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "太字にできるのは、この文の内側だけです。",
            fontSize = 11.sp,
            color = OnSurfaceSubtle
        )
        Spacer(modifier = Modifier.height(16.dp))

        // **存在する段だけを出す。** 押せない選択肢は理由の説明を毎回要求する。
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item.availablePresets.forEach { preset ->
                val isCurrent = preset == item.currentPreset
                if (isCurrent) {
                    Button(
                        onClick = { onSelectPreset(preset) },
                        modifier = Modifier.heightIn(min = 48.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ButtonAi,
                            contentColor = OnButtonAi
                        )
                    ) { Text("✓ ${preset.label()}", color = OnButtonAi) }
                } else {
                    OutlinedButton(
                        onClick = { onSelectPreset(preset) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text(preset.label()) }
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = onReset,
                enabled = item.isRangeAdjusted,
                modifier = Modifier.heightIn(min = 48.dp)
            ) { Text("最初の範囲に戻す") }
        }

        Text(
            "変更後の太字率 %.1f%%".format(projectedBoldRatio * 100.0),
            fontSize = 12.sp,
            color = if (isWithinBoldLimit) OnSurfaceSubtle else ErrorText
        )

        // **告知は状態として残す。** 一時的な通知にすると、見ていない場所の変化を
        // 見ていない間に流すことになる。次に選択集合か確定範囲が変わるまで消えない。
        //
        // **主語は開いている候補で決まる。** 理由を残して再訪できるようにした結果、
        // 外された候補自身のシートも開けるようになった。そこで件数だけを言うと、
        // 目の前の候補を「ほかの1箇所」と呼んで関係を逆に読ませる。
        overlapNotice(isDeselectedByOverlap, otherDeselectedCount)?.let { notice ->
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = notice,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = ErrorText,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
    }
}

/**
 * 重なり解消の告知。**開いている候補との関係で文言が変わる。**
 *
 * 自分が外された側なら「ほか」と呼ばない。両方に当てはまるときは、
 * シートが説明すべき相手＝開いている候補自身を優先する。
 */
internal fun overlapNotice(isDeselectedByOverlap: Boolean, otherDeselectedCount: Int): String? = when {
    isDeselectedByOverlap -> "! この箇所は範囲が重なるため、選択が外れています。"
    otherDeselectedCount > 0 -> "! 重なるため、ほかの${otherDeselectedCount}箇所の選択を外しました。"
    else -> null
}

/**
 * 親文のうち、確定範囲だけを太字＋下線で示す。
 *
 * **色だけの手がかりにしない**（→ `docs/dev/system/ui_design_principles.md` §1）。
 * 太字と下線の両方を掛けるのは、灰色にしても範囲が伝わるようにするためである。
 */
internal fun highlightedParent(item: DistillCandidateItem) = buildAnnotatedString {
    val parent = item.parentText
    val start = item.boldStartInParent.coerceIn(0, parent.length)
    val end = item.boldEndInParent.coerceIn(start, parent.length)
    append(parent.substring(0, start))
    withStyle(
        SpanStyle(
            fontWeight = FontWeight.Bold,
            textDecoration = TextDecoration.Underline
        )
    ) { append(parent.substring(start, end)) }
    append(parent.substring(end))
}
