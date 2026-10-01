package com.example.newproject.ui.screen

import com.example.newproject.model.SectionRef

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

/** 窓が切り替わったときに、出ている余白をどちらへ移すか（→ features/margin_pane.md §5.4 の2つ目の表）。 */
internal enum class MarginWindowShift {
    None,

    /** シートをしまう。ペインは設定どおりに出る。 */
    SheetToPane,

    /** 書きかけを持ったペインが出せなくなったので、同じ中身をシートで出す。 */
    PaneToSheet
}

/**
 * [previousCanShowPane] は前に見た窓の判定で、**まだ見ていなければ null**（何も移さない）。
 *
 * **移り変わりではなく、前に見た値と今の値の比較で決める。** 途中の窓は届くとは限らない
 * （→ lessons L58）。前の値は画面の作り直しをまたいで保つ — 折りたたみの開閉で Activity は作り直される。
 */
internal fun marginWindowShiftFor(
    previousCanShowPane: Boolean?,
    canShowPane: Boolean,
    paneOpen: Boolean,
    sheetVisible: Boolean,
    hasDraft: Boolean
): MarginWindowShift = when {
    previousCanShowPane == null || previousCanShowPane == canShowPane -> MarginWindowShift.None
    // 出せない → 出せる。閉じる設定ならシートのまま。
    canShowPane -> if (sheetVisible && paneOpen) MarginWindowShift.SheetToPane else MarginWindowShift.None
    // 出せる → 出せない。書きかけが無ければ何も出さない。
    else -> if (paneOpen && !sheetVisible && hasDraft) MarginWindowShift.PaneToSheet else MarginWindowShift.None
}

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
