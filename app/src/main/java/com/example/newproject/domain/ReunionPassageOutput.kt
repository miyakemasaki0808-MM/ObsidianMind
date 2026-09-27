package com.example.newproject.domain

import com.example.newproject.ai.PromptBuilder
import com.example.newproject.model.ReunionPassage

/**
 * 前後の要約の生成結果から、入力の境目の印を取り除く（→ features/reunion_card.md 判断6「前後の要約」）。
 *
 * 印は入力の区切りで、ノートの本文には無い。モデルが復唱すると、カード・端末内の保存・印の保存の
 * どこにも機械向けの記号が残る。**表示と保存の前に必ずここを通す。**
 *
 * **本文が同じ言葉を含むときは消さない。** 復唱か本文の引用かを区別できず、引用なら本人の言葉である。
 * 括弧の有無・大小文字・前後の鉤括弧の揺れは同じ印として扱う。
 */
fun cleanReunionPassageOutput(generated: String, passage: ReunionPassage): String {
    if (READ_MARKER_PATTERN.containsMatchIn(passage.before) ||
        READ_MARKER_PATTERN.containsMatchIn(passage.after)
    ) {
        return generated.trim()
    }
    return generated.lines()
        .map { line -> line.replace(READ_MARKER_PATTERN) { match -> joinerAround(line, match.range) }.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
}

/**
 * 印を抜いた跡を何で埋めるか。**両隣が英数字のときだけ空白1つ**で語を分け、ほかは詰める
 * （和文の「読んでいた。 この先」に空白を残さない）。印の外の空白には触れない。
 */
private fun joinerAround(line: String, range: IntRange): String {
    val before = line.getOrNull(range.first - 1)
    val after = line.getOrNull(range.last + 1)
    return if (before.isAsciiWordChar() && after.isAsciiWordChar()) " " else ""
}

private fun Char?.isAsciiWordChar(): Boolean = this != null && this < '\u0080' && isLetterOrDigit()

/** 印の語（`READ UP TO HERE`）を、括弧と空白の揺れを許して引く。語は印の定義から作る。 */
private val READ_MARKER_PATTERN: Regex = run {
    val words = PromptBuilder.REUNION_READ_MARKER.trim('[', ']').trim()
        .split(Regex("\\s+"))
        .joinToString("\\s+") { Regex.escape(it) }
    Regex("""[ \t\u3000]*[「『"“（(]?\[?\s*$words\s*]?[」』"”）)]?[ \t\u3000]*""", RegexOption.IGNORE_CASE)
}
