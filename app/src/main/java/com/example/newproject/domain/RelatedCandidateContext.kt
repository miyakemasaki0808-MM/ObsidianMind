package com.example.newproject.domain

import com.example.newproject.ai.RelatedCandidateLine

// 関連ノートAI候補の「本文コンテキスト」整形（純ロジック・Uri非依存）。
// 候補をタイトルだけでなく本文冒頭スニペット・タグ・aliasesで肉付けしつつ、
// プロンプト全体が膨らみすぎないよう入力バジェット内へ動的に収める。

// ID付き候補コンテキスト。snippet はプロンプト用に1行へ畳んだ本文冒頭。
internal data class CandidateContext(
    val id: String,
    val title: String,
    val snippet: String,
    val tags: List<String> = emptyList(),
    val aliases: List<String> = emptyList()
)

// キャッシュに載せる候補の本文情報（ID・タイトルは呼び出し毎に決まるので含めない）。
internal data class CandidateContextData(
    val snippet: String,
    val tags: List<String>,
    val aliases: List<String>
) {
    companion object {
        val EMPTY = CandidateContextData("", emptyList(), emptyList())
    }
}

private const val SNIPPET_SHRINK_STEP = 20

/**
 * frontmatter を除いた本文の最初の段落を1行へ畳み、[maxLen] で切る。
 * Obsidianノートは先頭が `---`・見出し・空行になりやすいため、それらを飛ばして
 * 最初の本文段落を採る（タイトルだけでは判断材料が乏しい問題への対処）。
 */
internal fun extractRelatedSnippet(content: String, maxLen: Int): String {
    if (maxLen <= 0) return ""
    val paragraph = stripFrontmatter(content)
        .lineSequence()
        .map { it.trim() }
        .dropWhile { it.isEmpty() || it.startsWith("#") }
        .takeWhile { it.isNotEmpty() }
        .joinToString(" ")
        .replace(Regex("\\s+"), " ")
        .trim()
    return if (paragraph.length <= maxLen) paragraph else paragraph.take(maxLen).trim()
}

// 先頭が `---` で始まり閉じ `---` があれば、その間（frontmatter）を除く。
internal fun stripFrontmatter(content: String): String {
    val lines = content.trimStart('\uFEFF').lines()
    if (lines.firstOrNull()?.trim() != "---") return content
    val closeRelative = lines.drop(1).indexOfFirst { it.trim() == "---" }
    return if (closeRelative >= 0) lines.drop(closeRelative + 2).joinToString("\n") else content
}

/**
 * 候補行を入力バジェット [charBudget] 内へ収めて整形する。
 * ID・タイトルは常に残し、超過時は **タグ → aliases → 本文スニペット** の順に削る
 * （本文スニペットが最も判断材料になるため最後まで残す）。
 * それでも収まらなければタイトルのみにする。
 */
internal fun renderCandidatesWithinBudget(
    candidates: List<CandidateContext>,
    charBudget: Int,
    maxSnippetLen: Int,
    minSnippetLen: Int
): List<RelatedCandidateLine> {
    fun render(snippetCap: Int, aliases: Boolean, tags: Boolean): List<RelatedCandidateLine> =
        candidates.map { RelatedCandidateLine(it.id, it.title, buildDetail(it, snippetCap, aliases, tags)) }

    fun fits(lines: List<RelatedCandidateLine>): Boolean = totalLength(lines) <= charBudget

    render(maxSnippetLen, aliases = true, tags = true).let { if (fits(it)) return it }
    render(maxSnippetLen, aliases = true, tags = false).let { if (fits(it)) return it }
    render(maxSnippetLen, aliases = false, tags = false).let { if (fits(it)) return it }

    var cap = maxSnippetLen
    while (cap > minSnippetLen) {
        cap = (cap - SNIPPET_SHRINK_STEP).coerceAtLeast(minSnippetLen)
        render(cap, aliases = false, tags = false).let { if (fits(it)) return it }
    }
    return candidates.map { RelatedCandidateLine(it.id, it.title, null) }
}

// プロンプトへ実際に描画したときの総文字数（改行込み）。RelatedCandidateLine の整形と一致させる。
private fun totalLength(lines: List<RelatedCandidateLine>): Int =
    lines.sumOf { it.renderForPrompt().length } + (lines.size - 1).coerceAtLeast(0)

private fun buildDetail(c: CandidateContext, snippetCap: Int, aliases: Boolean, tags: Boolean): String? {
    val parts = mutableListOf<String>()
    val snippet = if (snippetCap > 0) c.snippet.take(snippetCap).trim() else ""
    if (snippet.isNotBlank()) parts.add(snippet)
    if (aliases && c.aliases.isNotEmpty()) parts.add("aka ${c.aliases.joinToString(", ")}")
    if (tags && c.tags.isNotEmpty()) parts.add("tags: ${c.tags.joinToString(", ")}")
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" | ")
}
