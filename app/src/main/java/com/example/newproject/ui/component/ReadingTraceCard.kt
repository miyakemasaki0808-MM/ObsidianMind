package com.example.newproject.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.model.ReunionKind
import com.example.newproject.model.state.ReadingTraceCard
import com.example.newproject.ui.theme.OnSurfaceFaint
import com.example.newproject.ui.theme.AccentText
import com.example.newproject.ui.theme.OnSurface
import com.example.newproject.ui.theme.PanelBlue

// ---------------------------------------------------------------------------
// 「前回のあなた」再会カード。
//
// Rediscover でノートが引かれた時だけ本文の上に出る。中身はユーザー自身の読み方
// （どこまで読んだか）で、AIはそれを俯瞰要約するだけ。要約が間に合わない・失敗した
// 場合も生の痕跡が出たままになるので、カードが空振りすることはない。
// ---------------------------------------------------------------------------

@Composable
internal fun ReadingTraceCardPanel(
    card: ReadingTraceCard,
    modifier: Modifier = Modifier,
    nowMillis: Long = System.currentTimeMillis(),
    onDismiss: () -> Unit,
    onOpenReflection: () -> Unit = {},
    onToggleMark: () -> Unit = {}
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = PanelBlue,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "✦ 前回のあなた",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = AccentText
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = readingTraceHeadline(card, nowMillis),
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = OnSurface
            )
            // AI要約は生の痕跡を置き換えず、その下に足す。要約が届く前・失敗した後でも
            // 上の1文だけで意味が通る状態を保つ。
            card.aiSummary?.takeIf { it.isNotBlank() }?.let { summary ->
                Spacer(modifier = Modifier.height(8.dp))
                // **前置きは種別から決める。** 同じ枠に別種のものが出るので、
                // 文言だけで見分けさせない（→ features/reunion_card.md 判断2）。
                reunionLead(card)?.let { lead ->
                    Text(
                        text = lead,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        color = OnSurfaceFaint
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                }
                Text(
                    text = summary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = OnSurface
                )
            }
            if (card.isSummaryLoading) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "読み方をまとめています…",
                    fontSize = 12.sp,
                    color = OnSurfaceFaint
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 返事の中身はここへ出さない。**在ることだけ**を示して画面へ渡す
                // （3つ並べるとカードが重くなり、1文で伝える役目が壊れる）。
                if (card.hasReflectionReply) {
                    TextButton(onClick = onOpenReflection) { Text("前回の返事を見る") }
                }
                // **枠に何も出ていなければ押せない。** 内容ごと控える印なので、
                // 控えるものが無いまま押せると「中身の無い印」ができる。
                if (!card.aiSummary.isNullOrBlank()) {
                    TextButton(onClick = onToggleMark) {
                        Text(
                            text = if (card.isMarked) "✓ まだ考えたい" else "まだ考えたい",
                            // **押した状態を色でも示す。** 同じ見た目のテキストボタンが
                            // 並ぶと区別できない、というのは実機で出た指摘（→ AppColors の3役ルール）。
                            color = if (card.isMarked) AccentText else OnSurface
                        )
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("読んだ") }
            }
        }
    }
}

/**
 * 枠の1件に添える前置き。**種別から決める。**
 *
 * 俯瞰要約には前置きを付けない（現行の見え方をそのまま保つ）。
 * 印が付いていれば種別によらず印の前置きになる — 出しているのは
 * **押した時点で保存した内容**であって、いま作ったものではないため。
 *
 * 純関数なのでJVMユニットテストで文面を固定できる。
 */
internal fun reunionLead(card: ReadingTraceCard): String? = when {
    card.aiSummary.isNullOrBlank() -> null
    card.isMarked -> "前回「まだ考えたい」と印を付けています"
    card.aiSummaryKind == ReunionKind.Question -> "前回のあなたはこの問いで止まっていました"
    card.aiSummaryKind == ReunionKind.Staleness -> "今も有効か確認したい箇所があります"
    else -> null
}

/**
 * 生の痕跡を1文にする。要約が無くてもこれだけで「前回の自分」が伝わるようにする。
 * 純粋関数なのでJVMユニットテストで文面を検証できる。
 */
internal fun readingTraceHeadline(card: ReadingTraceCard, nowMillis: Long): String {
    val whenLabel = elapsedLabel(card.lastVisitAtMillis, nowMillis)
    val where = when {
        card.lastProgressPercent >= 100 -> "最後まで読んでいます"
        !card.lastSectionTitle.isNullOrBlank() ->
            "「${card.lastSectionTitle}」の節で止まっています（全体の${card.lastProgressPercent}%）"
        else -> "全体の${card.lastProgressPercent}%のあたりで止まっています"
    }
    // 「5日前に読んで」には助詞が要るが「今日読んで」には要らない。
    // 相対表記だけが「前」で終わるので、そこで見分ける。
    val whenPhrase = if (whenLabel.endsWith("前")) "${whenLabel}に" else whenLabel
    // 2回以上開いていれば回数を添える（俯瞰の入口になる）。
    val times = if (card.visitCount >= 2) "これまで${card.visitCount}回開いています。" else ""
    return "$times${whenPhrase}読んで、$where。"
}

/** ざっくりした経過表示。「再会」の距離感が伝わればよいので粒度は粗くする。 */
internal fun elapsedLabel(fromMillis: Long, nowMillis: Long): String {
    val days = (nowMillis - fromMillis) / MILLIS_PER_DAY
    return when {
        days <= 0L -> "今日"
        days == 1L -> "昨日"
        days < 30L -> "${days}日前"
        days < 365L -> "${days / 30}ヶ月前"
        else -> "${days / 365}年前"
    }
}

private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
