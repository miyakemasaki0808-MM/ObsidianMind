package com.example.newproject.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.domain.ReunionSlot
import com.example.newproject.model.ReunionKind
import com.example.newproject.model.state.ReadingTraceCard
import com.example.newproject.ui.theme.OnSurfaceFaint
import com.example.newproject.ui.theme.AccentText
import com.example.newproject.ui.theme.OnSurface
import com.example.newproject.ui.theme.PanelBlue

// ---------------------------------------------------------------------------
// 「前回のあなた」再会カード。
//
// Rediscover でノートが引かれた時だけ本文の上に出る。見出しの1文はどこまで読んだかで、
// その下の枠に中身を1件だけ出す（→ features/reunion_card.md 判断6）。枠が間に合わない・
// 失敗した場合も見出しの1文が出たままになるので、カードが空振りすることはない。
// ---------------------------------------------------------------------------

/**
 * @param slot 枠に出すもの。**`reunionSlot` で求めたものを渡す**（枠の分岐を画面へ書かない）。
 * @param onResume 続きから読む。カードを畳み、本文を読み進めたところの少し手前へ送る。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReadingTraceCardPanel(
    card: ReadingTraceCard,
    slot: ReunionSlot,
    modifier: Modifier = Modifier,
    nowMillis: Long = System.currentTimeMillis(),
    onDismiss: () -> Unit,
    onOpenMemos: () -> Unit = {},
    onResume: () -> Unit = {}
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
            // 枠は見出しの1文を置き換えず、その下に足す。枠が届く前・失敗した後でも
            // 上の1文だけで意味が通る状態を保つ。
            when (slot) {
                is ReunionSlot.Shown -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    // **前置きは種別から決める。** 同じ枠に別種のものが出るので、
                    // 文言だけで見分けさせない（→ features/reunion_card.md 判断2）。
                    reunionLead(slot)?.let { lead ->
                        Text(
                            text = lead,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = OnSurfaceFaint
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                    }
                    Text(
                        text = slot.text,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = OnSurface
                    )
                }
                ReunionSlot.Waiting -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "前回読んだところを確かめています…",
                        fontSize = 12.sp,
                        color = OnSurfaceFaint
                    )
                }
                ReunionSlot.Hidden -> Unit
            }
            Spacer(modifier = Modifier.height(4.dp))
            // **入りきらなければ折り返す。** ボタンは最大3つ並び、1行では狭い画面からはみ出す。
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                // 送り先があるのは途中まで読んだノートだけ。AIを使わないので、枠が出なくても出す。
                if (card.resumeBlockIndex != null) {
                    TextButton(onClick = onResume) { Text("続きから読む") }
                }
                // メモの中身はここへ出さない。**在ることだけ**を示してシートへ渡す
                // （並べるとカードが重くなり、1文で伝える役目が壊れる）。
                if (card.hasMemos) {
                    TextButton(onClick = onOpenMemos) { Text("前回のメモを見る") }
                }
                TextButton(onClick = onDismiss) { Text("読んだ") }
            }
        }
    }
}

/**
 * 枠の1件に添える前置き。**種別から決める。**
 *
 * 前後の要約とノートの要約には前置きを付けない — 見出しの1文がどこまで読んだかを既に言っている。
 *
 * 当時の問いの前置きの「止まっていました」は、読む位置ではなく考えが止まった問いを指す
 * （→ features/reunion_card.md 判断6）。
 *
 * 純関数なのでJVMユニットテストで文面を固定できる。
 */
internal fun reunionLead(slot: ReunionSlot.Shown): String? = when (slot.kind) {
    ReunionKind.Question -> "前回のあなたはこの問いで止まっていました"
    ReunionKind.Staleness -> "今も有効か確認したい箇所があります"
    ReunionKind.Overview, ReunionKind.Passage -> null
}

/**
 * 生の痕跡を1文にする。枠が無くてもこれだけで「前回の自分」が伝わるようにする。
 * 純粋関数なのでJVMユニットテストで文面を検証できる。
 *
 * **「止まった」と言わない。** 記録しているのは前回いちばん先まで読んだところで、
 * 最後に見ていた場所ではない（巻き戻して離れても記録は下がらない → features/reunion_card.md 判断6）。
 */
internal fun readingTraceHeadline(card: ReadingTraceCard, nowMillis: Long): String {
    val whenLabel = elapsedLabel(card.lastVisitAtMillis, nowMillis)
    val where = when {
        card.lastProgressPercent >= 100 -> "最後まで読んでいます"
        !card.lastSectionTitle.isNullOrBlank() ->
            "「${card.lastSectionTitle}」の節まで読んでいます（全体の${card.lastProgressPercent}%）"
        else -> "全体の${card.lastProgressPercent}%のあたりまで読んでいます"
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
