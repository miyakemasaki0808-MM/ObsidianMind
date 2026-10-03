package com.example.newproject.ui.screen

import com.example.newproject.model.SectionRef
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * ✎ を押したときの動き（→ features/margin_pane.md §5.4）。
 *
 * **規則は1つ。出ている余白をしまう。出ていなければ、その窓で出せる面を出す。**
 * ペインとシートを同時に出さない。✎ は書く入口なので、どの動きも生成を始めない。
 */
internal enum class MarginToggle {
    /** シートをしまう。ペインの設定は変えない。 */
    HideSheet,

    /** ペインをしまい、設定を「閉じる」へ。 */
    ClosePane,

    /** ペインを出し、設定を「開く」へ。 */
    OpenPane,

    /** シートを出す。ペインの設定は変えない。 */
    ShowSheet
}

internal fun marginToggleFor(
    canShowPane: Boolean,
    paneOpen: Boolean,
    sheetVisible: Boolean
): MarginToggle = when {
    // ペインを出せる窓でもシートが出ていることがある（閉じる設定のままFoldを開いたとき）。
    // そのときはまずシートをしまい、ペインを出すのは次の ✎ に回す。
    sheetVisible -> MarginToggle.HideSheet
    canShowPane && paneOpen -> MarginToggle.ClosePane
    canShowPane -> MarginToggle.OpenPane
    else -> MarginToggle.ShowSheet
}

/** ✎ の読み上げ名。押したときに起きることを言う。 */
internal fun marginToggleDescription(toggle: MarginToggle): String = when (toggle) {
    MarginToggle.ClosePane -> "メモの欄をしまう"
    MarginToggle.OpenPane -> "メモの欄を出す"
    MarginToggle.HideSheet, MarginToggle.ShowSheet -> "このノートのメモ"
}

/** 見出しの要約ボタンを押したときに、要約をどの面に出すか（→ features/margin_pane.md §5.4）。 */
internal enum class SummaryEntry {
    /** 出ているペインに出す。 */
    Pane,

    /** ペインをこのノートの間だけ出す。**設定は変えない。** */
    PaneForNote,

    /** シートに出す。出ていなければ出す。 */
    Sheet
}

/**
 * **出ている面があればそこに出す**（ペインとシートを同時に出さない）。出ていなければ、その窓で出せる面を出す。
 * ペインを出せる窓で設定が閉じているときは、ペインをこのノートの間だけ出す — 要約は本文の横で読むほうがよく、
 * 一度の要約のために設定を書き換えると、次のノートでも頼んでいないペインが出る。
 */
internal fun summaryEntryFor(canShowPane: Boolean, paneVisible: Boolean, sheetVisible: Boolean): SummaryEntry = when {
    paneVisible -> SummaryEntry.Pane
    sheetVisible -> SummaryEntry.Sheet
    canShowPane -> SummaryEntry.PaneForNote
    else -> SummaryEntry.Sheet
}

/**
 * このノートの間だけ出したペインを、今出すか。**出したノート [owner] と今のノートが同じときだけ。**
 * 画面の保存値は、別のノートを開いている状態で復元されることがある（冊子から別のノートを読んで戻ったとき）。
 * 保存の鍵で照合したつもりにならず、値そのものに持ち主を持たせて照合する。
 */
internal fun paneForNoteShown(owner: String?, currentNote: String?): Boolean = owner != null && owner == currentNote

/** 窓が切り替わったときに、出ている余白をどちらへ移すか（→ features/margin_pane.md §5.4 の2つ目の表）。 */
internal enum class MarginWindowShift {
    None,

    /** シートをしまう。ペインは設定どおりに出る。 */
    SheetToPane,

    /** 書きかけを持つか書いている途中のペインが出せなくなったので、同じ中身をシートで出す。 */
    PaneToSheet
}

/**
 * [previousCanShowPane] は前に見た窓の判定で、**まだ見ていなければ null**（何も移さない）。
 *
 * **移り変わりではなく、前に見た値と今の値の比較で決める。** 途中の窓は届くとは限らない
 * （→ lessons L58）。前の値は画面の作り直しをまたいで保つ — 折りたたみの開閉で Activity は作り直される。
 *
 * [writing] は書きかけがあるか、入力欄で書いている途中か。
 */
internal fun marginWindowShiftFor(
    previousCanShowPane: Boolean?,
    canShowPane: Boolean,
    paneOpen: Boolean,
    sheetVisible: Boolean,
    writing: Boolean
): MarginWindowShift = when {
    previousCanShowPane == null || previousCanShowPane == canShowPane -> MarginWindowShift.None
    // 出せない → 出せる。閉じる設定ならシートのまま。
    canShowPane -> if (sheetVisible && paneOpen) MarginWindowShift.SheetToPane else MarginWindowShift.None
    // 出せる → 出せない。書きかけも無く書いてもいなければ、何も出さない。
    else -> if (paneOpen && !sheetVisible && writing) MarginWindowShift.PaneToSheet else MarginWindowShift.None
}

/**
 * ペインが出ているのに、シートが出ている扱いのままか。そうならシートの扱いを落とす（ペインとシートを同時に出さない → §5.4）。
 * 全画面のシートで書いたまま戻ると、この形でペインの窓へ来る。
 *
 * **窓の情報が揃う前は判定しない** — 画面の作り直しの直後は、折り目の無い窓として仮にペインが組まれることがある。
 * そのときに落とすと、本当はシートで続けるはずの書きかけからシートが消える。
 */
internal fun dropsHiddenSheet(windowKnown: Boolean, paneVisible: Boolean, sheetVisible: Boolean): Boolean =
    windowKnown && paneVisible && sheetVisible

/**
 * 節の呼び名（→ features/margin_pane.md §5.2）。見出しより前は「ノートの冒頭」、見出しの無いノートは「ノート全体」。
 * **同名の見出しの2つ目からは順番を添える** — 名前だけでは、書き込み先がどちらの節か見分けられない。
 */
internal fun sectionLabel(section: SectionRef, hasHeadings: Boolean): String = when {
    section.title == null -> if (hasHeadings) "ノートの冒頭" else "ノート全体"
    section.ordinal == 0 -> section.title
    else -> "${section.title}（${section.ordinal + 1}つ目）"
}

/**
 * 知らせる書き込み先。**書き込み先が今の本文の節と違うときだけ**返す（→ features/margin_pane.md §5.2 の5）。
 * 本文の節がまだ分からない（解析の前）ときは知らせない。
 */
internal fun writeTargetNotice(target: SectionRef?, bodySection: SectionRef?): SectionRef? =
    target?.takeIf { bodySection != null && it != bodySection }

/**
 * 面の中で、目的のメモまで送る依頼（→ features/margin_pane.md §5.4「入口から目的の中身へ直接届く」）。
 * 送ったら依頼を消す（同じ依頼で何度も送らない）。
 */
internal sealed interface MemoReveal {
    /**
     * 見出しの印から。**行き先の節を持つ。** 本文をその節まで送れるとは限らない（短い節は上端まで来ない）ので、
     * 面の節が本文についてくることを当てにしない。
     */
    data class Section(val section: SectionRef) : MemoReveal

    /** 再会カードの「前回のメモを見る」から。ほかの節のメモも開いて、メモの並びへ。 */
    data object AllMemos : MemoReveal
}

/** 面の中の送り先。 */
internal sealed interface MemoRevealStop {
    /** この節のメモの並びの始まり。 */
    data object CurrentMemos : MemoRevealStop

    /** ほかの節のメモを開き、[section] の組へ。null は並びの始まり。 */
    data class OtherGroup(val section: SectionRef?) : MemoRevealStop
}

/**
 * 依頼を面の中の送り先へ。行き先が面の節ならこの節のメモ、違えばほかの節のメモを開いてその組へ送る。
 * **別の節のメモを行き先にしない。**
 */
internal fun memoRevealStop(reveal: MemoReveal, current: SectionRef?): MemoRevealStop = when (reveal) {
    MemoReveal.AllMemos -> MemoRevealStop.OtherGroup(section = null)
    is MemoReveal.Section ->
        if (reveal.section == current) MemoRevealStop.CurrentMemos else MemoRevealStop.OtherGroup(reveal.section)
}

/** 見出しの脇の印の読み上げ名。**色だけにしない**ので件数を言う。 */
internal fun headingMemoMarkDescription(count: Int): String = "この節のメモ ${count}件"

/**
 * シートを書いている間の畳み方にするか（→ features/margin_pane.md §5.5）。
 * **シートで、入力欄に触れていて、キーボードが出ているときだけ。** キーボードを閉じれば元の並びに戻す —
 * 入力欄に触れたままでも、キーボードが無ければ畳む理由が無い。ペインは高さが足りるので畳まない。
 */
internal fun compactWhileTyping(asSheet: Boolean, inputFocused: Boolean, imeVisible: Boolean): Boolean =
    asSheet && inputFocused && imeVisible

/**
 * シートが本文を覆う高さ（px）。[sheetOffset] はシートの上端で、器の上端からの位置。まだ組まれていなければ null で、覆っていない。
 * **隠れている間はシートが器の下端にあるので0になる** — 出ているかどうかを別に見なくてよい。
 */
internal fun sheetCoveredHeight(layoutHeight: Int, sheetOffset: Float?): Int =
    sheetOffset?.let { (layoutHeight - it).roundToInt().coerceIn(0, layoutHeight) } ?: 0

/**
 * 本文の列の上の操作（見出し・ボタン・再会カード）を隠すか（→ features/margin_pane.md §5.5）。
 * **入力欄に触れてキーボードが出ている間だけ。** キーボードが出ると、残る高さを操作とカードが使い切って本文が消える。
 *
 * - 縦積み — シートで書いているとき。シートはさらに本文の下を覆う
 * - 余白ペイン — ペインで書いているとき。本文の列の上に操作とカードが残る形は縦積みと同じ
 * - 左右2列 — 隠さない。操作は横の列にあり、本文の高さを取らない
 */
internal fun hidesReaderControls(layout: ReaderLayout, sheetVisible: Boolean, writingWithKeyboard: Boolean): Boolean =
    writingWithKeyboard && when (layout) {
        ReaderLayout.Stacked -> sheetVisible
        is ReaderLayout.MarginPane -> true
        ReaderLayout.SideBySide -> false
    }

/** 前回の読書の跡の行（→ features/margin_pane.md §5.2 の3）。 */
internal sealed interface PreviousReadingRow {
    data object Hidden : PreviousReadingRow

    /** 行の高さだけを取る。書いている間に跡の無い節へ移っても、入力欄を上げない。 */
    data object Reserved : PreviousReadingRow

    data class Shown(val atEpochMillis: Long) : PreviousReadingRow
}

/**
 * [shownAt] はこの節に跡を出すなら前回の訪問の日時（→ `showsPreviousReading`）。
 * [heldAtFocus] は書き始めたときに行があったかで、書いていなければ null。
 *
 * - **シートで書いている間は畳むので出さない**（→ §5.5。残すのは見出し・書き込み先・入力欄・置くボタン・保存の状態だけ）
 * - **書いている間は、書き始めたときの行の有無を保つ**（→ §5.2）。行が増えても減っても入力欄が上下に動く。
 *   跡は遅れて届き、面の節は書いている間も本文についていくので、どちらでも行が出入りしうる
 */
internal fun previousReadingRowFor(shownAt: Long?, compact: Boolean, heldAtFocus: Boolean?): PreviousReadingRow = when {
    compact -> PreviousReadingRow.Hidden
    heldAtFocus == false -> PreviousReadingRow.Hidden
    shownAt != null -> PreviousReadingRow.Shown(shownAt)
    heldAtFocus == true -> PreviousReadingRow.Reserved
    else -> PreviousReadingRow.Hidden
}

/**
 * 「前回はここまで読んだ · 9/12」。今年でなければ年も添える。
 *
 * **「ここで止まった」と言わない。** 記録は前回いちばん深く読んだところで、読み戻って離れても下がらない（→ §5.7）。
 */
internal fun previousReadingLabel(atEpochMillis: Long, nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val at = Instant.ofEpochMilli(atEpochMillis).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
    val date = if (at.year == today.year) {
        "${at.monthValue}/${at.dayOfMonth}"
    } else {
        "${at.year}/${at.monthValue}/${at.dayOfMonth}"
    }
    return "前回はここまで読んだ · $date"
}
