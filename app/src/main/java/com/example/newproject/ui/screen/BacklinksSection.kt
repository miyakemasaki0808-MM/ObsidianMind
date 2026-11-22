package com.example.newproject.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.newproject.model.NoteLoadId
import com.example.newproject.model.state.BacklinkRow
import com.example.newproject.model.state.BacklinksState
import com.example.newproject.model.state.KnowledgeIndexProgress
import com.example.newproject.ui.theme.OnSurface
import com.example.newproject.ui.theme.OnSurfaceMetaBlue
import com.example.newproject.ui.theme.OnSurfaceMuted
import com.example.newproject.ui.theme.PanelDivider

/** 「このノートのバックナンバー」の節に何を出すか（→ docs/dev/features/backlinks.md §3・§5）。余白ペインと関連タブで共有する。 */
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
 * 番号は発行元の印まで比べる（プロセスの作り直しで数え直した番号と一致させない → [NoteLoadId]）。
 */
internal fun backlinksExpanded(openedAt: NoteLoadId?, noteLoadId: NoteLoadId): Boolean = openedAt == noteLoadId

/** ペインの節の開閉。 */
internal class BacklinksExpansion(val expanded: Boolean, val toggle: () -> Unit)

/** 開いたときの番号の保存値。**発行元の印も残す** — 落とすと、作り直したプロセスの同じ数の番号と一致する。 */
internal val BacklinksOpenedAtSaver: Saver<NoteLoadId?, LongArray> = Saver(
    save = { openedAt -> openedAt?.let { longArrayOf(it.issuer, it.count) } },
    restore = { saved -> NoteLoadId(issuer = saved[0], count = saved[1]) }
)

/** 開いたときのノートの表示の番号を、画面の保存値に置く（→ [backlinksExpanded]）。 */
@Composable
internal fun rememberBacklinksExpansion(noteLoadId: NoteLoadId): BacklinksExpansion {
    var openedAt by rememberSaveable(stateSaver = BacklinksOpenedAtSaver) { mutableStateOf<NoteLoadId?>(null) }
    val expanded = backlinksExpanded(openedAt, noteLoadId)
    return BacklinksExpansion(expanded) { openedAt = if (expanded) null else noteLoadId }
}

/** 進み具合の1行。読む予定の数がまだ分からない（最初の走査の前）なら数を出さない。 */
internal fun backlinksProgressText(building: BacklinksSection.Building): String =
    if (building.total == 0) "索引を作っています" else "索引を作っています（${building.indexed} / ${building.total}）"

internal const val BACKLINKS_HEADING = "このノートのバックナンバー"
internal const val BACKLINKS_UNAVAILABLE_TEXT = "このノートのバックナンバーを数えられませんでした"

/** ✎ の読み上げ。 */
internal const val BACKLINK_MEMO_DESCRIPTION = "余白メモあり"

/** 畳んだときに出す行の数（→ backlinks.md 判断3）。 */
internal const val BACKLINKS_SHOWN = 3

/**
 * 畳んだときに出す行と、畳んだ残りの数。[showAll] なら全部を出して残りは0。
 * 並びは引く側が決めた順（久しく読んでいない順）のまま。
 */
internal fun backlinkRowsShown(rows: List<BacklinkRow>, showAll: Boolean): Pair<List<BacklinkRow>, Int> =
    if (showAll || rows.size <= BACKLINKS_SHOWN) rows to 0 else rows.take(BACKLINKS_SHOWN) to rows.size - BACKLINKS_SHOWN

internal fun backlinkMoreText(rest: Int): String = "ほか $rest 件"

/** リンクを1行に入れるために手前に残す文字数と、文頭から出してもリンクが1行に入る目安の文字数。 */
private const val LEAD_BEFORE_LINK = 8
private const val LINE_FIT_CHARACTERS = 20

/**
 * 1文を1行に出す文字列（→ backlinks.md §5 の1文）。**リンクが目安の幅より後ろにあるときだけ**、リンクの手前を少し残して前を「…」で切る。
 * 幅は測らず文字数の目安で決める。はみ出す後ろは画面が「…」で切る。
 */
internal fun backlinkLine(row: BacklinkRow): String {
    val link = row.sentenceLink ?: return row.sentence
    if (link.last >= row.sentence.length || link.last < LINE_FIT_CHARACTERS || link.first <= LEAD_BEFORE_LINK) return row.sentence
    return "…" + row.sentence.substring(link.first - LEAD_BEFORE_LINK).trimStart()
}

/**
 * 1ノート1行。題名と、リンクを含む最初の1文を1行ずつ。そこに余白メモを書いていれば題名の後ろに ✎。
 * **読み上げでは1つの行として読む**（押せる行は子の文を1つにまとめて読む）。✎ は「余白メモあり」と読む。
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
        Row {
            Text(
                text = row.note.title,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                color = OnSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (row.hasMemo) {
                Text(
                    text = " ✎",
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = OnSurfaceMetaBlue,
                    modifier = Modifier.semantics { contentDescription = BACKLINK_MEMO_DESCRIPTION }
                )
            }
        }
        Text(
            text = backlinkLine(row),
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = OnSurfaceMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/**
 * 行を3件だけ並べ、残りは「ほか N 件」に畳む。押すと残りを同じ並びで出す。
 * [more] は「ほか N 件」の開閉で、**持ち主は呼び出し側**（[rememberBacklinksExpansion] でノートを替えると畳む）。
 * ここで持つと、ペインで行を右で開いて戻ったときに一覧が作り直されて畳まれ、押した行が見えなくなる。
 */
@Composable
internal fun BacklinkRowList(rows: List<BacklinkRow>, more: BacklinksExpansion, divided: Boolean, onOpen: (BacklinkRow) -> Unit) {
    val (shown, rest) = backlinkRowsShown(rows, showAll = more.expanded)
    shown.forEachIndexed { index, row ->
        BacklinkItem(row = row, onClick = { onOpen(row) })
        if (divided && (index < shown.lastIndex || rest > 0)) HorizontalDivider(color = PanelDivider, thickness = 0.5.dp)
    }
    if (rest > 0) {
        Text(
            text = backlinkMoreText(rest),
            fontSize = 12.sp,
            color = OnSurfaceMetaBlue,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 44.dp)
                .clickable(onClickLabel = "残りを出す", onClick = more.toggle)
                .padding(vertical = 12.dp)
        )
    }
}
