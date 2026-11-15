package com.example.newproject.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.model.state.BacklinkRow
import com.example.newproject.model.state.BacklinksState
import com.example.newproject.model.state.KnowledgeIndexProgress
import com.example.newproject.ui.theme.OnSurface
import com.example.newproject.ui.theme.OnSurfaceMetaBlue
import com.example.newproject.ui.theme.OnSurfaceMuted

/** 「このノートへのリンク」の節に何を出すか（→ docs/dev/features/backlinks.md §3・§5）。余白ペインと関連タブで共有する。 */
internal sealed interface BacklinksSection {
    /** 出さない。ノートが無い・引いている・0件。 */
    data object Hidden : BacklinksSection

    /** 索引を最初に作っている。件数の代わりに進み具合を出す（作り終えるまでは漏れなく数えられない）。 */
    data class Building(val indexed: Int, val total: Int) : BacklinksSection

    /** 数えられなかった。 */
    data object Unavailable : BacklinksSection

    data class Rows(val rows: List<BacklinkRow>) : BacklinksSection
}

/**
 * 節の出し方を決める。**数えられないことを最も先に出す** — 走査が読めずに空だったり置き場が壊れていたりすると、
 * 索引の作成も進まないので、進み具合を出し続けると「待てば出る」と誤って伝える。作る側が止まったとき（[KnowledgeIndexProgress.Failed]）も、
 * 照会が成功していても作り終えていない索引の行なので、数えられないと出す。
 * 次に、最初の作成の途中なら進み具合。作り終えていて1件以上あるときだけ行を出す。
 */
internal fun backlinksSectionFor(state: BacklinksState, progress: KnowledgeIndexProgress): BacklinksSection = when {
    state == BacklinksState.Idle -> BacklinksSection.Hidden
    state == BacklinksState.Unavailable || progress == KnowledgeIndexProgress.Failed -> BacklinksSection.Unavailable
    progress is KnowledgeIndexProgress.Building -> BacklinksSection.Building(progress.indexed, progress.total)
    state is BacklinksState.Ready && state.rows.isNotEmpty() -> BacklinksSection.Rows(state.rows)
    else -> BacklinksSection.Hidden
}

/**
 * 節が開いているか。**開いたときのノートの表示の番号（`NoteUiState.noteLoadId`）が今と同じときだけ** —
 * ノートの参照で比べると A→B→A で同じ値に戻り、ノートの画面が組まれていない間（全画面）の切り替えを見逃す
 * （ノートを替えると畳んだ状態に戻る → features/backlinks.md §6）。右で開いて余白へ戻る・画面の作り直しでは番号が変わらない。
 */
internal fun backlinksExpanded(openedAt: Long?, noteLoadId: Long): Boolean = openedAt == noteLoadId

/** ペインの節の開閉。 */
internal class BacklinksExpansion(val expanded: Boolean, val toggle: () -> Unit)

/** 開いたときのノートの表示の番号を、画面の保存値に置く（→ [backlinksExpanded]）。 */
@Composable
internal fun rememberBacklinksExpansion(noteLoadId: Long): BacklinksExpansion {
    var openedAt by rememberSaveable { mutableStateOf<Long?>(null) }
    val expanded = backlinksExpanded(openedAt, noteLoadId)
    return BacklinksExpansion(expanded) { openedAt = if (expanded) null else noteLoadId }
}

/** 進み具合の1行。読む予定の数がまだ分からない（最初の走査の前）なら数を出さない。 */
internal fun backlinksProgressText(building: BacklinksSection.Building): String =
    if (building.total == 0) "索引を作っています" else "索引を作っています（${building.indexed} / ${building.total}）"

/** 同じノートにあるほかのリンクの数。無ければ出さない。 */
internal fun backlinkOthersText(row: BacklinkRow): String? =
    row.otherCount.takeIf { it > 0 }?.let { "ほか $it 箇所" }

internal const val BACKLINKS_HEADING = "このノートへのリンク"
internal const val BACKLINKS_UNAVAILABLE_TEXT = "このノートへのリンクを数えられませんでした"

/**
 * 1ノート1行。題名と、リンクを含む最初の1文と、ほかの箇所の数。**読み上げでは1つの行として読む**
 * （押せる行は子の文を1つにまとめて読む）。
 */
@Composable
internal fun BacklinkItem(row: BacklinkRow, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp)
            .clickable(onClickLabel = "開く", onClick = onClick)
            .padding(vertical = 6.dp)
    ) {
        Text(text = row.note.title, fontSize = 14.sp, lineHeight = 18.sp, color = OnSurface)
        Text(
            text = row.sentence,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = OnSurfaceMuted,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp)
        )
        backlinkOthersText(row)?.let {
            Text(text = it, fontSize = 11.sp, color = OnSurfaceMetaBlue, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
