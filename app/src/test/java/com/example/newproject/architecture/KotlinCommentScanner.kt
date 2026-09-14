package com.example.newproject.architecture

/**
 * Kotlin ソースから**実際のコメントだけ**を取り出し、コメントの形の規則を当てる。
 *
 * 正規表現でソース全体を走査すると、文字列の中の `//` や `/** */` をコメントと取り違え、
 * 正しいコードでゲートが落ちる。**文字列の開始位置は抽出した後からは復元できない**ので、
 * 先頭から字句を読んで文字列・raw string・文字リテラルを飛ばす。
 *
 * 字句は検査に要る範囲だけを読む。文字列テンプレート `${...}` の中は通常のコードとして読み直す
 * （中に文字列やコメントが入りうるため）。ブロックコメントは Kotlin と同じく入れ子を数える。
 */
internal object KotlinCommentScanner {

    data class Comment(val start: Int, val text: String) {
        val end: Int get() = start + text.length

        /** `/**/` は空のブロックコメントで KDoc ではない。 */
        val isKDoc: Boolean get() = text.startsWith("/**") && text != "/**/"
    }

    fun comments(source: String): List<Comment> {
        val found = mutableListOf<Comment>()
        Lexer(source, found).code(0, untilClosingBrace = false)
        return found
    }

    /** 直後にもう1つ KDoc が続いている（＝どの宣言にも付かない）KDoc の開始位置。 */
    fun detachedKDocOffsets(source: String): List<Int> =
        comments(source)
            .filter { it.isKDoc }
            .zipWithNext()
            .filter { (first, second) -> source.substring(first.end, second.start).isBlank() }
            .map { (first, _) -> first.start }

    /**
     * 経緯の目印（日付・レビューの指摘番号）を含むコメント行。行番号は1始まり。
     *
     * 「」と "" で引用した部分は**書式の例**として数えない（`最終検証: 2026-08-12` のような行を扱う解析器がある）。
     */
    fun historyMarkLines(source: String): List<Pair<Int, String>> =
        comments(source).flatMap { comment ->
            comment.text.lines().mapIndexedNotNull { index, line ->
                if (HISTORY_MARK.containsMatchIn(QUOTED.replace(line, " "))) {
                    lineOf(source, comment.start) + index to line.trim()
                } else {
                    null
                }
            }
        }

    fun lineOf(source: String, offset: Int): Int = source.substring(0, offset).count { it == '\n' } + 1

    /** 経緯の目印。日付（`2026-09-07`）とレビューの指摘番号（`P2-1`）。 */
    private val HISTORY_MARK = Regex("""\b20\d{2}-\d{2}-\d{2}\b|\bP[0-3]-\d+\b""")

    private val QUOTED = Regex("""「[^」]*」|"[^"]*"""")

    private class Lexer(private val s: String, private val out: MutableList<Comment>) {

        /** コードを読む。[untilClosingBrace] なら対応する `}` の直後の位置を返す（テンプレートの終わり）。 */
        fun code(from: Int, untilClosingBrace: Boolean): Int {
            var i = from
            var depth = 0
            while (i < s.length) {
                when {
                    s.startsWith("//", i) -> {
                        val end = s.indexOf('\n', i).let { if (it < 0) s.length else it }
                        out += Comment(i, s.substring(i, end))
                        i = end
                    }
                    s.startsWith("/*", i) -> i = blockComment(i)
                    s.startsWith("\"\"\"", i) -> i = rawString(i)
                    s[i] == '"' -> i = string(i)
                    s[i] == '\'' -> i = charLiteral(i)
                    s[i] == '{' -> { depth++; i++ }
                    s[i] == '}' -> {
                        if (untilClosingBrace && depth == 0) return i + 1
                        depth--
                        i++
                    }
                    else -> i++
                }
            }
            return i
        }

        private fun blockComment(start: Int): Int {
            var i = start + 2
            var depth = 1
            while (i < s.length && depth > 0) {
                when {
                    s.startsWith("/*", i) -> { depth++; i += 2 }
                    s.startsWith("*/", i) -> { depth--; i += 2 }
                    else -> i++
                }
            }
            out += Comment(start, s.substring(start, i))
            return i
        }

        private fun string(start: Int): Int {
            var i = start + 1
            while (i < s.length) {
                when {
                    s[i] == '\\' -> i += 2
                    s[i] == '"' -> return i + 1
                    s.startsWith("\${", i) -> i = code(i + 2, untilClosingBrace = true)
                    // 閉じない文字列で残りを丸ごと飲み込まない。
                    s[i] == '\n' -> return i
                    else -> i++
                }
            }
            return i
        }

        /** 閉じの `"""` の前に余分な `"` が続いたら、それは中身（`""""` は中身が `"`）。 */
        private fun rawString(start: Int): Int {
            var i = start + 3
            while (i < s.length) {
                when {
                    s.startsWith("\"\"\"", i) -> {
                        while (i < s.length && s[i] == '"') i++
                        return i
                    }
                    s.startsWith("\${", i) -> i = code(i + 2, untilClosingBrace = true)
                    else -> i++
                }
            }
            return i
        }

        private fun charLiteral(start: Int): Int {
            if (start + 1 < s.length && s[start + 1] == '\\') {
                var i = start + 3
                while (i < s.length && s[i] != '\'' && s[i] != '\n') i++
                return i + 1
            }
            return if (start + 2 < s.length && s[start + 2] == '\'') start + 3 else start + 1
        }
    }
}
