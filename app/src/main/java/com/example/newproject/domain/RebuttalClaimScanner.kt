package com.example.newproject.domain

import com.example.newproject.domain.markdown.MarkdownBlock
import com.example.newproject.domain.markdown.TableRowRegex
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
 * **インラインコードは中身を残す**（冊子の扉と同じ）。枠には原文を出すので、中身ごと落とすと主語が消えた文が出る。
 */
internal fun scanRebuttalClaims(content: String): List<String> =
    readableUnits(content)
        .flatMap { unit -> splitIntoSentences(unit).asSequence() }
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
 * 条件節を落とした後半だけが「本文にある一文」として出る。段落のつなぎ方は本文の画面と同じ（行を空白でつなぐ）。
 * 別の段落・見出し・リスト項目はつながない。
 */
private fun readableUnits(content: String): Sequence<String> {
    // フェンスとリンクだけの行は空行に替える。**詰めると前後の段落が1つにつながる。**
    val prepared = withoutFencedCode(content, keepBlankLines = true)
        .lineSequence()
        .map { line -> if (line.isLinkOnlyLine()) "" else line }
        .joinToString("\n")
    return parseMarkdownBlocks(prepared).asSequence().flatMap { block ->
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

/** 引用の行を、空行で区切った段落ごとにつなぐ。つなぎ方は本文の段落と同じ。 */
private fun quoteParagraphs(lines: List<String>): Sequence<String> = sequence {
    val paragraph = mutableListOf<String>()
    for (line in lines + "") {
        if (line.isNotBlank()) {
            paragraph += visible(line.trim())
        } else if (paragraph.isNotEmpty()) {
            yield(paragraph.joinToString(" "))
            paragraph.clear()
        }
    }
}

/** 記法を落として中身を残す。 */
private fun visible(text: String): String = text.unwrapInlineCode().stripMarkdownMarkers()

/**
 * リンクだけの行。主張ではないので候補の段落から外す。**表の行は外さない** —
 * 外すと表が2つに割れ、次の行が列の名前として扱われて候補から消える。
 */
private fun String.isLinkOnlyLine(): Boolean =
    (WIKILINK.containsMatchIn(this) || MARKDOWN_LINK.containsMatchIn(this)) &&
        isLinkOnly() &&
        !TableRowRegex.matches(this)

private val CLAIM_END = Regex("""[。！？!?.]\s*$""")
