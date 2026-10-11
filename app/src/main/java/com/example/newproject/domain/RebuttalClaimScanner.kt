package com.example.newproject.domain

// ---------------------------------------------------------------------------
// 反証の一文の「候補列挙」。**規則で列挙し、選別だけをAIに任せる**
// （→ features/rebuttal_sentence.md §5）。
//
// 規則が見るのは**文の形だけ**で、反証かどうかは見ない。形で落とせるもの（問い・ラベル・
// 見出し・コード）だけを落とし、取りこぼさない側へ寄せる。
//
// 前処理は `MarkdownPlainText.kt` と共有し、「何を候補にするか」はここに持つ。
// 純関数だが入力サイズに比例するので、呼び出し側は Dispatchers.Default へ逃がすこと（→ lessons L13）。
// ---------------------------------------------------------------------------

/** 候補にする文の長さ（UTF-16の文字数）。短すぎる文は主張として読めず、長すぎる文は枠に1文で収まらない。 */
internal const val REBUTTAL_CLAIM_MIN_CHARS = 12
internal const val REBUTTAL_CLAIM_MAX_CHARS = 120

/**
 * 関連ノート1件の本文から、主張の形をした文を原文順に返す。同じ文は1つに畳む。
 *
 * **インラインコードは中身を残す**（冊子の扉と同じ）。枠には原文の1文をそのまま出すので、
 * 中身ごと落とすと主語が消えた文が出る。
 */
internal fun scanRebuttalClaims(content: String): List<String> =
    withoutFencedCode(stripFrontmatter(content))
        .lineSequence()
        // **記法を落とす前に行の種類を見る。** 落とした後では見出しと本文の行を見分けられない。
        .filterNot { it.isHeadingLine() || it.isTableDelimiter() || it.isLinkOnly() }
        .map { it.unwrapInlineCode().stripMarkdownMarkers() }
        .flatMap { line -> splitIntoSentences(line).asSequence() }
        .map { it.trim() }
        .filter { it.length in REBUTTAL_CLAIM_MIN_CHARS..REBUTTAL_CLAIM_MAX_CHARS }
        .filter { it.hasReadableText() }
        // 終止符で終わる断片だけを文とみなす。ラベル行（「最終検証: 2026-08-12」）を落とすため。
        .filter { CLAIM_END.containsMatchIn(it) }
        // **問いは主張ではない。** 再会カードが問いとして拾う判定と同じものを使って捨てる。
        .filterNot { QUESTION_END.containsMatchIn(it) }
        .distinct()
        .toList()

private val CLAIM_END = Regex("""[。！？!?.]\s*$""")
