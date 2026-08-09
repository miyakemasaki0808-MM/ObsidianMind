package com.example.newproject.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.domain.RemarkLimits
import com.example.newproject.model.state.RemarkState
import com.example.newproject.model.state.ReplyStatus
import com.example.newproject.ui.component.GradientHeader
import com.example.newproject.ui.theme.AppGradient
import com.example.newproject.ui.theme.ButtonAi
import com.example.newproject.ui.theme.ButtonOutlineOnGradient
import com.example.newproject.ui.theme.ButtonPrimary
import com.example.newproject.ui.theme.ButtonSecondary
import com.example.newproject.ui.theme.ErrorText
import com.example.newproject.ui.theme.OnButtonAi
import com.example.newproject.ui.theme.OnButtonPrimary
import com.example.newproject.ui.theme.OnButtonSecondary
import com.example.newproject.ui.theme.OnSurface
import com.example.newproject.ui.theme.OnSurfaceMuted
import com.example.newproject.ui.theme.OnSurfaceSubtle
import com.example.newproject.ui.theme.Panel
import com.example.newproject.ui.theme.PanelBlue

/**
 * ノートへのひとことと、それへの返事の画面。**非タブルート。**
 *
 * ## なぜ画面を分けるか（2026-08-09・実機確認1巡目）
 *
 * 当初はAIタブのパネルへ出していたが、要約 → 蒸留（多状態の大きいパネル）→ ひとこと
 * という長い同一スクロールの最下段になり、**いちばん短い結果がいちばん埋もれた。**
 * 完了通知の「見る」を押しても同じタブへ戻るだけで、実質何も起きていなかった。
 *
 * ## なぜ返事欄があるか（2026-08-09・実機確認2巡目）
 *
 * 1文だけ返して終わると**AIが問いを投げて会話を一方的に打ち切る**形になり、
 * 読後感が宙に浮く（オーナーの実機所感「モヤっとした」）。
 * `feature_ideas` N-6 が「ユーザーの言葉を受け取る口が一つも無い」と書いていた穴が、
 * 体験として表に出たもの。返事を書けるようにして初めて
 * **読む → 問いが返る → 自分の言葉を残す → 次の再会で読み返す**が一周する。
 *
 * **返事を書いてもAIへ再送しない。** 往復させると「AIと会話するアプリ」になり、
 * 「AIは相手役／本質はノートを読む」という北極星から外れる。
 *
 * 主ボタンは「返事を残す」。**「もう一度きく」は脇へ下げる** —
 * 主役はAIの問いではなく、それを受けたユーザーの言葉のほうだから。
 */
@Composable
fun RemarkScreen(
    state: RemarkState,
    onRegenerate: () -> Unit,
    onSaveReply: (String) -> Unit,
    onBack: () -> Unit
) {
    val ready = state as? RemarkState.Ready
    // **下書きは rememberSaveable。** ユーザー自身が書いた言葉なので、
    // 画面回転やActivity再生成で消してはいけない（保存前でも同じ）。
    // キーにひとことを含めるのは、別の問いへ差し替わったら書き直すため。
    var draft by rememberSaveable(ready?.reflection?.remark) {
        mutableStateOf(ready?.reflection?.reply.orEmpty())
    }
    var confirmingRegenerate by rememberSaveable { mutableStateOf(false) }
    // **保存済みかどうかは savedAt だけで決めない。** 書き直している途中でも
    // 過去の日時があるだけで「保存済み」と出ていた。いま画面にある文字列と
    // 保存されている文字列が同じかを見る。
    val isDirty = ready != null && draft.trim() != ready.reflection.reply.orEmpty()

    // **スクロールする本文と、常に見えるボタン列を分ける。**
    // 1本のスクロールにすると、返事が長いほど保存ボタンが下へ押し出され、
    // 未保存のまま戻れてしまう（2026-08-09 実機4巡目）。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppGradient)
            .safeDrawingPadding()
            .padding(horizontal = 20.dp)
    ) {
      Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        // **見出しは GradientHeader へ渡す。** グラデーションの停止色は相対輝度が
        // 0.121〜0.458 に散るので、背景を持たない場所に onVibrant を直接置くと
        // どこかで必ず基準を割る（→ VibrantTextUsageTest）。
        GradientHeader(
            title = "ノートへのひとこと",
            subtitle = sourceTitleOf(state)?.let { "「$it」について" },
            titleSize = 22.sp
        )
        Spacer(modifier = Modifier.height(20.dp))

        when (state) {
            is RemarkState.Loading -> LoadingBody()
            is RemarkState.Ready -> RemarkBody(state.reflection.remark)
            // 空振りは失敗ではないので、AIに言わせず固定文で受ける。
            is RemarkState.Empty -> RemarkBody("今は新しい問いは見つかりませんでした。")
            // 書式失敗は空振りと分ける。もう一度きけば変わりうることを伝える。
            is RemarkState.Unusable ->
                RemarkBody("うまく言葉にできなかったようです。もう一度きいてみてください。")
            is RemarkState.Error -> RemarkBody("ひとことをもらえませんでした。\n${state.message}")
            // 保存済みが無ければここが起点になる（AIタブは常にこの画面へ渡す）。
            is RemarkState.Idle ->
                RemarkBody("このノートへのひとことは、まだありません。")
        }

        if (ready != null) {
            Spacer(modifier = Modifier.height(20.dp))
            ReplyField(
                draft = draft,
                // **上限超過を黙って捨てない。** 以前は入力変更ごと無視していたため、
                // 長文を貼ると無反応になり「書いたのに保存されなかった」ように見えた。
                // 受け取ってから切り、切ったことを下に出す。
                onDraftChange = { draft = it.take(RemarkLimits.MAX_REPLY_CHARS) },
                status = ready.replyStatus,
                isDirty = isDirty
            )
            // 映し返しは返事の**下**に置く。読む順序（問い→返事→応答）に合わせる。
            ready.reflection.mirrored?.let { mirrored ->
                Spacer(modifier = Modifier.height(12.dp))
                MirroredBody(mirrored)
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
      }

      if (ready != null) {
        Button(
            onClick = { onSaveReply(draft) },
            enabled = ready.replyStatus != ReplyStatus.Saving && draft.isNotBlank() && isDirty,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ButtonPrimary,
                contentColor = OnButtonPrimary
            ),
            border = BorderStroke(1.dp, ButtonOutlineOnGradient),
            shape = RoundedCornerShape(24.dp)
        ) {
            Text(
                when {
                    ready.replyStatus == ReplyStatus.Failed -> "もう一度保存する"
                    ready.reflection.hasReply -> "返事を書き直す"
                    else -> "返事を残す"
                },
                color = OnButtonPrimary
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
      }
        Button(
            // 返事を書いた後に作り直すと、その返事が宙に浮く（新しい問いへの返事ではない）。
            // 消える前に一度尋ねる。
            onClick = {
                if (ready?.reflection?.hasReply == true) confirmingRegenerate = true else onRegenerate()
            },
            enabled = state !is RemarkState.Loading,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ButtonAi, contentColor = OnButtonAi),
            border = BorderStroke(1.dp, ButtonOutlineOnGradient),
            shape = RoundedCornerShape(24.dp)
        ) {
            Text(
                if (state is RemarkState.Idle) "ひとことをもらう" else "別のひとことをもらう",
                color = OnButtonAi
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ButtonSecondary,
                contentColor = OnButtonSecondary
            ),
            border = BorderStroke(1.dp, ButtonOutlineOnGradient),
            shape = RoundedCornerShape(24.dp)
        ) { Text("← 戻る", color = OnButtonSecondary) }
        Spacer(modifier = Modifier.height(16.dp))
    }

    if (confirmingRegenerate) {
        AlertDialog(
            onDismissRequest = { confirmingRegenerate = false },
            title = { Text("返事が消えます") },
            text = {
                Text("新しいひとことをもらうと、いま残している返事も一緒に置き換わります。")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingRegenerate = false
                    onRegenerate()
                }) { Text("もらう") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingRegenerate = false }) { Text("やめる") }
            }
        )
    }
}

/**
 * 返事の入力欄。
 *
 * **汎用エディタにしない。** 上限を [MAX_REPLY_CHARS] に抑え、行数も伸ばしすぎない。
 * 長文を書ける口にすると Obsidian の劣化版になるので、
 * 「読んでいる流れを止めずに一言だけ置く」用途へ絞る（→ feature_ideas N-6）。
 */
@Composable
private fun ReplyField(
    draft: String,
    onDraftChange: (String) -> Unit,
    status: ReplyStatus,
    isDirty: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Panel,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("あなたの返事", color = OnSurfaceMuted, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = draft,
                onValueChange = onDraftChange,
                modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                placeholder = {
                    Text("いま浮かんだことを短く…", color = OnSurfaceSubtle, fontSize = 14.sp)
                },
                textStyle = androidx.compose.ui.text.TextStyle(color = OnSurface, fontSize = 15.sp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            val label = when {
                // 編集中は保存状態より優先する（保存済みの表示が嘘になるのを防ぐ）。
                isDirty && status != ReplyStatus.Saving -> "未保存"
                status == ReplyStatus.Saving -> "保存中…"
                // **預かっただけを「保存済み」と呼ばない。** 離脱時の書き込みで確定する。
                status == ReplyStatus.Held -> "保存中（このノートを離れるときに確定します）"
                status == ReplyStatus.Saved -> "保存済み"
                status == ReplyStatus.Failed -> "保存できませんでした"
                else -> null
            }
            val isProblem = status == ReplyStatus.Failed
            Text(
                text = listOfNotNull(label, "${draft.length} / ${RemarkLimits.MAX_REPLY_CHARS}")
                    .joinToString(" ・ "),
                color = if (isProblem) ErrorText else OnSurfaceSubtle,
                fontSize = 11.sp
            )
            // 上限で止めるのではなく、長くなったことを静かに知らせるだけ。
            // 壁にすると、長く書きたかった回にユーザーの言葉が消える。
            if (draft.length > RemarkLimits.SOFT_REPLY_CHARS) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "長めの返事です。ここは書き留める場所なので、" +
                        "まとまった文章はノート本体のほうが探しやすくなります。",
                    color = OnSurfaceSubtle,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

/**
 * 返事を受けてAIが返した1文。**問いではなく、受け取ったことを示して閉じる文。**
 *
 * ひとことと同じ面を使いつつ見出しで区別する。別の色を当てないのは、
 * どちらもAIの言葉であり、区別すべきは**役割**（問う／応じる）だから。
 */
@Composable
private fun MirroredBody(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = PanelBlue,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("受け取りました", color = OnSurfaceMuted, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(10.dp))
            Text(text = text, color = OnSurface, fontSize = 15.sp, lineHeight = 26.sp)
        }
    }
}

/**
 * 生成中も面（[Panel]）の上へ置く。グラデーション直上に文字を出すと
 * 停止色によって読めなくなるため、状態によらず同じ面に載せる。
 */
@Composable
private fun LoadingBody() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Panel,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(color = OnSurface)
            Spacer(modifier = Modifier.height(12.dp))
            Text("考えています…", color = OnSurfaceMuted, fontSize = 14.sp)
        }
    }
}

/**
 * 面を不透明にするのは、グラデーション上で半透明にすると
 * 実効的な背景が停止色ごとに変わり、文字のコントラストが位置で動くため
 * （AIタブの空状態パネルと同じ理由）。
 */
@Composable
private fun RemarkBody(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Panel,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("AIからのひとこと", color = OnSurfaceMuted, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(10.dp))
            Text(text = text, color = OnSurface, fontSize = 17.sp, lineHeight = 30.sp)
        }
    }
}

private fun sourceTitleOf(state: RemarkState): String? = when (state) {
    is RemarkState.Loading -> state.sourceTitle
    is RemarkState.Ready -> state.sourceTitle
    is RemarkState.Empty -> state.sourceTitle
    is RemarkState.Unusable -> state.sourceTitle
    is RemarkState.Error -> state.sourceTitle
    is RemarkState.Idle -> null
}
