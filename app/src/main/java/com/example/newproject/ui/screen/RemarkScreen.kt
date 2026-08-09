package com.example.newproject.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.model.state.RemarkState
import com.example.newproject.ui.component.GradientHeader
import com.example.newproject.ui.theme.AppGradient
import com.example.newproject.ui.theme.ButtonAi
import com.example.newproject.ui.theme.ButtonOutlineOnGradient
import com.example.newproject.ui.theme.ButtonSecondary
import com.example.newproject.ui.theme.OnButtonAi
import com.example.newproject.ui.theme.OnButtonSecondary
import com.example.newproject.ui.theme.OnSurface
import com.example.newproject.ui.theme.OnSurfaceMuted
import com.example.newproject.ui.theme.Panel

/**
 * ノートへのひとことを1文だけ見せる画面。**非タブルート。**
 *
 * ## なぜ画面を分けるか（2026-08-09・実機確認の指摘から）
 *
 * 当初はAIタブのパネルへ出していたが、要約 → 蒸留（多状態の大きいパネル）→ ひとこと
 * という長い同一スクロールの最下段になり、**いちばん短い結果がいちばん埋もれた。**
 * 完了通知の「見る」を押しても同じタブへ戻るだけで、実質何も起きていなかった。
 *
 * **旧「AI補記メモ」の専用画面を戻したのではない。** あれは Markdown ファイル全体を
 * 開く重い画面で、撤去は正しかった。ここが引き受けるのは
 * **届いた1文を落ち着いて読む瞬間**だけである。
 * 非タブルートなので下部ナビ/レールが自動的に消え、1文だけが画面に残る
 * （→ [note_fullscreen](../../../../../../../docs/dev/design/note_fullscreen.md) 判断1 と同じ構造）。
 *
 * 再生成のボタンをこの画面へ置くのは、**結果を読んでから「もう一度」を判断する**のが
 * 自然な順序だから。AIタブ側は入口1つだけに保つ。
 */
@Composable
fun RemarkScreen(
    state: RemarkState,
    onRegenerate: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppGradient)
            .safeDrawingPadding()
            .padding(horizontal = 20.dp)
    ) {
        // **見出しは GradientHeader へ渡す。** グラデーションの停止色は相対輝度が
        // 0.121〜0.458 に散るので、背景を持たない場所に onVibrant を直接置くと
        // どこかで必ず基準を割る（→ VibrantTextUsageTest）。
        GradientHeader(
            title = "ノートへのひとこと",
            subtitle = sourceTitleOf(state)?.let { "「$it」について" },
            titleSize = 22.sp
        )
        Spacer(modifier = Modifier.height(24.dp))

        // 1文なので、縦中央に置いて画面のほとんどを余白にする。
        // 埋もれさせないことがこの画面の唯一の役目なので、詰めない。
        Box(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center
        ) {
            when (state) {
                is RemarkState.Loading -> LoadingBody()
                is RemarkState.Ready -> RemarkBody(state.remark)
                // 空振りは失敗ではないので、AIに言わせず固定文で受ける。
                is RemarkState.Empty -> RemarkBody("今は新しい問いは見つかりませんでした。")
                // 書式失敗は空振りと分ける。もう一度きけば変わりうることを伝える。
                is RemarkState.Unusable ->
                    RemarkBody("うまく言葉にできなかったようです。もう一度きいてみてください。")
                is RemarkState.Error -> RemarkBody("ひとことをもらえませんでした。\n${state.message}")
                is RemarkState.Idle -> RemarkBody("まだひとことはありません。")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onRegenerate,
            enabled = state !is RemarkState.Loading,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ButtonAi, contentColor = OnButtonAi),
            border = BorderStroke(1.dp, ButtonOutlineOnGradient),
            shape = RoundedCornerShape(24.dp)
        ) { Text("もう一度きく", color = OnButtonAi) }

        Spacer(modifier = Modifier.height(8.dp))
        // 塗りを持たせるのは、グラデーション直上で文字色を選べないため。
        // 「もう一度きく」がAI生成系（Indigo）なので、こちらは補助（緑）にして
        // 同色のボタンが並ばないようにする（3役ルールの但し書き → AppColors.kt）。
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
