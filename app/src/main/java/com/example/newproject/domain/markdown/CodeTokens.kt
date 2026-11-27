package com.example.newproject.domain.markdown

/** コードの色分けの種類（→ features/note_typesetting.md 判断15）。文法は解析せず、字句の4種だけを切り分ける。 */
internal enum class CodeTokenKind { Keyword, StringLiteral, Comment, Number }

/** コードの [start] から [end]（含まない）が [kind] にあたる。 */
internal data class CodeToken(val start: Int, val end: Int, val kind: CodeTokenKind)

/** 色分けする言語。札の語（大小は見ない）から引く。知らない言語と札の無い枠は色分けしない。 */
private enum class CodeLanguage { Python, Kotlin, Shell }

private val LANGUAGES: Map<String, CodeLanguage> = mapOf(
    "python" to CodeLanguage.Python,
    "py" to CodeLanguage.Python,
    "python3" to CodeLanguage.Python,
    "kotlin" to CodeLanguage.Kotlin,
    "kt" to CodeLanguage.Kotlin,
    "kts" to CodeLanguage.Kotlin,
    "sh" to CodeLanguage.Shell,
    "bash" to CodeLanguage.Shell,
    "zsh" to CodeLanguage.Shell,
    "shell" to CodeLanguage.Shell
)

private val PYTHON_KEYWORDS = setOf(
    "False", "None", "True", "and", "as", "assert", "async", "await", "break", "case", "class", "continue", "def", "del",
    "elif", "else", "except", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "match",
    "nonlocal", "not", "or", "pass", "raise", "return", "try", "while", "with", "yield"
)

private val KOTLIN_KEYWORDS = setOf(
    "abstract", "as", "break", "by", "catch", "class", "companion", "const", "constructor", "continue", "data", "do",
    "else", "enum", "false", "for", "fun", "if", "import", "in", "init", "inline", "interface", "internal", "is",
    "lateinit", "null", "object", "open", "override", "package", "private", "protected", "public", "return", "sealed",
    "super", "suspend", "this", "throw", "true", "try", "typealias", "val", "var", "when", "while"
)

private val SHELL_KEYWORDS = setOf(
    "alias", "case", "do", "done", "elif", "else", "esac", "export", "fi", "for", "function", "if", "in", "local",
    "readonly", "return", "source", "then", "unset", "until", "while"
)

/**
 * [code] を [language] の字句で切り分ける。対象は Python・Kotlin・シェルの3つで、ほかは空（色分けしない）。
 * **本文の長さに比例するので、本文の解析と一緒に Main の外で呼ぶ**（コードのブロックを作るときに切る）。
 * 閉じていない文字列とコメントは、その行の末尾（三重の引用符と Kotlin の囲みのコメントはコードの末尾）までとして扱い、落ちない。
 */
internal fun codeTokensOf(language: String?, code: String): List<CodeToken> {
    val lang = language?.lowercase()?.let { LANGUAGES[it] } ?: return emptyList()
    val keywords = when (lang) {
        CodeLanguage.Python -> PYTHON_KEYWORDS
        CodeLanguage.Kotlin -> KOTLIN_KEYWORDS
        CodeLanguage.Shell -> SHELL_KEYWORDS
    }
    val tokens = mutableListOf<CodeToken>()
    // シェルで今の位置が語の頭か。直前の生の1字ではなく、エスケープと行の継続を反映した状態で持つ。
    var shellWordStart = true
    var i = 0
    while (i < code.length) {
        val c = code[i]
        // シェルは引用符の外でもバックスラッシュが次の1字を文字にする（`\"` は文字列を、`\#` はコメントを始めない）。
        // 文字にした字は語の一部になる。行末のバックスラッシュは改行ごと消えて前後の語がつながるので、語の頭かどうかを変えない。
        if (lang == CodeLanguage.Shell && c == '\\') {
            if (code.getOrNull(i + 1) != '\n') shellWordStart = false
            i += 2
            continue
        }
        when {
            isLineComment(lang, code, i, shellWordStart) -> {
                val end = code.indexOf('\n', i).let { if (it < 0) code.length else it }
                tokens += CodeToken(i, end, CodeTokenKind.Comment)
                i = end
            }
            lang == CodeLanguage.Kotlin && code.startsWith("/*", i) -> {
                val close = code.indexOf("*/", i + 2)
                val end = if (close < 0) code.length else close + 2
                tokens += CodeToken(i, end, CodeTokenKind.Comment)
                i = end
            }
            c == '"' || c == '\'' -> {
                val end = stringEnd(lang, code, i)
                tokens += CodeToken(i, end, CodeTokenKind.StringLiteral)
                i = end
            }
            c.isDigit() && !isWordChar(code.getOrNull(i - 1)) -> {
                var end = i + 1
                while (end < code.length && (code[end].isLetterOrDigit() || code[end] == '_' || code[end] == '.')) end++
                tokens += CodeToken(i, end, CodeTokenKind.Number)
                i = end
            }
            c.isLetter() || c == '_' -> {
                var end = i + 1
                while (end < code.length && isWordChar(code[end])) end++
                if (code.substring(i, end) in keywords) tokens += CodeToken(i, end, CodeTokenKind.Keyword)
                i = end
            }
            else -> i++
        }
        shellWordStart = code[i - 1].isWhitespace() || code[i - 1] in SHELL_WORD_BREAKS
    }
    return tokens
}

private fun isWordChar(c: Char?): Boolean = c != null && (c.isLetterOrDigit() || c == '_')

/** シェルで語を区切る記号。この直後の `#` は新しい語の頭にあたる。 */
private const val SHELL_WORD_BREAKS = ";&|()<>"

/**
 * 行のコメントの始まりか。シェルの `#` は語の頭（[shellWordStart]: 行頭・空白・区切りの記号の後で、エスケープした字の後ではない）でだけ
 * コメント（`${#a}` や `a#b` は違う）。
 */
private fun isLineComment(lang: CodeLanguage, code: String, i: Int, shellWordStart: Boolean): Boolean = when (lang) {
    CodeLanguage.Python -> code[i] == '#'
    CodeLanguage.Shell -> code[i] == '#' && shellWordStart
    CodeLanguage.Kotlin -> code.startsWith("//", i)
}

/** [start] の引用符で始まる文字列の終わり（含まない）。三重の引用符は複数行にまたがり、ほかは行の末尾で打ち切る。 */
private fun stringEnd(lang: CodeLanguage, code: String, start: Int): Int {
    val quote = code[start]
    val triple = lang != CodeLanguage.Shell && code.startsWith("$quote$quote$quote", start)
    if (triple) {
        val close = code.indexOf("$quote$quote$quote", start + 3)
        return if (close < 0) code.length else close + 3
    }
    // シェルの一重引用符は中でエスケープしない。それ以外はバックスラッシュの次の字を飛ばす。
    val escapes = !(lang == CodeLanguage.Shell && quote == '\'')
    var i = start + 1
    while (i < code.length) {
        val c = code[i]
        if (c == '\n' && !(lang == CodeLanguage.Shell)) return i
        if (escapes && c == '\\') {
            i += 2
            continue
        }
        if (c == quote) return i + 1
        i++
    }
    return code.length
}
