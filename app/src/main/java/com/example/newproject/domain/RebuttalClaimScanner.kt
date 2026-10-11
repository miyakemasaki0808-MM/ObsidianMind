package com.example.newproject.domain

import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.domain.markdown.TableRowRegex
import com.example.newproject.domain.markdown.inlineVisibleText
import com.example.newproject.domain.markdown.parseMarkdownBlocks

// ---------------------------------------------------------------------------
// 反証の一文の「候補列挙」。**規則で列挙し、選別だけをAIに任せる**
// （→ features/rebuttal_sentence.md §5）。
//
// 規則が見るのは**文の形だけ**で、反証かどうかは見ない。形で落とせるもの（問い・ラベル・
// 見出し・コード）だけを落とし、取りこぼさない側へ寄せる。
//
// 候補は枠に原文のまま出すので、**文の単位は本文の画面と同じパーサで決める**（段落・リスト項目など）。
// 純関数だが入力サイズに比例するので、呼び出し側は Dispatchers.Default へ逃がすこと（→ lessons L13）。
// ---------------------------------------------------------------------------

/** 候補にする文の長さ（UTF-16の文字数）。短すぎる文は主張として読めず、長すぎる文は枠に1文で収まらない。 */
internal const val REBUTTAL_CLAIM_MIN_CHARS = 12
internal const val REBUTTAL_CLAIM_MAX_CHARS = 120

/**
 * 関連ノート1件の本文から、主張の形をした文を原文順に返す。同じ文は1つに畳む。
 *
 * 文字は**本文の画面に見えるとおり**にする（→ [visible]）。コードの中身もリンクの表示名も残す。
 */
internal fun scanRebuttalClaims(content: String): List<String> =
    readableUnits(content)
        // 英文のピリオドでも割る。割らないと英文の複数文が1候補になり、合計が上限を超えると全部消える。
        .flatMap { unit -> splitIntoSentences(unit, splitAtPeriods = true).asSequence() }
        .map { it.trim() }
        .filter { it.length in REBUTTAL_CLAIM_MIN_CHARS..REBUTTAL_CLAIM_MAX_CHARS }
        .filter { it.hasReadableText() }
        // 終止符で終わる断片だけを文とみなす。ラベル（「最終検証: 2026-08-12」）を落とすため。
        .filter { CLAIM_END.containsMatchIn(it) }
        // **問いは主張ではない。** 再会カードが問いとして拾う判定と同じものを使って捨てる。
        .filterNot { QUESTION_END.containsMatchIn(it) }
        .distinct()
        .toList()

/**
 * 本文を、本文の画面が1つの文字列として描く単位に分ける（段落・リスト項目・引用の段落・表のセル）。
 *
 * **段落の中の改行では切らない。** 1文を折り返して書いた本文を行ごとに割ると、
 * 条件節を落とした後半だけが「本文にある一文」として出る。段落の切れ目は本文の画面と同じパーサで決め、
 * 折り返しは [joinSoftWrappedLines] でつなぐ（どこで折り返しても同じ文になる）。別の段落・見出し・リスト項目はつながない。
 */
private fun readableUnits(content: String): Sequence<String> {
    // フェンスとリンクだけの行は空行に替える。**詰めると前後の段落が1つにつながる。**
    val prepared = withoutFencedCode(content, keepBlankLines = true)
        .lineSequence()
        .map { line -> if (line.isLinkOnlyLine()) "" else line }
        .joinToString("\n")
    return parseMarkdownBlocks(prepared, ::joinSoftWrappedLines).asSequence().flatMap { block ->
        when (block) {
            is MarkdownBlock.Paragraph -> sequenceOf(visible(block.text))
            is MarkdownBlock.ListBlock -> block.items.asSequence().map { visible(it.text) }
            is MarkdownBlock.Blockquote -> quoteParagraphs(block.lines)
            // 表は本文の行のセルを1つずつ。見出しの行は列の名前なので候補にしない。
            is MarkdownBlock.Table -> block.rows.asSequence().flatten().map(::visible)
            is MarkdownBlock.Heading,
            is MarkdownBlock.CodeBlock,
            is MarkdownBlock.Image,
            MarkdownBlock.HorizontalRule -> emptySequence()
        }
    }
}

/** 引用の行を、空行で区切った段落ごとにつなぐ。つなぎ方は段落と同じ。 */
private fun quoteParagraphs(lines: List<String>): Sequence<String> = sequence {
    val paragraph = mutableListOf<String>()
    for (line in lines + "") {
        if (line.isNotBlank()) {
            paragraph += visible(line.trim())
        } else if (paragraph.isNotEmpty()) {
            yield(joinSoftWrappedLines(paragraph))
            paragraph.clear()
        }
    }
}

/**
 * 本文の画面に見える文字。**記法を別の規則で落とさない** — 正規表現で落とすと、コードの中の `|` や `*` が消え、
 * `[[note|表示名]]` がリンク先の名前に化けて、本文と違う主張が候補になる。
 */
private fun visible(text: String): String = inlineVisibleText(text)

/**
 * リンクだけの行。主張ではないので候補の段落から外す。**表の行は外さない** —
 * 外すと表が2つに割れ、次の行が列の名前として扱われて候補から消える。
 */
private fun String.isLinkOnlyLine(): Boolean =
    (WIKILINK.containsMatchIn(this) || MARKDOWN_LINK.containsMatchIn(this)) &&
        isLinkOnly() &&
        !TableRowRegex.matches(this)

private val CLAIM_END = Regex("""[。！？!?.]\s*$""")
