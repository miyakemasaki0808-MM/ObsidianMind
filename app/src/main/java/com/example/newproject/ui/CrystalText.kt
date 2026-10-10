package com.example.newproject.ui

import com.example.newproject.model.Crystal
import com.example.newproject.model.NoteField
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ---------------------------------------------------------------------------
// 結晶の画面の文言（→ docs/dev/features/reflect_crystal.md §3・§5「表示」）。
// 文言は候補で、実機で見て決め直してよい。UIに条件を書かないため純関数に切り出す。
// ---------------------------------------------------------------------------

/** ✨タブの見出し。 */
internal const val CRYSTAL_PANEL_TITLE = "💎 結晶"

/** ノート名から拡張子を落とした表示名。 */
internal fun crystalNoteLabel(noteTitle: String): String = noteTitle.removeSuffix(".md")

/** 根拠の行。「A・B から」。 */
internal fun crystalSourcesLine(crystal: Crystal): String =
    crystal.sources.joinToString("・") { crystalNoteLabel(it.noteTitle) } + " から"

/** 一覧への入口。 */
internal fun crystalListEntryLabel(count: Int): String = "結晶の一覧（${count}件）"

/** 一覧に出す作った日。 */
internal fun crystalDateLabel(createdAt: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    DATE_FORMAT.format(Instant.ofEpochMilli(createdAt).atZone(zone))

/** 棚の見出し。「2026年10月 · 7」。 */
internal fun crystalMonthLabel(month: YearMonth, count: Int): String = "${month.year}年${month.monthValue}月 · $count"

/** 宝石の下に出す日。月は棚の見出しにあるので日だけ。 */
internal fun crystalDayLabel(createdAt: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    "${Instant.ofEpochMilli(createdAt).atZone(zone).dayOfMonth}日"

/** 分野の分からない宝石の呼び名。凡例・中身・読み上げで同じ言葉を使う。 */
internal const val CRYSTAL_NO_FIELD_LABEL = "分野なし・未判定"

/** 宝石の分野の並び。「技術・開発 ＋ 思考・内省」。空なら [CRYSTAL_NO_FIELD_LABEL]。 */
internal fun crystalFieldsLabel(fields: List<NoteField>): String =
    if (fields.isEmpty()) CRYSTAL_NO_FIELD_LABEL else fields.joinToString(" ＋ ") { it.label }

/** 開いた中身の1行目。分野と作った日。**色以外の手がかり**（1.4.1）として分野名を必ず出す。 */
internal fun crystalDetailCaption(createdAt: Long, fields: List<NoteField>, zone: ZoneId = ZoneId.systemDefault()): String =
    crystalFieldsLabel(fields) + " · " + crystalDateLabel(createdAt, zone)

/** 宝石の読み上げ。「2026年10月7日の結晶、技術・開発と思考・内省」。 */
internal fun crystalGemDescription(createdAt: Long, fields: List<NoteField>, zone: ZoneId = ZoneId.systemDefault()): String {
    val date = Instant.ofEpochMilli(createdAt).atZone(zone)
    val fieldText = if (fields.isEmpty()) CRYSTAL_NO_FIELD_LABEL else fields.joinToString("と") { it.label }
    return "${date.year}年${date.monthValue}月${date.dayOfMonth}日の結晶、$fieldText"
}

/** 開いた根拠のノート名。分野が分かれば添える — 宝石に出ない3つ目の分野も、ここで分かる。 */
internal fun crystalSourceLabel(noteTitle: String, field: NoteField?): String =
    crystalNoteLabel(noteTitle) + (field?.let { "（${it.label}）" } ?: "")

/** 一覧がまだ空のときの説明。**何も要求しないことを伝える**（書く・押す操作は無い）。 */
internal const val CRYSTAL_EMPTY_MESSAGE =
    "まだ結晶はありません。ノートを読んで要約がたまると、読んできたものに共通する筋が、1日に多くて1つできます。"

private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy/M/d")
