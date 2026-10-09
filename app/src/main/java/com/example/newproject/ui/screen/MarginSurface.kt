package com.example.newproject.ui.screen

import androidx.compose.runtime.saveable.Saver
import com.example.newproject.model.DocumentRef
import com.example.newproject.model.RelatedNote
import com.example.newproject.model.SectionRef
import com.example.newproject.model.state.RelatedNotesState
import com.example.newproject.model.state.SectionChatProblem
import com.example.newproject.model.state.SectionSummary
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

    /** シートに出す。出ていなければ出す。 */
    Sheet,

    /** 面を出さず、要約を始めるだけ。ボタンの記号が生成中へ変わる。 */
    Background
}

/**
 * **出ている面があればそこに出す**（ペインとシートを同時に出さない）。
 * 面が出ていなければ、**まだ頼んでいない節は始めるだけで面を出さない** — 頼むたびに面が読書へ割り込む。
 * [requested] はこの節の要約を持っているか（生成中・完成・失敗・出せない理由のどれか）。持っていれば記号がその状態を示しているので、
 * 押すのは見に行くときであり、シートで見せる。**ペインの設定は書き換えない** — 一度見るために開くと、次のノートでも頼んでいないペインが出る。
 */
internal fun summaryEntryFor(paneVisible: Boolean, sheetVisible: Boolean, requested: Boolean): SummaryEntry = when {
    paneVisible -> SummaryEntry.Pane
    sheetVisible -> SummaryEntry.Sheet
    requested -> SummaryEntry.Sheet
    else -> SummaryEntry.Background
}

/** 面を出さずに始めた要約を、その後どうするか（→ [backgroundSummaryStep]）。 */
internal enum class BackgroundSummaryStep {
    /** 結果がまだ届いていない。 */
    Wait,

    /** 端末AIの状態で出せなかった。面を出して理由を見せる。 */
    ShowNotice,

    /** 追うのをやめる。結果はボタンの記号が示す。 */
    Done
}

/**
 * **端末AIが使えない理由が届いたときだけ、面を出す。** 理由は失敗として数えないので記号が 💬 のまま変わらず
 * （→ `sectionSummaryStatus`）、面を出さないと押しても何も起きないように見える。生成中・完成・生成の失敗は記号が示す。
 *
 * [summary] が無いのは、頼んだ結果がまだ画面の状態へ届いていないときなので待つ。要約が取り除かれる経路
 * （別の節を頼む・ノートや本文が替わる）では、画面のほうが追うのをやめる。
 * [faceVisible] は面が出ているか。出ていれば理由はそこに見えている。
 */
internal fun backgroundSummaryStep(summary: SectionSummary?, faceVisible: Boolean): BackgroundSummaryStep = when {
    faceVisible -> BackgroundSummaryStep.Done
    summary == null -> BackgroundSummaryStep.Wait
    summary.summaryProblem is SectionChatProblem.AiStatus -> BackgroundSummaryStep.ShowNotice
    summary.isSummaryLoading -> BackgroundSummaryStep.Wait
    else -> BackgroundSummaryStep.Done
}

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

/**
 * 送り終えた依頼 [handled] を消した後に残す依頼。**止められて終わったときも送り終えたことにする**（利用者のスクロールは
 * 送る動きを取り消す）。消さないと、次に同じ入口を押しても依頼が変わらず、送り直せない。
 * **古い依頼の終わりで、後から来た依頼を消さない。**
 */
internal fun <T> pendingAfterReveal(pending: T?, handled: T): T? = if (pending == handled) null else pending

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

/**
 * ペインの「このノートの関連」に並べる候補（→ features/margin_pane.md §5.9）。**並びを入れ替えず、新しい候補を下へ足す。**
 * AI の推薦は、モデルの準備が済んで関連ノートを読み直したときに後から届く。そのとき開いている一覧の並びを動かさない。
 *
 * [shown] はこのノートで出した並びで、まだ無ければ null。[state] がまだ届いていなければ [shown] のまま返す
 * （読み直しの間の読み込み中で一覧を消さない）。AI を新しく呼ばず、関連ノートの結果だけを使う。
 */
internal fun paneRelatedCandidates(shown: List<RelatedNote>?, state: RelatedNotesState): List<RelatedNote>? {
    val success = state as? RelatedNotesState.Success ?: return shown
    val base = shown.orEmpty()
    val known = base.mapTo(HashSet()) { it.ref }
    return base + (success.relatedNotes + success.aiNotes).distinctBy { it.ref }.filterNot { it.ref in known }
}

/**
 * ペインの「このノートの関連」の並びと開閉。**持ち主のノート [owner] を値として持つ。**
 * 画面の保存値は、別のノートを開いている状態で復元されることがある（冊子から別のノートを読んで戻ったとき）。
 * 保存の鍵で照合したつもりにならず、値そのものに持ち主を持たせて照合する。
 *
 * 右で読んでいる間に画面が組み直されても（全画面やほかのタブとの往復・画面の保存と復元）、右の本文は残るので、
 * 戻ったときに選んだ一覧と、届いた順の並びが要る。だから画面の保存値に置く（→ [PaneRelatedListSaver]）。
 */
internal data class PaneRelatedList(
    val owner: String,
    /** 並べる候補（→ [paneRelatedCandidates]）。まだ届いていなければ null。 */
    val candidates: List<RelatedNote>?,
    val expanded: Boolean
)

/**
 * 今のノート [note] の一覧を、保存していた [saved] と関連ノートの状態 [state] から作る。
 * **持ち主の違う保存値は使わない** — 別のノートへ候補と開閉を持ち越さない。ノートが決まらない間（読み込み中）は保存値のまま返す。
 */
internal fun paneRelatedListFor(saved: PaneRelatedList?, note: String?, state: RelatedNotesState): PaneRelatedList? {
    if (note == null) return saved
    val held = saved?.takeIf { it.owner == note }
    return PaneRelatedList(note, paneRelatedCandidates(held?.candidates, state), held?.expanded ?: false)
}

/**
 * [PaneRelatedList] を画面の保存値へ入れる形。候補は表示と開くのに要る欄だけを持ち、本文冒頭のスニペットは落とす。
 * 並びは `持ち主, 開閉, 候補があるか` に、候補ごとの `題名, 参照, リンク済みか, 更新日時` を続ける。
 */
internal val PaneRelatedListSaver: Saver<PaneRelatedList?, Any> = Saver(
    save = { list ->
        list?.let {
            ArrayList<Any?>().apply {
                add(it.owner)
                add(it.expanded)
                add(it.candidates != null)
                it.candidates?.forEach { note ->
                    add(note.title)
                    add(note.ref.value)
                    add(note.isWikilinked)
                    add(note.lastModified)
                }
            }
        }
    },
    restore = { saved ->
        val values = saved as List<*>
        PaneRelatedList(
            owner = values[0] as String,
            expanded = values[1] as Boolean,
            candidates = if (values[2] as Boolean) {
                values.drop(3).chunked(4).map { (title, ref, linked, modified) ->
                    RelatedNote(
                        title = title as String,
                        ref = DocumentRef(ref as String),
                        isWikilinked = linked as Boolean,
                        lastModified = modified as Long?
                    )
                }
            } else {
                null
            }
        )
    }
)

/**
 * 並べ読みを終えるか。**余白ペインでない並べ方になったら終える**（Fold を閉じた・回して横の折り目になった・
 * ✎ でペインをしまった → features/margin_pane.md §5.9）。
 * **窓の情報が揃う前は判定しない** — 画面の作り直しの直後は、仮に余白ペインでない並べ方で組まれることがある。
 */
internal fun endsSideReading(windowKnown: Boolean, paneVisible: Boolean, reading: Boolean): Boolean =
    reading && windowKnown && !paneVisible
