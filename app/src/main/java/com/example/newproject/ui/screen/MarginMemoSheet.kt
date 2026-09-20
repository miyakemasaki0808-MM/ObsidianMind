package com.example.newproject.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.domain.SOFT_MEMO_CHARS
import com.example.newproject.domain.isMemoOverSoftLimit
import com.example.newproject.model.MarginMemo
import com.example.newproject.model.state.MarginMemoState
import com.example.newproject.model.state.MemoSaveStatus
import com.example.newproject.ui.theme.AccentText
import com.example.newproject.ui.theme.ButtonPrimary
import com.example.newproject.ui.theme.DangerAction
import com.example.newproject.ui.theme.OnButtonPrimary
import com.example.newproject.ui.theme.OnSurface
import com.example.newproject.ui.theme.OnSurfaceFaint
import com.example.newproject.ui.theme.OnSurfaceMuted
import com.example.newproject.ui.theme.PanelChip
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 余白メモのシート。**読んでいる流れを止めずに、短い断片を何度でも置く口。**
 *
 * **ルートを増やさない。** 結果が長くも遅くもないので、専用画面にすると
 * 「読む → 書く → 戻る」の往復が重くなる（廃止したひとことが専用ルートを
 * 持っていたのは、待ち時間と長い結果があったため）。
 *
 * **置いても閉じない。** 続けて書けることがこの機能の要点で、
 * 閉じると「何度でも置ける」が体験として消える。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MarginMemoSheet(
    state: MarginMemoState,
    onSave: (String) -> Unit,
    onDelete: (MarginMemo) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        scrimColor = BottomSheetDefaults.ScrimColor.copy(alpha = 0.5f)
    ) {
        MarginMemoSheetContent(state = state, onSave = onSave, onDelete = onDelete)
    }
}

/**
 * シートの中身。**`ModalBottomSheet` から切り出してあるのは、シートを開かずに
 * 入力の振る舞いを検査するため**（調整シートと同じ切り分け）。
 *
 * 連続して置けること自体がこの機能の要点なので、**下書きの扱いはUIテストで固定する。**
 */
@Composable
internal fun MarginMemoSheetContent(
    state: MarginMemoState,
    onSave: (String) -> Unit,
    onDelete: (MarginMemo) -> Unit
) {
    // **下書きは回転やプロセス復元をまたいで保つ。** 書きかけを警告なく消さない。
    var draft by rememberSaveable { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<MarginMemo?>(null) }

    val ready = state as? MarginMemoState.Ready

    // **入力欄を楽観的に空にしない。** 受け取れたと分かってから、
    // **その要求で出した文字列そのもの**のときだけ空にする。
    //
    // - 状態から「置けたら空にする」を導くと、`status` が残るので
    //   **再コンポーズのたびに2件目が消える**
    // - 押した瞬間に空にすると、置けなかったときに**原文を戻せない**
    //   （上限で切り詰めた後の文字列しか手元に無い）
    //
    // 受理の件数が増えたときだけ消すので、**置けなかった入力は何もしなくても残る。**
    var submitted by remember { mutableStateOf<String?>(null) }
    var seenAccepted by remember { mutableStateOf(ready?.acceptedCount ?: 0L) }
    LaunchedEffect(ready?.acceptedCount) {
        val accepted = ready?.acceptedCount ?: 0L
        // 開き直しで件数が戻ることがあるので、**増えたときだけ**消費する。
        if (accepted > seenAccepted) {
            // 待っているあいだに書き直していたら消さない。
            if (draft == submitted) draft = ""
            submitted = null
        }
        seenAccepted = accepted
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 360.dp)
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
    ) {
            Text("このノートのメモ", color = OnSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                text = "思いついたことを短く。ノート本体は変わりません。",
                color = OnSurfaceFaint,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("いま思ったこと", color = OnSurfaceFaint) },
                minLines = 2,
                maxLines = 6
            )

            // **合図であって壁ではない。** 超えても切らないし、置けなくもならない。
            if (isMemoOverSoftLimit(draft)) {
                Text(
                    text = "少し長めです（${SOFT_MEMO_CHARS}字をめやすに）。このまま置けます。",
                    color = OnSurfaceMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusText(state)
                Spacer(modifier = Modifier.height(0.dp))
                Button(
                    onClick = {
                        submitted = draft
                        onSave(draft)
                    },
                    // **受け付けられない状態では押させない。** 押せてしまうと、
                    // Controller が何もしないまま入力だけが宙に浮く。
                    enabled = ready != null &&
                        ready.status != MemoSaveStatus.Saving &&
                        draft.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ButtonPrimary,
                        contentColor = OnButtonPrimary
                    ),
                    shape = RoundedCornerShape(20.dp)
                ) { Text("置く", color = OnButtonPrimary) }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))

            when (state) {
                is MarginMemoState.Loading -> CircularProgressIndicator(
                    modifier = Modifier.height(20.dp),
                    color = AccentText,
                    strokeWidth = 2.dp
                )

                is MarginMemoState.Error -> Text(
                    text = "前のメモを読めませんでした。\n${state.message}",
                    color = OnSurfaceMuted,
                    fontSize = 12.sp
                )

                is MarginMemoState.Ready ->
                    if (state.memos.isEmpty()) {
                        Text(
                            text = "このノートにはまだメモがありません。",
                            color = OnSurfaceFaint,
                            fontSize = 12.sp
                        )
                    } else {
                        state.memos.forEach { memo ->
                            MemoRow(memo = memo, onDelete = { pendingDelete = memo })
                        }
                    }

                MarginMemoState.Idle -> Unit
            }
    }

    // **消すのは不可逆なので確認を挟む。** 置くのは何度でもやり直せるが、消したものは戻らない。
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("このメモを消しますか？") },
            text = { Text("消したメモは戻せません。") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(target)
                    pendingDelete = null
                }) { Text("消す", color = DangerAction) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("やめる") }
            }
        )
    }
}

/**
 * 保存の進み具合。**[MemoSaveStatus.Held] を「保存済み」と呼ばない** —
 * 預かった時点ではまだファイルに書かれていない。
 */
@Composable
private fun StatusText(state: MarginMemoState) {
    val ready = state as? MarginMemoState.Ready ?: return
    val text = when (ready.status) {
        MemoSaveStatus.None -> if (ready.wasTruncated) "長すぎたので、ここまでを置きました" else ""
        MemoSaveStatus.Saving -> "置いています…"
        MemoSaveStatus.Held -> "このノートを離れるときに保存します"
        MemoSaveStatus.Saved -> if (ready.wasTruncated) "長すぎたので、ここまでを置きました" else "置きました"
        // **入力は消えていない**ことまで言う。消えたと思わせない。
        MemoSaveStatus.Full -> "このノートのメモがいっぱいです。1件消すと置けます"
        MemoSaveStatus.Failed -> "置けませんでした。もう一度お試しください"
    }
    if (text.isEmpty()) return
    Text(
        text = text,
        color = if (ready.status == MemoSaveStatus.Failed) DangerAction else OnSurfaceMuted,
        fontSize = 11.sp,
        modifier = Modifier.padding(end = 12.dp)
    )
}

@Composable
private fun MemoRow(memo: MarginMemo, onDelete: () -> Unit) {
    Surface(
        color = PanelChip,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(memo.text, color = OnSurface, fontSize = 14.sp, lineHeight = 20.sp)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 書いた場所は**紐づけではなく当時の記録**。見出しが消えても解決し直さない。
                Text(
                    text = memoCaption(memo),
                    color = OnSurfaceFaint,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onDelete) { Text("消す", color = OnSurfaceMuted, fontSize = 12.sp) }
            }
        }
    }
}

private fun memoCaption(memo: MarginMemo): String {
    val stamp = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
        .format(Date(memo.writtenAtEpochMillis))
    return memo.sectionTitle?.let { "$stamp ・ $it のあたり" } ?: stamp
}
