package com.example.newproject.ui

import com.example.newproject.model.Crystal
import java.time.Instant
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

/** 一覧がまだ空のときの説明。**何も要求しないことを伝える**（書く・押す操作は無い）。 */
internal const val CRYSTAL_EMPTY_MESSAGE =
    "まだ結晶はありません。ノートを読んで要約がたまると、読んできたものに共通する筋が、1日に多くて1つできます。"

private val DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy/M/d")
