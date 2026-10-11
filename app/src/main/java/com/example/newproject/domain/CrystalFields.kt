package com.example.newproject.domain

import com.example.newproject.model.Crystal
import com.example.newproject.model.NoteField

/** 宝石に塗る分野の数の上限。3つに割ると、小さな宝石では細くて見分けられない（→ reflect_crystal.md 判断11）。 */
internal const val CRYSTAL_GEM_MAX_FIELDS = 2

/**
 * 結晶の宝石を塗る分野（→ `docs/dev/features/reflect_crystal.md` 判断11）。
 *
 * 根拠のノートの分野（[fieldOf] が相対パスから引く）を数え、多い順に [CRYSTAL_GEM_MAX_FIELDS] 個まで返す。
 * **同数なら根拠の並びで先に出たほう** — 先頭は結晶を作ったときに読んでいたノートなので、宝石の左に来る。
 * 分野の分からない根拠は数えない。空なら、どの根拠の分野も分からない（宝石は無彩色）。
 */
internal fun crystalFields(crystal: Crystal, fieldOf: (vaultRelativePath: String) -> NoteField?): List<NoteField> {
    val counts = LinkedHashMap<NoteField, Int>()
    crystal.sources.forEach { source ->
        val field = fieldOf(source.vaultRelativePath) ?: return@forEach
        counts[field] = (counts[field] ?: 0) + 1
    }
    // 安定な並べ替えなので、同数は根拠に出た順のまま残る。
    return counts.entries.sortedByDescending { it.value }.take(CRYSTAL_GEM_MAX_FIELDS).map { it.key }
}
